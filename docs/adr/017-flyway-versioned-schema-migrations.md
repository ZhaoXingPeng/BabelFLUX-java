# ADR 017：以 Flyway 管理版本化 schema 迁移

## 状态

已采纳，取代 ADR 016 的应用启动期 JDBC 补列方案。

## 背景

`schema.sql` 加 `JdbcSchemaMigration` 可以让新库启动，但无法提供版本序列、checksum、审核记录、
迁移权限边界或可靠的恢复演练。应用数据源因此需要 DDL 权限，升级路径也不能由发布工件重放。

## 决策

- 使用 Flyway 管理 `backend/src/main/resources/db/migration/` 下的迁移；当前完整基线为
  `V1__initial_schema.sql`。
- 关闭 `spring.sql.init`，删除 `JdbcSchemaMigration`。业务 MySQL 操作继续通过 MyBatis Mapper；
  JDBC starter 仅保留连接池/Flyway 等基础设施依赖，不再承载业务 CRUD 或启动期 DDL。
- 新库直接执行 V1。已有 schema 的首次切换必须先备份，并仅在该次启动设置
  `FLYWAY_BASELINE_ON_MIGRATE=true` 和 baseline version `1`；成功后恢复为 `false`。
- 运行时 `MYSQL_USERNAME` 使用仅有 DML 权限的 `babelflux_app`，Flyway 使用独立的
  `FLYWAY_USERNAME=babelflux_migrator`。迁移用户只在 `babelflux.*` 有所需 DDL/DML 权限。
- 禁止 `clean`，禁止修改已发布 migration；失败采用新增 forward-fix migration 或从已验证备份恢复到
  隔离实例后处理。

## 验证

- `FlywayMigrationTest` 覆盖新 H2 schema 的 V1、重复迁移和已有 schema 的显式 baseline。
- `FlywayMysqlMigrationIntegrationTest` 仅在 `RUN_MYSQL_FLYWAY_IT=true` 的隔离 MySQL 中验证迁移账号
  可执行 DDL，应用账号可读写且被拒绝 DDL。
- `scripts/verify-mysql-flyway-backup-restore.ps1` 负责临时容器中的迁移、备份、恢复和 schema history
  检查。运行该脚本不等同于生产 RTO/RPO 或灾备结论。

## 后果

后续 schema 变更必须新增递增版本的 migration 和升级测试。生产部署需要保管两组数据库凭据，并在
首次转换、权限切换和旧账号回收时执行明确的健康检查与回滚判断。
