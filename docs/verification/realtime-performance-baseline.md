# 实时 PCM 背压与 Provider 模拟基线

## 目标与边界

本基线验证 Java 实时 runner 在固定 PCM 压力输入下的队列行为、首字幕/完成事件时序，以及 provider 超时后用户仍能收到错误和会话报告。它使用测试替身，不读取用户音频、不发送网络请求、不需要 DashScope Key。

它不是百炼、浏览器、生产服务器或公网网络的性能报告，不能用作吞吐量、准确率、SLA、HA 或容量结论。真实模型和设备验证仍须独立记录运行地点、区域、模型、网络、机器和原始脱敏结果。

## 固定输入与口径

| 项目 | 值 |
| --- | --- |
| PCM | 合成零值 16 kHz、单声道、s16le |
| 帧 | 1,280 bytes（40 ms）x 41 帧 |
| 服务端队列 | 25 帧（本压测专用，1 s） |
| provider 替身 | 首个 provider 事件固定等待 20 ms，后续音频发送固定等待 2 ms |
| 重复次数 | 5 次，单 JVM、顺序执行 |
| 首字幕延迟 | `acceptAudio` 首帧至 `transcript_segment` 首次发出 |
| 完成延迟 | `acceptAudio` 首帧至 `session_report` 发出 |
| 丢帧率 | `Session.droppedInputFrames / 41`；满队时服务端丢弃最旧帧 |

Provider 替身在首帧发送时阻塞，保证 40 个后续固定帧先进入有界队列。因此这项压力输入每次预期保留 1 个 in-flight 帧和 25 个队列帧，丢弃 15 帧；这是测试夹具的可重复预期，并非线上丢帧率。

## 复现

```powershell
cd backend
mvn -B -Dtest=RealtimePerformanceBaselineTest test

cd ../frontend
npm ci --ignore-scripts
npm test -- --run src/realtime/audioBackpressure.test.ts
```

基线测试会输出一行 `REALTIME_BASELINE`，包含 5 次运行的 P50/P95/P99、固定输入丢帧数和 provider 错误数。分位数采用 nearest-rank：5 次样本时 P50 为第 3 个有序值，P95/P99 为最大值。

## 2026-09-10 本机模拟结果

```text
环境：Windows 本机，JDK 21.0.12，Maven Surefire；未连接浏览器、百炼或中间件
输入：固定 16 kHz mono s16le 零值 PCM，41 x 1,280 bytes
运行：5 次，顺序执行；provider 为受控 Mockito 替身
首字幕：P50/P95/P99 = 48/53/53 ms
完成：P50/P95/P99 = 513/521/521 ms
丢帧：15/41 = 36.6%（受控队列溢出用例）
provider errors：0
超时回归：模拟 provider timeout 后发出 error，随后仍发出 session_report
```

结论：有界队列、丢帧计量和超时后收尾契约在固定输入下可复验。受控压测故意造成队列满载，36.6% 不能泛化为正常会话表现；真实 provider 的首字幕、最终字幕、网络失败和长时稳定性仍待按独立实验运行。

## 浏览器与服务端的协同降级

浏览器和服务端各自维护队列，不能将任一数值误作端到端排队长度：

| 层 | 阈值/策略 | 用户可见行为 |
| --- | --- | --- |
| 浏览器 WebSocket | `bufferedAmount > 32,000 bytes`，约等于 16 kHz mono s16le 的 1 s；丢弃当前新帧 | 显示 `lagging`，并给出估算缓冲毫秒数和“已丢弃当前音频帧”；下一帧可发送时显示“浏览器发送缓冲已恢复” |
| Java runner | 默认 250 帧（10 s），可用 `REALTIME_QUEUE_FRAMES` 配置；满载丢弃最旧帧 | 发出 `source_sync_state.lagging`、累计 `droppedFrames`，最终报告明确输入损失 |

浏览器优先丢弃新帧，避免本地发送积压继续扩大；服务端优先丢弃旧帧，避免 provider 消费落后时持续输出过期语音。两层都只提供降级，不承诺音频完整性。浏览器阈值的边界测试覆盖“等于阈值仍发送”“超过阈值丢当前帧并计算 1,040 ms”“socket 已关闭不发送”。

## 真实实验的后续要求

进行真实 LiveTranslate 验证时，必须另外保留以下脱敏记录：

- 固定合成或授权测试音频的校验值、采样率、帧长和总时长；禁止提交真实用户音频。
- 每次从首帧发送到首个 transcript、translation、audio 事件和 `session_report` 的时长，至少 5 次并给出 P50/P95/P99。
- 浏览器 `bufferedAmount` 丢帧、服务端 `lagging/droppedFrames`、provider 超时/错误、断网和恢复后的用户可见事件。
- 地区、网络类型、机器/JDK、模型和提交版本；不含 API key、完整请求头、会话内容或生产库数据。
