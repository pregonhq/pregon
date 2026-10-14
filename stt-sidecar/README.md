# STT sidecar

Speech-to-text service used by `pregon-server` (ADR-0015). Python, FastAPI and, from Phase 1,
faster-whisper. Its contract is [`openapi.yaml`](openapi.yaml).

## Dependencies

Direct dependencies live in `requirements.in`. `requirements.txt` is generated with exact
versions and hashes, excluding anything published in the last 14 days (ADR-0014). To regenerate
it, set `--exclude-newer` to 14 days before today:

```
docker run --rm -v "$PWD:/work" -w /work python:3.13.16-slim-trixie sh -c '
  pip install --quiet --root-user-action=ignore uv==0.12.21 &&
  uv pip compile requirements.in --python-version 3.13 --generate-hashes \
    --exclude-newer YYYY-MM-DD --no-header --output-file requirements.txt'
```
