# Load tests

[k6](https://k6.io) scripts that measure segment ingestion (`PUT /v1/segments/{segmentId}`,
ADR-0024) under the load model of [`docs/capacity.md`](../docs/capacity.md). ADR-0025 lands its
performance work in a second step, each change with a load test showing the before and the
after: this is that test. An optimization without a measurement is a claim, not a result.

Nothing here runs in CI. A load test needs a machine that is doing nothing else, and the
numbers of a shared runner mean nothing.

## Requirements
- k6 2.3.0 (ADR-0014): `brew install k6`, or the binary from the k6 releases page.
- The `lite` stack running from `deploy/` (see the README's quick start), or any reachable
  server plus an admin key and an ingest key for it.

## Running against the local stack
```
load/run.sh steady SOURCES=100 DURATION=5m
load/run.sh storm SOURCES=50 BUFFER=300
```
`run.sh` creates an admin key and an ingest key inside the server container for the run
(`pregon keys create`, ADR-0018), hands them to k6 through the environment, revokes them when k6
exits, and writes the summary to `load/results/<scenario>-<timestamp>.json` (gitignored). Any
`VAR=value` after the scenario reaches k6 as an environment variable.

Against another server, run k6 directly. Connections and keys have no defaults (ADR-0020):
```
PREGON_URL=https://pregon.example.com PREGON_ADMIN_KEY=pgn_... PREGON_INGEST_KEY=pgn_... \
  SCENARIO=steady SOURCES=1000 k6 run load/ingest.js
```

## What each scenario does
Every run first creates one location and `SOURCES` sources through the Admin API, as an adopter
would, and archives them at the end: nothing is deleted. Audio is synthetic Ogg/Opus built in
JavaScript (`lib/oggopus.js`, the same generator the server and sidecar tests use), 10 s at
24 kbit/s, about 30 KB. No recorded sound is involved.

| Scenario | Models | Load | Passes when |
|---|---|---|---|
| `steady` | Operating hours (`docs/capacity.md`, "Steady state") | `SOURCES` × 0.07 uploads/s for `DURATION`; `RETRY_RATIO` of the uploads is sent twice, as an agent does when the connection drops before it reads the response | every first upload is `202`, every retry gets its original receipt with `200`, no `4xx`/`5xx`; `p(95)` of the first upload under `P95_MS` and `p(99)` under `P99_MS` |
| `storm` | The flush after a network incident (`docs/capacity.md`, "Bursts") | `SOURCES` agents upload `BUFFER` segments each as fast as the server lets them, honoring `Retry-After` on `503` and backing off with full jitter otherwise, like the real agent (`lib/agent.js`) | no segment is given up or rejected; the interesting numbers are `buffer_flush_seconds`, `uploads_throttled`, `retry_after_seconds` and `attempts_per_segment` |

The storm's segments carry capture times from before the outage, so their receipts come back
`clockSkewSuspected` (counted in `receipts_skew_flagged`); that is the server doing its job, not
a failure. In the steady scenario a `503` fails a check on purpose: nominal load must never be
throttled.

### Variables
| Variable | Default | Meaning |
|---|---|---|
| `SCENARIO` | `steady` | `steady` or `storm` |
| `SOURCES` | `100` | Active sources (steady) or agents flushing at once (storm); also the sources created by `setup()` |
| `DURATION` | `5m` steady, `30m` storm | Length of the steady run; upper bound of the storm |
| `RETRY_RATIO` | `0.05` | Share of steady uploads sent a second time |
| `BUFFER` | `300` | Segments each agent flushes in the storm |
| `SEGMENT_SECONDS` | `10` | Length of every segment |
| `OPUS_KBPS` | `24` | Bitrate the synthetic audio is sized to |
| `POOL` | `16` | Distinct segments each VU prepares in advance |
| `P95_MS`, `P99_MS` | `250`, `1000` | Latency thresholds of the steady scenario, first uploads |

## Custom metrics
`uploads_accepted` (202), `uploads_duplicate` (200), `uploads_throttled` (503),
`uploads_rejected` (4xx), `uploads_failed` (other 5xx, network), `uploads_given_up`,
`receipts_skew_flagged`, `retry_after_seconds`, `attempts_per_segment`, `buffer_flush_seconds`.
`http_req_duration` is tagged `kind=first|retry|admin`.

## Reading the results
- Run the same scenario with the same variables before and after a change, on the same machine,
  and compare the two files in `load/results/`. One run is a measurement; two are evidence.
- The laptop runs k6, Docker Desktop's VM and the server at once, and Compose caps the server at
  two CPUs and PostgreSQL at one: absolute numbers are for comparing runs on that machine, not
  for sizing production.
- With the STT sidecar running, the queue drains at the speed of transcription, so a storm
  eventually ends; the time it takes is the number to watch. The thresholds of the steady
  scenario are starting points to be replaced by measurements, as `docs/capacity.md` says of
  every figure in it.
