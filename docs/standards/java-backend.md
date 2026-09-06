# Java 后端规范

## 版本与依赖

- Java 21 LTS、Spring Boot 3.4、Maven；CI 使用 `mvn -B test`。
- 依赖只允许在 `backend/pom.xml` 声明；新增中间件必须同步 README、配置样例和 ADR。
- 第三方 SDK 类型不得穿透 `provider` 层；对外只暴露领域 DTO。

## 架构约束

```text
web adapters -> application services -> domain ports <- infrastructure/providers
```

Controller/WebSocket handler 只做协议转换和鉴权。会话状态由 `SessionService`
编排，`SessionRepository`、`EventPublisher`、`ReportSearchIndexer` 是可替换端口。
默认内存实现用于本地开发；Redis 用于跨实例状态，RabbitMQ 用于异步事件，
Elasticsearch 用于报告检索。通过配置开关显式启用，禁止在业务代码中直接 new 客户端。

## 编码与并发

- DTO 使用不可变 `record`；领域聚合只通过方法改变状态，禁止暴露可写集合。
- 共享状态使用并发容器或明确的锁；I/O 不得在 WebSocket 接收线程执行长时间阻塞操作。
- 外部调用必须设置超时、记录 request id，并将供应商错误映射为稳定的 API 错误码。
- 集合处理优先使用线性复杂度；跨句纠偏窗口固定上限，避免 O(n²) 扫描整场字幕。

## 测试与性能记录

- Controller 契约测试覆盖状态码、字段命名和兼容路径。
- Service 测试覆盖状态机、重复 stop、鉴权失败和并发创建。
- provider 使用 WireMock/MockWebServer，禁止单元测试调用真实百炼。
- 每个涉及性能的 PR 评论必须附基准命令、硬件/数据规模、P50/P95、吞吐和结论；
  使用 STAR 格式记录问题、措施、结果和可复现证据。
