# BabelFlux 工程规范总索引

本文是 Java 后端迁移后的规范入口。规则服务于可运行、可审查和可回滚的交付；
历史审计材料保留在 `docs/reviews/`，不能替代新的 Issue、PR 或真实验证记录。

## 规范入口

| 主题 | 规范文件 | 适用范围 |
| --- | --- | --- |
| 协作、分支、提交、Issue、PR | [`CONTRIBUTING.md`](../../CONTRIBUTING.md) | 所有代码和文档变更 |
| 分层、依赖方向、事件契约 | [`architecture.md`](architecture.md) | 后端、前端、桌面跨层设计 |
| Java 编码、并发、测试 | [`java-backend.md`](java-backend.md) | `backend/` Java 21 + Spring Boot |
| STAR 实验回帖 | [`star-performance-template.md`](../process/star-performance-template.md) | 性能、缺陷和真实联调 |
| 语音用户体验与底层证据 | [`voice-experience-matrix.md`](../verification/voice-experience-matrix.md) | ASR、TTS、LiveTranslate、中间件 |
| 安全与密钥 | [`SECURITY.md`](../../SECURITY.md) | API key、token、媒体 URL、日志 |

`docs/process/开发与提交规范.txt` 仅保留为旧文档兼容入口，现行规则以本页、
`CONTRIBUTING.md` 和 PR 模板为准；不得再按其中的 Gitee 流程创建或合并变更。

## 交付规则

1. 先建 Issue，再从最新 `main` 创建短生命周期分支；一个 PR 只解决一个可验证结果。
2. 分支使用 `feat/<topic>`、`fix/<topic>`、`refactor/<topic>`、`docs/<topic>`、
   `test/<topic>` 或 `chore/<topic>`，提交标题使用：

   ```text
   <gitemoji> <type>(<scope>): 中文摘要
   ```

   示例：`🐛 fix(voice): 保留会后纠偏缺失分段的实时译文`。
3. PR 描述必须写清关联 Issue、用户可见变化、实现边界、实验结果、验证命令、风险和回滚；
   每次有意义的实验及时追加评论，不把最后一次总结当作全部证据。
4. 合并默认使用 rebase-and-merge；共享 `main` 禁止 force-push。合并后删除远端临时分支，
   当前公开仓库只保留 `main`。

## 证据等级

| 等级 | 可以证明 | 不能外推为 |
| --- | --- | --- |
| E0 静态检查 | 文档、格式、配置结构正确 | 运行时可用 |
| E1 单元/性质测试 | 纯函数、状态边界、算法不变量 | 真实 provider 或中间件质量 |
| E2 契约/API 测试 | DTO、事件顺序、错误码和兼容性 | 网络、设备和长时稳定性 |
| E3 真实中间件 | 指定版本、拓扑和故障窗口下的行为 | 多节点 HA、跨地域容灾 |
| E4 真实语音/模型 | 指定模型、语料、设备和会话的端到端结果 | 线上触发率、普遍质量或性能提升 |

真实模型和中间件必须记录版本、地址、输入规模、session/report ID、指标和失败边界；
单次成功不能写成百分比提升，mock 不能写成端到端通过。

## STAR 评论格式

```text
实验：假设、环境、输入规模、命令和基线
结果：用户可见结果、底层指标、日志/报告 ID、失败样例和恢复动作
结论：保留/放弃/下一步，以及不能外推的范围
```

性能至少提供重复样本的 P50/P95/P99；缺陷修复必须同时提供回归测试名称和修复前后行为。
密钥、Authorization、Cookie、原始音频和真实用户隐私只能保留在受控本地环境，不能进入仓库、
Issue、PR 或日志。

## 最近工作确认（2026-09-09）

| PR | 结果 | 证据 |
| --- | --- | --- |
| [#95](https://github.com/ZhaoXingPeng/BabelFLUX-java/pull/95) | 会后纠偏缺段在总 deadline 内定向补救，失败回退实时译文 | `FinalCorrectionServiceTest` 10/10；真实百炼 5 句及 MySQL/Redis/RabbitMQ/Elasticsearch 闭环 |
| [#94](https://github.com/ZhaoXingPeng/BabelFLUX-java/pull/94) | ES 索引设置更新幂等且保留根因 | 真实索引任务 `indexed`，错误可诊断 |
| [#91](https://github.com/ZhaoXingPeng/BabelFLUX-java/pull/91) | 新句到达时中断旧 TTS，丢弃迟到旧块 | 5 次真实会话的音频代际和前端播放测试 |
| [#87](https://github.com/ZhaoXingPeng/BabelFLUX-java/pull/87) | JDBC scheduler timestamp 精度统一 | MySQL/H2 失败样例、outbox/ES claim 回归 |
| [#98](https://github.com/ZhaoXingPeng/BabelFLUX-java/pull/98) | 首页文件的规范说明和提交示例统一 | YAML 解析、diff 检查和合并后首页标题核对 |

当前完整门禁最近一次结果为后端 122 通过、4 个外部集成按默认配置跳过，前端 56/56，
Frontend/Backend/Desktop 远端检查全部通过；这不替代 Issue #96 中尚未完成的 20 分钟长时稳定性、
重复性能分位数、浏览器背压和多节点故障域实验。
