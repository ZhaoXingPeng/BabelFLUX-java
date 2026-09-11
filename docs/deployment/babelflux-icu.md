# babelflux.icu 部署记录

> 本文只记录可复现的部署步骤、验证结果和回滚边界。服务器密码、API key、私钥和真实用户数据不得写入本文。

## 目标

- 服务器：Ubuntu 22.04；SSH 管理端口不记录在仓库
- 域名：`babelflux.icu`
- DNS：根域和 `www` 的 A 记录指向当前服务器，TTL 600 秒，启用
- 应用：BabelFlux Java（Spring Boot 后端、Vue 前端、Tauri 桌面端不在服务器部署范围）

## 发布约定

- 使用带时间戳的发布目录，切换 `current` 符号链接完成发布。
- systemd 管理 Java 后端；Nginx 提供前端静态文件并反代 `/api` 与 WebSocket。
- 每次发布前保存 Nginx、systemd 和环境变量配置，失败时切回上一个发布目录。
- 模型密钥与数据库凭据只通过服务器环境变量或受限的 env 文件注入。

## 验证记录

部署过程中的实验、结果、结论和未解决问题以关联 PR 评论为准；本文保留最终命令、端口和回滚说明。

### 最新已验证发布

- 发布版本：`913c252`（PR #115 合并后的 MyBatis 持久层）
- 发布目录：`/opt/babelflux/releases/20260910T053457Z-913c252`
- 当前指针：`/opt/babelflux/current`
- 后端：`babelflux.service`，`127.0.0.1:8000`
- 前端：Nginx `:80`，`babelflux.icu` / `www.babelflux.icu`
- 配置：`/etc/babelflux/babelflux.env`、`/etc/systemd/system/babelflux.service`、`/etc/nginx/sites-available/babelflux`
- 简历预览：授权 PDF 位于服务器 `/opt/babelflux/public/cv.pdf`，通过 Nginx 精确 location `/cv` 内联提供；文件不进入 Git。

### 实测结果

- `systemctl is-active babelflux nginx`：均为 `active`
- 服务器本机带 Host `www.babelflux.icu` 请求 `/`：HTTP 200，返回前端 `index.html`
- 服务器本机带 Host `www.babelflux.icu` 请求 `/api/health`：`{"status":"ok"}`
- 服务器本机 POST `/api/sessions`（`inputMode=demo`）：返回 `sessionId`、`wsToken` 和 `status=created`
- 已验证前端生产构建显式使用 `https://babelflux.icu/api` 与 `wss://babelflux.icu/api`，产物不含本地
  `localhost:8000` 地址。
- 已验证 MySQL、Redis、RabbitMQ、Elasticsearch 基础健康；公网 WSS 静音 PCM 探针握手成功，并收到
  `source_sync_state` 与 `session_report`，未收到 error。

### 已知边界

- 服务器首次 `apt` 访问 Ubuntu 官方 HTTP 源超时，切换到 `https://mirrors.aliyun.com/ubuntu` 后安装成功；该镜像源变更属于服务器运维状态，不写入应用配置。
- 首次上传普通 Maven JAR 导致 `no main manifest attribute`；重新执行 `mvn clean package -DskipTests` 生成 Spring Boot repackage JAR 后恢复。
- 生产入口为 `https://babelflux.icu`；中间件和 Java 后端保持回环监听，不将直接访问主机端口作为验收。
- WSS 静音 PCM 探针只证明认证、连接和结束生命周期；不能作为字幕准确率、首字幕延迟、TTS 质量、
  长时稳定性或高可用的结论。
- 当前发布是单机部署。多节点、跨可用区复制、容量压测、恢复时间目标和完整可观测性平台尚未证明。

### 回滚

```bash
systemctl stop babelflux
ln -sfn /opt/babelflux/releases/<previous-version> /opt/babelflux/current
systemctl start babelflux
nginx -t && systemctl reload nginx
```

简历入口回退时只需移除两个 HTTPS server 中的 `deployment/nginx/cv.location.conf`
对应 location 并 reload Nginx，不影响 BabelFlux 应用发布和数据卷。
