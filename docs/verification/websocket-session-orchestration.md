# WebSocket 会话编排拆分验证

## 范围

本次调整不改 REST 路由、WebSocket URL、token 类型、客户端命令或服务端事件契约。它将原先集中在 `SessionWebSocketHandler` 的运行编排拆为独立协作组件：

```text
WebSocket frame
      |
SessionWebSocketHandler (auth / parse / route)
      |
RealtimeSessionCoordinator ---- RunnerLeaseManager ---- Redis lease
      |                                      |
      v                                      v
RealtimeSessionRunner                 renewal failure cleanup
      |
SessionEventDispatcher ---- SessionEventHub ---- HandoffSessionSubscriber
      |
primary socket
```

完整职责边界和禁止依赖见 `docs/standards/architecture.md`。

## 行为对比

| 场景 | 拆分前后不变的行为 | 回归测试 |
| --- | --- | --- |
| 主连接启动 | token 通过后启动一个 runner；启动快照先落库；同 session 的第二连接被拒绝 | `startsRunAndForwardsPcmFrames`、`persistsStartupSnapshotBeforeCreatingRunner`、`rejectsSecondPrimarySocketForSameSession` |
| Redis lease | lease 已被远端占用时本机不启动或结束会话；续租失败停止本 runner 并释放 owner | `rejectsStopFromSocketWhenAnotherInstanceHoldsRunnerLease`；续租逻辑由 `RunnerLeaseManager` 单独封装 |
| handoff | 只读 socket 可 replay 与订阅事件，不能再启动第二个 runner | `handoffSocketReplaysEventsWithoutStartingAnotherRunner` |
| 控制命令 | `media_clock` 校验非负整数；暂停、恢复广播兼容的 `source_sync_state` | `forwardsMediaClockAndRejectsInvalidClockValues`、`broadcastsPauseAndResumeStateToHandoffSocket` |
| 结束/断连 | 主 runner 收 stop；无活跃 runner 时返回同一持久化报告；断连释放订阅和 owner | `audioEndWithoutActiveRunEmitsPersistedReport`、`SessionServiceDistributedFinishTest` |

## 验证

```text
cd backend && mvn -B -Dtest=SessionWebSocketHandlerTest test
结果：12 tests, 0 failures, 0 errors

cd backend && mvn -B test
结果：完整后端质量门禁通过（外部集成按环境开关跳过）
```

## 风险与回滚

- 风险：组件间的关闭顺序若被后续改动打破，可能导致终态事件或 lease 释放遗漏。`RealtimeSessionCoordinator.close` 与 `RunnerLeaseManager.release` 保持幂等，测试覆盖主连接与 handoff 的关闭路径。
- 风险：Redis 续租失败时只能保证本机停止与 TTL 最终释放，不能宣称多节点 HA。
- 回滚：回退本 PR 即恢复单个 handler 的原有组织方式；不包含 schema、配置、API、事件字段或数据迁移。
