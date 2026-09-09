# Desktop Client

桌面客户端阶段用于承接 Web 无法完成的系统级能力：

1. 电脑全局悬浮字幕。
2. 系统音频或应用音频采集。
3. 置顶窗口、透明度、锁定、字号、热键和托盘。
4. 通过 `lingosync://floating/start` 从 Web 快速启动。

当前目录已起 Vue + Tauri 2 悬浮字幕客户端，窗口默认是透明、无边框、置顶、可拖动的 overlay：

- Web 通过 `/api/sessions/{sessionId}/handoff` 获取短时单用 token。
- `lingosync://floating/start?...&token=h_*` 唤起桌面端。
- 桌面端用 token 调 `/api/sessions/handoff/claim` 兑换短时 WS token。
- 透明置顶 overlay 复用 `frontend/src/components/workbench/FloatingCaption.vue` 和 `frontend/src/styles/main.css`，避免样式分叉。
- standalone 模式默认选择 `Windows 系统音频`，Windows 下通过 WASAPI loopback 读取默认播放设备的全局音频，转换为 16k/mono/s16le PCM 后复用后端 WebSocket 协议；其他音源仍走浏览器/WebView 授权采集。

桌面端只依赖后端公开的会话、handoff 和 WebSocket 契约，不直接依赖 Java 服务内部实现；协议字段变更必须同步更新前端类型、桌面桥接和契约测试。

## Run

开发态预览会加载 `http://localhost:5175`，必须同时跑 Vite 与 Tauri：

```bash
cd desktop
npm install
npm run dev
npm run tauri dev
```

本地浏览器预览可使用：

```text
http://localhost:5175/?displayMode=bilingual
```

真实接管需要 Web 工作台生成的 deep link：

```text
lingosync://floating/start?sessionId=...&displayMode=bilingual&token=h_...
```

正式客户端不要使用 `target/debug/lingosync-desktop.exe` 注册 deep-link；debug 程序依赖 5175 dev server，
单独从 `lingosync://` 启动会出现 WebView2 的“无法访问此页面”。用于本机实验时：

```bash
cd desktop
npm run client:build
npm run client:register
```

`client:register` 会把当前用户的 `lingosync://` 协议注册到 release 客户端：

```text
desktop/src-tauri/target/release/lingosync-desktop.exe
```

release 客户端会加载打包后的 `desktop/dist`，不依赖 5175，也不会弹出 Windows 控制台窗口。点击“开始”后默认直接读取 Windows 系统音频，不再弹出屏幕共享选择器。

## Phase Plan

1. PR22：客户端工程脚手架。
2. PR23：deep link 启动闭环。
3. PR24：全局悬浮字幕窗口 MVP。
4. PR25：系统音频采集原型。
5. PR26：客户端会话与后端协议打通。

本轮实现对应 `docs/architecture/客户端悬浮框方案_v1.0.md` 的最小 1.0。

详细规划见：

```text
docs/architecture/桌面客户端阶段规划_AI同声传译助手.txt
```
