# ADR-0019: Valkey as the Redis-compatible store
Status: Accepted

## Context
ADR-0002 lists Redis in the `lite` profile. Since version 7.4 Redis is no longer under an OSI
license (Redis 8 offers RSALv2, SSPLv1 or AGPLv3). Pregón is Apache 2.0 and its compose files
should only pull clearly open components by default.

## Decision
Ship Valkey (BSD-3-Clause, Linux Foundation) in the compose profiles. Code talks the Redis
protocol through a standard client, so adopters can point to Redis or any compatible service.
Configuration keeps the protocol name: `PREGON_REDIS_URL`.

## Consequences
No license questions for adopters; Redis remains a drop-in option.
