# STT sidecar

Speech-to-text service used by `pregon-server` (ADR-0015): Python, FastAPI and faster-whisper.
Its contract is [`openapi.yaml`](openapi.yaml), written by hand and linted in CI.

## How it works
- `POST /v1/transcriptions` takes a batch: a `request` JSON part (`language`, optional campaign
  `prompt`, the segment ids) and one Ogg/Opus part **named with each segment id**. Each segment is
  transcribed on its own and reported with its own status, so one undecodable audio does not fail
  the batch.
- The body is read with a streaming multipart parser (`app/multipart.py`) that keeps parts in
  memory and refuses a part over `PREGON_STT_MAX_SEGMENT_BYTES` while it arrives. Audio never
  touches disk (ADR-0006); logs carry ids, sizes and timings, never text.
- `GET /health` is `503` until the model is loaded. Docker Compose waits for the `200`, so the
  server never sends jobs to a sidecar that cannot take them. The first start downloads the model
  into the `/models` volume.
- Whisper runs with `condition_on_previous_text=False`: segments are a few seconds long and a
  hallucination in one must not leak into the next. The campaign phrases go in as the initial
  prompt (ADR-0007).

## Configuration
All variables are tunables with defaults; the sidecar has no connections or secrets of its own.

| Variable | Default | Purpose |
|---|---|---|
| `PREGON_STT_MODEL` | `small` | Whisper model size or path |
| `PREGON_STT_DEVICE` | `cpu` | `cpu` or `cuda` |
| `PREGON_STT_COMPUTE_TYPE` | `int8` | Quantization |
| `PREGON_STT_MODEL_DIR` | `/models` | Where models are downloaded and read from |
| `PREGON_STT_CPU_THREADS` | `0` | Threads for one transcription; `0` lets the engine decide |
| `PREGON_STT_BEAM_SIZE` | `5` | Beam search width; `1` is faster and a little less accurate |
| `PREGON_STT_CONCURRENCY` | `1` | Batches transcribed at once; on CPU one batch already uses every core |
| `PREGON_STT_MAX_BATCH` | `16` | Segments accepted per request (the contract caps it at 64) |
| `PREGON_STT_MAX_SEGMENT_BYTES` | `1048576` | Largest audio part; aligned with the server's segment limit |

## Tests
```
pip install --require-hashes -r requirements.txt -r requirements-dev.txt
python -m pytest            # everything, downloads the `tiny` model once (about 75 MB)
python -m pytest -m "not model"   # API and parser against a fake engine, under a second
```
Set `PREGON_STT_MODEL_DIR` to keep the downloaded model between runs.

## Dependencies
Direct dependencies live in `requirements.in` (runtime) and `requirements-dev.in` (tests).
The `.txt` files are generated with exact versions and hashes, excluding anything published in
the last 14 days (ADR-0014). To regenerate them, set `--exclude-newer` to 14 days before today:

```
docker run --rm -v "$PWD:/work" -w /work python:3.13.16-slim-trixie sh -c '
  pip install --quiet --root-user-action=ignore uv==0.12.21 &&
  uv pip compile requirements.in --python-version 3.13 --generate-hashes \
    --exclude-newer YYYY-MM-DD --no-header --output-file requirements.txt &&
  uv pip compile requirements-dev.in --python-version 3.13 --generate-hashes \
    --exclude-newer YYYY-MM-DD --no-header --output-file requirements-dev.txt'
```
`av` is pinned to 18.x on purpose: PyAV 19 removed an argument faster-whisper 1.2.1 still passes,
and every decode fails. Lift the pin when faster-whisper releases a fix.
