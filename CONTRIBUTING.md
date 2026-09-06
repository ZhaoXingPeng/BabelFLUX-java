# Contributing to BabelFlux

感谢参与 BabelFlux。项目采用“先 Issue、后分支、再 PR”的节奏：每个变更都应有可追踪的目标、可复现的验证和清晰的回滚边界。

## 工作流

1. 为功能、缺陷或架构调整创建 Issue；紧急安全修复可以先开 PR，再补 Issue。
2. 从最新 `main` 创建短生命周期分支：`feat/<topic>`、`fix/<topic>`、`refactor/<topic>`、`docs/<topic>`、`test/<topic>`、`ci/<topic>`。
3. 一个分支聚焦一个 Issue，一个 PR 聚焦一个可审查结果。大功能拆成可独立合并的小 PR。
4. 每个 commit 保持可运行、可回滚，并在 commit body 或 PR 评论中记录实验和结果。
5. 推送分支并创建 PR；CI 全绿、至少一次人工审查后才合并到 `main`。
6. 合并后删除分支，并同步本地 `main`。

## Issue 与 PR 语言

Issue 和 PR 的标题、描述及评论默认使用中文，便于项目协作和审查。代码标识、命令、文件路径、标准协议名和必要的第三方术语可以保留英文；若必须使用英文内容，应补充中文说明。

## Commit 格式

格式：`<gitemoji> <type>(<scope>): <imperative summary>`

摘要使用动词开头，建议不超过 72 个字符；正文说明背景、方案和验证。允许中文或英文，但同一 commit 内保持一种语言。

| Gitemoji | Type | 用途 |
| --- | --- | --- |
| ✨ | `feat` | 新增用户可见能力 |
| 🐛 | `fix` | 修复行为或回归 |
| ♻️ | `refactor` | 不改变外部行为的结构调整 |
| 🧪 | `test` | 新增或修正测试 |
| 📚 | `docs` | 文档、示例和 ADR |
| 🚀 | `ci` | CI、发布和自动化 |
| 🔧 | `chore` | 依赖、工具和维护性变更 |
| 🎨 | `style` | 不改变逻辑的格式或视觉调整 |
| ⚡ | `perf` | 性能改进 |
| 🔒 | `security` | 安全修复或防护 |

示例：

```text
✨ feat(web): add session history filters
🐛 fix(pipeline): keep partial subtitles monotonic
♻️ refactor(backend): extract source text normalization
🧪 test(pipeline): cover CJK overlap boundaries
🚀 ci: run backend and frontend quality gates
```

不要在 commit、Issue、PR 或日志中写入 API key、token、Cookie、私钥或真实用户数据。

## PR 要求

PR 标题沿用 commit 格式。描述必须回答以下问题：

- **Issue**：`Closes #123` 或说明为什么不关联 Issue。
- **做了什么**：用户可观察的变化和影响范围。
- **怎么做的**：关键设计、架构边界和取舍。
- **实验与结果**：尝试过什么、得到什么数据或结论；失败的实验也要记录。
- **验证**：运行的命令、测试数量、构建结果和未覆盖风险。
- **发布/回滚**：配置、迁移、兼容性或回滚步骤。

PR 评论用于持续记录实验，不要只在最后一次提交里补一段总结。每次有意义的实验都用固定格式：

```text
实验：<假设与方法>
结果：<观测到的结果>
结论：<保留、放弃或下一步>
```

## 合并策略

- 默认使用 **rebase and merge**，保留有意义的小 commit 和验证记录。
- 只有修复分支噪声 commit 时才使用 squash；squash 前须把 PR 描述中的实验记录整理完整。
- 禁止直接向 `main` 推送；禁止未经确认的 force push。

## 质量门禁

本地推荐运行：

```bash
./scripts/check.sh
```

最小门禁包括：

- Backend：`mvn -B test`
- Frontend：`npm run test`、`npm run build`
- Desktop：`npm run build`

CI 失败时，PR 作者应在评论中说明原因、影响和修复计划，不要通过删除测试或放宽门禁来“修绿”。

## 设计变更

跨层协议、事件字段、持久化格式和模型 provider 变更必须在 `docs/standards/architecture.md` 或独立 ADR 中记录。重构应先补行为测试，再移动代码，保证每个小 PR 都能单独验证和回滚。
