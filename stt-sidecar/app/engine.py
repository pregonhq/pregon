"""The speech-to-text engine behind a small port, so the API is testable without a model.

faster-whisper is imported lazily: it is heavy, and the API tests never need it.
"""

from __future__ import annotations

import io
import logging
import threading
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from .settings import Settings

log = logging.getLogger("pregon.stt")

SAMPLING_RATE = 16_000


class AudioDecodingError(Exception):
    """The bytes are not audio the engine can decode."""


@dataclass(frozen=True)
class TranscriptSegment:
    start: float
    end: float
    text: str
    avg_logprob: float
    no_speech_prob: float
    compression_ratio: float


@dataclass(frozen=True)
class Transcript:
    text: str
    language: str
    duration: float
    segments: tuple[TranscriptSegment, ...]


class Transcriber(Protocol):
    def transcribe(self, audio: bytes, language: str, prompt: str | None) -> Transcript:
        """Transcribes one segment held in memory. Raises AudioDecodingError for undecodable bytes."""


class FasterWhisperTranscriber:
    """faster-whisper on the configured device. Construction loads (and first downloads) the model."""

    def __init__(self, settings: Settings) -> None:
        from faster_whisper import WhisperModel

        self._model = WhisperModel(
            settings.model,
            device=settings.device,
            compute_type=settings.compute_type,
            cpu_threads=settings.cpu_threads,
            download_root=settings.model_dir,
        )
        self._beam_size = settings.beam_size

    def transcribe(self, audio: bytes, language: str, prompt: str | None) -> Transcript:
        from faster_whisper.audio import decode_audio

        try:
            waveform = decode_audio(io.BytesIO(audio), sampling_rate=SAMPLING_RATE)
        except Exception as error:  # PyAV raises many types; all mean "not decodable audio"
            raise AudioDecodingError(str(error) or error.__class__.__name__) from error
        duration = len(waveform) / SAMPLING_RATE

        # Segments are a few seconds long, so there is no earlier text to condition on; keeping
        # the default would let a hallucination in one window leak into the next.
        segments, _info = self._model.transcribe(
            waveform,
            language=language,
            initial_prompt=prompt or None,
            beam_size=self._beam_size,
            condition_on_previous_text=False,
            vad_filter=False,
        )
        parts = tuple(
            TranscriptSegment(
                start=float(s.start),
                end=float(s.end),
                text=s.text.strip(),
                avg_logprob=float(s.avg_logprob),
                no_speech_prob=float(s.no_speech_prob),
                compression_ratio=float(s.compression_ratio),
            )
            for s in segments
        )
        text = " ".join(p.text for p in parts if p.text)
        return Transcript(text=text, language=language, duration=duration, segments=parts)


class ModelLoader:
    """Loads the engine in a background thread; the health probe reports readiness meanwhile."""

    def __init__(self, factory: Callable[[], Transcriber]) -> None:
        self._factory = factory
        self._lock = threading.Lock()
        self._transcriber: Transcriber | None = None
        self._error: BaseException | None = None
        self._started = False

    @classmethod
    def ready_with(cls, transcriber: Transcriber) -> ModelLoader:
        loader = cls(lambda: transcriber)
        loader._transcriber = transcriber
        loader._started = True
        return loader

    def start(self) -> None:
        with self._lock:
            if self._started:
                return
            self._started = True
        threading.Thread(target=self._load, name="model-loader", daemon=True).start()

    def _load(self) -> None:
        try:
            transcriber = self._factory()
        except BaseException as error:  # noqa: BLE001 - reported through the health probe
            log.exception("model failed to load")
            with self._lock:
                self._error = error
            return
        with self._lock:
            self._transcriber = transcriber
        log.info("model loaded")

    @property
    def transcriber(self) -> Transcriber | None:
        with self._lock:
            return self._transcriber

    @property
    def detail(self) -> str | None:
        """Why the sidecar is not ready, or None when it is."""
        with self._lock:
            if self._transcriber is not None:
                return None
            if self._error is not None:
                return f"model failed to load: {self._error}"
            return "loading model"
