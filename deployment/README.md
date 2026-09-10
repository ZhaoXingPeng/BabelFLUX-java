# 生产部署

本目录提供 Ubuntu 22.04 单机发布的受版本控制模板。应用二进制、前端静态文件、Nginx
和 systemd 使用版本化发布目录；MySQL、Redis、RabbitMQ、Elasticsearch 使用 Docker
named volumes 保存数据。实际密码、API key 与证书私钥不属于本目录，也不得提交。

## 运行边界

- Nginx 是唯一对公网开放的应用入口：`80` 仅用于 ACME 与 HTTPS 跳转，`443` 提供前端、REST
  API 和 WebSocket。
- Java 后端只监听 `127.0.0.1:8000`；四个中间件端口也只绑定 `127.0.0.1`。
- `babelflux.service` 以无登录权限的 `babelflux` 用户运行，敏感配置从 root-only
  `/etc/babelflux/babelflux.env` 读取。
- Elasticsearch 单节点必须将宿主机 `vm.max_map_count` 设为至少 `262144`。
- 当前 Elasticsearch 容器限制为 `768 MiB`，因此使用 `384 MiB` 堆并关闭未使用的 ML 模块；
  不要将堆恢复为 `512 MiB`，否则直接内存和原生开销可能触发 cgroup OOM 重启。

## 首次部署顺序

1. 在服务器安装 Docker Engine、Docker Compose plugin、OpenJDK 21、Nginx、Certbot 与 ffmpeg。
2. 复制 `docker-compose.yml` 到 `/opt/babelflux/infrastructure/`，在同目录创建权限为 `0600` 的
   `.env`，填入随机的 MySQL/RabbitMQ 密码，执行 `docker compose up -d`。
3. 上传生产构建产物至新的 `/opt/babelflux/releases/<version>/`，再原子更新
   `/opt/babelflux/current` 符号链接。
4. 创建 `/etc/babelflux/babelflux.env`（权限 `0600`），启用 MySQL、Redis、RabbitMQ 和
   Elasticsearch，并提供百炼环境变量；安装 `babelflux.service` 后执行 `systemctl enable --now babelflux`。
5. 先安装 `nginx/babelflux.http.conf`，通过 webroot 方式签发两个域名的证书，再切换为
   `nginx/babelflux.conf` 并 reload Nginx。
6. 逐项验证服务状态、本机监听范围、MySQL schema、Redis、RabbitMQ、Elasticsearch、`
   /api/health`、会话创建、Nginx WebSocket 升级和外部 HTTPS。

## 百炼端点配置

`/etc/babelflux/babelflux.env` 必须由 root 创建并保持 `0600`。其中的
`DASHSCOPE_API_KEY` 是服务器默认模型凭据，不能写入本仓库、Issue、PR 或命令输出。
当 LLM 通过业务空间 OpenAI-compatible HTTP 地址访问时，必须另外设置：

```text
DASHSCOPE_WEBSOCKET_BASE_URL=wss://dashscope.aliyuncs.com/api-ws/v1
```

HTTP 兼容端点和实时 WebSocket 端点不是同一地址。这个变量供 LiveTranslate、ASR 和实时
TTS 使用；它避免从兼容 HTTP 地址错误推导出 `/api-ws/v1`。标准 DashScope HTTP 部署未设置
该变量时仍可使用旧的地址推导行为。

## 本次实例验证（2026-09-10）

- 公网入口：`https://babelflux.icu`；`www` 跳转到根域；HTTP 跳转至 HTTPS。
- 当前应用发布：`/opt/babelflux/releases/20260910T022700Z-dashscope-ws-endpoint`，构建对应
  `c9084ae`；Java 后端和四个中间件均只监听回环地址，Nginx 仅对外开放 `80/443`。
- 验证：`/api/health`、根页面 TLS、`qwen-flash` HTTP 生成、MySQL/Redis/RabbitMQ/
  Elasticsearch 健康状态，以及公网 WSS microphone 会话均成功。实时探针收到
  `source_sync_state(syncing)` 和 `session_report`，未收到错误。
- 边界：实时探针使用 16 kHz PCM 静音，只证明认证、连接和结束生命周期；不将其作为字幕
  准确率、首字幕延迟、TTS 质量或长时稳定性的结论。
- 发布保留：本次按部署指令删除旧发布 JAR；若需要二进制回退，应从前一 Git 提交重建、上传
  并原子切换 `current`，不会影响中间件持久卷。

## 回滚

应用发布失败时，先停止 `babelflux.service`，将 `current` 指回前一个已验证的 release，再启动服务。
中间件使用持久卷，应用回滚不会删除会话数据。中间件版本变更必须先备份数据卷并另建 Issue/PR。
