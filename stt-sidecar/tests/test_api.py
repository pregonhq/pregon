"""The HTTP contract (openapi.yaml) against a fake engine: shapes, pairing, limits, readiness."""

from __future__ import annotations

import json
import threading
import uuid
from dataclasses import dataclass, field

import pytest
from fastapi.testclient import TestClient

from app.engine import AudioDecodingError, ModelLoader, Transcript, TranscriptSegment
from app.main import create_app
from app.settings import Settings
from tests import oggopus

UNDECODABLE = b"not audio at all"


@dataclass
class FakeTranscriber:
    calls: list[tuple[bytes, str, str | None]] = field(default_factory=list)

    def transcribe(self, audio: bytes, language: str, prompt: str | None) -> Transcript:
        self.calls.append((audio, language, prompt))
        if audio == UNDECODABLE:
            raise AudioDecodingError("Invalid data found when processing input")
        return Transcript(
            text=f"transcript of {len(audio)} bytes",
            language=language,
            duration=10.0,
            segments=(TranscriptSegment(0.0, 4.5, "transcript of", -0.21, 0.02, 1.1), TranscriptSegment(4.5, 10.0, f"{len(audio)} bytes", -0.3, 0.05, 1.2)),
        )


SETTINGS = Settings(model="fake", max_batch=3, max_segment_bytes=20_000, model_dir="unused")


@pytest.fixture
def engine() -> FakeTranscriber:
    return FakeTranscriber()


@pytest.fixture
def client(engine: FakeTranscriber) -> TestClient:
    with TestClient(create_app(SETTINGS, transcriber=engine)) as client:
        yield client


def segment_id() -> str:
    return str(uuid.uuid4())


def post(client: TestClient, request: dict | str | None, audio: dict[str, bytes], **extra_parts: bytes):
    files = {}
    if request is not None:
        files["request"] = ("request", request if isinstance(request, str) else json.dumps(request), "application/json")
    for name, data in {**audio, **extra_parts}.items():
        files[name] = (name, data, "audio/ogg")
    return client.post("/v1/transcriptions", files=files)


def assert_problem(response, status: int) -> dict:
    assert response.status_code == status, response.text
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body["status"] == status and body["detail"]
    return body


class TestHealth:
    def test_up_with_model_details_once_loaded(self, client: TestClient):
        response = client.get("/health")

        assert response.status_code == 200
        assert response.json() == {"status": "UP", "model": "fake", "device": "cpu", "computeType": "int8"}

    def test_down_while_the_model_loads_and_transcriptions_get_503(self):
        release = threading.Event()
        engine = FakeTranscriber()

        def slow_factory():
            release.wait(timeout=10)
            return engine

        with TestClient(create_app(SETTINGS, loader=ModelLoader(slow_factory))) as client:
            health = client.get("/health")
            assert health.status_code == 503
            assert health.json()["status"] == "DOWN"
            assert health.json()["detail"] == "loading model"

            refused = post(client, {"language": "es", "segments": [segment_id()]}, {})
            assert_problem(refused, 503)
            assert refused.headers["retry-after"]
            release.set()

    def test_down_with_the_reason_when_the_model_failed_to_load(self):
        def broken_factory():
            raise RuntimeError("no such model: fake")

        loader = ModelLoader(broken_factory)
        with TestClient(create_app(SETTINGS, loader=loader)) as client:
            for _ in range(100):
                if loader.detail != "loading model":
                    break
                threading.Event().wait(0.02)
            health = client.get("/health")
            assert health.status_code == 503
            assert "no such model" in health.json()["detail"]


class TestTranscribe:
    def test_a_batch_returns_one_result_per_segment_in_request_order(self, client: TestClient, engine: FakeTranscriber):
        ids = [segment_id() for _ in range(3)]
        audio = {ids[0]: oggopus.silence(100, 1), ids[1]: oggopus.silence(200, 2), ids[2]: oggopus.silence(50, 3)}

        response = post(client, {"language": "es", "prompt": "combo, papas grandes", "segments": ids}, audio)

        assert response.status_code == 200, response.text
        body = response.json()
        assert body["model"] == "fake"
        assert [r["id"] for r in body["results"]] == ids
        first = body["results"][0]
        assert first["status"] == "transcribed"
        assert first["text"] == f"transcript of {len(audio[ids[0]])} bytes"
        assert first["language"] == "es"
        assert first["duration"] == 10.0
        assert first["segments"][0] == {"start": 0.0, "end": 4.5, "text": "transcript of", "avgLogprob": -0.21, "noSpeechProb": 0.02, "compressionRatio": 1.1}
        assert isinstance(first["processingMillis"], int)
        assert [(call[0], call[1], call[2]) for call in engine.calls] == [(audio[i], "es", "combo, papas grandes") for i in ids]

    def test_without_prompt_the_engine_gets_none(self, client: TestClient, engine: FakeTranscriber):
        sid = segment_id()

        assert post(client, {"language": "es", "segments": [sid]}, {sid: oggopus.silence(10)}).status_code == 200
        assert engine.calls[0][2] is None

    def test_an_undecodable_segment_fails_alone_and_the_batch_goes_on(self, client: TestClient):
        good, bad, other = segment_id(), segment_id(), segment_id()

        response = post(client, {"language": "es", "segments": [good, bad, other]}, {good: oggopus.silence(10), bad: UNDECODABLE, other: oggopus.silence(20)})

        assert response.status_code == 200
        results = response.json()["results"]
        assert [r["status"] for r in results] == ["transcribed", "failed", "transcribed"]
        assert results[1] == {"id": bad, "status": "failed", "error": "audio not decodable: Invalid data found when processing input"}

    def test_parts_are_paired_by_name_not_by_position(self, client: TestClient, engine: FakeTranscriber):
        a, b = segment_id(), segment_id()
        audio_a, audio_b = oggopus.silence(10, 1), oggopus.silence(10, 2)

        # The parts arrive in the opposite order of the segments list.
        response = post(client, {"language": "es", "segments": [a, b]}, {b: audio_b, a: audio_a})

        assert response.status_code == 200
        assert engine.calls[0][0] == audio_a and engine.calls[1][0] == audio_b


class TestRejections:
    def test_segment_without_audio_part(self, client: TestClient):
        present, missing = segment_id(), segment_id()
        body = assert_problem(post(client, {"language": "es", "segments": [present, missing]}, {present: oggopus.silence(5)}), 400)
        assert missing in body["detail"]

    def test_audio_part_not_listed_in_segments(self, client: TestClient):
        listed, stray = segment_id(), segment_id()
        body = assert_problem(post(client, {"language": "es", "segments": [listed]}, {listed: oggopus.silence(5), stray: oggopus.silence(5)}), 400)
        assert stray in body["detail"]

    def test_duplicate_segment_ids(self, client: TestClient):
        sid = segment_id()
        assert_problem(post(client, {"language": "es", "segments": [sid, sid]}, {sid: oggopus.silence(5)}), 400)

    def test_missing_or_invalid_request_part(self, client: TestClient):
        sid = segment_id()
        assert_problem(post(client, None, {sid: oggopus.silence(5)}), 400)
        assert_problem(post(client, "{not json", {sid: oggopus.silence(5)}), 400)
        assert_problem(post(client, {"segments": [sid]}, {sid: oggopus.silence(5)}), 400)
        assert_problem(post(client, {"language": "es", "segments": []}, {}), 400)
        assert_problem(post(client, {"language": "es", "segments": ["not-a-uuid"]}, {}), 400)
        assert_problem(post(client, {"language": "es", "segments": [sid], "speaker": "x"}, {sid: oggopus.silence(5)}), 400)

    def test_language_must_be_a_primary_subtag(self, client: TestClient):
        sid = segment_id()
        assert_problem(post(client, {"language": "es-MX", "segments": [sid]}, {sid: oggopus.silence(5)}), 400)
        assert_problem(post(client, {"language": "Spanish", "segments": [sid]}, {sid: oggopus.silence(5)}), 400)

    def test_prompt_longer_than_the_contract_allows(self, client: TestClient):
        sid = segment_id()
        assert_problem(post(client, {"language": "es", "prompt": "x" * 2001, "segments": [sid]}, {sid: oggopus.silence(5)}), 400)

    def test_more_segments_than_the_sidecar_accepts(self, client: TestClient):
        ids = [segment_id() for _ in range(SETTINGS.max_batch + 1)]
        body = assert_problem(post(client, {"language": "es", "segments": ids}, {i: oggopus.silence(5) for i in ids}), 400)
        assert str(SETTINGS.max_batch) in body["detail"]

    def test_audio_over_the_limit_is_413(self, client: TestClient, engine: FakeTranscriber):
        sid = segment_id()
        assert_problem(post(client, {"language": "es", "segments": [sid]}, {sid: b"\x00" * (SETTINGS.max_segment_bytes + 1)}), 413)
        assert engine.calls == []

    def test_audio_exactly_at_the_limit_is_accepted(self, client: TestClient):
        sid = segment_id()
        assert post(client, {"language": "es", "segments": [sid]}, {sid: b"\x00" * SETTINGS.max_segment_bytes}).status_code == 200

    def test_declared_body_over_the_batch_limit_is_413_before_reading(self, client: TestClient):
        response = client.post(
            "/v1/transcriptions",
            content=b"",
            headers={"content-type": "multipart/form-data; boundary=x", "content-length": str(50_000_000)},
        )
        assert_problem(response, 413)

    def test_non_multipart_body_is_400(self, client: TestClient):
        assert_problem(client.post("/v1/transcriptions", json={"language": "es", "segments": []}), 400)
