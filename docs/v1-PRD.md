# LifeLog V2 — Product Requirements Document

## Problem Statement

The original LifeLog Android app captured personal data (location, transcripts, meals, calendar) but failed to make it useful. Two core problems: (1) on-device Whisper was too slow for real-time voice interaction, and the wake word detection was fake (full STT + text matching instead of actual keyword spotting), causing the assistant to be unusable; (2) months of raw lifelog data were collected with zero distillation — no facts extracted, no routines detected, no summaries generated — making the data impossible to query with AI.

## Solution

Rebuild LifeLog as a server-assisted voice assistant + lifelog system. The phone handles wake word detection (Sherpa-ONNX, on-device) and data capture; the home server (AMD 9070XT + 7700X, Windows + WSL2/Docker) handles the heavy lifting: STT (server-side Whisper), LLM reasoning (LM Studio, Gemma3:12B), web search (SearXNG), TTS (Piper), and a multi-stage distillation pipeline that turns raw events into facts, summaries, and routines. The system is exposed to the internet via port forwarding + Caddy reverse proxy with Let's Encrypt TLS.

## User Stories

### Voice Assistant
1. As a user, I want to say "Hey Assistant" and have my phone respond, so that I can interact hands-free
2. As a user, I want the assistant to answer general knowledge questions, so that I get useful information without touching my phone
3. As a user, I want the assistant to perform web searches and synthesize results, so that I get current information
4. As a user, I want the assistant to answer questions about my past activities, so that I can recall what I did
5. As a user, I want responses spoken back to me via TTS, so that the interaction is fully voice-driven
6. As a user, I want the assistant to work over the internet (not just WiFi), so that it works anywhere

### Lifelog Capture
7. As a user, I want my location tracked and stored, so that I can recall where I was on a given day
8. As a user, I want ambient audio transcribed when passive recording is enabled, so that I can recall conversations
9. As a user, I want to toggle passive recording on/off, so that I control when audio is captured
10. As a user, I want calendar events synced, so that my schedule is part of my lifelog
11. As a user, I want meal tracking, so that I can recall what I ate
12. As a user, I want SMS and call logs captured, so that communication patterns are tracked
13. As a user, I want photos (with metadata) tracked, so that I can recall when/where I took them

### Lifelog Intelligence (Distillation)
14. As a user, I want transcripts immediately processed into facts, so that knowledge is extracted in real-time
15. As a user, I want hourly summaries of my activity, so that I can review my day in chunks
16. As a user, I want daily summaries generated automatically, so that I can recall what happened each day
17. As a user, I want routine patterns detected (sleep, commute, grocery trips), so that I understand my habits
18. As a user, I want all lifelog data searchable via RAG, so that I can ask natural language questions about my past

### Infrastructure
19. As a user, I want the system accessible over the internet with TLS, so that it works from anywhere securely
20. As a user, I want DDNS to keep my domain pointing at my home IP, so that connectivity survives IP changes
21. As a user, I want SSO authentication (Keycloak) protecting the server, so that only I can access it

### Phone App
22. As a user, I want the app to start on boot and run as a foreground service, so that it's always listening
23. As a user, I want a dashboard showing my lifelog data, so that I can browse my history
24. As a user, I want a chat interface for text-based interaction, so that I can query the assistant silently
25. As a user, I want a live transcript feed, so that I can see what's being captured in real-time
26. As a user, I want speaker recognition, so that I know who said what in transcripts

## Implementation Decisions

### New repo, cherry-picked from v1
- New repo (`boomerjrtv/lifelog-v2`) with clean architecture
- Copy the keepable v1 components: Room DB layer (entities, DAOs, migrations), UI screens (Dashboard, Chat, Transcript, Setup, Insights, Speaker Review), SettingsRepository, CalendarEventEntity/Dao, speaker recognition (ECAPA + clustering)
- Do NOT copy: WakeWordService, LifeLogService audio pipeline, on-device Whisper, the 1686-line LifeLogService monolith, the 2750-line LifeLogApi monolith

### Single audio service replaces dual-service v1
- One foreground service owns the microphone (no more mic contention between LifeLogService and WakeWordService)
- Sherpa-ONNX runs on-device for wake word detection ("Hey Assistant") — always active, low power
- When passive recording is ON: audio chunks stream to server-side Whisper via the orchestrator
- When wake word fires: audio streams to orchestrator → Whisper STT → LLM (with optional SearXNG search) → Piper TTS → audio playback

### Server stack (WSL2/Docker on Windows)
- FastAPI orchestrator — the central routing layer
- faster-whisper (Docker) — server-side STT, GPU-accelerated via ROCm
- SearXNG (Docker) — web search, no API keys
- Piper (Docker) — TTS
- LM Studio (Windows native) — LLM inference at localhost:1234, OpenAI-compatible API
- Keycloak (Docker) — SSO/OIDC with OAuth2+PKCE for mobile auth
- Caddy (Docker) — reverse proxy, auto-TLS via Let's Encrypt
- DDNS script — polls WAN IP, updates registrar DNS via API
- nomic-embed-text (LM Studio) — embeddings for RAG

### LLM strategy
- Single model in LM Studio: Gemma3:12B for everything (assistant responses, search synthesis, lifelog queries, fact extraction)
- Background tasks (extraction, summaries) queue and run through the same endpoint at lower priority
- Embeddings via nomic-embed-text (already loaded in LM Studio)

### Distillation pipeline (hybrid real-time + batched)
- Transcripts → immediate fact extraction (LLM call per transcript, entities/topics/facts)
- Location/calendar/meals → hourly batched summaries
- Daily summaries + routine extraction → 3am batch job
- All outputs stored with embeddings for RAG
- v2 scope: fact extraction + daily summaries. Routines and weekly patterns deferred to v3.

### Network exposure
- Port forwarding (443 → server) + Caddy with Let's Encrypt TLS
- Dynamic IP handled by custom DDNS script hitting registrar API
- Domain: user to purchase (~$10/year, Porkbun/Cloudflare recommended)

### Auth
- v1: API key (simple, single-user)
- v2: Keycloak SSO with OAuth2+PKCE flow for the phone app

### Data sources
- Captured: location, transcripts (passive recording), calendar, meals, SMS, call logs, photos with metadata
- Dropped from v1: battery, heartbeat, wifi telemetry
- Future (v3): browser history (Vanadium), spending/bank data

### v1 vs v2 vs v3 scope
- v1 ("It talks and it searches"): New repo + app refactor, Sherpa-ONNX wake word, FastAPI orchestrator, SearXNG, Piper, server-side Whisper, API key auth, Caddy + DDNS, existing lifelog capture
- v2 ("It remembers"): Keycloak SSO, fact extraction pipeline, hourly/daily summaries, RAG over lifelog data
- v3 ("It's smart"): Passive recording toggle, routine extraction, insights dashboard, new data sources (SMS, photos, browser history, spending)

## Testing Decisions

- Good tests test external behavior, not implementation details
- Orchestrator API endpoints: test via FastAPI TestClient against mocked LM Studio/Whisper/SearXNG/Piper
- Distillation stages: test with sample transcripts, verify fact extraction output format
- Phone app wake word: integration test using recorded audio samples, verify Sherpa-ONNX triggers correctly
- Audio pipeline: test with PCM audio fixtures, verify end-to-end flow through orchestrator mock
- Follow red-green-refactor for all new modules

## Out of Scope (for v1 PRD)

- Keycloak SSO (v2)
- Fact extraction / distillation pipeline (v2)
- RAG over lifelog data (v2)
- Routine extraction (v3)
- Passive recording toggle (v3)
- SMS/call log/photo capture (v3)
- Browser history integration (v3)
- Spending/bank data integration (v3)
- WireGuard VPN (deferred)
- Web dashboard (deferred)
- Multi-user support (not planned)

## Further Notes

- The v1 app (android-lifelog repo) serves as reference implementation and source for cherry-picked components
- The v1 Termux server (lifelog_debug/server.py) is deprecated; all server logic moves to FastAPI in Docker
- LM Studio stays on Windows native (better ROCm support); all other services run in WSL2/Docker
- The existing speaker recognition code (ECAPA embeddings + SpeakerClusterAssigner) will be migrated to the new app
