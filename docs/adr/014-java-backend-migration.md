# ADR-014: Java/Spring Boot backend migration

## Status

Accepted. The migration is implemented incrementally in the independent
`BabelFLUX-java` workspace. The original Python backend is removed from this
workspace; the Vue and Tauri clients remain unchanged.

## Context

BabelFlux needs a backend that demonstrates production Java skills for
interviews: explicit domain boundaries, concurrent session handling, external
provider isolation, and operational middleware. The existing backend was
FastAPI/asyncio and coupled the session pipeline, provider adapters and HTTP
protocol in one runtime.

## Decision

- Java 21 LTS and Spring Boot 3.4 with Maven.
- REST and raw WebSocket adapters preserve `/api` paths and camelCase payloads.
- `SessionService` owns application orchestration; `SessionRepository`,
  `EventPublisher` and `ReportSearchIndexer` are ports.
- In-memory session storage is the local default. Redis, RabbitMQ and
  Elasticsearch adapters are configuration-gated for deployment environments.
- DashScope access is isolated behind `DashScopeClient`; credentials are read
  from environment-backed configuration and requests have explicit timeouts.

## Consequences

The first slice is runnable without infrastructure and can be validated against
the unchanged frontend. Realtime DashScope WebSocket ingestion, media decoding,
revision and report generation remain separate migration slices so each can be
tested and rolled back independently.

## Verification

`mvn -B test` passes. A live smoke test verified health, session creation,
history retrieval and WebSocket `session_started` -> `source_sync_state` ->
`session_report` event order.
