# ADR-0017: Database migrations with Flyway
Status: Accepted

## Decision
Flyway (version managed by Spring Boot, ADR-0014), plain SQL migrations in `pregon-server`
under `db/migration`, named `V<n>__<description>.sql`. Applied migrations are never edited;
every change is a new migration. Every table carries `tenant_id` (ADR-0009).
Liquibase was rejected: its XML/YAML changelogs add abstraction that this project does not need.

## Consequences
Schema history is readable SQL; integration tests run all migrations on a Testcontainers PostgreSQL.
