# ADR-0010: Event contract and pluggable transport
Status: Accepted

## Decision
Events follow the CloudEvents specification, with JSON Schema. Transport behind an interface:
in-memory for the MVP; Redis Streams (small installs) and Kafka KRaft (multi-location/scale) later.
## Consequences
Clients in any language can produce events; Kafka is optional, not a requirement for adopters.
