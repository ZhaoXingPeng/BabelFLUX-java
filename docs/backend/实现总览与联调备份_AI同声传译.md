# AI 同声传译助手（BabelFlux / 巴别流 同传）— 实现总览与联调备份

> 历史说明：本文保留早期 Python/FastAPI 实现的联调备份。当前可运行后端已迁移到独立的 Java 21 + Spring Boot 工作区；请以 [`backend/README.md`](../../backend/README.md) 和 [`docs/adr/014-java-backend-migration.md`](../adr/014-java-backend-migration.md) 的 Java 边界、测试和中间件说明为准。本文中的 `backend/app`、Python 命令和旧测试数量不再是当前工作区的启动方式。

> 本文档对项目「重要实现」做一次完整备份：系统架构、模型全链路与选型、实时/会后双层纠偏、
> 音频采集（Web + 桌面）、会话报告、WebSocket 协议、桌面悬浮窗、真实链路联调发现并修复的问题、
> 部署与运行方式、以及已验证的测试结论。便于交接、复盘与回归。
>
> 最后更新：2026-06-06

---

## 1. 系统架构

三端 + 一个真实模型链路：

| 层 | 技术栈 | 职责 |
| --- | --- | --- |
| 前端 Web 工作台 | Vue 3 + Pinia + Vite + TypeScript + GSAP | 配置会话、采集/上传/直链输入、双语字幕流、实时纠偏高亮、会后报告与下载 |
| 桌面悬浮窗 | Tauri v2（WebView2）+ Vue 3 | 透明置顶字幕；standalone 自采集 或 接管 Web 会话（deep-link handoff） |
| 后端 | FastAPI + asyncio + ffmpeg | 会话管理、音频入口、同传管线编排、双层纠偏、报告生成与下载 |
| 模型 | 阿里云百炼（DashScope，标准端点） | 实时识别+翻译、实时纠偏、会后完整纠偏、TTS |

数据流（实时采集为例）：

```
音源 → AudioWorklet(16k 单声道 PCM) → WS 二进制帧 → FastAPI
  → InterpretationPipeline → qwen3.5-livetranslate(ASR+翻译, 服务端 VAD)
  → transcript/translation 事件 → 前端字幕流
  → RealtimeReviser(qwen-flash 跨句复核) → revision_event → 前端琥珀高亮
结束 → 会后完整纠偏(qwen-plus 全局校正) → session_report → 报告下载(txt/srt/md/json)
```

---

## 2. 模型全链路与选型

所有真实模型均经标准端点 `https://dashscope.aliyuncs.com`（HTTP `/api/v1`、WS `/api-ws/v1`）实测连通。
鉴权：`Authorization: Bearer <DASHSCOPE_API_KEY>` + `X-DashScope-WorkSpace: <workspaceId>`。

| 环节 | 模型（.env 变量） | 说明 |
| --- | --- | --- |
| 实时识别+翻译 | `qwen3.5-livetranslate-flash-realtime`（LIVE_TRANSLATE_MODEL） | 单 WS 同时出 ASR 与翻译；服务端 VAD 自动断句；可选 TTS 模态 |
| 内嵌 ASR | `qwen3-asr-flash-realtime`（LIVE_TRANSLATE_ASR_MODEL） | LiveTranslate 的 `input_audio_transcription.model` |
| 实时纠偏 | `qwen-flash`（REALTIME_REVISION_MODEL） | 低延迟，跨句轻量复核，保守触发 |
| 会后完整纠偏 | `qwen-plus`（FINAL_CORRECTION_MODEL） | 全局校正、统一术语、生成摘要 |
| 语音合成 | `qwen3-tts-flash-realtime`（TTS_MODEL，voice=Cherry） | 可选，回放译文 |

**选型要点 / 踩坑**：
- 七牛云 maas marketplace 模型名（`qwen3.7-plus` / kimi / glm / minimax 等）在**标准 dashscope 端点报 `url error`**（域名/证书不匹配），故会后纠偏改用标准端点实测可用的强模型；`qwen-plus` 为默认，`qwen3-max` / `deepseek-v4-pro` 亦已验证可作备选（见 `model_strategy.py`）。
- LiveTranslate 走 WebSocket realtime 协议：`session.update`(配置语种/模态/ASR/热词/音色) → `input_audio_buffer.append`(base64 PCM) → 服务端 VAD 出 `speech_started/stopped` 与 `sentence`（`sentence_end` 为最终句）。
- 热词（glossary）通过 `translation.corpus.phrases` 注入引擎，故实时纠偏模块聚焦**跨句语义级**修正而非术语注入。

---

## 3. 双层纠偏（项目核心）

### 3.1 实时纠偏（在线，qwen-flash）— `backend/app/services/revision.py`
传译进行中，每当一句译文 final，回看最近窗口（默认 4 句、限速 6 次/分、最小置信度 0.62），
判断**之前的句子**是否需结合最新上下文修正；仅高置信度产出 `revision_event`，前端**琥珀高亮扫过**展示。

- 非阻塞后台复核，不阻塞主字幕流；默认不改，只纠明显错误。
- **关键修复（prompt 重平衡）**：原 prompt 过度强调「极度保守」，真实模型对明显错误也不动 →
  干净音频上实时纠偏 0 触发。改为保留「不做风格润色/不动最后一句」，但**显式列出应纠类别+示例**：
  一词多义选错义项、专有名词、数字/单位/日期/金额、否定与主客体、术语一致性。
- **真实模型验证**（`backend/scripts/prove_realtime_revision.py`，非 mock）：
  - 术语错「变压器」→「Transformer」：纠正，confidence 0.98
  - 数字单位错「50 秒」→「50 毫秒」：纠正，confidence 0.98
  - 问候语 / 常规技术句（GPU/CPU）：返回空，零误纠
  - 在线 TED 视频直链联调中实时纠偏**真实触发**（量词「几位作品」→「几件作品」）

### 3.2 会后完整纠偏（qwen-plus）— `backend/app/services/report.py`
会话结束把整场「原文 + 实时译文 + 术语表 + 领域策略」交给 qwen-plus 做一次全局校正：
统一术语、修正实时阶段来不及纠的错误、为每句给出 `finalTranslation`、列出 `revisions`、产出 `summary` 与 `qualityNotes`。

- **领域差异化 PROMPT**：通用/技术/商务/教育/医疗/法律 各有关注点（`DOMAIN_REVISION_FOCUS` / `DOMAIN_GUIDANCE`），实时与会后共享同一套领域知识。
- **优雅降级**：LLM 调用失败或返回异常结构时，直接用实时译文拼报告，保证「结束→可下载」始终可用。

---

## 4. 音频采集（Web + 桌面共用）— `frontend/src/composables/useAudioCapture.ts`

- `acquireStream(kind)`：麦克风走 `getUserMedia`；系统音频/屏幕/标签页走 `getDisplayMedia`（须勾选「分享音频」，否则报错提示）。
- `startAudioCapture`：用 `new AudioContext({sampleRate:16000})` 让浏览器**原生重采样**到 16k，规避手写重采样误差；AudioWorklet（Blob URL 注入，无需构建配置）把 Float32 转 s16le，累积到 ~100ms 一帧经 WS 二进制推送；接静音 gain 到 destination 保证 worklet 调度且不外放。
- **就绪后推流**：前端在收到后端首个 `source_sync_state`（即 `pcm_queue` 已建）后才开始推流，避免早期帧被丢弃。后端 capture 类输入模式（microphone/browser_audio/screen_window/system_audio）走 `pipeline.run_pcm_stream(queue)`。

---

## 5. WebSocket 事件协议（camelCase）

路由：`/api/ws/sessions/{sessionId}`（前端 `ws://host/api`，桌面 `ws://host` + `/api/...`）。

客户端→服务端：`start_session`（可携带语种/领域/源覆盖项）、二进制 PCM 帧、`audio_end`/`audio_chunk_end`、`stop_session`、`pause_session`/`resume_session`。

服务端→客户端：`session_started`、`source_sync_state`、`transcript_segment`、`translation_segment`、`revision_event`、`session_report{reportId}`、`error`。

输入模式与音频入口（`ws.py::_run_ingest`）：
- `url` → ffmpeg 从在线直链解码喂入；
- `upload_video`/`upload_audio` → 解码先前上传到 `/sessions/{id}/media` 的文件；
- 采集类 → 前端推 PCM；
- `demo` → DEMO_MEDIA_PATH 样例媒体或 mock 事件流（`MODEL_PROVIDER != real` 时全程 mock）。

---

## 6. 会话报告与下载 — `backend/app/api/sessions.py` + `report.py`

- 结束后落盘 `storage/reports/{reportId}.json`；前端经 REST 拉取/下载。
- 四种格式：`txt`（双语终稿+校正记录）/`srt`（双语字幕轴）/`md`（表格终稿）/`json`（结构化全量）。
- **中文文件名修复**：`Content-Disposition` 用 RFC 6266 ASCII 兜底 + RFC 5987 `filename*=UTF-8''<percent-encoded>`，规避中文会话名导致的 latin-1 `UnicodeEncodeError`（曾致下载 500）。

---

## 7. 桌面悬浮窗 — `desktop/`（Tauri v2）

两种模式：
- **standalone（自采集）**：悬浮窗内置音源下拉（默认系统音频），选源即 `createSession` 并自采集 PCM 推流，无需先在 Web 端投送。
- **handoff（接管显示）**：由 Web 工作台经 `lingosync://` deep-link 投送 token，桌面 `claim` 后连 WS 仅显示。

三项关键修复（对应用户反馈）：
- **「白色/灰色框」→ 真半透明**：根因是 `main.css` 的 `:root{background:var(--paper)}` 特异性 (0,1,0) 盖过 `overlay.css` 的 `html`(0,0,1)，导致 WebView2 整窗刷成不透明暖白。改用同级 `:root` + `!important` 强制透明，只保留深色半透明字幕面板（`rgba(11,12,15,0.82)` + `blur(12px)`）。
- **「拖不动/只能上下」→ 任意位置拖到屏幕任何地方**：Tauri `data-tauri-drag-region` 仅对**被点中元素自身**生效；此前只挂在 `<section>`，点字幕文字拖不动。给两行文字 `<p>` 也加该属性（仅 desktop）。
- **自带音源下拉**：standalone 启动条提供「系统音频/屏幕窗口/标签页音频/麦克风」选择，默认系统音频。

---

## 8. 真实链路联调发现并修复的问题（健壮性）

实时纠偏真正触发后，在线直链联调暴露两处 latent bug（均已修复）：
1. **报告生成崩溃**：`generate_session_report` 的 `final_by_id`/`llm_revisions` 推导式假设 qwen-plus 返回的 `segments`/`revisions` 均为 dict；超长整稿下模型偶返回字符串列表等异常结构 → `TypeError`/`AttributeError`，且发生在降级 try **之外** → 整份报告失败。改为 `isinstance` 过滤，异常项忽略并降级为实时译文。
2. **WS send-after-close 崩溃**：客户端断开后 `finalize` 仍 `emit` → `RuntimeError: Cannot call send once close sent`。`emit` 加 try 吞掉发送异常；报告已落盘，前端可经 REST `/report` 拉取。

**已知特性（非 bug）**：连续独白（如 TED）被服务端 VAD 切成较粗的大段（整段才 final、首句延迟数十秒），会减少实时纠偏机会（需 ≥2 句 final 才能纠前句）。会议/对话类有自然停顿的内容分段更细。未盲调 VAD（DashScope turn_detection schema 无把握，避免破坏已验证链路）。

---

## 9. 部署与运行（Windows 单机，所有服务同机）

```bash
# 后端（依赖见 backend/requirements，需 ffmpeg 在 PATH）
cd backend && python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
# 前端
cd frontend && npm install && npm run dev   # http://localhost:5173
# 桌面（Tauri）
cd desktop && npm install && npm run tauri dev   # 或 npm run tauri build 出安装包
```

- `.env`（项目根，**已 gitignore，含真实 API Key，切勿提交**）：`MODEL_PROVIDER=real`、各模型名、标准 dashscope 端点、`DEMO_MEDIA_PATH`。
- 跨域：后端需允许 5173（Web）与 5175（桌面 devUrl）来源。
- 桌面 deep-link：scheme `lingosync`；Windows 调试态在 `setup` 中 `register_all()`。

---

## 10. 已验证的测试结论

| 项 | 结论 |
| --- | --- |
| 模型连通性 | LLM 文本 0.4–1s；LiveTranslate 连接 ~0.23s、EN→ZH 准确；TTS 2.35s/149KB——均标准端点实测 |
| 在线视频直链（TED mp4） | 识别/翻译/**实时纠偏(几位→几件)**/事件流全通 |
| 在线音频直链（mp3） | 报告已生成，txt/srt/md/json 四格式 **200 可下载** |
| 实时纠偏能力 | `prove_realtime_revision.py`：该纠必纠(conf 0.98)、干净零误纠 |
| 会后报告健壮性 | 脏 LLM 数据（字符串/混合）均优雅降级、四种渲染不崩 |
| 单元测试 | 后端 22 项全部通过；前端 & 桌面 `vue-tsc --noEmit` 均 exit 0 |

**联调脚本**（保留于 `backend/scripts/`）：`prove_realtime_revision.py`（实时纠偏能力证明）、`e2e_online_url.py`（在线直链端到端）。
