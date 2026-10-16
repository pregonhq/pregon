"""The real engine with the smallest model: decoding in memory, result shape, failure mode.

Marked `model`: the first run downloads `tiny` (about 75 MB) into PREGON_STT_MODEL_DIR or a
temporary directory. What the model says about synthetic silence is not asserted; that it decodes
Ogg/Opus from bytes, reports the right duration and refuses non-audio is.
"""

from __future__ import annotations

import os

import pytest

from app.engine import AudioDecodingError, FasterWhisperTranscriber, Transcript
from app.settings import Settings
from tests import oggopus

pytestmark = pytest.mark.model


@pytest.fixture(scope="module")
def engine(tmp_path_factory: pytest.TempPathFactory) -> FasterWhisperTranscriber:
    model_dir = os.environ.get("PREGON_STT_MODEL_DIR") or str(tmp_path_factory.mktemp("models"))
    return FasterWhisperTranscriber(Settings(model="tiny", compute_type="int8", model_dir=model_dir, beam_size=1))


def test_decodes_ogg_opus_from_memory_and_reports_its_duration(engine: FasterWhisperTranscriber):
    transcript = engine.transcribe(oggopus.silence(500), "es", "combo, papas grandes")

    assert isinstance(transcript, Transcript)
    assert transcript.language == "es"
    assert abs(transcript.duration - 10.0) < 0.1
    assert isinstance(transcript.text, str)
    for segment in transcript.segments:
        assert 0.0 <= segment.start <= segment.end <= 10.5
        assert 0.0 <= segment.no_speech_prob <= 1.0


def test_non_audio_bytes_raise_a_decoding_error(engine: FasterWhisperTranscriber):
    with pytest.raises(AudioDecodingError):
        engine.transcribe(b"definitely not an audio container", "es", None)


def test_wav_is_decodable_audio_even_if_the_server_rejects_it(engine: FasterWhisperTranscriber):
    # The sidecar is format-agnostic; Ogg/Opus-only is the ingestion contract's rule (ADR-0024).
    transcript = engine.transcribe(oggopus.wav(), "es", None)

    assert abs(transcript.duration - 0.1) < 0.01
