# 后端模型链路与选型 · AI 同声传译助手

> 历史说明：本文记录早期 Python/FastAPI 链路的模型实测与设计。当前实现位于独立 Java 21 + Spring Boot 工作区；Java 端的配置、纠偏状态和中间件边界以 [`backend/README.md`](../../backend/README.md) 为准。

> 本文记录 BabelFlux / 巴别流 同传后端真实管线接入阿里云百炼（DashScope）的
> 凭据/端点策略、全链路模型选型（含实测结论）、实时纠偏与会后完整纠偏的设计，
> 以及三种音频入口与统一前后端 WebSocket 事件协议。所有“已实测”结论均通过
> 真实 API 调用验证（2026-06-06）。

## 1. 凭据与端点策略（关键坑）

供应商通过「七牛云」业务空间下发的百炼 API Key，附带一个**专属 MaaS 域名**：

```
apiKey       sk-****（仅存于 .env，已 gitignore，严禁入库）
workspaceId  ws-axv6izbvdotqwbtk
maas 域名     ws-axv6izbvdotqwbtk.cn-beijing.maas.aliyuncs.com
```

- ⚠️ **专属 MaaS 域名证书与主机名不匹配**（实测 `SSLCERTIFICATE_VERIFY_FAILED:
  Hostname mismatch`），其 `/compatible-mode/v1`（OpenAI 兼容）下挂的 marketplace
  模型（kimi-k2.6 / glm-5.1 / MiniMax-M2.7 / mimo-v2.5-pro / qwen3.7-plus …）
  在开启 TLS 校验时**不可达**。
- ✅ **实测可行方案**：统一走标准华北2（北京）端点
  `https://dashscope.aliyuncs.com/api/v1`（HTTP）/
  `wss://dashscope.aliyuncs.com/api-ws/v1`（WebSocket），并在请求头携带
  `X-DashScope-WorkSpace: <workspaceId>`。该端点提供本项目全链路所需的全部模型。

对应配置见 `.env`（实际值）与 `.env.example`（模板 + 注释）。代码落点：
`backend/app/services/providers/dashscope/config.py`（端点拼装）、
`client.py` / `realtime.py` 的 `_headers()`（注入 workspace 头）。

## 2. 全链路模型选型（实测）

| 环节 | 模型 | 协议 | 实测 | 说明 |
| --- | --- | --- | --- | --- |
| **核心实时同传** | `qwen3.5-livetranslate-flash-realtime` | WS realtime | ✅ 0.23s 建连，端到端识别+翻译 | 一条连接完成「源语音识别 + 翻译 + 可选中文语音」，服务端 VAD 自动断句，译文随上下文自我精修 |
| 实时同传内置 ASR | `qwen3-asr-flash-realtime` | （随上） | ✅ | LiveTranslate 的 `input_audio_transcription.model` |
| **实时纠偏** | `qwen-flash` | HTTP text-gen | ✅ 0.41s | 跨句轻量复核，低延迟优先 |
| **会后完整纠偏** | `qwen-plus`（默认） | HTTP text-gen | ✅ 0.68s | 强一致全局校正；可换 `qwen3-max`（✅ 0.97s）/ `deepseek-v4-pro`（✅ 4.0s） |
| 可选中文语音 | `qwen3-tts-flash-realtime` | WS realtime | ✅ 2.35s / 149KB PCM | 仅 `ttsEnabled` 时启用，音色默认 Cherry |
| ASR-only 兜底 | `fun-asr-realtime` | WS | 协议已实现 | 仅识别不翻译的降级路径 |
| 识别+翻译兜底 | `gummy-realtime-v1` | SDK/WS | 策略已规划 | 备选实时引擎 |

**不可用记录（标准端点）**：`qwen3.7-plus` / `qwen3.6-flash` 返回
`url error, please check url`——这些是七牛云 marketplace 名，须走专属 MaaS 兼容端点
（受第 1 节证书问题阻塞）。因 `qwen-plus`/`qwen3-max`/`deepseek-v4-pro` 已满足
会后纠偏的质量与时延需求，**本项目不依赖 marketplace 模型**。

## 3. 实时纠偏 vs 会后完整纠偏（选题二核心）

选题二要求“能自动纠正历史识别/翻译错误”。我们做**两层纠偏**：

### 3.1 在线实时纠偏（`services/revision.py` · RealtimeReviser）
- 触发：每当一句译文最终确定（`translation_final`）后，后台**非阻塞**复核最近
  N=4 句。
- 模型：`qwen-flash`（低延迟）。
- 极度保守：仅修正「明显误译 / 术语不一致 / 人名·数字·单位·否定错误 / 一词多义选错」；
  默认不改，绝不做风格润色；不修改最后一句（仍可能变化）。
- 限速：每分钟 ≤6 次 LLM 复核；置信度阈值 0.62。
- 产物：`revision_event`（before/after/reason/confidence）→ 前端**高亮展示**被修正
  的字幕卡（见前端纠偏脉冲动效）。修正后的译文段以 `status=revised` 重新下发，
  并携带 `originalText` / `revisionReason` 供对照。
- Prompt：`build_realtime_revision_prompt(domain, src, tgt)`，按领域差异化关注点。

### 3.2 会后完整纠偏（`services/report.py`）
- 触发：会话结束（`stop_session` 或音频自然结束）。
- 模型：`qwen-plus`（可配置为更强模型）。
- 通读全场「原文 + 实时译文」，利用完整上下文统一术语、修正实时阶段来不及纠正的
  错误，为**每一句**产出 `finalTranslation`，并汇总 `revisions` / `summary` /
  `qualityNotes` / `glossaryHits`。
- 降级：LLM 失败时直接用实时译文拼报告，保证“结束 → 可下载”始终可用。
- Prompt：`build_final_correction_prompt(domain, src, tgt, glossary)`，输出严格 JSON。

### 3.3 领域 Prompt
`DOMAIN_REVISION_FOCUS` 覆盖 通用/技术/商务/教育/医疗/法律 六域，实时纠偏与会后
纠偏共享同一套领域知识（关注点差异化），术语表（glossary）在两层都注入。

## 4. 三种音频入口（`services/pipeline.py` · `api/ws.py`）

1. **在线 URL**（`inputMode=url`）：后端用 ffmpeg 从直链解码为 16k 单声道 PCM，
   按 ~1x 实时节奏喂入；断流自动重连。
2. **本地上传**（`upload_video` / `upload_audio`）：先 `POST /sessions/{id}/media`
   上传，再解码同上。
3. **前端采集**（microphone / browser_audio / screen_window / system_audio）：
   前端把 PCM 经 WS 二进制帧推来，后端 `run_pcm_stream` 消费。

媒体解码 `services/media.py`：ffmpeg 子进程 → s16le PCM 帧流；消费端节流对 ffmpeg
形成背压，长音频不会撑爆缓冲。

## 5. 统一前后端事件协议（camelCase）

服务端 → 前端：
`session_started` / `source_sync_state` / `transcript_segment`（源字幕，partial→final）
/ `translation_segment`（译文，partial→final→revised）/ `revision_event`（纠偏记录）
/ `session_report{reportId}` / `error`。

前端 → 服务端：
`start_session`（可带 sourceLanguage/targetLanguage/domain/inputMode/sourceUrl 覆盖）
/ 二进制 PCM 帧 / `audio_end` / `pause_session` / `resume_session` / `stop_session`。

字幕段 `status`：`partial`（流式）→ `final`（最终）→ `revised`（被纠偏，带
`originalText`/`revisionReason`）。`source_sync_state.status`：
listening/syncing/lagging/missing/recovered。

## 6. 报告下载（`GET /sessions/{id}/report/download?format=`）

支持 `txt` / `srt` / `md` / `json`。

> 🐞 **已修复**：原实现把含中文的会话名直接写入 `Content-Disposition: filename="…"`，
> HTTP 头只能 latin-1 编码 → `UnicodeEncodeError` → 500。现按 RFC 6266 提供 ASCII
> 兜底 `filename` + RFC 5987 `filename*=UTF-8''<percent-encoded>`，浏览器优先用后者
> 还原中文文件名。

## 7. 如何复现实测

```bash
cd backend
# 1) 简单连通性（LLM）
python - <<'PY'  # 见 docs 附带的 /tmp 测试脚本思路
PY
# 2) 端到端：启服务 + WS 客户端
DEMO_MEDIA_PATH=/path/to/clip.m4a python -m uvicorn app.main:app --port 8000
# 然后 POST /api/sessions（inputMode=demo）→ 连 WS → start_session → 收事件 →
# session_report → GET /api/sessions/{id}/report[/download?format=srt]
python -m pytest -q   # 22 passed（全 mock，不打真实网络）
ruff check .          # clean
```

实测结论（2026-06-06，28s TED 片段，领域=通用）：transcript_segment 16 条、
translation_segment 23 条流式、会后完整纠偏 finalRevisions=1、LLM 摘要生成正常、
txt/srt/md/json 四格式下载均 200。
