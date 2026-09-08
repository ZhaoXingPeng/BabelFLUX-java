# ADR 017：JDBC 重试与租约统一使用应用 UTC 时钟

## 背景

outbox 和 Elasticsearch 索引任务原先在 SQL 条件中使用 `current_timestamp`，
但写入的 `next_attempt_at`、`lease_until` 来自 Java `Instant`。MySQL 服务器按
本机 Asia/Hong_Kong 时区运行时，数据库当前时间比 UTC 参数快 8 小时，导致未来
重试被立即视为到期。RabbitMQ 不可达时，真实实例在数秒内把一次事件重试了数十次，
形成连接/日志风暴。

## 决策

- `JdbcSessionEventOutbox` 和 `JdbcReportIndexJobStore` 的新增、查询、抢占和完成时间
  均使用 `Timestamp.from(Instant.now())` 作为 JDBC 参数。
- 不再在状态条件中混用数据库 `current_timestamp`；成功时间和 receipt 时间也由应用
  UTC 时钟写入。
- 该修复不要求修改既有表结构，兼容 MySQL/H2 和多时区部署。

## 验证

- 完整后端回归：`mvn -B test`，76 项通过，0 失败，4 跳过。
- 真实 MySQL 8.0.43（127.0.0.1:3308，服务器时区 SYSTEM/Asia-Hong_Kong）用例：
  2 项通过；未来 30 秒 retry 不会立即出现在 `pending()`。
- 真实 RabbitMQ 故障烟测：broker 停止后 4 秒内事件为 `pending`、`attempts=2`、
  `last_error=java.net.ConnectException: Connection refused: getsockopt`；旧实现同样
  时间窗口曾达到 29 次以上。broker 以相同数据目录恢复后，事件最终 `published`，
  receipt 数为 1。

## 回滚

回滚应用代码即可恢复旧行为；不需要删除或回滚任何数据库列。若部署回滚，必须同时
监控非 UTC MySQL 上的重试速率，避免重新引入连接风暴。
