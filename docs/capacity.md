# Capacity model

What the server is designed against, and where the defaults in `configuration.md` come from.
Figures are estimates to be replaced by measurements (ROADMAP: per-stage metrics).

## One source
- Speech reaches the server as segments of roughly 10 s, about 30 KB each (Opus, 24 kbit/s).
- During operating hours a busy counter yields a segment every 10–20 s: **~0.07 uploads/s**.
- Metadata kept per segment: ~120 bytes in `segment`.

## Steady state

| Active sources | Uploads/s | Inbound audio | `segment` rows/day | Metadata/day |
|---|---|---|---|---|
| 100 | ~7 | 0.2 MB/s | 0.6 M | 70 MB |
| 1 000 | ~70 | 2 MB/s | 6 M | 0.7 GB |
| 10 000 | ~700 | 20 MB/s | 60 M | 7 GB |

## Bursts
After a network incident every agent flushes its RAM buffer at once. With a buffer of a few
hundred segments per agent, the upload rate is **10 to 50 times the average for several
minutes**. The server answers `503` with `Retry-After` from the observed drain rate, with jitter,
so the storm spreads instead of repeating.

## The bottleneck is transcription
faster-whisper `small`, `int8`, on CPU runs at about 1–3× real time per core: one core keeps up
with one to three busy sources. The queue is where the difference between capture and
transcription accumulates; its size, not the upload rate, drives `Retry-After`.

| Default | Value | Derivation |
|---|---|---|
| `PREGON_INGEST_MAX_SEGMENT_BYTES` | 1 MiB | ~5 min of Opus: longer than any segment the segmenter produces |
| `PREGON_INGEST_QUEUE_MAX_BYTES` | 256 MiB | ~9 000 segments ≈ 25 h of speech: a day of backlog per tenant |
| `PREGON_INGEST_QUEUE_SOURCE_SHARE` | 0.5 | one source may use half the queue; two noisy sources still leave nothing, so the tenant limit protects the rest |
| `PREGON_INGEST_INFLIGHT_MAX_BYTES` | 64 MiB | ~2 000 concurrent 30 KB uploads in heap at once; a storm beyond that is rejected before reading |
| `PREGON_INGEST_RETRY_AFTER_MIN` | 5 s | below this agents hammer; above it a short hiccup is amplified |
| `PREGON_INGEST_RETRY_AFTER_MAX` | 5 min | an agent buffer holds hours, so waiting minutes loses nothing |
| `PREGON_INGEST_QUOTA_RECONCILE` | 10 s | how far in-memory counters may drift from the database |
| `PREGON_STT_LEASE` | 2 min | a 10 s segment transcribes in seconds; a lease that long only expires on a dead worker |

## Costs per upload on the happy path (ADR-0025)
- 0 database round trips for authentication and source lookup (in-memory caches).
- 1 SHA-256 pass over the audio (~1–2 GB/s per core with intrinsics: 20 MB/s inbound costs about
  1–2 % of a core).
- 1 SQL statement (insert or find), plus the audio row in the same transaction.
- 0 disk writes outside PostgreSQL.
