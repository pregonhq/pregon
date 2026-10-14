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
| `PREGON_STT_URL` | STT sidecar base URL (ADR-0015) | yes, points to the `stt` service |

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

The reason for each default is documented next to the setting when it is implemented.

## Business configuration (API / YAML)
- **Sources**: id, location, input (mic/rtsp/file), timezone, operating hours.
- **Audio pipeline per source** (defaults provided): energy gate margin, VAD threshold,
  silence hangover, pre-roll, min/max segment length.
- **Interactions**: strategy `silence-gap` (MVP, with gap duration) or `pos-event` (later).
- **Campaigns**: id, period, language, products with phrases and synonyms.

Every parameter must be documented with its default and why that default was chosen.
