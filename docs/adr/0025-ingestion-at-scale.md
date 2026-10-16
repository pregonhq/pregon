# ADR-0025: Ingestion hot path, quotas and queue under sustained load
Status: Accepted 2026-10-16 (refines ADR-0024; amends the Segment row of ADR-0022 for retention)

## Context
ADR-0024 fixes *what* ingestion guarantees. This ADR fixes *how* it stays cheap when thousands
of agents upload continuously, and after an outage, when every agent flushes its RAM buffer at
once. The load model is in `docs/capacity.md`: about 0.07 uploads per second per active source,
30 KB each, with bursts of 10 to 50 times the average for minutes after a network incident.

The code before this ADR made two database round trips per authenticated request (key lookup and
`last_used_at` update), one more to read the source, and would have needed a `SUM` over the queue
to enforce quotas. Spring's multipart support writes every part to a temporary file by default.
None of this is visible with one source; all of it is the first thing to fall with a thousand.

Transcription, not ingestion, is the bottleneck: a CPU core running Whisper `small` keeps up with
one to three sources. The queue *will* grow. Ingestion exists to absorb and order that difference
with bounded memory, never to hide it.

## Decision

### 1. One database round trip on the happy path, in cost order
Every rejection happens at the cheapest stage that can decide it (ADR-0001 applied to ingestion):

1. **Before reading the body**: `Content-Length` over the limit → `413`; id not a UUID → `400`;
   API key from the in-memory cache → `401`/`403`; in-flight byte budget exhausted → `503`;
   source from the in-memory cache → `404`/`409`; tenant or source quota full → `503`.
2. **While reading**: the Ogg/Opus header is checked on the first chunk (`OggS` capture pattern
   and an `OpusHead` first packet); a WAV is rejected after 47 bytes. SHA-256 is computed while
   the bytes arrive, in a single pass.
3. **After reading**: one SQL statement records the segment or returns the existing one.

### 2. Authentication without round trips
Resolved keys are cached in memory (`id → tenant, scopes, secret hash`) with a short TTL; the
secret is still compared in constant time on every request. Revoking a key (`pregon keys revoke` or the
Admin API) evicts it from the local cache and publishes the eviction over Valkey pub/sub, like
sources (§3), so revocation is immediate in the normal case. The TTL (`PREGON_AUTH_CACHE_TTL`, 30 s)
is the bound when that message is lost: a revoked key never works for longer. This revocation
window is documented, not hidden. `last_used_at` is recorded in memory and flushed once a minute
in a single batched `UPDATE`.

### 3. Sources from memory
`(tenant, sourceId) → status` is cached with a short TTL and invalidated explicitly when the Admin
API changes a source. With several server instances the invalidation travels over Valkey pub/sub,
which is what Valkey is for here (ephemeral signals, ADR-0019); a lost message is bounded by the
TTL.

### 4. Bodies never touch disk; memory has a budget
- The multipart in-memory threshold is set above the largest accepted segment, and Spring's
  multipart limit is aligned with `PREGON_INGEST_MAX_SEGMENT_BYTES`, so there is exactly one size
  limit and it is ours.
- An **in-flight byte budget** (`PREGON_INGEST_INFLIGHT_MAX_BYTES`) is acquired for the request's
  `Content-Length` before reading. Without budget the answer is an immediate `503` with
  `Retry-After`. This bounds the heap under a storm regardless of thread counts, and makes the
  budget and the connection pool, not threads, the real limits once virtual threads are enabled.
- Tomcat's `maxSwallowSize` is small: a request rejected before its body is read does not get its
  body read anyway out of politeness.

### 5. Content identity is the parsed metadata plus the audio hash
`sourceId`, `captureStart`, `captureEnd` and `voicedDuration` are stored parsed. Comparing the
columns *is* comparing by value: `10:00:00Z` and `11:00:00+01:00` are equal for free, and no
canonical JSON or metadata hash exists. Only the audio needs a SHA-256, because it is deleted.
`agentVersion` is diagnostic and not part of the identity: an agent upgraded in the middle of a
retry must not receive `409`.

### 6. One statement records or finds the segment
```sql
WITH ins AS (
  INSERT INTO segment (...) VALUES (...)
  ON CONFLICT (tenant_id, id) DO NOTHING
  RETURNING received_at)
SELECT true, received_at, NULL, ... FROM ins
UNION ALL
SELECT false, received_at, source_id, capture_start, capture_end, voiced_duration, audio_sha256
FROM segment WHERE tenant_id = :t AND id = :id AND NOT EXISTS (SELECT 1 FROM ins);
```
A returned `true` is a `202`; a `false` row is compared in Java to produce `200` or `409`.
When two uploads of the same id race, the second blocks on the conflict until the first commits,
inserts nothing, and its `SELECT` (whose snapshot predates the commit) returns no row: only this
case performs a second `SELECT`. Unique-violation exceptions are never part of the flow.

The audio row goes in the same transaction. The quota is checked **after** the segment is known
to be new, so a retry of an accepted segment is answered `200` even when the queue is full; a new
segment over quota is rolled back and answered `503`. An archived source is checked after the
duplicate check for the same reason: the retry gets its receipt, only a new segment gets `409`.

### 7. Quotas and `Retry-After` live in memory and are reconciled
A `SUM` over the queue per upload is O(n); a counter row per tenant is a hot row that serializes
the tenant and bloats. Instead:
- Per-tenant and per-source counters in memory (`LongAdder`): ingestion adds, the worker
  subtracts on completion or failure. Compared against `PREGON_INGEST_QUEUE_MAX_BYTES` and
  `limit × PREGON_INGEST_QUEUE_SOURCE_SHARE`.
- Counters are rebuilt at startup and reconciled every `PREGON_INGEST_QUOTA_RECONCILE` from
  `SELECT tenant_id, source_id, sum(audio_bytes) ... WHERE state IN ('QUEUED','LEASED')`.
  `audio_bytes` is a column of `segment` so this never touches the audio.
- **The limit is soft by design**: concurrent uploads may overshoot by one segment each. It is a
  relief valve, not a balance.
- `Retry-After` derives from the observed drain: an exponentially weighted moving average of
  completed bytes per second per tenant, fed by the worker.
  `retryAfter = clamp(min, (queuedBytes − 0.8 × limit) / drainRate, max)`, with the floor
  `PREGON_INGEST_RETRY_AFTER_MIN` when no drain has been observed, the ceiling
  `PREGON_INGEST_RETRY_AFTER_MAX`, and ±20 % server-side jitter so a thousand agents do not return
  in the same second. Agent-side jitter alone does not end a storm the server started.

### 8. Storage: two tables, two lifetimes
- **`segment`** (kept): primary key `(tenant_id, id)`. Ids are UUIDv7 (ADR-0022), so the index
  grows at its right edge: sequential inserts, no random page splits. This is the operational
  reason v7 is required, not a preference.
- **`segment_audio`** (transient): `segment_id, bytes`, deleted after transcription.
  `ALTER COLUMN bytes SET STORAGE EXTERNAL`: Opus is incompressible and TOAST would otherwise
  spend CPU trying. Autovacuum is tuned aggressively on this table
  (`autovacuum_vacuum_scale_factor = 0.01`, `autovacuum_vacuum_cost_delay = 0`) because rows live
  minutes.
- A composite foreign key `(tenant_id, source_id) → source (tenant_id, id)` keeps a segment inside
  its tenant even if application code is wrong, which requires `UNIQUE (tenant_id, id)` on
  `source` (missing before this ADR).
- **`segment` is partitioned by range of `id`.** A UUIDv7 carries its timestamp in the high 48
  bits, so a range of ids is a range of time and a primary-key lookup prunes to one partition.
  Monthly partitions; `PREGON_RETENTION_SEGMENTS` (`P1M`) drops old ones (`DROP`, instant, no
  vacuum), so between one and two months of metadata exist at any time. Deduplication only needs
  the window in which an agent can still retry (its RAM buffer: hours); auditing which segments
  formed an interaction needs days; KPIs live in interactions and matches, never in segments. At
  1 000 sources a month of rows and indexes is about 40 GB, a quarter about 120 GB: more than a
  mini PC should spend on data nobody queries. Interactions reference segment ids as values,
  without a foreign key, since the partition may be gone. This amends ADR-0022: segment metadata
  is kept for the retention period, not forever.
- **Partitions exist before they are needed.** An insert into a month without a partition fails,
  which would take ingestion down at midnight on the first of the month. The server creates the
  partitions for the current month and the next two at startup and once a day, and the table has a
  `DEFAULT` partition as a safety net. The `DEFAULT` partition also receives segments whose UUIDv7
  carries an absurd timestamp (an agent with a wrong clock): they are stored and flagged by the
  clock-skew check instead of failing with a `500`. Rows in `DEFAULT` are counted; a non-zero count
  is an operational signal.

### 9. Queue and workers
```
QUEUED ──claim (lease, attempt+1)──▶ LEASED ──complete──▶ TRANSCRIBED (audio deleted)
  ▲                                    │ fail, or lease expired
  └──────── attempt < max ─────────────┘──── attempt = max ──▶ FAILED (audio deleted)
```
- **Batch claim**: `UPDATE ... SET state='LEASED', lease_until=now()+:lease, attempt=attempt+1
  WHERE id IN (SELECT id FROM segment WHERE state='QUEUED' OR (state='LEASED' AND lease_until<now())
  ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED) RETURNING ...`. A partial index on `(id) WHERE
  state IN ('QUEUED','LEASED')` stays small and cached; `ORDER BY id` is capture order.
- **The attempt is counted when the job is claimed, not when it fails.** A poisonous audio that
  kills the worker never calls `fail`; counting there would make the lease expire, another worker
  claim it, die, and so on without end.
- **The lease token is the attempt number**: `complete` and `fail` run
  `UPDATE ... WHERE id=? AND attempt=? AND state='LEASED'`. A slow worker whose lease expired and
  was re-claimed cannot overwrite the new worker's result; `UNIQUE (segment_id)` on transcripts
  completes the guarantee that transcribing twice does not duplicate.
- Workers wake on `LISTEN/NOTIFY` after each insert, with a fallback poll every few seconds, so N
  idle workers do not run N × 10 queries per second against an empty queue.
- **Workers exist only when an STT sidecar is configured** (`PREGON_STT_URL`, a connection, no
  default per ADR-0020). Without it nothing drains the queue, which also makes backpressure tests
  deterministic.
- The queue port lives in `core` without Spring (`SegmentQueue`): `claim(max, lease) → List<Job>`,
  `complete(id, attempt) → COMPLETED | STALE`, `fail(id, attempt, reason) → REQUEUED | FAILED | STALE`.
  Enqueuing is not part of the port: it happens inside the ingestion transaction. Implementations
  take a `java.time.Clock`, so lease expiry is tested by moving the clock, never by waiting. The
  behavior is fixed by `SegmentQueueContract`, an abstract test every implementation extends.

### 10. Every stage reports what it dropped (ADR-0001)
Counters per rejection reason (`413`, `400`, `401`, `403`, `404`, `409`, `503-inflight`,
`503-quota`), accepted and duplicate uploads, queued bytes per tenant, drain rate, expired leases,
`FAILED` segments and the latency of the insert statement.

## Decided by the maintainer (2026-10-16)
- `PREGON_AUTH_CACHE_TTL = PT30S`. Halving the TTL halves the revocation window; the cost (one
  primary-key lookup per key every 30 s, a few hundred per second at 10 000 sources) is noise.
- `PREGON_RETENTION_SEGMENTS = P1M`, for the reasons in §8.

## Rollout
The design is accepted whole; the code lands in two steps, so that a single maintainer never has
correctness and performance in the same change:
1. **Correctness** (the branch of PR #18): migration V3 with the partitioned `segment`, the queue
   implementing `SegmentQueue`, the endpoint, the limits and the quotas, until the red tests are
   green. Quotas may start as a `SUM(audio_bytes)` over the partial index: at the queue sizes of
   `docs/capacity.md` it is cheap, and the tests cannot tell the difference.
2. **Performance**: the caches (§2, §3), batched `last_used_at`, in-memory counters (§7), the
   drain-based `Retry-After` and `LISTEN/NOTIFY`, each with a load test (k6) showing the before
   and the after. An optimization without a measurement is a claim, not a result.
Partitioning is in step 1 because PostgreSQL cannot turn a regular table into a partitioned one
later without copying it.

## Consequences
The happy path costs one database statement, one SHA-256 pass and no disk; memory is bounded by
an explicit budget; quotas are O(1) per request. Costs accepted: a revocation window equal to the
cache TTL, soft quotas, and segment metadata that expires. Beyond roughly 10 000 active sources
the write volume of audio into PostgreSQL (tens of MB/s of WAL) becomes the ceiling; the next
step at that point is object storage for queued audio with an outbox, and is out of scope here.
