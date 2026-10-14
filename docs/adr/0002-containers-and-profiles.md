# ADR-0002: Everything in containers, selected by Compose profiles
Status: Accepted

## Decision
All services ship as container images. Docker Compose profiles: `lite` (MVP: server, PostgreSQL,
Redis, STT sidecar), later `full` (Kafka, n8n, observability, UI) and `gpu`. Images are non-root,
have healthchecks and resource limits, multi-arch (amd64/arm64) in later phases. Models are not
baked into images: downloaded on first start into a volume. Images published to GHCR.
## Consequences
One-command start; same images for a mini PC or a server. Helm/k3s only in later phases.
