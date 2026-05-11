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
LM_STUDIO_URL_LOCAL = "http://127.0.0.1:1234"

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
            media_type="audio/wav",
            headers={"X-Transcript": llm_reply[:500].encode("utf-8").decode("latin-1", errors="replace")}
        )
    else:
        return {"text": llm_reply, "audio": None}


@app.post("/chat/text")
async def chat_text_only(req: ChatRequest):
    """Send text to LLM, return text only (no TTS)."""
    llm_reply = await _llm_respond(req.text)
    return {"text": llm_reply}


async def _llm_respond(text: str, system_prompt: str = "") -> str:
    """Query LM Studio for LLM response."""
    sys = system_prompt or "You are a helpful voice assistant. Keep responses concise and conversational — they will be spoken aloud."

    # Auto-detect first available chat model
    model_name = "google/gemma-4-e4b"
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(f"{lm_studio_base}/v1/models")
            if resp.status_code == 200:
                models = resp.json().get("data", [])
                chat_models = [m["id"] for m in models if "embed" not in m["id"].lower()]
                if chat_models:
                    model_name = chat_models[0]
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
                return reply
            else:
                logger.error(f"LM Studio error: {resp.status_code} {resp.text}")
                return f"[LLM error: {resp.status_code}]"
    except Exception as e:
        logger.error(f"LM Studio connection failed: {e}")
        return "[LLM unavailable]"


async def _tts_synthesize(text: str) -> bytes | None:
    """Synthesize text to WAV audio via Piper."""
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            resp = await client.post(
                "http://localhost:5000/api/tts",
                json={"text": text[:1000]},
            )
            if resp.status_code == 200:
                return resp.content
    except Exception as e:
        logger.warning(f"Piper unavailable: {e}")

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
            media_type="audio/wav",
            headers={
                "X-Transcript": transcript[:300].encode("utf-8").decode("latin-1", errors="replace"),
                "X-Reply": llm_reply[:500].encode("utf-8").decode("latin-1", errors="replace"),
            }
        )
    else:
        return {"transcript": transcript, "text": llm_reply, "audio": None}
