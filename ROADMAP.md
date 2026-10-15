# Roadmap

## Phase 0 — Foundations
- [x] GitHub organization and repository configured (see maintainer checklist)
- [x] Build tool decided; multi-module skeleton: `core`, `spi`, `server`, `agent` (`stt-sidecar` comes with the compose file)
- [x] Latest stable versions verified and pinned (ADR-0014)
- [x] CI: build + tests + gitleaks on every PR
- [x] `api/openapi.yaml` skeleton (ingest + admin), linted in CI
- [x] `deploy/docker-compose.yml` with `lite` profile (server, PostgreSQL, Valkey, STT sidecar), smoke-tested in CI

## Phase 1 — MVP: live audio, end to end
- [x] Domain model designed and documented (ADR-0022)
- [ ] Capture agent: mic, RTSP and WAV-replay sources; ring buffer; adaptive energy gate;
      Silero VAD; hysteresis segmenter; Opus; idempotent upload with retries
- [ ] Operating hours per source (agent only listens during business hours)
- [ ] Server: ingestion API, persistent queue, retention policy (delete audio after transcription)
- [ ] STT sidecar: faster-whisper, fixed language, campaign phrases as initial prompt, batching
- [ ] Campaign rules: keywords, synonyms, fuzzy matching (Spanish normalizer behind an interface)
- [ ] Interactions by silence gap; match-rate KPI (offer rate as the main case) per location, role, source and shift via API
- [ ] Per-stage metrics: % dropped by gate, % dropped by VAD, queue size, STT latency
- [x] API key authentication (ADR-0018): scoped keys, `pregon keys` command

**MVP is done when:** `docker compose --profile lite up` + running the agent + creating a campaign
via API + speaking near the mic results in a KPI readable via API, with per-stage metrics.

## Later phases (designed, not scheduled)
- Interactions from POS events (ticket = interaction)
- Additive KPI and campaign features (ADR-0023): rate of "all targets matched" for protocols and
  checklists, active time windows per campaign, take rate crossing matches with POS tickets
- Legal guide for adopters: Mexico's 2025 personal data law, GDPR and the EU AI Act; visible
  notice; measurement per post, never per person
- Semantic offer detection, in increments, each with its own ADR (ADR-0022):
  1. Local embeddings tier (ONNX): paraphrases close to a target's phrases ("¿y para acompañar?")
  2. LLM tier (none/local/cloud, ADR-0012): offering versus merely mentioning, only for undecided
     cases, with Redis cache and daily budget
  3. Tenant catalog synchronized from the adopter's POS menu, feeding category items
- Pluggable transports: Redis Streams, Kafka (KRaft); outbox; idempotent consumers
- Signed webhooks and n8n example workflows
- Admin UI with Angular Elements (`ui` profile)
- SDKs: Kotlin (Android/JVM), TypeScript; Spring Boot starter; Maven Central and npm releases
- Multilingual: per-location language lists, detection once per interaction, phrases per language
- Observability: OpenTelemetry, Grafana; multi-arch images on GHCR; GPU profile
- Multi-tenant SaaS deployment; Helm chart for k3s
- Plugins: POS-to-video correlation, people counting, shelf display compliance
