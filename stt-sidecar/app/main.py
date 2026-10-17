"""Pregón speech-to-text sidecar (ADR-0015). The contract is stt-sidecar/openapi.yaml.

FastAPI's generated docs are disabled: the contract is written by hand and linted in CI.
"""

from __future__ import annotations

import logging
import threading
import time
from contextlib import asynccontextmanager
from typing import Any
from uuid import UUID

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .engine import AudioDecodingError, FasterWhisperTranscriber, ModelLoader, Transcriber
from .multipart import MultipartError, MultipartReader, PartTooLarge, TooManyParts
from .problems import problem
from .settings import CONTRACT_MAX_SEGMENTS, Settings

log = logging.getLogger("pregon.stt")

REQUEST_PART = "request"
# Headroom for the request part and multipart framing on top of the audio parts.
FRAMING_BYTES = 64 * 1024
RETRY_AFTER_LOADING = "5"


class TranscriptionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    language: str = Field(pattern=r"^[a-z]{2,3}$")
    prompt: str | None = Field(default=None, max_length=2000)
    segments: list[UUID] = Field(min_length=1, max_length=CONTRACT_MAX_SEGMENTS)


def create_app(
    settings: Settings | None = None,
    transcriber: Transcriber | None = None,
    loader: ModelLoader | None = None,
) -> FastAPI:
    """Builds the application. Production loads faster-whisper; tests pass a ready transcriber or
    their own loader to control when the model becomes available."""
    settings = settings or Settings.from_env()
    if loader is None:
        loader = (
            ModelLoader.ready_with(transcriber)
            if transcriber is not None
            else ModelLoader(lambda: FasterWhisperTranscriber(settings))
        )
    # One batch at a time per slot: on CPU the engine already uses every core for one job.
    slots = threading.Semaphore(settings.concurrency)
    max_body = settings.max_batch * settings.max_segment_bytes + FRAMING_BYTES

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        loader.start()
        yield

    app = FastAPI(title="Pregón STT sidecar", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    def health_body() -> dict[str, Any]:
        body = {
            "status": "UP" if loader.detail is None else "DOWN",
            "model": settings.model,
            "device": settings.device,
            "computeType": settings.compute_type,
        }
        if loader.detail is not None:
            body["detail"] = loader.detail
        return body

    @app.get("/health")
    def health() -> JSONResponse:
        body = health_body()
        return JSONResponse(body, status_code=200 if body["status"] == "UP" else 503)

    @app.post("/v1/transcriptions")
    async def transcribe(request: Request) -> JSONResponse:
        engine = loader.transcriber
        if engine is None:
            return problem(503, loader.detail or "not ready", headers={"Retry-After": RETRY_AFTER_LOADING})

        declared = request.headers.get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > max_body:
            return problem(413, f"body larger than {max_body} bytes")

        try:
            reader = MultipartReader(request.headers.get("content-type"), settings.max_segment_bytes, settings.max_batch + 1)
            async for chunk in request.stream():
                reader.feed(chunk)
            reader.finish()
        except PartTooLarge as error:
            return problem(413, error.reason)
        except TooManyParts:
            return problem(400, f"at most {settings.max_batch} segments per request")
        except MultipartError as error:
            return problem(400, error.reason)

        parts: dict[str, bytes] = {}
        for part in reader.parts:
            if part.name in parts:
                return problem(400, f"duplicate part {part.name!r}")
            parts[part.name] = bytes(part.data)

        request_part = parts.pop(REQUEST_PART, None)
        if request_part is None:
            return problem(400, f"missing part {REQUEST_PART!r}")
        try:
            spec = TranscriptionRequest.model_validate_json(request_part)
        except ValidationError as error:
            return problem(400, "; ".join(f"{'/'.join(str(x) for x in e['loc'])}: {e['msg']}" for e in error.errors()))
        if len(spec.segments) > settings.max_batch:
            return problem(400, f"at most {settings.max_batch} segments per request")
        ids = [str(s) for s in spec.segments]
        if len(set(ids)) != len(ids):
            return problem(400, "segments must be unique")
        missing = [i for i in ids if i not in parts]
        if missing:
            return problem(400, f"segments without an audio part: {', '.join(missing)}")
        unexpected = sorted(set(parts) - set(ids))
        if unexpected:
            return problem(400, f"audio parts not listed in segments: {', '.join(unexpected)}")

        results = await run_in_threadpool(transcribe_batch, engine, spec, ids, parts)
        return JSONResponse({"model": settings.model, "results": results})

    def transcribe_batch(engine: Transcriber, spec: TranscriptionRequest, ids: list[str], parts: dict[str, bytes]) -> list[dict[str, Any]]:
        started = time.monotonic()
        results: list[dict[str, Any]] = []
        with slots:
            for segment_id in ids:
                t0 = time.monotonic()
                try:
                    transcript = engine.transcribe(parts[segment_id], spec.language, spec.prompt)
                except AudioDecodingError as error:
                    log.warning("segment %s: audio not decodable: %s", segment_id, error)
                    results.append({"id": segment_id, "status": "failed", "error": f"audio not decodable: {error}"})
                    continue
                results.append(
                    {
                        "id": segment_id,
                        "status": "transcribed",
                        "text": transcript.text,
                        "language": transcript.language,
                        "duration": transcript.duration,
                        "segments": [
                            {
                                "start": s.start,
                                "end": s.end,
                                "text": s.text,
                                "avgLogprob": s.avg_logprob,
                                "noSpeechProb": s.no_speech_prob,
                                "compressionRatio": s.compression_ratio,
                            }
                            for s in transcript.segments
                        ],
                        "processingMillis": int((time.monotonic() - t0) * 1000),
                    }
                )
        # Ids, sizes and timings only: transcripts never reach the logs (ADR-0006).
        log.info(
            "batch of %d segments, %d audio bytes, %d ms",
            len(ids),
            sum(len(parts[i]) for i in ids),
            int((time.monotonic() - started) * 1000),
        )
        return results

    app.state.settings = settings
    return app


app = create_app()
