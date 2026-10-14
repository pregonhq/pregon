# Pregón

> In Latin American markets, the *pregonero* announced products aloud.
> **Pregón listens for that offer — and turns it into data.**

**Status:** design phase, building the MVP. Contributions and design discussions are welcome.

Pregón is an open-source, embeddable engine that turns live business signals — starting with
counter audio — into events and KPIs. First use case: *"Is our counter staff actually offering
this week's seasonal product, and how often?"*

## Principles

- **Cheap by default.** Silence and noise are discarded on the device before any model runs.
  Expensive AI is the last resort, never the default path.
- **Privacy by design.** Raw audio lives only in memory and is deleted after transcription.
  Only business events are stored. No biometrics, no emotion recognition.
- **API-first.** Everything is managed through a public, versioned API. The admin UI is optional
  and is just another API client.
- **Embeddable anywhere.** Run it as containers, embed the Java core in your app, or integrate
  via SDKs and webhooks. Built to be added to many different software products.
- **Containers everywhere.** One command to run: `docker compose --profile lite up`.

## How it works (MVP)

```
CAPTURE AGENT (on device)                        PREGÓN SERVER
Mic / RTSP → ring buffer (RAM)
  → adaptive energy gate      (drops silence)
  → VAD (Silero, ONNX)        (drops noise)
  → hysteresis segmenter      (speech segments)
  → Opus + idempotent upload ──────────────────► persistent queue
                                                 → local batch transcription (Whisper)
                                                 → campaign rules
                                                 → interactions → KPIs (via API)
```

## Integration modes (target)

| Mode | For whom |
|---|---|
| Service (containers + REST API) | Any language or platform |
| Embedded Java library | JVM applications |
| Spring Boot starter | Spring applications |
| Device SDKs (Kotlin, TypeScript) | Android, desktop, web |
| Signed webhooks | No-code tools (n8n, Zapier) |

## Documentation

- [Vision and scope](docs/vision.md)
- [Architecture decisions (ADRs)](docs/adr)
- [Configuration](docs/configuration.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## License

[Apache License 2.0](LICENSE)
