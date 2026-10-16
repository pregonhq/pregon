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
| `PREGON_STT_URL` | STT sidecar base URL (ADR-0015); required once the server calls the sidecar (Phase 1) | yes, points to the `stt` service |

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
| `PREGON_INGEST_QUEUE_SOURCE_SHARE` | `0.5` | Largest fraction of the tenant's queue one source may hold, so a noisy source cannot starve the others |
| `PREGON_INGEST_RETRY_AFTER_MIN` | `PT5S` | Smallest `Retry-After` of a `503`, used while no transcription rate has been observed yet |
| `PREGON_INGEST_RETRY_AFTER_MAX` | `PT5M` | Largest `Retry-After` of a `503` (ADR-0025) |
| `PREGON_INGEST_INFLIGHT_MAX_BYTES` | `67108864` | Upload bytes held in memory at once before an immediate `503` (64 MiB, ADR-0025) |
| `PREGON_INGEST_QUOTA_RECONCILE` | `PT10S` | How often in-memory queue counters are reconciled with the database (ADR-0025) |
| `PREGON_AUTH_CACHE_TTL` | `PT30S` | How long a resolved API key is cached. Revoking evicts it immediately; this is the bound if that message is lost (ADR-0025) |
| `PREGON_RETENTION_SEGMENTS` | `P1M` | How long segment metadata is kept; older monthly partitions are dropped, so 1 to 2 months exist at any time (ADR-0025) |
| `PREGON_INGEST_CLOCK_SKEW_THRESHOLD` | `PT2M` | Capture end vs arrival time beyond which a segment is flagged |
| `PREGON_STT_LEASE` | `PT2M` | How long a worker owns a job before it returns to the queue |
| `PREGON_STT_MAX_ATTEMPTS` | `3` | Failed attempts before a segment is `FAILED` and its audio deleted |

The reason for each default is documented next to the setting when it is implemented; the
ingestion and queue defaults are derived in `docs/capacity.md`.

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
