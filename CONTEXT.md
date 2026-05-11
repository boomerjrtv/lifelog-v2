# LifeLog V2

A self-hosted voice assistant and lifelog system running on a home server (AMD 9070XT + 7700X), controlled via a GrapheneOS Android phone with on-device wake word detection.

## Language

**Assistant**:
The voice-first AI that responds to wake word queries, performs web searches, and answers questions about the user's lifelog data.
_Avoid_: bot, chatbot, agent (use "agent" only for the AFK coding agents)

**Lifelog**:
The passive data capture system that records transcripts, location, calendar events, meals, and other personal data into a structured pipeline for later recall.
_Avoid_: journal, diary, tracker

**Wake Word**:
An on-device keyword ("Hey Assistant") detected by Sherpa-ONNX running in a foreground service on the phone. Triggers the assistant interaction flow.
_Avoid_: hotword, trigger phrase

**Orchestrator**:
The FastAPI server that routes audio → STT → LLM → Search → TTS for assistant interactions, and manages the distillation pipeline for lifelog data.
_Avoid_: backend, API server (too generic)

**Distillation Pipeline**:
The multi-stage process that transforms raw lifelog events into progressively compressed knowledge: transcripts → facts → hourly summaries → daily summaries → routines.
_Avoid_: processing pipeline, ETL

**Distillation Stage**:
A single level of the distillation pipeline (immediate, hourly, daily, weekly). Each stage compresses the previous layer's output.
_Avoid_: processing step, aggregation level

**Server**:
The Windows machine (192.168.0.45) running LM Studio for LLM inference, with WSL2/Docker for all other services. Exposed to the internet via port forwarding + Caddy.
_Avoid_: cloud, host, box

**Phone App**:
The Kotlin/Hilt/Compose Android app running on GrapheneOS. Houses the wake word engine, audio capture, passive recording, and all lifelog data collection.
_Avoid_: client, mobile app, frontend

**Passive Recording**:
The toggleable feature where the phone captures ambient audio, transcribes it via server-side Whisper, and feeds transcripts into the lifelog. Controlled by user toggle.
_Avoid_: always-on recording, ambient capture

**Inference Backend**:
LM Studio Server exposing an OpenAI-compatible API at localhost:1234. Runs Gemma3:12B for interactive tasks and nomic-embed-text for embeddings.
_Avoid_: model server, LLM endpoint

**Search Engine**:
SearXNG running in Docker — privacy-respecting metasearch engine used by the orchestrator for web queries.
_Avoid_: search API, Google (we don't use it)

**Voice Pipeline**:
The full path from wake word detection → audio streaming → server-side Whisper STT → LLM reasoning (with optional search) → Piper TTS → audio playback on phone.
_Avoid_: audio pipeline, speech pipeline

## Relationships

- The **Phone App** triggers the **Voice Pipeline** when a **Wake Word** is detected
- The **Voice Pipeline** is orchestrated by the **Orchestrator** on the **Server**
- The **Orchestrator** calls the **Inference Backend** for LLM reasoning and the **Search Engine** for web queries
- **Passive Recording** feeds transcripts into the **Distillation Pipeline** when enabled
- The **Distillation Pipeline** runs on the **Server** and produces facts, summaries, and routines from lifelog data
- All lifelog data is queryable via RAG through the **Orchestrator**

## Example dialogue

> **Dev:** "When the **Wake Word** fires, does the **Phone App** stream audio directly to Whisper or to the **Orchestrator**?"
> **Domain expert:** "The phone streams audio to the **Orchestrator** endpoint, which routes it to Whisper for STT, then to the **Inference Backend** for reasoning, and optionally queries the **Search Engine** before synthesizing TTS."

## Flagged ambiguities

- "recording" was previously used to mean both passive ambient capture and active assistant interaction — resolved: **Passive Recording** is the toggleable ambient feature; the **Voice Pipeline** is the active assistant flow.
- "server" was used ambiguously between the Termux Flask server and the home server — resolved: everything now runs on the **Server** (Windows machine), the Termux server is deprecated.
