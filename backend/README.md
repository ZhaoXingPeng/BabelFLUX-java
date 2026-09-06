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

The current suite covers health/session history plus one-time handoff issue,
claim, replay rejection, and malformed-token handling.

## DashScope gateway

`POST /api/models/llm/generate` accepts `model`, `endpoint` (`text` or
`multimodal`), `messages`, and optional `parameters`, then returns a normalized
`requestId`, `content`, `contentParts`, `finishReason`, and `usage` response.
The default `DASHSCOPE_HTTP_BASE_URL` uses the native Bailian generation paths.
When it points at a Bailian `/compatible-mode/v1` endpoint, the adapter uses
`/chat/completions` and keeps the same response contract. `DASHSCOPE_API_KEY`
is required at request time and is never logged or persisted.

## Technology boundaries

- `web`: HTTP and WebSocket adapters only.
- `service`: session lifecycle and application orchestration.
- `domain`: session aggregate and repository ports.
- `infrastructure`: in-memory baseline and optional Redis adapter.
- `messaging`: `EventPublisher` port with RabbitMQ adapter.
- `search`: report indexing port with Elasticsearch adapter.
- `provider`: Alibaba Cloud Bailian (DashScope) HTTP adapter.

Redis, RabbitMQ and Elasticsearch are disabled by default so a clean checkout
is runnable without external services. Enable them explicitly with
`REDIS_ENABLED=true`, `RABBITMQ_ENABLED=true`, or
`ELASTICSEARCH_ENABLED=true` after provisioning the corresponding service.

API keys are read only from environment variables. Never commit `.env` or a
real `DASHSCOPE_API_KEY`.
