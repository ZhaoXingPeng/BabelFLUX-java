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
- DashScope access is isolated behind HTTP and realtime WebSocket clients;
  credentials are read from environment-backed configuration and requests have
  explicit timeouts.
- The realtime runner owns a bounded one-second PCM queue, maps provider
  partial/final events to the existing WebSocket contract, and persists segment
  progress before report generation. `demo` input remains an explicit local
  fallback when no provider key is configured.

## Consequences

The service is runnable without infrastructure and can be validated against the
unchanged frontend. Media decoding and revision parity remain separate slices;
the realtime Java path currently handles browser PCM input and report
generation, with no unmeasured performance claim or automatic reconnect claim.

## Verification

`mvn -B test` passes (47 tests, 2 Docker-backed integration tests skipped when
their opt-in flags are absent). A local live smoke test verified health/session
flows and a real DashScope WebSocket handshake received
`session.created`/`session.updated`/`session.finished` without an error event;
audio/text generation was not claimed from a silent probe.
