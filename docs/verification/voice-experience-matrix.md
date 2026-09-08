# BabelFlux 语音系统体验与底层验证矩阵

版本：v1.14（2026-09-09）

本矩阵把语音岗位要求转成可复现的项目验收项。岗位调研强调 ASR、TTS、语音翻译、端到端语音交互、流式低延迟、音频前端处理、性能/内存优化和技术测试文档；BabelFlux 当前以后端 PCM 流和百炼适配器为主，不能把尚未实现的降噪、回声消除、麦克风阵列或声源定位写成已完成能力。

## 验证规则

- 用户体验项必须有实际启动的前端和后端、真实输入、可见事件或可播放音频；单元测试不能替代体验证据。
- 底层项必须有代码位置、边界测试或运行日志；“理论上不会发生”不算通过。
- 延迟使用 `request -> first visible/audio event` 定义，至少记录 P50/P95；只有一次运行时写单次观测，不写百分比提升。
- 真实百炼与真实中间件分开记录。H2、Mockito、Fake WebSocket 和 provider mock 只能证明契约，不代表真实服务质量。
- 任何丢帧、超时、断线、错配或降级都要保留失败样例和恢复动作，不能只记录成功样例。

## 用户可见体验矩阵

| ID | 场景 | 可复现输入/操作 | 通过标准 | 当前证据 |
| --- | --- | --- | --- | --- |
| U-01 | 前端启动 | `npx vite --host 127.0.0.1 --port 5173`，打开根路径 | HTTP 200，Vue 入口可加载 | PASS：2026-09-08，HTTP 200，568 bytes |
| U-02 | 后端启动 | `MYSQL_ENABLED=true`、MySQL 8.0.43，`mvn -B spring-boot:run` | Tomcat 监听 8000，`GET /api/health` 返回 ok | PASS：真实 MySQL 3307 启动，`{"status":"ok"}` |
| U-03 | 演示同传 | 创建 `inputMode=demo` 会话，WS 发送 `start_session` 后 `stop_session` | 双语字幕事件顺序正确，结束后可查报告 | PASS：MySQL 实测 `session_started`、2 组 transcript/translation、`session_report`；`ended`/segments/report 均落库 |
| U-04 | 真实实时同传 | 16 kHz mono PCM 通过 WS 推送，发送 `audio_end` | 收到 transcript/translation，报告可下载，错误可见 | PASS：2026-09-09 重启后真实链路 final 源文为 `The bell flux voice smoke test.`，中文翻译和 `session_report` 均返回；修复前曾出现重复词 |
| U-05 | TTS 首包与格式 | `/api/models/tts/speech`，模型 `qwen3-tts-flash-realtime` | 返回非空 PCM，采样率/格式与请求一致 | PASS：5 次均 HTTP 200，24 kHz PCM，99840–115200 bytes，耗时 908/938/998/1008/1465 ms（P50 998，P95 1465） |
| U-06 | ASR 可读性 | 将真实 TTS PCM 送入 `/api/models/asr/transcriptions` | final 文本可读，partial 最终收敛 | PASS：`fun-asr-realtime` 连续 5 次 HTTP 200，final 均为 `The bell flux voice smoke test.`；`qwen3-asr-flash-realtime` 独立 ASR 端点真实返回 `ModelNotFound`，已记录为账号/模型边界 |
| U-07 | 多轮连续对话 | 5 轮短句 + 1 段 2 分钟语音 | 无断线/卡死，轮次顺序和字幕滚动正确 | NOT RUN：需固定语料和重复次数 |
| U-08 | 暂停/恢复 | 主 WS 发送 `pause_session` / `resume_session`，同时观察跨实例 handoff | 主端和 handoff 状态一致，恢复后不重复或跳过明显内容 | PASS：真实 8013/8014 session `5215d44b-5b04-4b28-96e7-6ea3daec5c3a` 两端均收到“会话已暂停/会话已恢复”；MySQL 最终 `ended` 且报告存在 |
| U-09 | 播放中断 | TTS 播放中输入下一句 | 旧音频停止，新句首包延迟可记录 | NOT RUN |
| U-10 | 跨句纠偏 | 包含数字、否定、专有名词和术语表的固定语料 | 修正事件高亮，最终报告保留修订记录 | NOT RUN：当前仅有 provider/服务单测 |
| U-11 | 长时稳定性 | 20 分钟固定音频或 20 轮会话 | 无内存持续增长、无 WebSocket 重连风暴、报告最终生成 | NOT RUN |
| U-12 | 故障可理解 | 无 key、provider 超时、非法 PCM、上游 4xx/5xx | 用户收到稳定错误/降级提示，不暴露凭据 | PASS（已覆盖输入/模型选择边界）：非法 PCM 和未允许 TTS 模型均在本地返回 HTTP 400；真实 `ModelNotFound` 为 HTTP 422 + 稳定 code/message；已允许但 provider 不响应的模型仍按握手超时返回 504 |
| U-13 | PCM 队列溢出反馈 | 真实后端 WS 快速注入 200 个 40 ms/1280 bytes PCM 帧 | 页面收到 lagging 状态且显示实际缓冲时长，随后可结束并拿到报告 | PASS：2026-09-09 后端 8005 + 百炼实时链路收到 172 次 `lagging`，`lagMs` 均为 1000（修复前同场景为 0），1.03 s 收到 `session_report` |
| U-14 | 重复结束会话 | 两个 WS 客户端复用同一 session token 并发发送 `audio_end` | 两端返回同一报告，用户不感知重复纠偏或重复事件 | PASS：修复后 8006 两端均返回同一 `reportId`；MySQL outbox `session.finished=1`、`report.generated=1` |
| U-15 | 重复启动主连接 | 两个 WS 客户端复用同一 session token 并发发送 `start_session` | 只有一个实时 runner；第二连接得到可理解错误，主连接字幕/报告不重复 | PASS：修复后 8008 仅一个连接收到 2 组字幕和 1 份报告，另一连接收到“会话已在其他连接中运行” |
| U-16 | 跨实例重复启动主连接 | 两个后端实例共享 Redis，两个 WS 客户端复用同一 session token 并发发送 `start_session` | 全局只有一个实时 runner；非 owner 实例得到可理解错误，owner 正常输出字幕和报告 | PASS：8013/8014 实测仅 8013 获得 lease 并输出 2 组字幕；8014 返回“会话已在其他实例中运行” |
| U-17 | 跨实例重复结束会话 | 两个后端实例共享 MySQL，两个 WS 客户端复用同一 session token 并发发送 `audio_end` | 两端返回同一报告；报告生成、纠偏调用和生命周期事件均不重复 | PASS：修复后 8013/8014 均返回同一 reportId，MySQL outbox `session.finished=1`、`report.generated=1` |
| U-18 | 跨实例 handoff 字幕投送 | 主 WS 与 handoff WS 连接不同后端实例，主端启动真实会话 | handoff 端收到与主端相同的字幕、状态和 session_report，不出现“已连接但无字幕” | PASS：8013 主端与 8014 handoff 各收到 6 个启动/字幕事件及同一 `session_report` |
| U-19 | 跨实例启动状态即时可见 | 8013 创建会话并发送带语言/领域覆盖的 `start_session`，立即从 8014 查询历史 | 查询立即显示 `running` 及本次覆盖参数，不需等会话结束 | PASS：当前提交 `dc29ad9` 实测 250 ms 内读到 `running/ja/en/running-state-real/balanced`；结束后两实例历史一致 |
| U-20 | 报告时长准确 | 发送约 3 秒真实语音，等待 provider/纠偏完成后查看历史和报告 | 报告时长跟随音频时间轴，不因后端处理等待膨胀 | PASS：修复后百炼 5 次报告均为 3004 ms；墙钟完成 4156–8748 ms，时长不随处理等待变化 |
| U-21 | 会后纠偏完整性 | 5 轮真实 TTS PCM 通过 LiveTranslate，比较实时最终译文与会后报告 | 会后纠偏不得删减实时译文中的句意、数字或术语；异常候选回退实时译文并明确标记 | PASS：基线 session `12e27456-209d-476d-a3ef-9f123e3b9dae` 发现 2 句长度删减和 1 句等长语义删减；修复后 session `92c61a5e-2d2c-4886-ba41-8983add946ee` 5/5 段报告均保留实时内容，`correctionStatus=completed`、0 error |

## 用户不可见的底层矩阵

| ID | 关注点 | 设计/代码证据 | 当前证据 |
| --- | --- | --- | --- |
| I-01 | PCM 边界 | `RealtimeSessionRunner` 使用 25 帧有界队列，满载丢弃最旧帧并发送 lag 状态 | PASS：`RealtimeSessionRunnerTest`、`MediaPcmSourceTest` |
| I-02 | 音频时钟 | 发送音频时长与客户端 `media_clock` 分开计量，250 ms 节流并报告 lag | PASS：WebSocket 控制测试；真实长时观测未跑 |
| I-03 | 段落绑定 | item/response ID 绑定，未绑定译文按 FIFO 归并，partial/final 分开 | PASS：`DashScopeRealtimeClientTest`、runner 测试 |
| I-04 | 纠偏复杂度 | 纠偏只扫描固定窗口，按分钟限流；不扫描整场字幕 | PASS：`BoundedRevisionWindowTest`、`RealtimeRevisionServiceTest` |
| I-05 | Provider 超时 | HTTP/WS 设置连接与读取超时；会后纠偏失败回退实时译文 | PASS：provider 与 `FinalCorrectionServiceTest` |
| I-06 | 资源清理 | runner 使用 virtual thread、bounded final drain 和 `AutoCloseable` provider | PASS：runner 生命周期测试；长时资源曲线未测 |
| I-07 | 实时输出音频 | `response.audio.delta` 归属 segment 并下发 `audio_segment` base64 | PASS：2026-09-09 LiveTranslate `ttsEnabled=true` 真实链路收到 7 个 `audio_segment`，合计 107520 bytes；客户端归一化测试通过 |
| I-08 | 中间件可靠性 | MySQL 事实源、Redis TTL 票据、Rabbit outbox、ES 派生索引 | PASS：MySQL 3308、Redis 6380、RabbitMQ 4.3.5/OTP 28 5673、ES 9200 均有真实链路证据；Rabbit 包含断 broker 重试、重启恢复、重复投递幂等与 DLQ |
| I-09 | 多实例索引 | ES job `pending -> processing -> indexed`，owner + lease 条件更新，过期可恢复 | PASS：两实例 ES live 竞争与过期 lease 恢复；H2 测试覆盖条件更新/幂等 |
| I-10 | 安全边界 | API key 仅环境变量；URL 媒体 host/私网地址限制；错误不回传 header/audio | PASS：现有安全与媒体 URL 测试 |
| I-11 | 队列 lag 计量 | PCM 队列容量 25 帧，丢弃最旧帧时按 `audio.size() * FRAME_MS` 计算 `lagMs` | PASS：`RealtimeSessionRunnerTest.reportsMeasuredQueueLagWhenInputOverrunsProvider` 断言 1000 ms；真实 WS 压测 172 次均为 1000 ms，无 0 ms 误报 |
| I-12 | 报告生成幂等 | `SessionService.finish` 以 sessionId 维护 in-flight future，合并并发结束调用 | PASS：`SessionServiceTest.concurrentFinishCallsGenerateAndPublishOneReport`；真实 MySQL 对照显示重复事件 2/2 -> 1/1 |
| I-13 | 主 runner 所有权 | `SessionWebSocketHandler` 以 sessionId 原子占用主连接，handoff 保持只读 | PASS：`SessionWebSocketHandlerTest.rejectsSecondPrimarySocketForSameSession`；真实双 WS 仅一次 runner/一次生命周期事件 |
| I-14 | Redis 分布式 runner lease | `SET NX PX` 原子获取；Lua 按 owner 校验续租/释放；TTL 到期自动回收 | PASS：真实 Redis 6380 获取后 TTL 28759 ms；等待 12 s（含一次 10 s 续租）仍为 26749 ms；停止后 TTL=-2；MySQL outbox `session.finished=1`、`report.generated=1` |
| I-15 | 跨 JVM 报告最终化幂等 | `JdbcSessionRepository.findByIdForUpdate` 在同一事务内锁定 session 行；第二事务读取已持久化 `report_json` 后直接复用 | PASS：H2 两事务并发测试和真实 MySQL 双实例回归均只调用一次生成器、只追加一组生命周期事件 |
| I-16 | Redis 实时事件 fan-out | `RedisMessageListenerContainer` 订阅 `babelflux:events:session:*`；消息 envelope 携带 publisher，远端只写入本地 bounded history/queue，忽略自身回环 | PASS：真实 8013->Redis 6380->8014 handoff 收到字幕和终态；Pub/Sub 无持久重放、Redis 故障边界已记录 |
| I-17 | 控制状态 fan-out | `pause_session` / `resume_session` 构造 `source_sync_state`，统一经 `SessionEventHub.publish` 后回发主连接 | PASS：修复前 handoff 只有 ready；修复后真实双实例均收到暂停/恢复状态；handoff 不具备 runner 控制权 |
| I-18 | 启动快照持久化 | `SessionWebSocketHandler` 在 `session.start()` 后、创建 runner 前调用 `SessionService.saveProgress`；失败时回滚终态 | PASS：H2/JDBC 与 handler 测试 14/14；真实 MySQL 查询启动后即为 running，最终 `segments_json`=2、`report_json` 非空，outbox 生命周期事件各 1 条 |
| I-19 | 最新快照原子最终化 | runner 等待纠偏任务后将内存 `Session` 交给带行锁的 `finish(Session)`，报告与最终快照一次保存；瞬时写入失败最多重试一次 | PASS：H2 JDBC 故障注入首次最终写入失败后重试成功，报告仍含 2 段；真实 MySQL 最终行与报告均为 2 段，生命周期事件各 1 条 |
| I-20 | 音频时长与处理耗时隔离 | `SessionReportService` 有字幕时使用 segment endMs 时间轴；空会话才使用 wall-clock fallback | PASS：受控 60 s wall-clock/3004 ms segment 测试返回 3004 ms；真实百炼 5/5 返回 3004 ms，MySQL 与 REST 一致 |
| I-21 | 会后纠偏完整性保护 | `FinalCorrectionService` 对长译文执行长度比例、最长公共子序列重叠和长数字/英文 token 保留校验；拒绝项不进入 `finalById` 或 revision | PASS：`FinalCorrectionServiceTest` 4/4；基线真实报告出现“第一轮检查完成”等删减候选，修复后候选被保留/拒绝策略不会覆盖实时译文，报告质量说明记录保护结果 |
| I-22 | ASR 音频输入契约 | `DashScopeSpeechClient.transcribe` 在建立 WebSocket 前校验 PCM 偶数字节和 8–48 kHz 采样率；非 PCM 不应用字节对齐规则 | PASS：`DashScopeSpeechClientTest` 6/6；README 伪造 PCM 真实请求由 HTTP 200 空结果修复为 HTTP 400；真实 TTS PCM 仍返回可读 final 文本 |
| I-23 | TTS 模型选择与握手预算 | `DashScopeSpeechClient` 使用可配置 `allowedTtsModels` 白名单；允许模型握手使用独立 deadline，音频/结束阶段保留通用读取超时 | PASS：单测 9/9；未知模型真实延迟由 30,453 ms 降为 135 ms/HTTP 400，合法模型 1,486 ms/HTTP 200；配置 `DASHSCOPE_ALLOWED_TTS_MODELS` 可扩展白名单 |

## 固定测量记录

每次真实语音实验追加一条记录，至少包含：

```text
时间与提交：
机器/CPU/内存/JDK：
前端/后端地址：
输入格式：采样率、声道、位深、帧长、时长：
模型与 provider：
用例：
首个 transcript/translation/audio 事件延迟：
P50/P95/P99（重复 >= 5 次时填写）：
端到端完成时间：
丢帧/队列溢出/断线/错误数：
用户可见结果：
底层日志与报告 ID：
结论、失败样例与下一步：
```

### 2026-09-08 MySQL + demo WebSocket 持久化烟测

```text
时间与提交：2026-09-08 12:00（Asia/Hong_Kong），fix/mysql-schema-migration @ 997bf0a
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / http://127.0.0.1:8000
输入格式：demo 事件流（非百炼计费输入；本轮不冒充真实 ASR）
模型与 provider：inputMode=demo，Java demo provider
用例：POST /api/sessions -> WS start_session -> stop_session
首个 transcript/translation/audio 事件延迟：未单独计时（后续性能实验补齐）
P50/P95/P99：未测（单次烟测，不形成分位数）
端到端完成时间：约 2.2 s（stop 后收到 session_report）
丢帧/队列溢出/断线/错误数：0/0/客户端正常关闭/0
用户可见结果：2 组双语字幕，报告事件正常返回
底层日志与报告 ID：session=6ab47794-3bd9-42ae-a38d-38880954bb8d；report=6ab47794-3bd9-42ae-a38d-38880954bb8d-report
数据库证据：MySQL 8.0.43 3307，babelflux_sessions.status=ended，segments=2，report_json 非空；lease_owner/lease_until 列存在
结论、失败样例与下一步：MySQL 增量迁移和会话持久化通过；继续补 U-07～U-12、多次延迟分位数和真实中间件实验
```

本记录对应用户体验矩阵 U-02/U-03 和底层矩阵 I-08；它不替代 U-04 的百炼 LiveTranslate 真实音频证据。

### 2026-09-08 Redis TTL 与一次性 handoff

```text
环境：Redis 8.10.1，127.0.0.1:6380；后端 REDIS_ENABLED=true、MYSQL_ENABLED=true
基础检查：redis-cli ping=PONG；SET babelflux:test live EX 30；TTL=30；GET=live
应用链路：创建 session 后 Redis 出现 websocket/handoff TTL key；首次 handoff claim 返回 wsToken
并发/重放边界：同一 handoff token 第二次 claim 返回 HTTP 409，Redis Lua 原子消费生效
RabbitMQ 真实验证已补齐；剩余边界是多节点集群、网络分区和长时压测，不把单节点烟测当作这些能力的证明。
```

### 2026-09-08 Elasticsearch 报告索引与搜索

```text
环境：Elasticsearch 7.17.24 single-node，127.0.0.1:9200，cluster health=green
后端配置：MYSQL_ENABLED=true、REDIS_ENABLED=true、ELASTICSEARCH_ENABLED=true
应用链路：创建 demo session -> WS start_session/stop_session -> session_report -> MySQL index job
报告：cb73303a-615f-4e5f-b9bc-43846a7775ec-report
状态证据：GET /api/reports/{reportId}/index-status -> status=indexed, attempts=0
搜索证据：GET /api/reports/search?q=Welcome -> total=1，返回同 reportId/sessionId 与 2 句摘要
边界：本项验证索引任务和搜索闭环；本地单节点 ES 不等同于多节点集群压测
```

### 2026-09-08 双实例索引 lease 与过期恢复

```text
实例：Java backend :8000 + :8001，共享 MySQL 8.0.43 :3307、Redis :6380、ES 7.17.24 :9200
正常竞争：报告 f4139806-f111-40c5-97ae-f099572e6e19-report 由 session_report 生成；ES _stats/indexing index_total 1 -> 2，DELTA=1；MySQL index status=indexed、attempts=0
过期恢复：手工将同一 job 置为 processing、lease_owner=crashed-instance、lease_until=过去时间；两实例调度后 ES index_total 2 -> 3，DELTA=1，状态重新 indexed、owner 清空
搜索结果：GET /api/reports/search?q=Java 返回该报告；没有重复 ES 文档（固定 reportId）
边界：这是共享单节点 ES 的真实并发/恢复烟测，不替代多节点故障压测和长时 P95/P99 观测
```

### 2026-09-08 RabbitMQ 真实 outbox/consumer 故障恢复

```text
环境：RabbitMQ 4.3.5，Erlang/OTP 28.5.0.6，节点 rabbitmq_it@wt；AMQP 5673，管理 15673，
      独立 data/log 目录 C:\project\BabelFLUX-middleware-it\rabbitmq-lease-20260908；
      MySQL 8.0.43 127.0.0.1:3308/babelflux_outbox_it；后端 http://127.0.0.1:8002；前端既有 5173
启动证据：rabbitmqctl status=0；RabbitMQ 4.3.5；OTP 28；无 alarm；管理 API HTTP 200；
      真实后端日志确认 MySQL Hikari 连接和 AMQP 5673 连接；队列消费者=1
首投：POST /api/sessions -> outbox session.created；MySQL status=published、attempts=0、
      last_error/lease=null；receipt 同 event_id 1 行；主队列 ready=0，DLQ ready=0
重复投递：管理 API 重发同 payload，routed=true；receipt 仍为 1 行，主队列 ready=0
断 broker：停止 rabbitmq_it 后创建会话；修复后 4 秒内 status=pending、attempts=2、
      last_error=java.net.ConnectException: Connection refused: getsockopt、lease 清空；
      旧实现同窗口 attempts 曾达 29+，根因为 MySQL current_timestamp(+08:00) 与 Java UTC 混用
恢复：使用相同 OTP28/数据目录启动 broker；事件按退避恢复并最终 published、last_error=null，
      receipt=1，主队列 ready=0
坏消息/DLQ：向 exchange 投递 9 bytes `{not-json`；listener JsonParseException，
      x-death.reason=rejected；DLQ GET 返回 payload `{not-json`，message_count=0（已 ack 取证）
结论：真实 outbox->Rabbit exchange->consumer->MySQL receipt 闭环、重复幂等、断 broker 恢复和
      rejected 消息 DLQ 均通过；不等同于多节点 HA、网络分区、TLS/RBAC 和 20 分钟压力测试
```

### 2026-09-09 百炼 TTS/ASR/LiveTranslate 真实语音回归

```text
提交：fix/bailian-tts-handshake（工作区修改，尚未提交时采集）；Windows 11 x64，JDK 21.0.12.1
前端/后端：http://127.0.0.1:5173（HTTP 200，Vite）/ http://127.0.0.1:8003（Tomcat，/api/health=200）
TTS：POST /api/models/tts/speech，qwen3-tts-flash-realtime、Cherry、pcm、24000 Hz、commit；5/5 HTTP 200。
     音频 bytes：99840、99840、115200、107520、103680；耗时：1465、908、1008、938、998 ms；P50=998 ms，P95=1465 ms。
     事件序列包含 session.created、session.updated、response.audio.delta、response.audio.done、response.done、session.finished。
ASR：将上述 TTS PCM 原样上传 /api/models/asr/transcriptions?model=fun-asr-realtime&audioFormat=pcm&sampleRate=24000；5/5 HTTP 200，
     耗时 994、903、843、1280、843 ms（P50=903 ms，P95=1280 ms），每次 6 个 segment，事件为 task-started、result-generated*、task-finished，
     final 均为 “The bell flux voice smoke test.”。同端点指定 qwen3-asr-flash-realtime 返回 ModelNotFound（账号可用模型边界，未静默改写）。
实时同传：TTS PCM 用 ffmpeg 从 24 kHz 重采样为 16 kHz，按 40 ms/1280 bytes 通过 WS /api/ws/sessions/{id} 推送；
     ttsEnabled=false 实测 final 源文 “The bell flux voice smoke test.”、中文 “贝尔福克斯语音烟雾测试”，session_report 在约 10.7 s 返回；
     ttsEnabled=true 实测同样文本，7 个 audio_segment，合计 107520 bytes，session_report 在约 11.6 s 返回。
底层缺陷与修复：百炼 `conversation.item.input_audio_transcription.text` 的 `stash` 是累计快照，旧逻辑按增量拼接会生成 “The The bell ...”；
     现按 `raw.stash` 快照替换，普通增量事件仍按原合并逻辑处理；新增 RealtimeSessionRunner 回归测试。TTS 握手同时接受 session.created/session.updated，
     修复只返回 session.created 时错误超时并产生 HTTP 502 的问题。
协议边界：一次发送 149760 bytes 的单帧会触发 WebSocket close 1009；浏览器采集实现按约 40 ms 分帧，不触发该限制。
结论：用户可见的 TTS 播放、ASR final、实时双语字幕和实时 TTS 音频均有真实证据；长时、多设备、网络分区和多节点 HA 仍未覆盖。
```

### 2026-09-09 不可用 ASR 模型错误分类

```text
复现：已启动的 :8003 后端调用 /api/models/asr/transcriptions，传入真实 TTS PCM、
      model=qwen3-asr-flash-realtime、audioFormat=pcm、sampleRate=24000。
修复前：百炼返回 code=ModelNotFound 和明确消息，但 API 固定映射为 HTTP 502；客户端会将可纠正的模型参数错误误认为瞬时网关故障并可能重试。
修复：ApiExceptionHandler 对 code=ModelNotFound 返回 HTTP 422，保留 message/code/requestId；其他 provider 上游失败仍返回 HTTP 502。
验证：新增 ApiExceptionHandlerTest；后端重启后对同一真实请求实际返回 422 + ModelNotFound，不输出 API key 或音频。
边界：仅处理已实测的 ModelNotFound；超时、无 key、非法 PCM、provider 5xx、断网和重试 UI 仍需逐项做真实矩阵验证。
```

### 2026-09-09 MySQL/Redis/RabbitMQ/Elasticsearch 四中间件启动与闭环

```text
实例：Elasticsearch 7.17.24 单节点 127.0.0.1:9200，cluster=green；MySQL 8.0.43 127.0.0.1:3307/babelflux；
      Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5/OTP 28.5.0.6 AMQP 5673、管理 15673；前端 5173。
启动缺陷：首次以 MYSQL_URL=jdbc:mysql://127.0.0.1:3307/babelflux 启动 8004 时，固定 H2 driver 导致启动失败；
      删除 driver-class-name 硬编码后，Spring Boot 自动选择 com.mysql.cj.jdbc.Driver，Hikari 连接成功，Tomcat 8004 启动。
      RabbitMQ URL 使用编码 vhost `%2f` 时真实返回 530 NOT_ALLOWED；改为默认 `/` vhost 后连接成功，消费者=2。
真实链路：POST /api/sessions(inputMode=demo, sessionName=ES middleware smoke) -> WebSocket start/stop -> session_report；
      MySQL babelflux_sessions.status=ended、segments_json 长度对应 2 段、report_json 非空；Redis PING=PONG；
      Rabbit 主队列 ready=0、DLQ ready=0；报告 6f9291c4-4db5-4260-ac62-7d9de5e7d548-report index-status=indexed、attempts=0；
      ES 搜索 q="MySQL ES smoke" 返回 total=3，并包含上述 reportId（包含此前两次同名烟测）。
结论：默认 H2 与真实 MySQL URL 不再互相冲突，四中间件在同一 Java 实例完成持久化、消息消费和报告索引闭环；
      vhost 写法和单节点 ES 安全未启用仍作为部署边界记录，不等同于 HA、TLS/RBAC 或长时压力测试。
```

### 2026-09-09 PCM 队列溢出 lag 指标真实回归（Issue #47）

```text
时间与提交：2026-09-09 02:19（Asia/Hong_Kong），fix/realtime-lag-metric（提交前工作区）
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / http://127.0.0.1:8005；后端真实连接 MySQL 3307、Redis 6380、RabbitMQ 5673、ES 9200
输入格式：16 kHz、mono、16-bit PCM，40 ms/frame，1280 bytes/frame；WS 一次快速发送 200 帧（约 8 s 媒体时长）
模型与 provider：inputMode=microphone，百炼 LiveTranslate，ttsEnabled=false
用例：POST /api/sessions -> WS start_session -> 发送 200 帧 -> audio_end
首个 transcript/translation/audio 事件延迟：本轮为合成正弦压力帧，未产生可读字幕；首个溢出状态约 320 ms
P50/P95/P99：不适用（单次故障注入，指标验证不作性能分位数）
端到端完成时间：1.03 s，收到 session_report `13ed1114-8580-4293-9d69-d3a6cd99bd71-report`
丢帧/队列溢出/断线/错误数：约 175 次丢弃最旧帧、172 次 lagging 状态、客户端正常关闭、0 error
用户可见结果：状态消息显示“音频输入超过 1 秒缓冲，已丢弃最旧帧”，`lagMs=1000`；修复前同一溢出路径固定显示 `lagMs=0`
底层日志与报告 ID：`RealtimeSessionRunner` 按有界队列实际帧数计算 `audio.size() * FRAME_MS`；报告 ID 如上
结论、失败样例与下一步：Issue #47 根因和修复已由真实 WS 压力复现；随机噪声不验证 ASR 质量，长时/网络分区仍待独立矩阵
```

### 2026-09-09 并发结束会话幂等真实回归（Issue #50）

```text
时间与提交：2026-09-09 02:38（Asia/Hong_Kong），fix/session-finish-idempotency（提交前工作区）
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 修复分支 Java backend http://127.0.0.1:8006；MySQL 3307、Redis 6380、RabbitMQ 5673、ES 9200
用例：POST /api/sessions(inputMode=demo) -> 两个 WS 客户端复用同一 token -> 并发发送 audio_end
基线（8005，修复前）：两端均返回同一 reportId，但 MySQL outbox 为 `session.finished=2`、`report.generated=2`、总计 5 条（含 session.created）
修复后（8006）：两端均在约 64 ms 返回同一 reportId `0bb646f7-7158-4667-81f3-f6c3e692d4ac-report`；outbox 为 `session.finished=1`、`report.generated=1`、总计 3 条
用户可见结果：重复点击/断开竞态不会产生第二份报告，两个客户端结果一致
底层证据：`SessionService` 的 in-flight `CompletableFuture` 使同一 session 只有一个生成者，其余调用等待并复用结果；不同 session 不共享 future
测试：`mvn -B '-Dtest=SessionServiceTest' test` 1/1；全量后端 86 通过、0 失败、4 个外部中间件测试按默认配置跳过
失败样例与边界：修复只覆盖单 JVM 内并发；跨多个 Java 实例仍需分布式锁/数据库版本列，暂不把本次结果写成 HA 证明
结论：重复百炼纠偏和生命周期事件的本地竞态已消除，真实 MySQL 事件计数由 2/2 降为 1/1；跨实例幂等列为后续工作
```

### 2026-09-09 同一会话重复启动 runner 真实回归（Issue #52）

```text
时间与提交：2026-09-09 02:51（Asia/Hong_Kong），fix/websocket-single-runner（提交前工作区）
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 修复分支 Java backend http://127.0.0.1:8008；MySQL 3307、Redis 6380、RabbitMQ 5673、ES 9200
输入格式：inputMode=demo（不消耗百炼额度）
用例：POST /api/sessions -> 同一 token 建立两个主 WS -> 两端并发 start_session -> 两端并发 stop_session
基线（8005，修复前）：两个连接各收到 2 个 `transcript_segment` + 2 个 `translation_segment`；MySQL outbox 后续出现 `session.finished=2`、`report.generated=2`
修复后（8008）：仅一个连接收到 2 组字幕并生成 `a2e9093d-87d2-40f1-8f59-a6d5607c3253-report`；另一个连接收到“会话已在其他连接中运行”，其 stop 也收到“会话正在其他连接中运行”
用户可见结果：第二个主连接不会偷偷启动或结束会话；主连接正常完成报告
底层证据：`activeSessionSockets.putIfAbsent(sessionId, socketId)` 原子占用；非 owner 的 stop/audio_end 被拒绝；MySQL outbox `session.finished=1`、`report.generated=1`
测试：`mvn -B '-Dtest=SessionWebSocketHandlerTest' test` 8/8；全量后端目标为 87 通过、0 失败、4 个外部中间件测试按默认配置跳过
失败样例与边界：旧实现按 socketId 管理 runner，两个主连接均可启动 provider；本修复为单 JVM 所有权，跨实例仍需分布式 session lease
结论：同一会话的主 runner 和结束控制已收敛到单一 owner，避免重复百炼连接、重复字幕和重复生命周期事件
```

### 2026-09-09 Redis lease 跨实例实时 runner 真实回归（Issue #54）

```text
提交：fix/websocket-redis-lease @ 248bf47
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 两个修复分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307/babelflux、Redis 8.10.1 127.0.0.1:6380、RabbitMQ 4.3.5 AMQP 5673、Elasticsearch 7.17.24 9200
输入格式：inputMode=demo（两组固定双语字幕，不消耗百炼实时 ASR；报告会后纠偏实际调用 qwen-plus）
用例：8013 POST /api/sessions -> 同一 wsToken 连接 8013/8014 -> 两端并发 start_session -> 等待 12 s -> 两端并发 stop_session
用户可见结果：8013 获得主 runner，收到 2 个 transcript_segment、2 个 translation_segment；8014 收到“会话已在其他实例中运行”；报告可查询，reportId=2781541f-adfc-490b-9436-a3f40e528f05-report
Lease 证据：Redis key `babelflux:lease:runner:{sessionId}` 获取后 PTTL=28759 ms；12 s 后 PTTL=26749 ms（10 s 定时续租有效）；owner 停止后 PTTL=-2（释放成功）
持久化证据：babelflux_sessions.status=ended；outbox 计数 `session.created=1`、`session.finished=1`、`report.generated=1`；报告 segments=2、correctionStatus=completed
底层实现：Redis Lua `SET NX PX` 防止跨 JVM 双写，续租/释放脚本比较 owner token，Redis 异常时向客户端返回稳定错误并依靠 TTL 回收；新增 repository/handler 测试
测试：`mvn -B test`，90 tests run，0 failures，4 个外部中间件测试按默认配置跳过；针对测试 15/15 通过
失败样例与边界：无 Redis 网络分区、进程崩溃恢复或多节点 HA；本次证明的是共享单 Redis 实例的跨 JVM 互斥和续租，不宣称故障域级别高可用
结论：同一 session 在两个真实后端实例上不会重复建立实时 runner；字幕、报告和生命周期事件均保持单份
```

### 2026-09-09 跨实例并发结束报告幂等真实回归（Issue #58）

```text
提交：fix/session-distributed-finish @ d667310
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 两个修复分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307/babelflux、Redis 8.10.1 127.0.0.1:6380、RabbitMQ 4.3.5 AMQP 5673、Elasticsearch 7.17.24 9200
实验（修复前）：session `132a537b-cc09-4f9e-a3a3-aee791381c93` 尚未启动 runner，两个实例并发 `audio_end`；两端返回同一 reportId，但 outbox 为 `session.created=1`、`session.finished=2`、`report.generated=2`
修复：`SessionRepository` 增加 `findByIdForUpdate`；JDBC 实现使用 `SELECT ... FOR UPDATE`，`SessionService.finish` 在生成前锁定 session 行。第二事务等待首个事务提交后读取 `report_json`，异常回滚可由后续调用接管。
实验（修复后）：session `fc3bde68-6d47-4df3-89bd-e4d6bf33f07d` 同样由 8013/8014 并发 `audio_end`；两端均返回 `fc3bde68-6d47-4df3-89bd-e4d6bf33f07d-report`
持久化证据：outbox 为 `session.created=1`、`session.finished=1`、`report.generated=1`；`babelflux_sessions.status=ended`，报告可通过 REST 查询
底层证据：H2 两事务并发测试验证第二事务在行锁处等待；`reports.generate` 调用次数为 1。报告生成期间外层事务保持锁，纠偏服务的 `NOT_SUPPORTED` 仅暂停事务连接，不释放锁
测试：`mvn -B '-Dtest=SessionServiceDistributedFinishTest,SessionServiceTest,JdbcSessionRepositoryTest' test`，3/3 通过；`mvn -B test` 全量 91 通过、0 失败、4 个外部中间件测试按默认配置跳过
失败样例与边界：行锁会让同 session 的重复结束请求等待报告生成时间；本轮未覆盖数据库故障、死锁重试、跨地域高延迟和长时锁等待，不能宣称多主 HA
结论：跨 JVM 的重复报告生成与重复生命周期事件已由真实 MySQL 回归修复，用户仍获得一致 reportId，百炼纠偏不会被重复调用
```

### 2026-09-09 跨实例 handoff 实时事件 fan-out 真实回归（Issue #60）

```text
提交：fix/websocket-handoff-events @ c8e79af
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 两个修复分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：Redis 8.10.1 127.0.0.1:6380；MySQL 8.0.43 3307；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
修复前复现：session `4309c108-a896-4f40-b5a2-008ec3b532b0` 主 WS 在 8013、handoff WS 在 8014；8013 收到 2 组字幕，8014 只有 `session_started`
修复：SessionEventHub 在 Redis 启用时订阅 `babelflux:events:session:*`；主实例本地 fan-out 后发布 envelope，远端解析并写入本地 bounded replay/queue；publisher=nodeId 过滤自身回环；收到 `session_report` 后完成远端 channel 回收
字幕回归：session `c5aa307c-87fa-4030-9596-54ad34cfb06c` 两端均收到 6 个启动/状态/字幕事件（session_started、ready、2 transcript、2 translation），事件内容一致
终态回归：session `b9b3c299-9e06-4391-8ed5-345af9413188` 停止后 8013/8014 均收到 `session_report`，reportId=`b9b3c299-9e06-4391-8ed5-345af9413188-report`，correctionStatus=completed
持久化证据：MySQL outbox 为 `session.created=1`、`session.finished=1`、`report.generated=1`；报告 REST 查询成功
测试：`mvn -B '-Dtest=SessionEventHubTest,SessionWebSocketHandlerTest' test`，13/13 通过；全量后端测试将在本 PR 更新后执行
失败样例与边界：Redis Pub/Sub 是实时、非持久总线；handoff 在订阅建立前错过的事件不能从 Redis 重放，Redis 网络故障期间跨实例字幕不可补偿；MySQL outbox 仍是生命周期事实源
结论：跨实例 handoff 从“已连接但无字幕”恢复为可见双语字幕和终态报告；未把单 Redis Pub/Sub 结果宣称为 Streams/集群 HA
```

### 2026-09-09 跨实例 handoff 暂停/恢复状态真实回归（Issue #62）

```text
提交：fix/websocket-handoff-sync @ 53f3e01
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 两个修复分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
修复前复现：session `fed42cf7-6b28-445f-8e05-1320a0e59cea` 主端收到“会话已暂停/会话已恢复”，8014 handoff 只有“演示同传引擎就绪”
修复：`SessionWebSocketHandler` 将 pause/resume 的 `source_sync_state` 统一经 `SessionEventHub.publish` 广播，再直接回发主连接；handoff 仍只读
修复后回归：session `5215d44b-5b04-4b28-96e7-6ea3daec5c3a` 两端均收到 `source_sync_state`：演示同传引擎就绪、会话已暂停、会话已恢复；字幕事件内容一致
持久化证据：MySQL `babelflux_sessions.status=ended`、`report_json` 非空；outbox 为 `session.created=1`、`session.finished=1`、`report.generated=1`
测试：`mvn -B '-Dtest=SessionWebSocketHandlerTest,SessionEventHubTest' test`，14/14 通过；前端 55/55 与构建门禁已在同一运行环境通过
失败样例与边界：本次 Node WS 采集窗口未捕获 `session_report`，但数据库已确认报告落库；Redis Pub/Sub 故障窗口仍可能丢实时控制状态，MySQL 是生命周期事实源
结论：U-08/I-17 跨实例暂停/恢复状态已从“主端可见、handoff 不可见”恢复为两端一致；不把 handoff 的只读订阅误标为控制权限
```

### 2026-09-09 跨实例会话启动快照真实回归（Issue #64）

```text
提交：fix/session-start-persistence @ dc29ad9
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 当前分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
修复前复现：session `660d8565-1413-43a3-89b4-d3696c1860d8` 启动后立即从 8014 查询仍为 `created`、旧语言/领域和 0 个 segment，结束后才更新
实验：8013 创建 demo session `af384c30-e476-44b5-8d39-32a17cad17dd`，WS 发送 `start_session` 覆盖 `ja -> en`、`running-state-real`、`balanced`；250 ms 后从 8014 查询，再发送 `audio_end`
用户可见结果：250 ms 内 8014 已返回 `status=running`、`sourceLanguage=ja`、`targetLanguage=en`、`domain=running-state-real`、`modelProfile=balanced`、`segmentCount=0`；结束后两实例均为 `ended`、2 段字幕和同一 reportId=`af384c30-e476-44b5-8d39-32a17cad17dd-report`
底层证据：MySQL 直接查询最终行 `ended/ja/en/running-state-real/balanced`，`segments_json` 包含 2 段、`report_json` 非空；outbox `session.created=1`、`session.finished=1`、`report.generated=1`
测试：`mvn -B '-Dtest=JdbcSessionRepositoryTest,SessionWebSocketHandlerTest' test`，14/14 通过；前后端健康检查均 HTTP 200/`{"status":"ok"}`
失败样例与边界：本次使用 demo provider 验证持久化时序，不宣称百炼实时 ASR 延迟；启动异常回滚由 handler 测试覆盖，数据库故障重试和长时稳定性仍未测
结论：U-19/I-18 从“结束时才可见”修复为“启动后跨实例立即可见”；启动快照成为 runner 创建前的持久化边界，避免刷新/切实例看到过期状态
```

### 2026-09-09 最新会话快照最终化真实回归（Issue #66）

```text
提交：fix/session-finalize-snapshot @ 4508284
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 当前分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
实验：8013 创建 demo session `6357038b-0943-41fd-bfac-5202f60ecb02`，WS 启动并发送覆盖参数后立即结束；runner 直接以最新内存快照最终化
用户可见结果：8013/8014 历史均显示 `ended`、2 段字幕、同一 reportId=`6357038b-0943-41fd-bfac-5202f60ecb02-report`；没有出现报告缺失或最后字幕丢失
底层证据：MySQL 最终行 `ended/ja/en/running-state-real/balanced`，`segments_json`=2、`report_json` 非空；outbox `session.created=1`、`session.finished=1`、`report.generated=1`
故障注入：H2 JDBC repository 在第一次 `ended + report` 保存时抛出 transient write failure；第二次最终化成功，保存调用为 3 次，报告 metrics.segments=2
失败样例与边界：本次真实链路使用 demo provider 验证最终化与中间件一致性；数据库持续不可用、纠偏服务长超时、跨地域锁等待和 20 分钟长时稳定性仍未覆盖
结论：I-19 已从“旧数据库快照最终化、一次写入失败即停”收敛为“最新内存快照带锁最终化并有界重试”；跨实例生命周期事件保持单份
```

### 2026-09-09 百炼报告音频时长隔离真实回归（Issue #68）

```text
提交：fix/report-audio-duration @ 5577187
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 当前分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
输入与模型：百炼 `qwen3-tts-flash-realtime` 生成 16 kHz PCM 96,136 bytes；LiveTranslate 使用默认实时模型，目标中文
修复前：一次真实 session 报告 `durationMs=60960`，但唯一字幕 segment `endMs=3004`；provider/纠偏等待被计入用户会议时长
修复后：5 次真实 session ID 分别为 `6d2c2260`、`ee6f7ac7`、`92381499`、`044a36d7`、`17d485b2`；墙钟完成 8748/4810/4558/4156/4971 ms，报告时长均为 3004 ms，均 1 段、1 个 translation final、1 个 session_report、0 error
底层证据：每个 MySQL session 行均为 `ended`、segments_json 1 段、report_json 非空；outbox `session.created=1`、`session.finished=1`、`report.generated=1`；8013/8014 REST 历史一致
测试：`mvn -B '-Dtest=SessionReportServiceTest,RealtimeSessionRunnerTest' test`，8/8 通过；受控 60 s wall-clock/3004 ms segment 测试固定返回 3004 ms，空会话仍返回 60000 ms
失败样例与边界：segment 时间戳只覆盖已识别语句，长静音且无字幕的精确媒体时长仍需独立 source duration 字段；本轮不宣称长时稳定性或 P95 处理延迟优化
结论：U-20/I-20 已从“报告时长受后端等待污染”修复为“有字幕时使用音频时间轴、空会话墙钟兜底”；用户看到的会议时长不再随 provider/纠偏耗时变化
```

### 2026-09-09 会后纠偏完整性真实回归（Issue #70）

```text
提交：fix/final-correction-completeness（提交前工作区）
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 当前分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
输入与模型：百炼 `qwen3-tts-flash-realtime` 生成 5 轮英文 16 kHz PCM；LiveTranslate 实时 ASR/翻译；会后 `qwen-flash` 纠偏
修复前失败样例：session `12e27456-209d-476d-a3ef-9f123e3b9dae` 的实时译文“第一轮检查延迟低”“第三轮核查，编号1”等被会后改成“第一轮检查完成”“第三轮检查编号一”；原有长度阈值只拒绝 2 句，等长语义删减仍可覆盖报告
修复后实验：session `92c61a5e-2d2c-4886-ba41-8983add946ee` 完成 5 段 transcript final、5 段 translation final、1 个 session_report、0 error；报告 `correctionStatus=completed`，5 段均保留实时关键信息，final revisions=4
用户可见结果：报告不再把“低延迟表现”“数字一、二、三”“暂停并恢复边界”等内容压缩成无依据的短句；正常纠偏仍显示术语微调
底层证据：`FinalCorrectionService` 增加长度比例、LCS 内容重叠（70%）及长数字/英文 token 保留校验；拒绝候选不进入 `finalById`/revision，状态降为 `partial` 并在 qualityNotes 记录保护原因
测试：`mvn -B '-Dtest=FinalCorrectionServiceTest' test` 4/4；后端全量 101 通过、0 失败、4 个外部集成测试按默认配置跳过；前端 55/55、生产构建通过
失败与边界：首次真实探针因百炼 TTS 单次读取超时（30 s）未进入会话，重试成功；语义重叠规则偏保守，极短译文不拦截，长时稳定性和多语种数字等价表达仍需扩展语料
结论：U-21/I-21 已由真实失败样例驱动完成保护，报告优先保证实时译文完整性，再接受会后模型修订；本轮不宣称语义评测或长时质量达标
```

### 2026-09-09 非法 PCM 输入真实回归（Issue #72）

```text
提交：fix/voice-pcm-input-contract（提交前工作区）
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 当前分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
修复前复现：将项目 README.md 作为 `audio/pcm` 上传到 `/api/models/asr/transcriptions?model=fun-asr-realtime&sampleRate=16000`，百炼实际返回 HTTP 200、空 text、0 segments；用户无法区分非法音频与静音
修复：`DashScopeSpeechClient` 在打开 provider WebSocket 前拒绝 PCM 奇数字节和 8–48 kHz 外采样率；空文件继续返回 400；WAV 等非 PCM 不套用偶数字节规则
修复后真实结果：同一 README 请求返回 HTTP 400，`{"detail":"PCM audio byte length must be even"}`；真实百炼 TTS PCM（96,136 bytes）请求仍 HTTP 200，返回 `The BabelFlux voice finalization regression test.` final，6 个结果事件，无回归
用户可见结果：非法上传立即得到可理解的格式错误，不再显示“识别完成但没有文字”；合法语音仍可识别
底层证据：校验发生在 `ensureConfigured`/WebSocket 连接前，非法输入不会消耗 provider 请求；错误由 `ApiExceptionHandler` 稳定映射为 HTTP 400
测试：`mvn -B '-Dtest=DashScopeSpeechClientTest' test` 6/6；后端全量 104 通过、0 失败、4 个外部集成测试按默认配置跳过；真实 8013/8014 健康检查 HTTP 200
失败与边界：未知 TTS 模型的真实请求仍可能等待 30 s 后返回 504，属于 provider 未及时发出模型错误事件，后续需模型白名单或上游超时分类；本 PR 不改非 PCM 解码器
结论：U-12/I-22 的非法 PCM 误报已由真实请求驱动修复；错误在本地边界被快速拒绝，合法百炼链路保持可用
```

### 2026-09-09 TTS 未知模型等待真实回归（Issue #74）

```text
提交：fix/voice-tts-handshake-timeout（提交前工作区）
机器/CPU/内存/JDK：Windows 11 x64，本机，JDK 21.0.12.1
前端/后端地址：http://127.0.0.1:5173 / 当前分支实例 http://127.0.0.1:8013、http://127.0.0.1:8014
中间件：MySQL 8.0.43 127.0.0.1:3307；Redis 8.10.1 127.0.0.1:6380；RabbitMQ 4.3.5 5673；Elasticsearch 7.17.24 9200
修复前复现：TTS 模型 `not-a-real-model` 请求在通用 30 s 读取窗口内无错误事件，真实测得 30,453 ms 后 HTTP 504；加入“每次读取 5 s”后仍因 provider 杂讯重置预算，15 s 客户端请求无响应
修复：新增默认 5 s 的 `speechHandshakeTimeout` 和整体 deadline；同时增加可配置 `allowedTtsModels`（默认仅已验证 `qwen3-tts-flash-realtime`），未知模型在打开 WebSocket 前本地拒绝
修复后真实结果：同一未知模型请求 135 ms 返回 HTTP 400；合法 `qwen3-tts-flash-realtime` 请求 1,486 ms 返回 HTTP 200，音频非空；音频响应阶段仍使用 30 s 通用读取窗口
用户可见结果：模型名写错时从等待半分钟变为即时可理解错误；合法 TTS 首包与音频输出不受影响
底层证据：`DashScopeSpeechClientTest` 覆盖白名单拒绝、握手/通用读取窗口分离和 deadline 超时；白名单为空可显式允许自定义 provider 模型
测试：`mvn -B '-Dtest=DashScopeSpeechClientTest' test` 9/9；后端全量 107 通过、0 失败、4 个外部集成测试按默认配置跳过；8013/8014 健康检查 HTTP 200
失败与边界：已加入白名单但 provider 不发送 lifecycle/音频事件仍可能在 5 s/30 s 窗口后返回 504；不把本地白名单当作 provider 模型可用性证明
结论：U-12/I-23 的模型选择等待已从 30 s 级别降为 135 ms 本地反馈；握手预算与音频读取预算分离，后续仍需补 provider 错误事件分类
```

## 当前结论

当前已证明“前后端可启动 + 百炼 LLM/TTS/ASR/LiveTranslate 最小闭环 + MySQL/Redis/RabbitMQ/Elasticsearch 真实运行 + Redis lease 跨实例 runner 互斥 + MySQL 跨实例报告最终化幂等 + Redis Pub/Sub handoff 事件 fan-out + 跨实例暂停/恢复状态同步”成立；尚未证明长时间稳定性、重复性能分位数、多节点 RabbitMQ HA、多轮用户体验和音频前端算法能力。后续 PR 必须先补 U-07、U-09～U-12 的可复现证据，再讨论性能优化百分比。
