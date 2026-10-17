# Contributing to Pregón

Thanks for your interest! The project is early, so design discussions are as valuable as code.

1. Open an issue before large changes.
2. Read `docs/vision.md` and `docs/adr` — they explain why things are the way they are.
3. API changes start in `api/openapi.yaml`.
4. Use Conventional Commits and include tests.
5. Never include secrets, real recordings or personal data in issues, tests or fixtures.
   Use synthetic or openly licensed audio only.
6. Write everything in English: code, comments, docs, commits, issues and PRs.
7. `./mvnw verify` runs everything (Docker is needed for Testcontainers). While a feature is being
   built test-first, its tests are red on purpose; to check the rest of the build alone exclude
   their package, e.g. `./mvnw verify -Dtest='!**/ingest/**' -Dsurefire.failIfNoSpecifiedTests=false`
   (a class pattern such as `!Segment*Test` does not reach nested test classes).
