# ADR-0001: Cost-aware processing cascade
Status: Accepted

## Context
Listening to a counter all day must not cost like transcribing or sending all day to an AI.
## Decision
Process in tiers ordered by cost; each tier resolves or discards what it can and passes only the
rest: energy gate → VAD → local transcription → rules → (later) local embeddings → (later) LLM.
The project must run fully without any paid API. Later LLM tier: pluggable provider
(none/local/cloud), cached in Redis, batched, with a daily budget per tenant.
## Consequences
Cheap hardware is viable; every tier must expose metrics (% resolved/dropped) to prove it.
