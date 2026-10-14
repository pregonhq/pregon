# ADR-0015: Own minimal STT sidecar
Status: Accepted

## Context
Transcription runs in a Python container with faster-whisper (ADR-0007). Existing images expose
generic APIs that do not fit everything Pregón needs: fixed language, campaign phrases as initial
prompt, batching and strict deletion of audio after use.

## Decision
Write a small sidecar (FastAPI + faster-whisper) with its own versioned contract
(`stt-sidecar/openapi.yaml`): `POST /v1/transcriptions` (one or several segments, `language`,
optional `prompt`), `GET /health`. Audio is processed in memory and never written to disk.
The model is downloaded on first start into a volume (ADR-0002). The server talks to it through
the speech-to-text port of ADR-0012, so a different STT provider can replace it.

## Consequences
Small code to maintain (a few hundred lines), full control of the contract and the privacy
guarantees. An existing OpenAI-compatible server can be plugged in later as another adapter.
