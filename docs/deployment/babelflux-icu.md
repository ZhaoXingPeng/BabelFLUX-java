# babelflux.icu 部署记录

> 本文只记录可复现的部署步骤、验证结果和回滚边界。服务器密码、API key、私钥和真实用户数据不得写入本文。

## 目标

- 服务器：Ubuntu 22.04，公网地址 `111.170.33.3`，SSH 端口 `27226`
- 域名：`babelflux.icu`
- DNS：`www` A 记录指向 `111.170.33.3`，TTL 600 秒，启用
- 应用：BabelFlux Java（Spring Boot 后端、Vue 前端、Tauri 桌面端不在服务器部署范围）

## 发布约定

- 使用带时间戳的发布目录，切换 `current` 符号链接完成发布。
- systemd 管理 Java 后端；Nginx 提供前端静态文件并反代 `/api` 与 WebSocket。
- 每次发布前保存 Nginx、systemd 和环境变量配置，失败时切回上一个发布目录。
- 模型密钥与数据库凭据只通过服务器环境变量或受限的 env 文件注入。

## 验证记录

部署过程中的实验、结果、结论和未解决问题以关联 PR 评论为准；本文保留最终命令、端口和回滚说明。

### 已执行发布

- 发布版本：`e680787`
- 发布目录：`/opt/babelflux/releases/e680787`
- 当前指针：`/opt/babelflux/current`
- 后端：`babelflux.service`，`127.0.0.1:8000`
- 前端：Nginx `:80`，`babelflux.icu` / `www.babelflux.icu`
- 配置：`/etc/babelflux/babelflux.env`、`/etc/systemd/system/babelflux.service`、`/etc/nginx/sites-available/babelflux`

### 实测结果

- `systemctl is-active babelflux nginx`：均为 `active`
- 服务器本机带 Host `www.babelflux.icu` 请求 `/`：HTTP 200，返回前端 `index.html`
- 服务器本机带 Host `www.babelflux.icu` 请求 `/api/health`：`{"status":"ok"}`
- 服务器本机 POST `/api/sessions`（`inputMode=demo`）：返回 `sessionId`、`wsToken` 和 `status=created`
- DNS：`www.babelflux.icu A 111.170.33.3`，TTL 600 秒，已启用

### 已知边界

- 服务器首次 `apt` 访问 Ubuntu 官方 HTTP 源超时，切换到 `https://mirrors.aliyun.com/ubuntu` 后安装成功；该镜像源变更属于服务器运维状态，不写入应用配置。
- 首次上传普通 Maven JAR 导致 `no main manifest attribute`；重新执行 `mvn clean package -DskipTests` 生成 Spring Boot repackage JAR 后恢复。
- 公网 HTTP 请求当前被上游 Apache 备案拦截页接管，HTTPS 端口未监听；服务器本机 Nginx 路由正常。需要完成备案/入口绑定后再做公网验收。
- 从外部直接访问 `111.170.33.3:8000` 返回 `uvicorn` 的 404，而不是本次 Java 服务，说明云侧仍存在端口映射或旧服务入口；不能将该响应计入 Java 部署验收。
- 服务器未配置 `DASHSCOPE_API_KEY`、MySQL、Redis、RabbitMQ 或 Elasticsearch，因此本次只验证 H2/内存 + demo API，不宣称真实语音和生产中间件已上线。

### 回滚

```bash
systemctl stop babelflux
ln -sfn /opt/babelflux/releases/<previous-version> /opt/babelflux/current
systemctl start babelflux
nginx -t && systemctl reload nginx
```
