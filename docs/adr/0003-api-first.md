# ADR-0003: API-first, optional admin UI, headless by default
Status: Accepted

## Decision
`api/openapi.yaml` is the source of truth. Two APIs: **Ingest** (high volume, signals) and
**Admin** (campaigns, sources, KPIs, configuration). The admin UI is optional, runs as a separate
container/profile and uses only public endpoints — no UI-only backend features. Business
configuration can also be declared as YAML and is applied through the same API at startup.
## Consequences
Any host software (e.g. a POS) can manage Pregón from its own UI. Clients can be generated.
