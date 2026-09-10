# ADR 016：使用 JDBC 元数据执行可移植的增量 schema 迁移

> 状态：已废弃。2026-09-10 起由 [ADR 017](017-flyway-versioned-schema-migrations.md) 取代。
> 本文保留此前 JDBC 补列方案的历史背景，不能作为当前部署或 schema 演进指南。

## 背景

项目原先在 `schema.sql` 中使用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 为 outbox lease 增量升级。该语法在 H2 可执行，但 MySQL 8.0.43 会在应用上下文初始化阶段报语法错误，导致启用 `MYSQL_ENABLED=true` 时服务无法启动。

## 决策

保留新表所需的完整列定义，删除 vendor-specific `ALTER TABLE` 语句；由 `JdbcSchemaMigration` 在数据源 schema 初始化完成后读取 JDBC `DatabaseMetaData`，对旧表逐列执行标准 `ALTER TABLE ... ADD COLUMN`。标识符只来自代码常量并经过 ASCII 白名单校验。两个实例同时启动时，重复添加的异常只有在二次元数据检查仍显示缺列时才失败。

## 验证

- `JdbcSchemaMigrationTest` 在 H2 旧表上验证缺列添加和重复执行幂等。
- MySQL 8.0.43 独立实例（127.0.0.1:3307）实际启动 Spring Boot `MYSQL_ENABLED=true`，schema 初始化通过。
- 同一实例完成 demo WebSocket 会话，MySQL 查询确认 session `ended`、segments 和 report 均已持久化；四个 lease 列存在。

## 风险与回滚

迁移仅增加可空列，不修改既有数据。若迁移失败，应用拒绝启动而不是在缺列状态下运行；回滚应用 commit 不会删除已增加的列。
