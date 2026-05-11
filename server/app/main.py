"""LifeLog V2 orchestrator — FastAPI server.

Routes audio to Whisper for STT, LM Studio for LLM, Piper for TTS,
and SearXNG for web search.
"""

import io
import json
import logging
import os
import tempfile
import wave

import httpx
from fastapi import FastAPI, File, Header, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("orchestrator")

app = FastAPI(title="LifeLog V2", version="0.2.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

LM_STUDIO_URL = "http://host.docker.internal:1234"
# For non-Docker: LM Studio on localhost
LM_STUDIO_URL_LOCAL = "http://127.0.0.1:11434"

# Will be set based on environment
lm_studio_base = LM_STUDIO_URL_LOCAL

# Faster-whisper model (loaded lazily)
_whisper_model = None


def get_whisper_model():
    global _whisper_model
    if _whisper_model is None:
        from faster_whisper import WhisperModel
        logger.info("Loading faster-whisper base model...")
        _whisper_model = WhisperModel("base", device="cpu", compute_type="int8")
        logger.info("Whisper model loaded")
    return _whisper_model


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/tts")
async def text_to_speech(text: str = "Hello world"):
    """Synthesize text to speech."""
    audio_bytes = await _tts_synthesize(text)
    if audio_bytes:
        from fastapi.responses import Response
        return Response(content=audio_bytes, media_type="audio/mpeg")
    return {"error": "TTS failed", "text": text}


# ── STT (Slice 3) ──────────────────────────────────────────────────────────

@app.post("/stt")
async def speech_to_text(audio: UploadFile = File(...)):
    """Accept raw PCM16 or WAV audio, return transcript."""
    raw = await audio.read()
    logger.info(f"STT: received {len(raw)} bytes")

    # Wrap PCM16 as WAV if it doesn't look like a WAV
    if not raw.startswith(b"RIFF"):
        raw = pcm16_to_wav(raw)

    # Try faster-whisper first, fall back to LM Studio whisper if unavailable
    transcript = await _transcribe_with_whisper_api(raw)
    return {"text": transcript}


async def _transcribe_with_whisper_api(audio_bytes: bytes) -> str:
    """Transcribe audio using faster-whisper locally."""
    try:
        model = get_whisper_model()
        # Write to temp file
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(audio_bytes)
            tmp_path = f.name

        segments, info = model.transcribe(tmp_path, language="en")
        import os; os.unlink(tmp_path)

        text = " ".join(seg.text for seg in segments).strip()
        logger.info(f"STT (faster-whisper local): {text[:100]}")
        return text
    except Exception as e:
        logger.error(f"Whisper transcription failed: {e}")
        return ""


def pcm16_to_wav(pcm: bytes, sample_rate: int = 16000, channels: int = 1) -> bytes:
    """Convert raw PCM16 bytes to WAV format."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(channels)
        wf.setsampwidth(2)  # 16-bit
        wf.setframerate(sample_rate)
        wf.writeframes(pcm)
    return buf.getvalue()


# ── Chat (Slice 4) ──────────────────────────────────────────────────────────

class ChatRequest(BaseModel):
    text: str
    session_id: str = ""


@app.post("/chat")
async def chat(req: ChatRequest):
    """Send text to LM Studio LLM, return TTS audio."""
    logger.info(f"Chat: {req.text[:100]}")

    # 1. Get LLM response
    llm_reply = await _llm_respond(req.text)
    logger.info(f"LLM reply: {llm_reply[:100]}")

    # 2. Synthesize to speech
    audio_bytes = await _tts_synthesize(llm_reply)

    if audio_bytes:
        from fastapi.responses import Response
        return Response(
            content=audio_bytes,
            media_type="audio/mpeg",
            headers={"X-Transcript": llm_reply[:500].encode("utf-8").decode("latin-1", errors="replace")}
        )
    else:
        return {"text": llm_reply, "audio": None}


@app.post("/chat/text")
async def chat_text_only(req: ChatRequest):
    """Send text to LLM, return text only (no TTS)."""
    llm_reply = await _llm_respond(req.text)
    return {"text": llm_reply}


# ── Web Search (Slice 6) ─────────────────────────────────────────────────────

@app.get("/search")
async def search(q: str = ""):
    """Search the web via SearXNG."""
    if not q:
        return {"error": "missing query parameter 'q'"}
    results = await _searxng_search(q)
    return {"results": results}


class SearchChatRequest(BaseModel):
    text: str


@app.post("/chat/search")
async def chat_with_search(req: SearchChatRequest):
    """Search web for query, feed results to LLM, return answer."""
    results = await _searxng_search(req.text)
    
    if not results:
        llm_reply = await _llm_respond(req.text)
        return {"text": llm_reply, "sources": []}
    
    # Build context from search results
    context = "\n".join(
        f"- {r['title']}: {r.get('snippet', r.get('url', ''))}"
        for r in results[:5]
    )
    prompt = f"""Answer this question using the search results below. If the search results don't help, answer from your own knowledge. Keep it under 3 sentences. No emojis.

Question: {req.text}

Search results:
{context}"""
    
    llm_reply = await _llm_respond(prompt, system_prompt="You answer questions using provided web search results. Be concise. No emojis. Plain text only.")
    return {"text": llm_reply, "sources": [r.get("url", "") for r in results[:3]]}


async def _searxng_search(query: str) -> list[dict]:
    """Search via DuckDuckGo (no Docker needed)."""
    try:
        from duckduckgo_search import DDGS
        results = []
        with DDGS() as ddgs:
            for r in ddgs.text(query, max_results=8):
                results.append({
                    "title": r.get("title", ""),
                    "url": r.get("href", ""),
                    "snippet": r.get("body", ""),
                })
        logger.info(f"Search '{query}': {len(results)} results (DuckDuckGo)")
        return results
    except Exception as e:
        logger.error(f"Search failed: {e}")
        return []


async def _llm_respond(text: str, system_prompt: str = "") -> str:
    """Query LM Studio for LLM response."""
    sys = system_prompt or "You are a helpful voice assistant for a smart home device. Rules: 1) Keep responses under 2 sentences. 2) NEVER use emojis, emoticons, or special unicode characters. 3) Use only plain English words and basic punctuation. 4) If you would normally use an emoji, use words instead (e.g. say 'that is great' not '😊')."

    # Auto-detect first available chat model
    model_name = "gemma3:12b"
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(f"{lm_studio_base}/api/tags")
            if resp.status_code == 200:
                models = resp.json().get("models", [])
                # Prefer gemma3:12b, fall back to first available
                for m in models:
                    name = m.get("name", "")
                    if "gemma3:12b" in name:
                        model_name = name
                        break
                if "gemma3:12b" not in model_name and models:
                    model_name = models[0].get("name", model_name)
    except Exception:
        pass

    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(
                f"{lm_studio_base}/v1/chat/completions",
                json={
                    "model": model_name,
                    "messages": [
                        {"role": "system", "content": sys},
                        {"role": "user", "content": text},
                    ],
                    "max_tokens": 500,
                    "temperature": 0.7,
                },
            )
            if resp.status_code == 200:
                data = resp.json()
                reply = data["choices"][0]["message"]["content"].strip()
                # Strip any remaining emojis/special chars for TTS
                import re
                reply = re.sub(r'[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF\U00002702-\U000027B0\U0000FE00-\U0000FEFF\U0001F900-\U0001F9FF\U00002600-\U000026FF\U0000200D\u200D\uFE0F]', '', reply)
                return reply.strip()
            else:
                logger.error(f"LM Studio error: {resp.status_code} {resp.text}")
                return f"[LLM error: {resp.status_code}]"
    except Exception as e:
        logger.error(f"LM Studio connection failed: {e}")
        return "[LLM unavailable]"


async def _tts_synthesize(text: str) -> bytes | None:
    """Synthesize text to WAV audio via edge-tts."""
    try:
        import edge_tts
        communicate = edge_tts.Communicate(text, "en-US-AriaNeural")
        # edge-tts outputs MP3, write to temp file then return bytes
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            tmp_path = f.name
        import asyncio
        loop = asyncio.get_event_loop()
        await communicate.save(tmp_path)
        audio = open(tmp_path, "rb").read()
        os.unlink(tmp_path)
        logger.info(f"TTS: synthesized {len(audio)} bytes MP3")
        return audio
    except Exception as e:
        logger.error(f"TTS failed: {e}")
        return None


# ── Voice (full loop, Slice 5) ─────────────────────────────────────────────

@app.post("/voice")
async def voice(audio: UploadFile = File(...)):
    """Full voice pipeline: STT → LLM → TTS."""
    raw = await audio.read()
    logger.info(f"Voice: received {len(raw)} bytes")

    # Wrap PCM as WAV if needed
    if not raw.startswith(b"RIFF"):
        raw = pcm16_to_wav(raw)

    # STT
    transcript = await _transcribe_with_whisper_api(raw)
    if not transcript or transcript.startswith("["):
        return {"error": "STT failed", "transcript": transcript}

    logger.info(f"Voice transcript: {transcript}")

    # LLM
    llm_reply = await _llm_respond(transcript)
    logger.info(f"Voice LLM reply: {llm_reply[:100]}")

    # TTS
    audio_bytes = await _tts_synthesize(llm_reply)

    if audio_bytes:
        from fastapi.responses import Response
        return Response(
            content=audio_bytes,
            media_type="audio/mpeg",
            headers={
                "X-Transcript": transcript[:300].encode("utf-8").decode("latin-1", errors="replace"),
                "X-Reply": llm_reply[:500].encode("utf-8").decode("latin-1", errors="replace"),
            }
        )
    else:
        return {"transcript": transcript, "text": llm_reply, "audio": None}
