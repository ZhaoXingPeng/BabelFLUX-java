# 面试视角架构审计：实时语音系统与 Java 后端

关联 Issue：#112
审计日期：2026-09-10（UTC+8）
代码基线：`main@913c252`（已合并 MyBatis 持久层）

## 结论先行

这是两个相互协作、但应分别评价的能力线：

1. **实时语音/同传系统能力**：浏览器和桌面端采集音频，后端通过 WebSocket 编排有界 PCM 流、
   外部实时模型、在线/会后纠偏和双语报告。
2. **通用 Java 后端工程能力**：Java 21、Spring Boot、领域端口、MyBatis/MySQL、Redis、RabbitMQ
   outbox、Elasticsearch 投影、事务/幂等、单机部署和测试工程。

不能把第一条线包装成自研 ASR/TTS/大模型算法，也不能把第二条线包装成已经具备高可用的通用平台。
前者展示的是**实时 AI 应用集成与流式系统工程**；后者展示的是**有状态 Java 服务的工程化实现**。

现有实现适合作为求职作品中“能运行、可解释边界、有真实中间件闭环”的项目。当前最需要补的不是
再引入一个框架，而是把数据库演进、可观测性、实时性能基线和职责拆分变成可重复的证据。对应的
后续事项已拆为 #120、#121、#122、#123。

## 审计方法与边界

本报告检查源码、配置、自动化测试、部署模板和 2026-09-10 已记录的隔离/生产验证。任何结论都必须
区分“代码存在”“测试覆盖”“单机环境实测”和“尚未证明”；不把健康检查、一次静音 WebSocket 会话或
单次构建成功外推为高可用、字幕质量、首字幕延迟或长时稳定性。

审计不记录 API key、token、生产密码、完整授权输出、真实用户音频或转写内容。

### 招聘需求取样

公开招聘页面随招聘批次动态变化，因此只将下列来源作为能力方向的交叉核验，不虚构某个具体岗位的
JD 条款：

| 官方来源（2026-09-10 访问） | 可用于校验的方向 | 不可据此声称 |
| --- | --- | --- |
| [快手校园招聘](https://campus.kuaishou.cn/recruit/campus/e/) | 工程岗位面向大规模业务，项目应能解释并发、稳定性和工程质量 | 某一具体 Java 岗位的精确职责或门槛 |
| [拼多多校园招聘](https://careers.pinduoduo.com/campus/grad) | 2027 校招入口及工程实践导向；项目必须能应对快速迭代与真实问题 | 未公开匿名 JD 中不存在的技术要求 |
| [京东校园招聘](https://campus.jd.com/) | 工程岗位招聘流程与技术岗位检索入口 | 未经登录/动态接口取得的职位详情 |
| [科大讯飞招聘入口](https://hr.iflytek.com/) | 语音方向应区分算法研究与产品/系统集成 | 本项目具备模型训练、声学算法或论文能力 |

由此导出的面试检查项是：Java/SQL/并发与中间件语义是否能讲清，失败和回滚是否可复现，实时语音链路
是否有背压和降级，以及是否诚实标明外部模型与未验证指标。它们是评审维度，不是对所有大厂岗位的
替代 JD。

## 能力线 A：实时语音/同传系统

### 已有证据

| 主题 | 现状与证据 | 评审结论 |
| --- | --- | --- |
| 实时协议 | [`SessionWebSocketHandler`](../../backend/src/main/java/com/babelflux/backend/websocket/SessionWebSocketHandler.java) 管理 token 校验、控制事件、二进制 PCM 与 handoff 订阅；协议在 README 中公开 | **保留。** REST 管会话、WS 承载音频和事件的职责可解释，且有协议测试。 |
| 背压 | [`RealtimeSessionRunner`](../../backend/src/main/java/com/babelflux/backend/service/RealtimeSessionRunner.java) 使用 `ArrayBlockingQueue`；满时丢弃最旧帧、累计 `droppedFrames` 并发送 `lagging` 状态 | **保留并量化。** 这是真实的过载策略，不等于浏览器到 Nginx 的端到端背压已经验证。 |
| 多实例会话归属 | Redis Lua 原子 claim/renew/release 位于 [`RedisSessionRepository`](../../backend/src/main/java/com/babelflux/backend/infrastructure/RedisSessionRepository.java)；WS handler 续租失败会停止会话 | **保留。** 单会话双跑有明确保护；Redis 故障会显式失败，未静默降级。 |
| 模型故障边界 | provider HTTP/WS 有超时；会后纠偏在 [`FinalCorrectionService`](../../backend/src/main/java/com/babelflux/backend/service/FinalCorrectionService.java) 中保留实时译文回退 | **保留。** 失败对用户可见，避免把不完整纠偏当终稿。自动重连、熔断和供应商切换尚未实现。 |
| 纠偏并发 | 实时修订采用有界窗口，会后按批并行并受全局 deadline 约束 | **保留。** 虚拟线程适合 I/O 等待；外部调用并发上限、成本和配额没有压测证据。 |
| 生产探针 | 已记录公网 WSS 静音 PCM 可握手并收到 `source_sync_state` 与 `session_report` | **有限通过。** 只证明认证、连接与结束生命周期，不能证明识别准确率、音频质量或时延。 |

### 语音线待补能力

| 优先级 | 缺口 | 风险 | 行动 |
| --- | --- | --- | --- |
| P1 | 无固定输入、重复次数、P50/P95/P99、队列丢帧比例和 provider 错误率基线 | 难以回答“延迟多少、压力下如何降级” | #121 建立合成/脱敏 PCM 基线，并绑定机器、网络和模型条件。 |
| P1 | 浏览器 `bufferedAmount` 与服务端丢帧状态未形成端到端策略 | 客户端仍可能持续发送，用户只在服务端积压后才看到提示 | #121 设计阈值、暂停/降采样/可见状态，并添加回归验证。 |
| P2 | WS handler 与 runner 同时承载过多生命周期职责 | 断连、租约、结束与报告事件的修改回归面大 | #123 在保持事件契约的前提下拆分协调层。 |
| P2 | provider 无自动重连、熔断或多供应商切换 | 单供应商瞬态故障只能降级/结束 | 先由 #121 给出失败基线；是否引入韧性机制另立小 Issue，不能凭想象上框架。 |

### 面试表达边界

可表述为：“我实现并验证了一个调用外部语音翻译模型的实时应用系统；在服务端用有界 PCM 队列、
Redis 会话租约、明确的丢帧提示、超时和报告回退管理流式失败。”

不可表述为：“我训练/优化了 ASR、TTS 或同传模型”，也不可将静音探针说成模型准确率或性能 benchmark。
若投递语音算法岗位，应另外准备语音信号处理、数据集、模型训练、离线指标和论文/实验能力；本仓库
并不提供这些证据。

## 能力线 B：通用 Java 后端工程

### 分层、事务与并发

| 主题 | 现状与证据 | 评审结论 |
| --- | --- | --- |
| 依赖方向 | `web` 处理 REST/WS，`service` 编排用例，`domain` 定义会话和仓储端口，`infrastructure`/`messaging`/`search`/`provider` 实现适配器；见 [`backend/README.md`](../../backend/README.md) | **保留。** 对小型单体而言足够清楚，避免为“DDD”增加无意义层。 |
| 事务边界 | [`SessionService`](../../backend/src/main/java/com/babelflux/backend/service/SessionService.java) 的创建/结束使用 `@Transactional`；长时间模型调用在 [`SessionReportService`](../../backend/src/main/java/com/babelflux/backend/service/SessionReportService.java) 中以 `NOT_SUPPORTED` 脱离数据库事务 | **保留。** 避免持锁等待外部模型，是正确取舍。 |
| 会话完成幂等 | 同 JVM 的 `CompletableFuture` 去重加数据库 `findByIdForUpdate`，已生成 report 直接返回 | **保留。** 能处理 stop/断连竞争；锁粒度和超时仍需在负载基线中观察。 |
| 异步可靠性 | 会话事实与 outbox 在同一事务内写入；[`JdbcSessionEventOutbox`](../../backend/src/main/java/com/babelflux/backend/messaging/JdbcSessionEventOutbox.java) 以条件更新 lease claim；消费者以 `event_id` 去重 | **保留。** 语义是 at-least-once，不应宣称 exactly-once。 |
| 搜索投影 | MySQL 为事实源；ES 索引任务有状态、lease、失败重试和重建入口 | **保留。** 搜索失败不会阻断报告读取；单节点 ES 不具备容灾。 |
| 类职责 | `SessionWebSocketHandler`、`RealtimeSessionRunner`、`FinalCorrectionService` 仍较集中 | **拆分。** 不是立刻换框架的问题，按 #123 先保持契约与回归测试再拆。 |

### 持久层：MyBatis、JDBC 与 MyBatis-Plus

当前关系型业务持久化已由 [`MyBatisSessionRepository`](../../backend/src/main/java/com/babelflux/backend/infrastructure/MyBatisSessionRepository.java)
和 `infrastructure.mybatis` Mapper 实现。会话快照、outbox、消费回执、审计和索引任务都经显式 SQL Mapper
访问 MySQL。JDBC 只保留在 [`JdbcSchemaMigration`](../../backend/src/main/java/com/babelflux/backend/infrastructure/JdbcSchemaMigration.java)
这类启动期 schema 元数据/DDL 基础设施职责。

结论如下：

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| Spring JDBC 作为主要 CRUD 持久层 | **已退出。** | 业务 SQL 边界已迁移到 MyBatis，继续维护两套业务实现没有收益。 |
| MyBatis 作为当前主力 | **保留。** | 显式 SQL 适合 `FOR UPDATE`、lease 条件更新、outbox 状态机和 JSON 快照映射；能在面试中说明 SQL 与事务语义。 |
| 追加 MyBatis-Plus | **暂不引入。** | 核心查询并非简单通用 CRUD；叠加 ORM 风格 API 会增加学习/配置面，不能消除自定义锁、租约和 outbox SQL。只有大量独立后台 CRUD 出现且重复 Mapper 被量化后，才单独评估。 |
| `schema.sql` + 启动期补列作为长期迁移机制 | **需要替换。** | 缺少版本序列、迁移审核、迁移账号边界和恢复演练。#120 评估 Flyway/等价方案并迁移。 |

### 中间件与交付

| 组件 | 现状与证据 | 结论与边界 |
| --- | --- | --- |
| MySQL | `utf8mb4`、单 schema、MyBatis Mapper、事务 outbox；部署模板将端口绑到回环 | **可用的单机事实源。** 需要 #120 的版本化迁移、最小权限和恢复演练；无主从/跨机容灾。 |
| Redis | token/handoff TTL、会话 runner lease、原子 Lua；Docker 开启 AOF 且仅回环监听 | **保留。** Redis 异常返回显式错误；单实例、无认证依赖回环隔离，不适合跨主机/不可信本机进程的威胁模型。 |
| RabbitMQ | durable exchange/queue/DLQ、outbox relay 指数退避、receipt 去重；管理端口只回环 | **保留。** 消费语义为至少一次；尚无 live broker 压力或故障恢复演练。 |
| Elasticsearch | MySQL 可重建投影、任务 lease/重试；单节点内存上限与 384 MiB heap 已配置 | **保留且限定。** 仅单节点搜索能力；关闭安全特性在仅回环的单机演示中可接受，跨主机部署前必须重新做认证/TLS/网络隔离设计。 |
| Spring Boot/配置 | Java 21、Boot 3.4、Actuator、环境变量注入、功能开关 | **保留。** 配置可运行，但业务指标、结构化脱敏日志、告警和访问控制证据不足，见 #122。 |
| Nginx/systemd | TLS、HTTP 跳转、WS 升级、后端和中间件回环监听；service 使用受限用户和 systemd sandbox 选项 | **保留。** 这是合理的单机生产基线；不是负载均衡、自动扩缩或高可用。 |
| CI/测试 | PR/main 执行 Maven、前端测试/构建和桌面 web 构建；RabbitMQ/ES/MySQL 真集成按环境变量 opt-in | **保留并补强。** 测试金字塔存在，但真实依赖的隔离、版本与性能证据需要持续收敛。 |

### Java 后端待补能力

| 优先级 | 缺口 | 风险 | 行动 |
| --- | --- | --- | --- |
| P1 | schema 演进和恢复没有版本化/演练证据 | 升级无法可靠解释，应用账号可能承担 DDL 权限 | #120：版本化迁移、账号分离、隔离恢复演练。 |
| P1 | 没有统一的脱敏结构化日志、业务指标、告警阈值 | 出现 outbox/索引/依赖异常时只能人工翻日志 | #122：定义指标、关联 ID、敏感字段禁止项和单机抓取方式。 |
| P2 | 真 MySQL、RabbitMQ、ES 集成测试默认不在 CI | 依赖升级/配置漂移可能晚发现 | 在 #120/#122 后评估 Testcontainers 或专用隔离环境，不共享生产账号。 |
| P2 | 单机中间件全部共宿主 | 单机故障同时影响应用与状态服务 | 明确保留为当前成本边界；出现可用性目标后再独立设计 HA，不提前微服务化。 |

## 验证事实与未证明项

| 证据 | 已证明 | 未证明 |
| --- | --- | --- |
| `mvn -B test` 于 `main@913c252` | 125 passed、0 failed、4 skipped | 外部真实依赖的持续可用性与跨版本兼容性 |
| `mvn -B -DskipTests clean package` | Spring Boot 可重建可发布 JAR | 运行时性能和安全性 |
| 已记录的隔离 MySQL 测试 | JDBC 与 MyBatis 路径均在隔离 schema 中通过 2/2 outbox 状态测试 | 不能代替生产账号授权、备份恢复或 live broker 证明 |
| 生产健康与中间件检查 | `/api/health`、Redis、RabbitMQ、ES、MySQL 基本可用；公网 WSS 静音会话完成 | 长时稳定性、并发容量、模型质量、TTS 声学质量、RTO/RPO、高可用 |

真实 MySQL 测试必须遵守 [`mysql-isolated-integration-test.md`](../verification/mysql-isolated-integration-test.md)
的专用账号和 schema 边界；该手册由 #118 跟踪，不能再以全局撤权方式“清理”测试账号。

## 推荐的项目叙事

简历和面试可将项目拆成两段，而不是称为“通用语音平台”：

- **实时 AI 语音系统**：描述输入采集、WebSocket PCM 流、queue overload 策略、跨实例会话归属、
  外部模型接入、纠偏和报告回退；用 #121 的真实基线补充量化结果后再写具体数字。
- **Java 后端工程**：描述 Spring Boot 分层、MyBatis/MySQL、事务 outbox、Redis lease、RabbitMQ
  至少一次消费幂等、ES 可重建索引、Nginx/systemd 单机发布；同时主动说明单机和外部依赖边界。

这比堆叠“Redis、MQ、ES、微服务、高并发”等关键词更可信。面试时应能画出会话创建、实时推流、会话
结束、outbox 发布和 ES 索引的时序，并说明每处失败如何可见、如何重试、如何回滚。
