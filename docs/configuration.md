# Configuration (proposal — finalize in Phase 0/1)

Rules: infrastructure via environment variables; business configuration via API or YAML.
Pregón must start with **zero configuration** using the defaults below.

## Environment variables (infrastructure)

| Variable | Default | Purpose |
|---|---|---|
| `PREGON_TENANT_DEFAULT` | `default` | Tenant used when none is given |
| `PREGON_LANGUAGE` | `es` | Single audio language for the installation (MVP) |
| `PREGON_STT_MODEL` | `small` | Whisper model size: tiny, base, small… |
| `PREGON_STT_DEVICE` | `cpu` | `cpu` or `cuda` |
| `PREGON_STT_COMPUTE_TYPE` | `int8` | Quantization |
| `PREGON_RETENTION_AUDIO` | `delete-after-transcription` | Or a duration for debugging, e.g. `PT2H` |
| `PREGON_RETENTION_TRANSCRIPTS` | `none` | `none` or a duration, e.g. `P30D` |
| `PREGON_DB_URL` / `_USER` / `_PASSWORD` | compose defaults | PostgreSQL |
| `PREGON_REDIS_URL` | compose default | Redis |

## Business configuration (API / YAML)
- **Sources**: id, location, input (mic/rtsp/file), timezone, operating hours.
- **Audio pipeline per source** (defaults provided): energy gate margin, VAD threshold,
  silence hangover, pre-roll, min/max segment length.
- **Interactions**: strategy `silence-gap` (MVP, with gap duration) or `pos-event` (later).
- **Campaigns**: id, period, language, products with phrases and synonyms.

Every parameter must be documented with its default and why that default was chosen.
