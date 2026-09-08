# BabelFlux 语音系统体验与底层验证矩阵

版本：v1.1（2026-09-09）

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
| U-08 | 暂停/恢复 | 语音流中发送 `pause_session` / `resume_session` | 前端状态一致，恢复后不重复或跳过明显内容 | NOT RUN：需真实设备/浏览器采集 |
| U-09 | 播放中断 | TTS 播放中输入下一句 | 旧音频停止，新句首包延迟可记录 | NOT RUN |
| U-10 | 跨句纠偏 | 包含数字、否定、专有名词和术语表的固定语料 | 修正事件高亮，最终报告保留修订记录 | NOT RUN：当前仅有 provider/服务单测 |
| U-11 | 长时稳定性 | 20 分钟固定音频或 20 轮会话 | 无内存持续增长、无 WebSocket 重连风暴、报告最终生成 | NOT RUN |
| U-12 | 故障可理解 | 无 key、provider 超时、非法 PCM、上游 4xx/5xx | 用户收到稳定错误/降级提示，不暴露凭据 | PARTIAL：真实 `ModelNotFound` 已从误导性 502 修正为 HTTP 422 + 稳定 code/message；其余故障矩阵未跑完 |

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

## 当前结论

当前已证明“前后端可启动 + 百炼 LLM/TTS/ASR/LiveTranslate 最小闭环 + MySQL/Redis/RabbitMQ/Elasticsearch 真实运行”成立；尚未证明长时间稳定性、重复性能分位数、多节点 RabbitMQ HA、多轮用户体验和音频前端算法能力。后续 PR 必须先补 U-07～U-12 的可复现证据，再讨论性能优化百分比。
