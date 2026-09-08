# BabelFlux 工程与架构规范

本文是项目架构的约束基线。实现可以演进，但跨层边界、事件契约和验证要求必须保持可追踪。

## 分层

```text
Web/Desktop adapters
        |
Spring MVC + raw WebSocket adapters (web, websocket)
        |
Application orchestration (service)
        |
Domain aggregate and ports (domain)
        ^
Infrastructure / provider adapters (infrastructure, messaging, search, provider)
```

- `web` 和 `websocket` 只负责协议适配、输入校验和鉴权，不承载字幕算法或数据库访问。
- `domain` 的 `Session`、报告和端口是业务契约；新增事件字段必须同时更新 Java 映射、前端类型和契约测试。
- `service` 负责会话时序、媒体输入、纠偏和报告编排；媒体解码、provider 调用和索引写入放在独立适配器。
- `provider`、`infrastructure`、`messaging`、`search` 只能通过稳定端口向应用层提供能力，不能把第三方 SDK 类型泄漏到 API 或前端。
- `frontend` 和 `desktop` 共享协议类型语义，但不能直接依赖后端实现细节。
- Elasticsearch 报告索引是可重建的派生数据；MySQL 中的索引任务使用带租约的状态机（`pending -> processing -> indexed`），租约过期后允许其他实例恢复，不能把 ES 当作事实源。

## 依赖方向

依赖只能向下流动：API -> services -> providers。禁止 provider 反向导入 API，禁止服务模块互相读取对方的私有状态。需要共享行为时，提取小型纯模块或明确的领域接口。

## 长文件拆分规则

文件超过 500 行或同时包含三类以上职责时，创建独立拆分 Issue。拆分顺序：

1. 先为现有行为补单元/契约测试。
2. 把无副作用函数提取到领域模块，保持原方法提供兼容代理，先不改变调用方。
3. 再移动状态机或 I/O 适配器，逐步减少代理。
4. 每一步都运行完整质量门禁，并在 PR 中记录前后行数、性能和行为差异。

当前拆分队列：

- `backend/src/main/.../service/RealtimeSessionRunner.java`：provider 事件归一化 -> 段落状态 -> 报告编排。
- `frontend/src/stores/session.ts`：WebSocket transport -> session reducer -> report/history state。
- `backend/src/main/.../websocket/SessionWebSocketHandler.java`：连接生命周期 -> inbound command handling -> outbound serialization。

## 事件契约

- 事件名称使用 `snake_case`，字段使用 `camelCase`。
- 新字段默认可选并提供兼容值；删除或改语义必须增加契约版本或迁移策略。
- 服务端事件必须有前端解析测试；前端发送的命令必须有后端校验测试。
- 错误事件不得包含密钥、完整请求头、原始音频或用户隐私文本。

## 测试策略

- 纯函数：边界条件和性质测试优先，目标是快速、确定、无网络。
- 服务层：使用 fake provider 验证状态转移、降级和资源清理。
- API/WebSocket：验证状态码、事件顺序、字段兼容性和断线行为。
- UI：覆盖用户可见状态、键盘/鼠标交互和失败路径；媒体 fixture 使用仓库内固定样本。
- 真实模型、真实设备和长时媒体属于显式联调实验，不作为每次 PR 的必需门禁。

## 变更记录模板

架构调整至少记录：背景、约束、方案、替代方案、风险、迁移步骤、验证结果和回滚方式。小型调整可直接写入 PR；跨模块调整应新增 `docs/adr/NNNN-<topic>.md`。
