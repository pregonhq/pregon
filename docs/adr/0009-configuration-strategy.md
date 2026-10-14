# ADR-0009: Configuration strategy
Status: Accepted (defaults refined by ADR-0020)

## Decision
Configurable from day one: `tenantId` on every entity (default `default`); operating hours and
timezone per source; retention for audio and transcripts; audio pipeline parameters; STT model and
device; interaction strategy. Not configurable yet: LLM provider, transport (in-memory behind an
interface), auth (API keys only), multilingual, UI theming, dynamic plugin loading.
Infra via env vars; business via API/YAML; zero-config start with documented defaults.
