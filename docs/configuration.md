# Configuration

Rules (ADR-0009, refined by ADR-0020): infrastructure via environment variables; business
configuration via API or YAML. Tunable parameters have documented defaults. Connections and
secrets have **no defaults**: if one is missing, the service refuses to start and names it.
The Docker Compose deployment wires the connections between its own services, so the only step
before the first start is `cp deploy/.env.example deploy/.env` and setting the secrets.

## Environment variables (infrastructure)

### Connections and secrets — required, no default

| Variable | Purpose | Set by compose |
|---|---|---|
| `PREGON_DB_URL` | PostgreSQL JDBC URL | yes, points to the `postgres` service |
| `PREGON_DB_USER` | PostgreSQL user | yes |
| `PREGON_DB_PASSWORD` | PostgreSQL password | no — from `.env` (`POSTGRES_PASSWORD`) |
| `PREGON_REDIS_URL` | Valkey/Redis URL (ADR-0019) | yes, points to the `valkey` service |
| `PREGON_STT_URL` | STT sidecar base URL (ADR-0015), e.g. `http://stt:8000`. The one connection that may be absent: without it the server accepts segments and runs no transcription workers (ADR-0025), and the `stt` health component does not exist | yes, points to the `stt` service |

### Tunable parameters — documented defaults

| Variable | Default | Purpose |
|---|---|---|
| `PREGON_TENANT_DEFAULT` | `default` | Tenant used when none is given |
| `PREGON_LANGUAGE` | `es` | Single audio language for the installation (MVP) |
| `PREGON_STT_MODEL` | `small` | Whisper model size: tiny, base, small… |
| `PREGON_STT_DEVICE` | `cpu` | `cpu` or `cuda` |
| `PREGON_STT_COMPUTE_TYPE` | `int8` | Quantization |
| `PREGON_RETENTION_AUDIO` | `delete-after-transcription` | Or a duration for debugging, e.g. `PT2H` |
| `PREGON_RETENTION_TRANSCRIPTS` | `none` | `none` or a duration, e.g. `P30D` |
| `PREGON_INGEST_MAX_SEGMENT_BYTES` | `1048576` | Largest accepted segment (1 MiB, about 5 min of Opus at 24 kbit/s) |
| `PREGON_INGEST_QUEUE_MAX_BYTES` | `268435456` | Queue size per tenant before `503` (256 MiB, about 25 h of speech at 24 kbit/s) |
| `PREGON_INGEST_CLOCK_SKEW_THRESHOLD` | `PT2M` | Capture end vs arrival time beyond which a segment is flagged |
| `PREGON_STT_BATCH` | `16` | Segments the server sends to the sidecar per request. Equal to the sidecar's `PREGON_STT_MAX_BATCH`; the contract caps both at 64 |
| `PREGON_STT_TIMEOUT` | `PT5M` | How long one batch may take end to end before it is retried later. 16 segments of 10 s at 1× real time on a CPU take under 3 min; the rest is room for a cold cache |
| `PREGON_STT_LEASE` | `PT2M` | How long a worker owns a job before it returns to the queue |
| `PREGON_STT_MAX_ATTEMPTS` | `3` | Failed attempts before a segment is `FAILED` and its audio deleted |

The reason for each default is documented next to the setting when it is implemented.

### STT sidecar — tunable parameters, documented defaults

The sidecar reads its own variables (`stt-sidecar/README.md`). `PREGON_STT_MODEL`, `PREGON_STT_DEVICE`
and `PREGON_STT_COMPUTE_TYPE` above are passed through to it by Compose when set in `.env`.

| Variable | Default | Purpose |
|---|---|---|
| `PREGON_STT_MODEL_DIR` | `/models` | Where models are downloaded on first start and read from (a volume in Compose) |
| `PREGON_STT_CPU_THREADS` | `0` | Threads for one transcription; `0` lets the engine decide |
| `PREGON_STT_BEAM_SIZE` | `5` | Beam search width; `1` is faster and a little less accurate |
| `PREGON_STT_CONCURRENCY` | `1` | Batches transcribed at once; on CPU one batch already uses every core |
| `PREGON_STT_MAX_BATCH` | `16` | Segments accepted per request; the contract caps it at 64 |
| `PREGON_STT_MAX_SEGMENT_BYTES` | `1048576` | Largest audio part, aligned with `PREGON_INGEST_MAX_SEGMENT_BYTES` |

## Business configuration (API / YAML)
- **Locations**: id, name, timezone, shifts (named weekly windows in local time).
- **Sources**: id, location, input (mic/rtsp/file), optional role, operating hours.
- **Audio pipeline per source** (defaults provided): energy gate margin, VAD threshold,
  silence hangover, pre-roll, min/max segment length.
- **Interactions per source**: strategy `SILENCE_GAP` (MVP) or `POS_TICKET` (later); gap
  threshold, allowed lateness before closing, minimum voiced duration, maximum duration.
- **Campaigns**: id, period (end optional for permanent campaigns), language, expectation
  (EXPECTED or FORBIDDEN), scope (locations and optional roles), targets with phrases and
  optional items with synonyms.

Every parameter must be documented with its default and why that default was chosen.
