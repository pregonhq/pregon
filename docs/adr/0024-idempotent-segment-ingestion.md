# ADR-0024: Idempotent segment ingestion and persistent queue
Status: Accepted (amends the Segment row of ADR-0022; hot path, quotas and retention refined by ADR-0025)

## Context
Agents upload speech segments over unreliable networks. When a connection drops after the server
committed a segment but before the agent read the response, the agent retries. Ingestion must
accept every segment exactly once, never lose an accepted one across restarts, and slow agents
down when transcription cannot keep up (ADR-0007).

## Decision
1. **The segment id is the idempotency key, in the URL.** Agents upload with
   `PUT /v1/segments/{segmentId}`, where the id is a UUIDv7 they generate (ADR-0022). `PUT` is
   idempotent by HTTP semantics, so no extra header is needed.
2. **Multipart upload.** One request carries a `metadata` part (JSON, validated by the contract)
   and an `audio` part (Ogg/Opus).
3. **Duplicates.** The server keeps a SHA-256 of the metadata and audio of every accepted segment.
   - First upload: `202 Accepted` with a receipt.
   - Same id, same content: `200 OK` with the original receipt. The agent stops retrying.
   - Same id, different content: `409 Conflict`. It is an agent bug; the agent must not retry.
   The hash is what allows the comparison after the audio is deleted.
4. **The queue is a PostgreSQL table** consumed with `SELECT ... FOR UPDATE SKIP LOCKED`.
   Recording the segment and enqueuing it happen in one transaction, so there is no state where
   one happened without the other. Valkey was rejected because it does not persist to disk in
   this deployment (ADR-0019) and a dual write could diverge; files on disk were rejected as more
   moving parts for audio of about 30 KB per 10 s (Opus at 24 kbit/s).
5. **Audio is transient, segment metadata is kept.** This amends ADR-0022: the segment record
   (id, tenant, source, capture times, voiced duration, hash, received time, state) is persisted
   because interactions reference their segments and duplicates are detected against it. The
   audio is deleted once transcribed. The metadata holds no personal data.
6. **Agents buffer in RAM only** (ADR-0006). The buffer is bounded; when full, the oldest
   segments are dropped and counted in a metric. A long outage leaves a visible gap, never
   invented data: privacy wins over completeness.
7. **Backpressure.** The queue has a maximum size in bytes per tenant and a share per source, so
   one noisy source cannot starve the others. Above the limit the server answers `503` with a
   `Retry-After` derived from the observed transcription rate. Agents retry with exponential
   backoff and full jitter, and always honor `Retry-After`.
8. **Workers take jobs with a lease.** A job whose lease expires returns to the queue. After 3
   failed attempts the segment becomes `FAILED` and its audio is deleted anyway. Transcribing the
   same segment twice must not duplicate results.

Validation happens before anything is stored: the source must exist in the caller's tenant
(`404` otherwise, without revealing other tenants) and must not be archived (`409`); the audio
must be Ogg/Opus (`400`) and within the size limit (`413`). A segment whose capture end is far
from its arrival time is accepted and flagged as clock-skew suspect (ADR-0022).

## Consequences
At-least-once delivery from agents becomes exactly-once acceptance on the server, with one
database and no distributed transaction. Limits, lease duration and attempts are tunable
parameters with documented defaults (ADR-0020). The ingestion endpoint, the queue and the
backpressure are maintainer-designed pieces: this ADR fixes their behavior, not their code.
