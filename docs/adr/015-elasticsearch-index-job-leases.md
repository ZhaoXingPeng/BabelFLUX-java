# ADR 015：为 Elasticsearch 报告索引任务增加租约

## 背景

报告快照以 MySQL 为事实源，Elasticsearch 只保存可重建的检索索引。旧的定时任务直接读取所有 `pending` 行；多实例部署时，同一个报告会被多个实例同时处理。虽然稳定文档 ID 能避免最终产生重复文档，但会浪费模型/网络资源，并可能让失败重试状态被其他实例覆盖。

## 决策

`babelflux_report_index_jobs` 使用与 RabbitMQ outbox 相同的租约状态机：

```text
pending --claim(owner, lease_until)--> processing --success(owner)--> indexed
                                      \--failure(owner)--> pending
```

`tryClaim` 通过带状态和租约条件的单条 SQL 更新保证并发互斥；`lease_until` 到期后其他实例可以恢复。`markIndexed` 和 `markFailed` 必须匹配 `status=processing` 与 `lease_owner`，旧 worker 不能覆盖新 worker 的结果。重新入队会清空租约并将任务重置为 `pending`。

## 取舍与风险

- 这是 at-least-once 处理，不承诺 exactly-once；ES 文档 ID 必须保持稳定以保证幂等。
- worker 在租约过期后仍可能完成一次写入，后续状态更新会因 owner 不匹配而被忽略；因此租约应覆盖正常 ES 请求 P99。
- 索引任务失败仍按指数退避重试，MySQL 的 `last_error` 保留截断后的可诊断信息。

## 验证

- `JdbcReportIndexJobStoreTest.onlyOneOwnerCanClaimAndExpiredLeaseIsRecoverable` 验证并发 claim 互斥、过期恢复和 owner 保护。
- `JdbcReportIndexJobStoreTest.enqueueIsIdempotentAndFailureStateIsInspectable` 验证重入队、失败状态和 payload 更新。
- `mvn -B test` 验证完整 Java 后端回归；Docker 不可用时 Elasticsearch Testcontainers 明确 skipped，不将 H2 结果当作 ES 实测。

## 回滚

回滚应用代码和 schema 迁移即可；新增租约列保留不会影响旧的 pending/indexed 行。若临时回滚到不识别租约的 worker，应停止多实例 relay，避免旧 worker 与新 worker 混用。
