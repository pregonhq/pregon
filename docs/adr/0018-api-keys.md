# ADR-0018: API key format and storage
Status: Accepted

## Decision
- Format: `pgn_<id>_<secret>`, where `<id>` is 8 random base62 characters (public, used to find
  the key) and `<secret>` is 32 random bytes in base62. The prefix makes leaked keys easy to detect
  by secret scanners.
- Sent as `Authorization: Bearer <key>`.
- Stored: id, `tenant_id`, scopes, SHA-256 of the secret, created/last-used/revoked timestamps.
  The full key is shown only once. A plain hash is enough because the secret is long and random;
  slow hashes (bcrypt, Argon2) are for low-entropy passwords. Comparison is constant-time.
- Scopes: `ingest` (agents, ADR-0003 Ingest API) and `admin` (Admin API).
- The first admin key is created with a command inside the server container
  (`docker compose exec server pregon keys create --scope admin`); keys are never printed to logs.

## Consequences
No secrets in plain text in the database; revocation is a single update.
