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
- **Containers everywhere.** One command to run: `docker compose -f deploy/docker-compose.yml --profile lite up`.

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

## Quick start

Requires Docker with Compose v2.

```
cp deploy/.env.example deploy/.env      # then set POSTGRES_PASSWORD
docker compose -f deploy/docker-compose.yml --profile lite up -d --wait
curl http://localhost:8080/actuator/health
```

The `lite` profile runs the server, PostgreSQL, Valkey and the speech-to-text sidecar.
Only the server is published to the host. Without `POSTGRES_PASSWORD`, compose refuses to start.

Every API call needs an API key. Create the first one inside the server container; it is printed
once and cannot be shown again:

```
docker compose -f deploy/docker-compose.yml exec server pregon keys create --scope admin --name "my laptop"
curl -H "Authorization: Bearer pgn_..." http://localhost:8080/v1/locations
```

`pregon keys list` shows the keys without their secrets and `pregon keys revoke ID` disables one.
Agents use keys with the `ingest` scope.

## Building from source

Requires JDK 25 (Maven is provided by the wrapper).

```
./mvnw verify
```

| Module | Java | Purpose |
|---|---|---|
| `pregon-spi` | 17 | Plugin contracts, safe to embed in any Java 17+ or Android host |
| `pregon-core` | 17 | Framework-agnostic domain and audio pipeline |
| `pregon-server` | 25 | Spring Boot service: Ingest and Admin APIs |
| `pregon-agent` | 25 | Capture agent that runs next to the microphone |

The embeddable modules can be checked on a JDK 17 with `./mvnw -pl pregon-spi,pregon-core verify`.

## Documentation

- [Vision and scope](docs/vision.md)
- [Architecture decisions (ADRs)](docs/adr)
- [Configuration](docs/configuration.md)
- [Capacity model](docs/capacity.md) and [load tests](load/README.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## License

[Apache License 2.0](LICENSE)
