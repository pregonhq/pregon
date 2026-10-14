"""Pregón speech-to-text sidecar (ADR-0015).

Phase 0 skeleton: only the health probe. Transcription with faster-whisper arrives in Phase 1.
The contract is stt-sidecar/openapi.yaml, so FastAPI's generated docs are disabled.
"""

from fastapi import FastAPI

app = FastAPI(title="Pregón STT sidecar", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}
