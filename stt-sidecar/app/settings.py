"""Settings of the sidecar, read from the environment (ADR-0009, ADR-0020).

Every value here is a tunable parameter with a documented default (docs/configuration.md). The
sidecar has no connections or secrets of its own: it only answers the server.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass

# Hard ceiling of segments per request, fixed by the contract (TranscriptionRequest.segments.maxItems).
CONTRACT_MAX_SEGMENTS = 64


@dataclass(frozen=True)
class Settings:
    model: str = "small"
    device: str = "cpu"
    compute_type: str = "int8"
    model_dir: str = "/models"
    cpu_threads: int = 0
    beam_size: int = 5
    concurrency: int = 1
    max_batch: int = 16
    max_segment_bytes: int = 1_048_576

    @staticmethod
    def from_env(env: Mapping[str, str] = os.environ) -> Settings:
        def integer(name: str, default: int, minimum: int, maximum: int | None = None) -> int:
            raw = env.get(name)
            if raw is None:
                return default
            try:
                value = int(raw)
            except ValueError:
                raise ValueError(f"{name} must be an integer, got {raw!r}") from None
            if value < minimum or (maximum is not None and value > maximum):
                bound = f"between {minimum} and {maximum}" if maximum is not None else f"at least {minimum}"
                raise ValueError(f"{name} must be {bound}, got {value}")
            return value

        return Settings(
            model=env.get("PREGON_STT_MODEL", Settings.model),
            device=env.get("PREGON_STT_DEVICE", Settings.device),
            compute_type=env.get("PREGON_STT_COMPUTE_TYPE", Settings.compute_type),
            model_dir=env.get("PREGON_STT_MODEL_DIR", Settings.model_dir),
            cpu_threads=integer("PREGON_STT_CPU_THREADS", Settings.cpu_threads, 0),
            beam_size=integer("PREGON_STT_BEAM_SIZE", Settings.beam_size, 1),
            concurrency=integer("PREGON_STT_CONCURRENCY", Settings.concurrency, 1),
            max_batch=integer("PREGON_STT_MAX_BATCH", Settings.max_batch, 1, CONTRACT_MAX_SEGMENTS),
            max_segment_bytes=integer("PREGON_STT_MAX_SEGMENT_BYTES", Settings.max_segment_bytes, 1),
        )
