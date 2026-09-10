# 隔离 MySQL 集成测试运行手册

本手册约束 `MysqlSessionEventOutboxIntegrationTest` 的真实 MySQL 验证。测试目标是验证 outbox
状态机在 MySQL 上的行为，不是验证生产数据库，也不能成为修改生产账号授权的理由。

## 安全边界

- 只能连接专用测试 schema，例如 `babelflux_it_<日期或流水号>`；不得指向 `babelflux` 生产 schema。
- 使用专用测试账号，例如 `babelflux_it`；不得复用运行 `babelflux.service` 的应用账号。
- 授权、撤权和删除操作的对象必须显式写为 ``<test_schema>.*``。禁止执行
  `REVOKE ALL PRIVILEGES, GRANT OPTION FROM '<user>'@'<host>'` 这类全局语句。
- 不在 shell 历史、Issue、PR、CI 日志或仓库中记录密码、连接串中的密码、API key 和生产账号授权。
- 测试不应连接公网 MySQL；优先使用本机 Docker 或仅回环可达的测试实例。

## 准备

1. 选定全新且带 `babelflux_it_` 前缀的 schema 名，并由具备数据库管理权限的操作者复核目标。
   如果目标名为 `babelflux`、为空、或不是该前缀，停止操作。
2. 创建专用测试账号，仅授予该测试 schema 的最小 DML 权限。测试需要读取和更新已经初始化的
   outbox 表；schema 初始化由受控的迁移步骤完成，不应让应用测试账号拥有生产 schema 的权限。
3. 在授权前保存账号当前授权快照，至少记录 `SHOW GRANTS FOR '<test-user>'@'<host>'` 的受限运维
   留档。快照不得提交到仓库或粘贴到公开 PR。
4. 通过受控启动或 schema 初始化脚本仅初始化测试 schema，使其拥有与测试所需版本一致的
   `babelflux_session_event_outbox` 表及 lease/错误字段。不要用测试过程改造生产 schema。
5. 在执行 Maven 前打印并人工复核 `TEST_MYSQL_URL` 的主机、端口和数据库名；命令输出中不要带
   密码。连接用户应为专用测试账号。

## 执行

在 `backend/` 中设置非敏感环境变量并执行单个集成测试：

```bash
RUN_MYSQL_IT=true \
TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3308/babelflux_it_example' \
TEST_MYSQL_USERNAME='babelflux_it' \
mvn -B '-Dtest=MysqlSessionEventOutboxIntegrationTest' test
```

PowerShell 使用 `$env:RUN_MYSQL_IT = 'true'` 等进程级环境变量；密码通过受保护的 CI secret 或
本地受限环境注入，不能写进命令历史。测试通过只证明该 outbox 状态迁移在该隔离 MySQL 实例上可用，
不证明 RabbitMQ 投递、生产容量或多实例稳定性。

## 清理与复核

1. 测试结束后先确认 Maven 进程已退出，再仅删除本次明确创建的 `babelflux_it_...` schema。
   删除前再次核对数据库名；不得使用通配符或由未验证环境变量拼接删除目标。
2. 仅在测试 schema 上撤销为本次测试额外授予的权限。不要修改其他 schema，也不要执行全局
   `REVOKE ALL`。
3. 用准备阶段的授权快照复核生产应用账号对 `babelflux.*` 的权限未改变；专用测试账号不应持有
   生产 schema 授权。
4. 若同一主机运行生产服务，执行 `/api/health` 和一次非破坏性数据库连通性检查，确认应用仍可
   访问自己的 schema。授权或健康检查异常时，停止后续发布并按原授权快照恢复。
5. 在 PR 的“实验 / 结果 / 结论”中仅记录测试 schema 的匿名标识、MySQL 版本、测试结果和清理
   结果，不记录地址、密码、完整授权输出或真实业务数据。

## 事故响应

一旦误操作影响非测试 schema 的授权，立即停止测试和发布，保留命令与时间线给受限运维记录，依据
操作前授权快照恢复最小权限，并完成应用健康检查。不得通过扩大账号权限或重跑全局授权命令来掩盖
问题。后续修复应在独立 Issue/PR 中说明影响范围、恢复验证和预防措施。
