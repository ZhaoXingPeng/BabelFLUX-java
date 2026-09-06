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
