# ADR-0006: Privacy by design
Status: Accepted

## Decision
Raw audio only in RAM (ring buffer) and the transient queue; deleted after transcription by default.
Default persistence: business events only. Transcripts optional with retention. Capture only in
operating hours. Out of scope for core: biometrics, speaker identification, emotion recognition.
Aggregated KPIs (source/shift) by default.
## Consequences
Lower legal risk for adopters; documentation must explain adopters' notice/consent duties.
