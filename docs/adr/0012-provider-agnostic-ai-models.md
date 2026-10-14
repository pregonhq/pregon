# ADR-0012: Provider-agnostic AI models
Status: Accepted

## Context
Later tiers of the cascade (ADR-0001) use AI models: embeddings and an LLM. Adopters have
different constraints: some cannot send data to a given vendor, some already pay for one, some
must run fully offline. Tying Pregón to a single vendor would block adoption and make the
project depend on one company's pricing and availability.

## Decision
No AI vendor is part of the core or a requirement. Models are reached through ports defined in
`spi` (e.g. `LanguageModel`, `EmbeddingModel`) with no vendor types in their signatures.
Each vendor is an adapter module, selected by configuration (provider, model, endpoint, key):
- `none` — default; the tier is skipped and the cascade ends at rules.
- `openai-compatible` — any server speaking the OpenAI Chat Completions / Embeddings API
  (OpenAI, Ollama, vLLM, LM Studio, OpenRouter, Groq, DeepSeek and similar).
- Native adapters only where the compatible API falls short (e.g. Anthropic, Gemini).
Prompts, output schemas and the daily budget gate live in core, not in adapters, so behavior
does not change when the provider changes. Responses are validated against a schema; a provider
error degrades to "undecided", never to a crash. Spring AI may be used inside adapters only.
The same principle applies to speech-to-text: local faster-whisper is the default, and a cloud
STT provider may be added later as another adapter of the same port.

## Consequences
Switching provider is a configuration change. Tests use a fake model adapter, so CI never needs
an API key. Features that only one vendor offers cannot be relied on by core.
