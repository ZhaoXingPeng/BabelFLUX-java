# Java 后端可观测性

## 目标与边界

本方案提供单机 Java 后端的请求、会话、WebSocket、异步任务和依赖失败信号。它不构成集中式日志平台、
分布式追踪、多副本可用性或模型质量/SLO 达标证明。

控制台日志使用 Spring Boot 的 `logstash` 结构化格式。每个 HTTP 请求接受长度不超过 64 的安全
`X-Request-Id`，不安全或缺失的值会替换为服务端 UUID，并在响应头返回。`request_id`、`event`、
`method`、`route`、`status`、`duration_ms` 是 HTTP 完成日志字段；异步失败日志额外使用
`component`、`operation`、`attempt`、`retry_seconds` 和 `error_type`。

禁止写入日志或指标标签的数据包括：WebSocket/handoff token、Authorization、Cookie、查询参数、
音频帧、完整转写/译文、会话/报告 ID、用户输入、模型 API key、数据库/消息队列连接密码和完整异常消息。
所有指标标签都是代码中的固定枚举或 HTTP 路由模板，不能使用原始 URL 或 ID。

## 指标

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `babelflux.http.server.requests` | `application,method,route,status,outcome` | 受关联 ID 过滤器观测的 HTTP 耗时计时器。 |
| `babelflux.api.failures` | `application,category,status` | 已分类的 API 失败，例如 `redis`、`elasticsearch`、`dashscope_timeout`。 |
| `babelflux.session.lifecycle` | `application,state` | 会话创建和成功完成次数，`state` 为 `created` 或 `completed`。 |
| `babelflux.websocket.active` | `application` | 当前已认证并被后端接受的 WebSocket 数。 |
| `babelflux.websocket.connections` | `application,state,reason/outcome` | WebSocket 打开、认证拒绝和关闭次数。 |
| `babelflux.async.tasks` | `application,component,operation,outcome` | outbox 发布和 ES 索引的成功或重试次数。 |
| `babelflux.dependency.failures` | `application,dependency` | Redis、RabbitMQ、Elasticsearch 或 DashScope 不可用的观测次数。 |

Micrometer 同时保留 Spring Boot 的标准 `http.server.requests`、JVM、进程和连接池指标。不要在
PromQL 中以 session ID、请求 ID 或原始 URL 聚合。

## 抓取与访问控制

Prometheus 格式端点是 `GET /actuator/prometheus`。生产 Java 后端仅监听 `127.0.0.1:8000`，Nginx
不得将 `/actuator/**` 代理到公网；在服务器上通过受限运维会话验证：

```bash
curl --fail --silent http://127.0.0.1:8000/actuator/prometheus | grep '^babelflux_'
```

Prometheus 必须与服务同机、通过受控网络访问该回环端口，或通过受限运维隧道抓取。不要为方便抓取而
将端点绑定到 `0.0.0.0`。日志平台接入时，成功 HTTP 完成日志按 10% 采样并保留 7 天；5xx、依赖失败、
重试与安全拒绝 100% 保留 30 天。该保留策略由平台运维执行，本仓库不引入日志存储集群。

## 单机最小告警

以下规则是值班起点，阈值需要结合基准和流量校准，不能当作容量或可用性承诺：

- 五分钟内 `babelflux.dependency.failures` 有持续增量，按 dependency 告警。
- 五分钟内 `babelflux.async.tasks{outcome="retry"}` 持续增长，按 outbox/ES 分流处理。
- `babelflux.http.server.requests` 的 5xx 比例超过 2%，或 p95 延迟显著偏离已建立基线时告警。
- `babelflux.websocket.active` 长期异常为零且入口请求正常时，检查认证、Redis 和升级链路。

## 验证

`MicrometerOperationalMetricsTest` 验证低基数指标、计时器与连接 gauge；
`RequestCorrelationFilterTest` 验证关联 ID 过滤、路由模板和 5xx 指标；
`SessionEventRelayTest` 验证 RabbitMQ 发布失败会同时记录依赖失败和 outbox 重试。测试不发送音频、
token 或真实凭据。
