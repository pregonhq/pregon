# ADR-0016: Audio input and encoding in the capture agent
Status: Accepted

## Context
The agent reads microphones, RTSP streams and WAV files, and uploads Opus segments (ADR-0007).
JavaCV bundles hundreds of megabytes of native libraries; pure-Java Opus encoders are not mature.

## Decision
- Microphone: `javax.sound.sampled` (part of the JDK).
- RTSP: an `ffmpeg` subprocess that decodes the stream and writes 16 kHz mono PCM (s16le) to stdout.
- WAV replay: plain Java, paced in real time ("as live") for tests and CI.
- Opus: segments are encoded by an `ffmpeg` subprocess.
All sources implement the same source port of `spi`, so the agent pipeline never knows where the
audio came from. `ffmpeg` is required by the agent and included in its container image.

## Consequences
Light, replaceable, easy to reason about. Requires `ffmpeg` on hosts that run the agent outside a
container. A process per encoded segment is acceptable at counter volumes; revisit if it is not.
