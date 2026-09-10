# Java 平台与中间件审计

关联 Issue：#112

## 目标与边界

本审计评估 BabelFLUX Java 后端是否采用适合单机生产部署与持续开发的主流实践。结论分为
“保留”“低风险修复”“拆分为后续 Issue”与“超出当前单机范围”，不把一次可用的演示或单项
健康检查外推为高可用、性能或安全结论。

审计覆盖以下边界：

- Web/API：REST Controller、WebSocket handler、DTO、校验、错误映射和鉴权边界；
- Application service：会话、报告、事务、同步/异步编排及服务粒度；
- Domain：聚合、不变量、仓储端口、事件和并发模型；
- Infrastructure：JDBC/MyBatis、MySQL、Redis、RabbitMQ、Elasticsearch、DashScope 和 ffmpeg；
- Background work：outbox、索引、重试、lease、调度、关闭与故障恢复；
- Delivery：Spring Boot 配置、Docker Compose、systemd、Nginx、TLS、密钥、可观测性、CI 和测试。

## 证据要求

每项结论应引用源码、配置、测试或已验证的生产行为。实现缺陷必须有可复现的触发条件；建议
变更必须明确兼容性、测试与回滚路径。不得记录 API key、令牌、生产密码、真实用户音频或其他
敏感输入。

## 检查矩阵

| 领域 | 需要核对的实践 | 证据 | 结论/行动 |
| --- | --- | --- | --- |
| 分层与依赖 | web/application/domain/infrastructure 依赖方向；Provider 与中间件是否泄漏到领域 | 待收集 | 待定 |
| 事务与并发 | `FOR UPDATE`、条件更新、幂等、事务边界、跨实例 lease | 待收集 | 待定 |
| 持久层 | JDBC 当前适配性；MyBatis/MyBatis-Plus 的迁移收益与风险 | 待收集 | 待定 |
| MySQL | 连接、schema 演进、索引、字符集、备份恢复、最小权限 | 待收集 | 待定 |
| Redis | TTL、token/lease、Pub/Sub、持久化、安全和不可用时行为 | 待收集 | 待定 |
| RabbitMQ | outbox、ack、重试、DLQ、消费者幂等、管理面暴露 | 待收集 | 待定 |
| Elasticsearch | 资源限制、版本、索引任务、重试、查询降级与数据生命周期 | 待收集 | 待定 |
| 交付与运维 | 密钥、网络暴露、TLS、健康检查、日志指标、备份与回滚 | 待收集 | 待定 |
| 测试流程 | 单测、集成测、真实环境验证、CI 门禁和变更记录 | 待收集 | 待定 |

## 已知前提

当前生产部署是单机形态。高可用、多 AZ、跨集群复制、容量压测、恢复时间目标和完整可观测性
平台不属于本次已证明的能力；如审计发现它们是目标需求，应单独立项而不是通过文档暗示已经
具备。
