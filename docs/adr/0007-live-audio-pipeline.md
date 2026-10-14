# ADR-0007: Live audio capture pipeline
Status: Accepted (parameters to be tuned with real data)

## Decision
"Live capture, near-live processing". Agent (Java): 16 kHz mono, 30 ms frames → RAM ring buffer
(~2 s) → adaptive energy gate (RMS vs moving noise floor) → Silero VAD via ONNX Runtime →
hysteresis segmenter (start after N speech frames, close after silence hangover, pre-roll from the
ring buffer, discard too-short, split too-long at lowest energy) → Opus → HTTP upload with a unique
segment id as idempotency key, retries on failure. Server: persistent queue (backpressure, no loss)
→ batched faster-whisper with fixed language and campaign phrases as initial prompt → audio deleted.
Sources: microphone, RTSP, WAV replay (for tests/CI). Hardware tip documented: a directional mic
aimed at staff is cheaper than diarization.
## Consequences
Streaming over WebSocket deferred. Per-stage metrics are mandatory.
