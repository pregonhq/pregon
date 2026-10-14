# ADR-0020: Defaults only where a wrong value is visible
Status: Accepted (refines ADR-0009)

## Context
ADR-0009 asks for a zero-configuration start. A default for a connection or a secret hides a
missing setting: the service starts green with a wrong value and fails later, silently.

## Decision
Two kinds of settings:
- **Tunable parameters** (language, STT model, audio pipeline thresholds, retention): documented
  defaults are allowed, each with the reason for its value.
- **Connections and secrets** (database URL/user/password, Valkey URL, STT URL, API keys):
  no defaults in the application. If one is missing, startup fails with a message naming it.
Zero configuration is delivered by the deployment, not by the code: the compose file wires the
connections between its own services, and secrets come from `.env`
(`cp deploy/.env.example deploy/.env`); compose refuses to start if a required secret is unset.

## Consequences
One documented step before the first start; no silent misconfiguration in production.
