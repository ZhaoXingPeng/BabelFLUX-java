# BabelFlux 语音系统体验与底层验证矩阵

版本：v1.0（2026-09-08）

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
| U-04 | 真实实时同传 | 16 kHz mono PCM 通过 WS 推送，发送 `audio_end` | 收到 transcript/translation，报告可下载，错误可见 | PASS：LiveTranslate 真实链路返回 translation，报告 `completed` |
| U-05 | TTS 首包与格式 | `/api/models/tts/speech`，模型 `qwen3-tts-flash-realtime` | 返回非空 PCM，采样率/格式与请求一致 | PASS：24 kHz PCM、96,000 bytes，单次 1,044 ms |
| U-06 | ASR 可读性 | 将真实 TTS PCM 送入 `/api/models/asr/transcriptions` | final 文本可读，partial 最终收敛 | PASS：final 为 `BabelFlux voice smoke test.` |
| U-07 | 多轮连续对话 | 5 轮短句 + 1 段 2 分钟语音 | 无断线/卡死，轮次顺序和字幕滚动正确 | NOT RUN：需固定语料和重复次数 |
| U-08 | 暂停/恢复 | 语音流中发送 `pause_session` / `resume_session` | 前端状态一致，恢复后不重复或跳过明显内容 | NOT RUN：需真实设备/浏览器采集 |
| U-09 | 播放中断 | TTS 播放中输入下一句 | 旧音频停止，新句首包延迟可记录 | NOT RUN |
| U-10 | 跨句纠偏 | 包含数字、否定、专有名词和术语表的固定语料 | 修正事件高亮，最终报告保留修订记录 | NOT RUN：当前仅有 provider/服务单测 |
| U-11 | 长时稳定性 | 20 分钟固定音频或 20 轮会话 | 无内存持续增长、无 WebSocket 重连风暴、报告最终生成 | NOT RUN |
| U-12 | 故障可理解 | 无 key、provider 超时、非法 PCM、上游 4xx/5xx | 用户收到稳定错误/降级提示，不暴露凭据 | PARTIAL：错误映射有单测，真实故障矩阵未跑完 |

## 用户不可见的底层矩阵

| ID | 关注点 | 设计/代码证据 | 当前证据 |
| --- | --- | --- | --- |
| I-01 | PCM 边界 | `RealtimeSessionRunner` 使用 25 帧有界队列，满载丢弃最旧帧并发送 lag 状态 | PASS：`RealtimeSessionRunnerTest`、`MediaPcmSourceTest` |
| I-02 | 音频时钟 | 发送音频时长与客户端 `media_clock` 分开计量，250 ms 节流并报告 lag | PASS：WebSocket 控制测试；真实长时观测未跑 |
| I-03 | 段落绑定 | item/response ID 绑定，未绑定译文按 FIFO 归并，partial/final 分开 | PASS：`DashScopeRealtimeClientTest`、runner 测试 |
| I-04 | 纠偏复杂度 | 纠偏只扫描固定窗口，按分钟限流；不扫描整场字幕 | PASS：`BoundedRevisionWindowTest`、`RealtimeRevisionServiceTest` |
| I-05 | Provider 超时 | HTTP/WS 设置连接与读取超时；会后纠偏失败回退实时译文 | PASS：provider 与 `FinalCorrectionServiceTest` |
| I-06 | 资源清理 | runner 使用 virtual thread、bounded final drain 和 `AutoCloseable` provider | PASS：runner 生命周期测试；长时资源曲线未测 |
| I-07 | 实时输出音频 | `response.audio.delta` 归属 segment 并下发 `audio_segment` base64 | PASS：客户端归一化测试；真实 TTS 输出已验证，LiveTranslate TTS 音频未单独验收 |
| I-08 | 中间件可靠性 | MySQL 事实源、Redis TTL 票据、Rabbit outbox、ES 派生索引 | PASS：MySQL 3307 与 Redis 6380 live；Rabbit/ES live skipped（本机未安装） |
| I-09 | 多实例索引 | ES job `pending -> processing -> indexed`，owner + lease 条件更新，过期可恢复 | PASS：`JdbcReportIndexJobStoreTest` 两项租约/幂等测试 |
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
未覆盖：RabbitMQ outbox 和 Elasticsearch 派生索引仍未安装，保持 skipped
```

## 当前结论

当前已证明“前后端可启动 + 百炼 LLM/TTS/ASR/LiveTranslate 最小闭环 + MySQL/Redis 真实运行”成立；尚未证明长时间稳定性、重复性能分位数、真实 RabbitMQ/Elasticsearch、多轮用户体验和音频前端算法能力。后续 PR 必须先补 U-07～U-12 的可复现证据，再讨论性能优化百分比。
