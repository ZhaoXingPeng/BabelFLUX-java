# Java 后端迁移路线

每个阶段保持前端协议兼容，并通过独立分支和 PR 合并。CI 暂不作为阶段门禁，
完整功能完成后再统一收口。

| 阶段 | 交付物 | 主要证据 |
| --- | --- | --- |
| 1. 基础骨架 | Java 21/Spring Boot、REST、WebSocket、会话领域模型 | `mvn -B test`、HTTP/WS smoke |
| 2. 协议 parity | 全量 session/history/report/handoff DTO 与契约测试 | 前端 fixture + MockMvc |
| 3. 百炼实时链路 | DashScope realtime WS、PCM 背压、结束时有界 drain | fake provider、真实握手联调记录（未宣称自动重连或性能提升） |
| 4. 纠偏与报告 | 有界滑动窗口、异步纠偏、报告生成和降级 | 正确性、P95、错误率 |
| 5. 持久化与检索 | MySQL 聚合持久化、Redis 会话/幂等、RabbitMQ 事件、ES 报告检索 | 容灾、并发和索引基准 |
| 6. 发布收口 | CI、镜像、观测、回滚演练 | 发布报告与 PR 评论 |

## 每个 PR 的 STAR 记录

Issue、PR 描述和合并前评论必须说明 Situation、Task、Action、Result；性能只填写
可复现实测值，缺陷必须附回归测试和修复前后行为。模板见
[`docs/process/star-performance-template.md`](../process/star-performance-template.md)。
