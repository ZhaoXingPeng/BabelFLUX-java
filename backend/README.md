# BabelFlux Java backend

This module is the backend migration target for BabelFlux. It uses Java 21 and
Spring Boot 3.4. The Vue/Tauri clients remain outside this module and keep the
existing `/api` REST and raw WebSocket contracts.

## Local run

```bash
cd backend
mvn spring-boot:run
```

The default server is `http://localhost:8000`. The migration slice provides
`GET /api/health`, session creation/history APIs, and
`/api/ws/sessions/{sessionId}`. Binary PCM frames are accepted by the socket;
DashScope realtime ingestion is implemented in the next slice.

Session creation preserves `sourceUrl`, `sourcePermission`, `ttsEnabled`, and
the priority-ordered `glossary` fields used by the unchanged Vue client.

## Desktop handoff

The Web and Tauri clients can hand a live session to the desktop floating
window without sharing the session's long-lived credentials:

```text
POST /api/sessions/{sessionId}/handoff
POST /api/sessions/handoff/claim
```

The first endpoint issues an `h_` token valid for 300 seconds and returns a
`lingosync://floating/start` deep link. The claim endpoint consumes that token
once, returns a short-lived `w_` WebSocket token, and includes the token in
`wsUrl`. Reuse returns `409`, expired tickets return `410`, and unknown tickets
return `404`.

## Verification

```bash
mvn -B test
```

The current suite covers health/session history, one-time handoff issue/claim,
JDBC and Redis state boundaries, Rabbit outbox delivery semantics, and ES
search contracts. Docker-backed RabbitMQ and Elasticsearch checks are
explicitly opt-in with `RUN_RABBITMQ_IT=true` and
`RUN_ELASTICSEARCH_IT=true`.

## DashScope gateway

`POST /api/models/llm/generate` accepts `model`, `endpoint` (`text` or
`multimodal`), `messages`, and optional `parameters`, then returns a normalized
`requestId`, `content`, `contentParts`, `finishReason`, and `usage` response.
The default `DASHSCOPE_HTTP_BASE_URL` uses the native Bailian generation paths.
When it points at a Bailian `/compatible-mode/v1` endpoint, the adapter uses
`/chat/completions` and keeps the same response contract. `DASHSCOPE_API_KEY`
is required at request time and is never logged or persisted.

The model protocol also exposes:

- `POST /api/models/strategy/plan` for a deterministic provider/revision plan;
- `POST /api/models/asr/transcriptions` for PCM multipart transcription;
- `POST /api/models/tts/speech` for realtime TTS audio returned as base64.

ASR and TTS use the Bailian WebSocket protocols and have the same timeout and
error-boundary rules as the LLM adapter. Unit tests use a fake transport; no
test sends credentials or audio to a real provider.

## Technology boundaries

- `web`: HTTP and WebSocket adapters only.
- `service`: session lifecycle and application orchestration.
- `domain`: session aggregate and repository ports.
- `infrastructure`: in-memory baseline and optional Redis adapter.
- `messaging`: `EventPublisher` port with RabbitMQ adapter.
- `search`: report indexing port with Elasticsearch adapter.
- `provider`: Alibaba Cloud Bailian (DashScope) HTTP adapter.

Redis, RabbitMQ and Elasticsearch are disabled by default so a clean checkout
is runnable without external services. `MYSQL_ENABLED=true` selects the JDBC
session repository (the default remains in-memory/H2); it persists session
metadata, segment JSON, and report snapshots. With `REDIS_ENABLED=true`,
handoff and WebSocket ticket state is stored with TTL and handoff claims use
an atomic Redis script, so a load-balanced instance can validate tickets
without silently falling back to process-local state. Redis failure is
reported as `503`.

With `RABBITMQ_ENABLED=true`, session creation and report completion append
versioned events to the MySQL outbox in the same transaction as the aggregate
write. A scheduled relay publishes them to the durable
`babelflux.session.events` exchange and retries failed deliveries. Consumers
record `event_id` receipts in MySQL, making broker redelivery idempotent; bad
messages are routed to the durable dead-letter queue. The checked-in tests use
H2 and mocks only, so they do not claim a live RabbitMQ verification.

Enable Elasticsearch explicitly with `ELASTICSEARCH_ENABLED=true` after
provisioning it. Reports are indexed as idempotent documents in the versioned
`babelflux-reports-v1` index. Search is exposed at
`GET /api/reports/search?q=&sourceLanguage=&domain=&from=&to=&page=&size=`;
results return `reportId` and `sessionId` so MySQL remains the source of truth.
Index jobs expose `GET /api/reports/{reportId}/index-status`, and
`POST /api/reports/rebuild` queues all persisted report snapshots for a rebuild.
Index failures stay in MySQL with retry timestamps and the report endpoint
continues to read the MySQL snapshot.

API keys are read only from environment variables. Never commit `.env` or a
real `DASHSCOPE_API_KEY`.
