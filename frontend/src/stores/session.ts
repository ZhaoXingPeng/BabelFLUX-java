import { defineStore } from "pinia";
import {
  createSession,
  getSessionReport,
  issueSessionHandoff,
  reportDownloadUrl,
  type CreateSessionPayload,
  type DesktopDisplayMode,
  type ReportFormat,
  type SessionReport
} from "../api/client";
import { createSessionSocket } from "../api/ws";
import {
  acquireStream,
  startAudioCapture,
  startMediaElementAudioCapture,
  type AudioCaptureSession,
  type CaptureSourceKind
} from "../composables/useAudioCapture";
import { createTtsPlayback } from "../composables/useTtsPlayback";
import type {
  RevisionEvent,
  SegmentStatus,
  ServerEvent,
  SessionStatus,
  SourceSyncState,
  SubtitleSegment
} from "../types/events";
import type {
  DesktopLaunchState,
  FloatingFormState,
  ProductMode,
  ProductModeOption,
  QuickFormState,
  ReportMetric,
  RuntimeState,
  SourceInputState,
  SourceOption,
  TranscriptPair,
  WorkspaceTile
} from "../types/workflow";
import {
  activeSegmentForPlayback,
  shouldKeepCurrentActiveSegment,
  upsertSegment
} from "./sessionTimeline";
import {
  createFixtureSourceSegments,
  createFixtureTranslationSegments,
  currentFixture,
  defaultFixture,
  fixturePlaybackState,
  fixtureSessionId,
  isFixtureSession,
  isFixtureSource,
  videoFixtures,
  FIXTURE_SUBTITLE_LATENCY_MS
} from "./sessionFixture";
import { sendAudioChunk } from "../realtime/audioBackpressure";

let socket: WebSocket | null = null;
let desktopLaunchTimer: number | null = null;
let desktopLaunchDismissTimer: number | null = null;
let removeDesktopLaunchListeners: (() => void) | null = null;
// File 对象不放进响应式 state（不可序列化），用模块级暂存供本地媒体预览与采集使用。
const pendingFiles: { quick: File | null; floating: File | null } = { quick: null, floating: null };
const localPreviewUrls: Record<ProductMode, string | null> = { quick: null, floating: null };
// 实时采集句柄与“本次会话应采集的音源种类”，同样不入响应式 state。
let audioCapture: AudioCaptureSession | null = null;
let pendingCaptureKind: CaptureSourceKind | null = null;
let pendingMediaElementCapture = false;
let pendingMediaReadyState: SourceSyncState | null = null;
let mediaElement: HTMLMediaElement | null = null;
let captureStarted = false;
let handleTtsPlaybackError: ((message: string) => void) | null = null;
let ttsPlayback = createTtsPlayback({
  onError: (message) => handleTtsPlaybackError?.(message)
});
let lastFixtureSpeechKey: string | null = null;
const queuedFixtureSpeechKeys = new Set<string>();
let estimatedOutputLatencyMs = 1000;
const recentOutputLatencies: number[] = [];
const sampledOutputLatencySegmentIds = new Set<string>();

function revokeLocalPreview(mode: ProductMode) {
  const url = localPreviewUrls[mode];
  if (url && typeof URL.revokeObjectURL === "function") URL.revokeObjectURL(url);
  localPreviewUrls[mode] = null;
}

function setLocalPreview(mode: ProductMode, file: File | null): string | null {
  revokeLocalPreview(mode);
  if (!file || typeof URL.createObjectURL !== "function") return null;
  localPreviewUrls[mode] = URL.createObjectURL(file);
  return localPreviewUrls[mode];
}

function stopBoundMediaElement() {
  if (!mediaElement) return;
  try {
    mediaElement.pause();
    mediaElement.currentTime = 0;
  } catch {
    // Some test doubles and detached elements do not allow clock mutation.
  }
  mediaElement = null;
}

const captureKindBySource: Record<string, CaptureSourceKind> = {
  microphone: "microphone",
  "browser-tab": "browser_audio",
  "screen-window": "screen_window",
  "system-audio": "system_audio"
};

const DESKTOP_LAUNCH_TIMEOUT_MS = 2500;
const DESKTOP_LAUNCH_SUCCESS_VISIBLE_MS = 3500;
const DEFAULT_SESSION_NAME_PATTERN = /^同传_\d{8}_\d{4}$/;
// 本地测试视频字幕的「同传产出延迟」：音频说到某句后约 1s，右侧才产出该句字幕，贴近低延迟同传节奏。
const SUBTITLE_LATENCY_MS = FIXTURE_SUBTITLE_LATENCY_MS;
const MIN_OUTPUT_LATENCY_MS = 250;
const MAX_OUTPUT_LATENCY_MS = 6000;
const OUTPUT_LATENCY_SAMPLE_SIZE = 8;
const ACTIVE_PENDING_TRANSLATION_HOLD_MS = 2600;
const REPORT_READY_TIMEOUT_MS = 150_000;
const REPORT_POLL_INTERVAL_MS = 1_000;
const defaultSourceSyncState: SourceSyncState = {
  status: "listening",
  lagMs: 0,
  message: "等待开始会话"
};

const productModeOptions: ProductModeOption[] = [
  { key: "quick", label: "快速同传", description: "Web 工作台" },
  { key: "floating", label: "客户端悬浮", description: "桌面端全局能力" }
];

const modelProfileOptions = ["智能默认", "快速低延迟", "高准确", "成本优先", "指定供应商"];
const domainOptions = ["通用", "技术", "商务", "教育", "医疗", "法律", "自定义术语表"];
const languageOptions = ["自动检测", "英语", "中文", "日语", "韩语", "法语", "德语"];
const targetLanguageOptions = ["中文", "英语", "日语", "韩语"];
const displayModeOptions = ["分区对照", "逐句对照", "悬浮字幕"];
const displayModeCopy: Record<string, string> = {
  分区对照: "原文和译文分栏审阅",
  逐句对照: "一句原文对应一句译文",
  悬浮字幕: "Web 内嵌字幕层，桌面端可全局悬浮"
};

const quickSourceOptions: SourceOption[] = [
  ...videoFixtures.map((fixture) => ({
    key: fixture.key,
    label: fixture.label,
    channel: `mp4 + mp3 + ${fixture.sourceLanguage}/${fixture.targetLanguage} 字幕`,
    availability: "web" as const,
    fixture: true
  })),
  { key: "video-file", label: "视频文件", channel: "mp4 / mov / webm", availability: "web" },
  { key: "audio-file", label: "音频文件", channel: "mp3 / wav / m4a", availability: "web" },
  { key: "url", label: "URL", channel: "网页视频或直播链接", availability: "web" },
  { key: "microphone", label: "麦克风", channel: "外放或线下讲座兜底", availability: "web" },
  { key: "browser-tab", label: "浏览器标签页音频", channel: "网课、网页视频", availability: "web" },
  { key: "screen-window", label: "屏幕或窗口", channel: "浏览器权限能力", availability: "web" },
  { key: "system-audio", label: "系统音频", channel: "桌面端能力", availability: "desktop", disabled: true }
];

const floatingSourceOptions: SourceOption[] = [
  { key: "microphone", label: "麦克风", channel: "外放或会议室", availability: "web" },
  { key: "browser-tab", label: "浏览器标签页音频", channel: "网页视频和网课", availability: "web" },
  { key: "screen-window", label: "屏幕或窗口音频", channel: "浏览器权限能力", availability: "web" },
  { key: "system-audio", label: "系统音频", channel: "桌面端能力", availability: "desktop", disabled: true }
];

const defaultSourceInputState: SourceInputState = {
  fileName: "",
  url: "",
  permissionState: "idle",
  permissionMessage: "等待准备声源"
};

const fileSourceKeys = new Set(["video-file", "audio-file"]);
const permissionSourceKeys = new Set(["microphone", "browser-tab", "screen-window"]);
const autoDetectSourceKeys = new Set([
  "video-file",
  "audio-file",
  "url",
  "microphone",
  "browser-tab",
  "screen-window",
  "system-audio"
]);

const inputModeBySourceKey: Record<string, CreateSessionPayload["inputMode"]> = {
  ...Object.fromEntries(videoFixtures.map((fixture) => [fixture.key, "demo"] as const)),
  "video-file": "media_element_audio",
  "audio-file": "media_element_audio",
  url: "url",
  microphone: "microphone",
  "browser-tab": "browser_audio",
  "screen-window": "screen_window",
  "system-audio": "system_audio"
};

const languageCodeByLabel: Record<string, string> = {
  自动检测: "auto",
  英语: "en",
  中文: "zh",
  日语: "ja",
  韩语: "ko",
  法语: "fr",
  德语: "de"
};

const languageLabelByCode: Record<string, string> = {
  auto: "自动检测",
  en: "英语",
  zh: "中文",
  ja: "日语",
  ko: "韩语",
  fr: "法语",
  de: "德语"
};

const speechLangByCode: Record<string, string> = {
  en: "en-US",
  zh: "zh-CN",
  ja: "ja-JP",
  ko: "ko-KR",
  fr: "fr-FR",
  de: "de-DE"
};

const samplePairs: TranscriptPair[] = [
  {
    time: "00:00:04",
    source: "Today we are going to talk about real-time AI translation.",
    translation: "今天我们要讨论实时 AI 翻译。",
    state: "final"
  },
  {
    time: "00:00:11",
    source: "The system should balance latency, accuracy and stability.",
    translation: "系统需要在延迟、准确率和稳定性之间取得平衡。",
    state: "translated"
  },
  {
    time: "00:00:18",
    source: "When more context arrives, earlier subtitles can be revised.",
    translation: "当后续上下文到达时，前面的字幕可以被自动修正。",
    state: "revised"
  }
];

interface SessionState {
  sessionId: string | null;
  status: SessionStatus;
  wsConnected: boolean;
  sourceSyncState: SourceSyncState;
  mediaUrl: string | null;
  audioUrl: string | null;
  playbackMs: number;
  activeSegmentId: string | null;
  fixtureAppliedRevisionIds: string[];
  sourceSegments: SubtitleSegment[];
  translationSegments: SubtitleSegment[];
  revisions: RevisionEvent[];
  errorMessage: string | null;
  productMode: ProductMode;
  activeMode: ProductMode | null;
  endingMode: ProductMode | null;
  showEndDialog: boolean;
  selectedDisplayMode: string;
  desktopLaunchState: DesktopLaunchState;
  desktopLaunchMessage: string;
  desktopDownloadPromptOpen: boolean;
  desktopHandoffUrl: string | null;
  ttsMuted: boolean;
  ttsVolume: number;
  ttsErrorMessage: string | null;
  modeStates: Record<ProductMode, RuntimeState>;
  quickForm: QuickFormState;
  floatingForm: FloatingFormState;
  quickInput: SourceInputState;
  floatingInput: SourceInputState;
  startRequestId: number;
  reportId: string | null;
  report: SessionReport | null;
  reportLoading: boolean;
  reportError: string | null;
}

function formatPlaybackTime(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

function stopFixtureSpeech(resetKey = true) {
  window.speechSynthesis?.cancel();
  if (resetKey) {
    lastFixtureSpeechKey = null;
    queuedFixtureSpeechKeys.clear();
  }
}

function pauseFixtureSpeech() {
  window.speechSynthesis?.pause();
}

function resumeFixtureSpeech() {
  window.speechSynthesis?.resume();
}

function speakFixtureTranslation(
  segmentId: string,
  text: string,
  targetLanguage: string,
  volume: number,
  muted: boolean,
  onError: (message: string) => void
) {
  const value = text.trim();
  const speechKey = `${segmentId}:${value}`;
  if (!value || lastFixtureSpeechKey === speechKey || queuedFixtureSpeechKeys.has(speechKey)) return;
  if (muted || volume <= 0) return;
  lastFixtureSpeechKey = speechKey;
  queuedFixtureSpeechKeys.add(speechKey);

  const SpeechSynthesisUtteranceCtor = window.SpeechSynthesisUtterance;
  if (!window.speechSynthesis || typeof SpeechSynthesisUtteranceCtor === "undefined") {
    onError("当前浏览器不支持本地语音播报；真实后端会话仍使用模型 TTS。");
    return;
  }

  const utterance = new SpeechSynthesisUtteranceCtor(value);
  utterance.lang = speechLangByCode[targetLanguage] ?? "zh-CN";
  utterance.volume = clamp01(volume);
  utterance.rate = 1;
  utterance.onend = () => queuedFixtureSpeechKeys.delete(speechKey);
  utterance.onerror = () => onError("本地语音播报失败，请确认浏览器语音合成可用。");
  window.speechSynthesis.speak(utterance);
}

function defaultSessionName(): string {
  const date = new Date();
  const pad = (value: number) => String(value).padStart(2, "0");
  return `同传_${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_${pad(date.getHours())}${pad(date.getMinutes())}`;
}

function createDefaultQuickForm(): QuickFormState {
  return {
    name: defaultSessionName(),
    domain: "通用",
    sourceLanguage: "中文",
    targetLanguage: "英语",
    modelProfile: "智能默认",
    source: defaultFixture.key,
    ttsEnabled: false
  };
}

function createDefaultFloatingForm(): FloatingFormState {
  return {
    domain: "通用",
    sourceLanguage: "自动检测",
    targetLanguage: "中文",
    modelProfile: "快速低延迟",
    source: "browser-tab",
    ttsEnabled: false,
    style: "双语字幕",
    size: "标准",
    opacity: "90%",
    captionPinned: false,
    captionOffsetY: 0
  };
}

function shouldRefreshDefaultSessionName(name: string): boolean {
  return name.trim().length === 0 || DEFAULT_SESSION_NAME_PATTERN.test(name);
}

function formatRuntimeState(state: RuntimeState): string {
  const labels: Record<RuntimeState, string> = {
    setup: "待开始",
    connecting: "连接中",
    running: "运行中",
    paused: "已暂停",
    report: "已生成报告",
    error: "启动失败"
  };
  return labels[state];
}

function toLanguageCode(label: string): string {
  return languageCodeByLabel[label] ?? label;
}

function toDesktopDisplayMode(style: string): DesktopDisplayMode {
  return style === "仅译文" ? "translation-only" : "bilingual";
}

function getUrlError(sourceKey: string, url: string): string | null {
  if (sourceKey !== "url") return null;
  const value = url.trim();
  if (!value) return "请输入 URL";

  try {
    const parsed = new URL(value);
    return parsed.protocol === "http:" || parsed.protocol === "https:"
      ? null
      : "仅支持 http 或 https URL";
  } catch {
    return "URL 格式不正确";
  }
}

function isSourceInputReady(sourceKey: string, input: SourceInputState): boolean {
  if (fileSourceKeys.has(sourceKey)) return input.fileName.trim().length > 0;
  if (sourceKey === "url") return getUrlError(sourceKey, input.url) === null;
  if (permissionSourceKeys.has(sourceKey)) return input.permissionState === "granted";
  return true;
}

function canStartMode(source: SourceOption, sourceKey: string, input: SourceInputState, state: RuntimeState): boolean {
  return ["setup", "report", "error"].includes(state) && !source.disabled && isSourceInputReady(sourceKey, input);
}

function stopMediaStream(stream: MediaStream) {
  stream.getTracks().forEach((track) => track.stop());
}

async function requestBrowserPermission(sourceKey: string): Promise<string> {
  const mediaDevices = navigator.mediaDevices;
  if (!mediaDevices) throw new Error("当前浏览器不支持媒体权限");

  if (sourceKey === "microphone") {
    const stream = await mediaDevices.getUserMedia({ audio: true });
    stopMediaStream(stream);
    return "麦克风已授权";
  }

  if (sourceKey === "browser-tab" || sourceKey === "screen-window") {
    const stream = await acquireStream(captureKindBySource[sourceKey]);
    stopMediaStream(stream);
    return sourceKey === "browser-tab"
      ? "标签页音频已授权"
      : "屏幕或窗口音频已授权";
  }

  if (sourceKey === "system-audio") {
    throw new Error("Windows 系统音频由桌面客户端原生采集，无需浏览器授权。");
  }

  throw new Error("该声源不需要浏览器授权");
}

function triggerDownload(url: string, filename?: string) {
  const anchor = document.createElement("a");
  anchor.href = url;
  if (filename) anchor.download = filename;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}

function srtTimestamp(ms: number): string {
  const clamped = Math.max(0, ms);
  const h = Math.floor(clamped / 3_600_000);
  const m = Math.floor((clamped % 3_600_000) / 60_000);
  const s = Math.floor((clamped % 60_000) / 1000);
  const millis = clamped % 1000;
  const pad = (value: number, len = 2) => String(value).padStart(len, "0");
  return `${pad(h)}:${pad(m)}:${pad(s)},${pad(millis, 3)}`;
}

function median(values: number[]): number {
  if (values.length === 0) return SUBTITLE_LATENCY_MS;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function wait(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function recordOutputLatency(segment: SubtitleSegment, playbackMs: number) {
  if (sampledOutputLatencySegmentIds.has(segment.segmentId)) return;
  if (playbackMs <= 0 || segment.startMs < 0) return;
  const latency = playbackMs - segment.startMs;
  if (latency < MIN_OUTPUT_LATENCY_MS || latency > MAX_OUTPUT_LATENCY_MS) return;
  sampledOutputLatencySegmentIds.add(segment.segmentId);
  recentOutputLatencies.push(latency);
  while (recentOutputLatencies.length > OUTPUT_LATENCY_SAMPLE_SIZE) recentOutputLatencies.shift();
  estimatedOutputLatencyMs = median(recentOutputLatencies);
}

/** 客户端报告渲染（本地演示下载用，与后端 report.py 的 txt/srt/md/json 对齐）。 */
function renderReportClient(
  report: SessionReport,
  format: ReportFormat
): { body: string; mime: string; ext: string } {
  if (format === "json") {
    return { body: JSON.stringify(report, null, 2), mime: "application/json", ext: "json" };
  }
  if (format === "srt") {
    const body = report.segments
      .map((seg, index) => {
        const end = seg.endMs > seg.startMs ? seg.endMs : seg.startMs + 2000;
        return `${index + 1}\n${srtTimestamp(seg.startMs)} --> ${srtTimestamp(end)}\n${seg.sourceText}\n${seg.finalTranslation}\n`;
      })
      .join("\n");
    return { body, mime: "application/x-subrip", ext: "srt" };
  }
  if (format === "md") {
    const lines = [
      `# ${report.sessionName}`,
      "",
      `- **领域**：${report.domain}`,
      `- **语言**：${report.sourceLanguage} → ${report.targetLanguage}`,
      `- **时长**：${report.durationText}`,
      `- **句数**：${report.metrics.segments}　实时修正：${report.metrics.realtimeRevisions}　会后修正：${report.metrics.finalRevisions}`,
      `- **全文纠偏**：${correctionStatusText(report)}`,
      "",
      "## 摘要",
      report.summary,
      "",
      "## 双语终稿",
      "",
      "| 时间 | 原文 | 终稿译文 |",
      "| --- | --- | --- |",
      ...report.segments.map(
        (seg) =>
          `| ${seg.timecode} | ${seg.sourceText.replace(/\|/g, "\\|")} | ${seg.finalTranslation.replace(/\|/g, "\\|")} |`
      )
    ];
    if (report.finalRevisions.length) {
      lines.push("", "## 会后校正记录", "", "| 原译文 | 校正后 | 原因 |", "| --- | --- | --- |");
      report.finalRevisions.forEach((rev) => {
        lines.push(
          `| ${rev.beforeText.replace(/\|/g, "\\|")} | ${rev.afterText.replace(/\|/g, "\\|")} | ${rev.reason} |`
        );
      });
    } else if (report.correctionStatus === "completed") {
      lines.push("", "## 会后校正记录", "", "全文纠偏已完成，本场未发现需要改写的译文。");
    }
    if (report.qualityNotes) {
      lines.push("", "## 质量说明", report.qualityNotes);
    }
    return { body: lines.join("\n"), mime: "text/markdown", ext: "md" };
  }
  const lines = [
    `# ${report.sessionName}`,
    `领域：${report.domain}  |  语言：${report.sourceLanguage} -> ${report.targetLanguage}  |  时长：${report.durationText}`,
    `生成时间：${report.generatedAt}`,
    `全文纠偏：${correctionStatusText(report)}`,
    "",
    "【摘要】",
    report.summary,
    "",
    "【双语终稿】",
    ...report.segments.flatMap((seg) => [`[${seg.timecode}] ${seg.sourceText}`, `          ${seg.finalTranslation}`])
  ];
  if (report.finalRevisions.length) {
    lines.push("", "【会后校正记录】");
    report.finalRevisions.forEach((rev) => lines.push(`- ${rev.beforeText}  =>  ${rev.afterText}  （${rev.reason}）`));
  } else if (report.correctionStatus === "completed") {
    lines.push("", "【会后校正记录】", "全文纠偏已完成，本场未发现需要改写的译文。");
  }
  if (report.qualityNotes) {
    lines.push("", "【质量说明】", report.qualityNotes);
  }
  return { body: lines.join("\n"), mime: "text/plain", ext: "txt" };
}

function correctionStatusText(report: SessionReport): string {
  if (report.correctionStatus === "pending") return "基础报告已可下载，全文纠偏生成中";
  const elapsedMs = report.correctionElapsedMs ?? 0;
  const elapsed = elapsedMs > 0 ? `，耗时 ${(elapsedMs / 1000).toFixed(1)} 秒` : "";
  const model = report.correctionModel ? `，模型 ${report.correctionModel}` : "";
  if (report.correctionStatus === "completed") return `已完成${model}${elapsed}`;
  if (report.correctionStatus === "partial") return `部分完成${model}${elapsed}`;
  if (report.correctionStatus === "timeout") return `超时降级${elapsed}`;
  if (report.correctionStatus === "skipped") return "未执行";
  return report.correctionModel ? `已完成${model}${elapsed}` : `降级为实时译文${elapsed}`;
}

export const useSessionStore = defineStore("session", {
  state: (): SessionState => ({
    sessionId: null,
    status: "idle",
    wsConnected: false,
    sourceSyncState: {
      status: "listening",
      lagMs: 0,
      message: "本地测试素材已就绪"
    },
    mediaUrl: defaultFixture.videoUrl,
    audioUrl: defaultFixture.audioUrl,
    playbackMs: 0,
    activeSegmentId: defaultFixture.segments[0]?.segmentId ?? null,
    fixtureAppliedRevisionIds: [],
    sourceSegments: createFixtureSourceSegments(),
    translationSegments: createFixtureTranslationSegments(),
    revisions: [],
    errorMessage: null,
    productMode: "quick",
    activeMode: null,
    endingMode: null,
    showEndDialog: false,
    selectedDisplayMode: "逐句对照",
    desktopLaunchState: "idle",
    desktopLaunchMessage: "等待投送到桌面悬浮窗",
    desktopDownloadPromptOpen: false,
    desktopHandoffUrl: null,
    ttsMuted: false,
    ttsVolume: 0.5,
    ttsErrorMessage: null,
    modeStates: {
      quick: "setup",
      floating: "setup"
    },
    quickForm: createDefaultQuickForm(),
    floatingForm: createDefaultFloatingForm(),
    quickInput: { ...defaultSourceInputState },
    floatingInput: { ...defaultSourceInputState },
    startRequestId: 0,
    reportId: null,
    report: null,
    reportLoading: false,
    reportError: null
  }),

  getters: {
    activeSourceSegment: (state): SubtitleSegment | undefined =>
      state.activeSegmentId
        ? state.sourceSegments.find((segment) => segment.segmentId === state.activeSegmentId)
        : [...state.sourceSegments].reverse().find((segment) => segment.status !== "revised"),
    activeTranslationSegment: (state): SubtitleSegment | undefined =>
      state.activeSegmentId
        ? state.translationSegments.find((segment) => segment.segmentId === state.activeSegmentId)
        : [...state.translationSegments].reverse().find((segment) => segment.status !== "revised"),
    productModes: (): ProductModeOption[] => productModeOptions,
    modelProfiles: (): string[] => modelProfileOptions,
    domains: (): string[] => domainOptions,
    languages: (): string[] => languageOptions,
    targetLanguages: (): string[] => targetLanguageOptions,
    displayModes: (): string[] => displayModeOptions,
    quickSources: (): SourceOption[] => quickSourceOptions,
    floatingSources: (): SourceOption[] => floatingSourceOptions,
    transcriptPairs: (state): TranscriptPair[] => {
      if (state.sourceSegments.length === 0 && state.translationSegments.length === 0) {
        // 会话已开始但字幕尚未「延迟产出」时显示空白等待，而非示例占位字幕；
        // 仅在没有任何会话（首屏预览态）时才回退到 samplePairs。
        return state.sessionId ? [] : samplePairs;
      }

      const ids: string[] = [];
      [...state.sourceSegments, ...state.translationSegments].forEach((segment) => {
        if (!ids.includes(segment.segmentId)) ids.push(segment.segmentId);
      });

      return ids.map((segmentId) => {
        const source = state.sourceSegments.find((segment) => segment.segmentId === segmentId);
        const translation = state.translationSegments.find((segment) => segment.segmentId === segmentId);
        const sourceStatus = source?.status;
        const translationStatus = translation?.status;
        const stateLabel =
          translationStatus === "revised"
            ? "revised"
            : sourceStatus === "partial" || translationStatus === "partial"
              ? "partial"
              : (translationStatus ?? sourceStatus ?? "partial");
        const startMs = source?.startMs ?? translation?.startMs ?? 0;
        return {
          segmentId,
          time: formatPlaybackTime(startMs),
          source: source?.text ?? "",
          translation: translation?.text ?? "",
          state: stateLabel,
          isActive: segmentId === state.activeSegmentId,
          originalTranslation: translation?.originalText,
          revisionReason: translation?.revisionReason
        };
      }).filter((pair) => {
        if (pair.translation.trim()) return true;
        if (!pair.source.trim()) return false;
        return pair.segmentId === state.activeSegmentId;
      });
    },
    currentPair(): TranscriptPair {
      const active = this.transcriptPairs.find((pair) => pair.isActive);
      const latest = this.transcriptPairs[this.transcriptPairs.length - 1];
      if (active || latest) return active ?? latest;
      if (this.sessionId) {
        return {
          time: formatPlaybackTime(this.playbackMs),
          source: "",
          translation: "",
          state: "partial",
          isActive: false
        };
      }
      return (
        samplePairs[0]
      );
    },
    quickSource(state): SourceOption {
      return quickSourceOptions.find((source) => source.key === state.quickForm.source) ?? quickSourceOptions[0];
    },
    floatingSource(state): SourceOption {
      return (
        floatingSourceOptions.find((source) => source.key === state.floatingForm.source) ??
        floatingSourceOptions[0]
      );
    },
    quickStatusLabel(state): string {
      return formatRuntimeState(state.modeStates.quick);
    },
    floatingStatusLabel(state): string {
      return formatRuntimeState(state.modeStates.floating);
    },
    selectedDisplayDescription(state): string {
      return displayModeCopy[state.selectedDisplayMode];
    },
    mediaKind(state): "video" | "audio" {
      return isFixtureSource(state.quickForm.source) || state.quickForm.source === "video-file" || state.quickForm.source === "url"
        ? "video"
        : "audio";
    },
    isLive(state): boolean {
      return ["running", "paused", "report"].includes(state.modeStates.quick);
    },
    quickUrlError(state): string | null {
      return getUrlError(state.quickForm.source, state.quickInput.url);
    },
    floatingUrlError(state): string | null {
      return getUrlError(state.floatingForm.source, state.floatingInput.url);
    },
    quickCanStart(): boolean {
      return canStartMode(this.quickSource, this.quickForm.source, this.quickInput, this.modeStates.quick);
    },
    floatingCanStart(): boolean {
      return canStartMode(
        this.floatingSource,
        this.floatingForm.source,
        this.floatingInput,
        this.modeStates.floating
      );
    },
    workspaceTiles(): WorkspaceTile[] {
      return [
        { label: "源文实时转写", value: this.currentPair.source },
        { label: "译文实时输出", value: this.currentPair.translation },
        {
          label: "修正记录",
          value: this.revisions[0]
            ? `${this.revisions[0].beforeText} -> ${this.revisions[0].afterText}`
            : "等待修正事件"
        },
        { label: "术语与摘要", value: "AI translation · 实时 AI 翻译 · 术语命中" }
      ];
    },
    reportMetrics(): ReportMetric[] {
      const report = this.report;
      if (report) {
        const segmentCount = report.metrics.segments || report.segments.length || this.transcriptPairs.length;
        const durationText =
          report.durationText ||
          report.metrics.durationText ||
          formatPlaybackTime(report.durationMs || this.playbackMs);
        return [
          { label: "时长", value: durationText },
          { label: "句数", value: `${segmentCount} 句` },
          {
            label: "修正",
            value: `实时 ${report.metrics.realtimeRevisions} · 会后 ${report.metrics.finalRevisions}`
          },
          { label: "导出", value: "TXT / SRT / MD / JSON" }
        ];
      }
      return [
        { label: "时长", value: formatPlaybackTime(this.playbackMs) },
        { label: "句数", value: `${this.transcriptPairs.length} 句` },
        { label: "修正", value: `${this.revisions.length} 条` },
        { label: "导出", value: "TXT / SRT / MD / JSON" }
      ];
    }
  },

  actions: {
    ensureTtsErrorHandler() {
      handleTtsPlaybackError = (message: string) => {
        this.ttsErrorMessage = message;
      };
    },

    selectMode(mode: ProductMode) {
      this.productMode = mode;
    },

    selectQuickSource(source: SourceOption) {
      if (source.disabled || this.quickForm.source === source.key) return;
      pendingFiles.quick = null;
      revokeLocalPreview("quick");
      this.quickInput = { ...defaultSourceInputState };
      this.quickForm.source = source.key;
      const fixture = isFixtureSource(source.key) ? currentFixture(source.key) : undefined;
      if (fixture) {
        this.quickForm.sourceLanguage = languageLabelByCode[fixture.sourceLanguage] ?? "自动检测";
        this.quickForm.targetLanguage = languageLabelByCode[fixture.targetLanguage] ?? "中文";
        this.loadTestVideoFixturePreview();
      } else if (!this.activeMode) {
        if (autoDetectSourceKeys.has(source.key)) {
          this.quickForm.sourceLanguage = "自动检测";
          if (this.quickForm.targetLanguage === "英语") this.quickForm.targetLanguage = "中文";
        }
        this.clearLocalMediaPreview();
      }
    },

    selectFloatingSource(source: SourceOption) {
      if (source.disabled || this.floatingForm.source === source.key) return;
      this.floatingForm.source = source.key;
      this.floatingInput = { ...defaultSourceInputState };
    },

    setQuickSourceFile(file: File | null) {
      this.quickInput.fileName = file?.name ?? "";
      pendingFiles.quick = file;
      const previewUrl = setLocalPreview("quick", file);
      if (this.quickForm.source === "video-file") {
        this.mediaUrl = previewUrl;
        this.audioUrl = null;
      } else if (this.quickForm.source === "audio-file") {
        this.mediaUrl = null;
        this.audioUrl = previewUrl;
      }
    },

    updateQuickSourceUrl(url: string) {
      this.quickInput.url = url;
    },

    setFloatingSourceFile(file: File | null) {
      this.floatingInput.fileName = file?.name ?? "";
      pendingFiles.floating = file;
    },

    updateFloatingSourceUrl(url: string) {
      this.floatingInput.url = url;
    },

    async requestQuickSourceAccess() {
      await this.requestSourceAccess("quick");
    },

    async requestFloatingSourceAccess() {
      await this.requestSourceAccess("floating");
    },

    async requestSourceAccess(mode: ProductMode) {
      const form = mode === "quick" ? this.quickForm : this.floatingForm;
      const input = mode === "quick" ? this.quickInput : this.floatingInput;

      input.permissionState = "requesting";
      input.permissionMessage = "正在请求权限";

      try {
        input.permissionMessage = await requestBrowserPermission(form.source);
        input.permissionState = "granted";
      } catch (error) {
        input.permissionState = "denied";
        input.permissionMessage = error instanceof Error ? error.message : "权限申请失败";
      }
    },

    clearDesktopLaunchWatchers() {
      if (desktopLaunchTimer !== null) {
        window.clearTimeout(desktopLaunchTimer);
        desktopLaunchTimer = null;
      }
      if (desktopLaunchDismissTimer !== null) {
        window.clearTimeout(desktopLaunchDismissTimer);
        desktopLaunchDismissTimer = null;
      }
      removeDesktopLaunchListeners?.();
      removeDesktopLaunchListeners = null;
    },

    markDesktopLaunchLaunched() {
      if (this.desktopLaunchState !== "launching") return;
      this.clearDesktopLaunchWatchers();
      this.desktopLaunchState = "launched";
      this.desktopLaunchMessage = "桌面悬浮窗已唤起";
      this.desktopDownloadPromptOpen = true;
      desktopLaunchDismissTimer = window.setTimeout(() => {
        if (this.desktopLaunchState !== "launched") return;
        this.desktopDownloadPromptOpen = false;
        this.desktopLaunchState = "idle";
        this.desktopLaunchMessage = "等待投送到桌面悬浮窗";
        desktopLaunchDismissTimer = null;
      }, DESKTOP_LAUNCH_SUCCESS_VISIBLE_MS);
      try {
        window.localStorage.setItem("babelflux.clientSeen", "1");
      } catch {
        // localStorage can be unavailable in privacy modes; launch success should not depend on it.
      }
    },

    armDesktopLaunchFallback() {
      this.clearDesktopLaunchWatchers();

      const handleVisibility = () => {
        if (document.visibilityState === "hidden") this.markDesktopLaunchLaunched();
      };
      const handleBlur = () => this.markDesktopLaunchLaunched();
      window.addEventListener("blur", handleBlur, { once: true });
      document.addEventListener("visibilitychange", handleVisibility);
      removeDesktopLaunchListeners = () => {
        window.removeEventListener("blur", handleBlur);
        document.removeEventListener("visibilitychange", handleVisibility);
      };

      desktopLaunchTimer = window.setTimeout(() => {
        this.clearDesktopLaunchWatchers();
        if (this.desktopLaunchState !== "launching") return;
        this.desktopLaunchState = "fallback";
        this.desktopLaunchMessage = "未检测到桌面客户端";
        this.desktopDownloadPromptOpen = true;
      }, DESKTOP_LAUNCH_TIMEOUT_MS);
    },

    launchDesktopUrl(deepLinkUrl: string) {
      this.desktopHandoffUrl = deepLinkUrl;
      this.desktopLaunchState = "launching";
      this.desktopLaunchMessage = "正在唤起桌面悬浮窗";
      this.desktopDownloadPromptOpen = false;
      this.armDesktopLaunchFallback();
      window.location.assign(deepLinkUrl);
    },

    async openDesktopFloating() {
      const targetLanguage = toLanguageCode(this.floatingForm.targetLanguage);
      const displayMode = toDesktopDisplayMode(this.floatingForm.style);

      this.desktopLaunchState = "launching";
      this.desktopLaunchMessage = "正在准备桌面悬浮窗";
      this.desktopDownloadPromptOpen = false;

      if (!this.sessionId) {
        const params = new URLSearchParams({
          source: "system-audio",
          sourceLanguage: "auto",
          targetLanguage,
          displayMode
        });
        this.launchDesktopUrl(`lingosync://floating/start?${params.toString()}`);
        return;
      }

      try {
        const handoff = await issueSessionHandoff(this.sessionId, {
          source: this.quickForm.source,
          sourceLanguage: toLanguageCode(this.quickForm.sourceLanguage),
          targetLanguage: toLanguageCode(this.quickForm.targetLanguage),
          displayMode
        });
        this.launchDesktopUrl(handoff.deepLinkUrl);
      } catch (error) {
        this.desktopLaunchState = "error";
        this.desktopLaunchMessage = error instanceof Error ? error.message : "无法创建桌面接管凭据";
        this.desktopDownloadPromptOpen = true;
      }
    },

    dismissDesktopDownloadPrompt() {
      this.clearDesktopLaunchWatchers();
      this.desktopDownloadPromptOpen = false;
      if (
        this.desktopLaunchState === "fallback" ||
        this.desktopLaunchState === "launched" ||
        this.desktopLaunchState === "error"
      ) {
        this.desktopLaunchState = "idle";
        this.desktopLaunchMessage = "等待投送到桌面悬浮窗";
      }
    },

    continueWithWebFloating() {
      this.desktopDownloadPromptOpen = false;
      this.selectedDisplayMode = "悬浮字幕";
      this.desktopLaunchState = "idle";
      this.desktopLaunchMessage = "已切回网页悬浮字幕";
    },

    reopenDesktop() {
      if (!this.desktopHandoffUrl) {
        this.openDesktopFloating();
        return;
      }
      this.launchDesktopUrl(this.desktopHandoffUrl);
    },

    async startMode(mode: ProductMode) {
      const blockedSource = mode === "quick" ? this.quickSource.disabled : this.floatingSource.disabled;
      const inputReady =
        mode === "quick"
          ? isSourceInputReady(this.quickForm.source, this.quickInput)
          : isSourceInputReady(this.floatingForm.source, this.floatingInput);
      if (blockedSource || !inputReady || !["setup", "report", "error"].includes(this.modeStates[mode])) return;

      if (mode === "quick" && shouldRefreshDefaultSessionName(this.quickForm.name)) {
        this.quickForm.name = defaultSessionName();
      }

      const requestId = this.startRequestId + 1;
      this.startRequestId = requestId;

      const previousMode = this.activeMode;
      if (previousMode && previousMode !== mode) {
        this.modeStates[previousMode] = "setup";
        this.stopSession("idle");
      }

      this.activeMode = mode;
      this.productMode = mode;
      this.modeStates.quick = mode === "quick" ? "connecting" : "setup";
      this.modeStates.floating = mode === "floating" ? "connecting" : "setup";

      const started = await this.startConfiguredSession(mode, requestId);
      if (!this.isCurrentStart(mode, requestId)) return;

      if (started) {
        this.modeStates[mode] = "running";
      } else {
        this.modeStates[mode] = "error";
        this.activeMode = null;
      }
    },

    pauseMode(mode: ProductMode) {
      if (this.activeMode !== mode || this.modeStates[mode] !== "running") return;
      this.modeStates[mode] = "paused";
      this.pauseSession();
    },

    resumeMode(mode: ProductMode) {
      if (this.activeMode !== mode || this.modeStates[mode] !== "paused") return;
      this.modeStates[mode] = "running";
      this.resumeSession();
    },

    handleMediaPlaybackPaused() {
      if (this.activeMode !== "quick") return;
      if (this.modeStates.quick === "running") this.pauseMode("quick");
    },

    handleMediaPlaybackPlayed() {
      if (this.activeMode !== "quick") return;

      if (this.modeStates.quick === "paused") this.resumeMode("quick");
      else if (
        this.isMediaElementCaptureSource() &&
        this.modeStates.quick === "running" &&
        this.status === "paused"
      ) {
        this.resumeSession();
      }
    },

    askEnd(mode: ProductMode) {
      this.endingMode = mode;
      this.showEndDialog = true;
    },

    cancelEnd() {
      this.showEndDialog = false;
      this.endingMode = null;
    },

    confirmEnd() {
      if (!this.endingMode) return;
      const mode = this.endingMode;
      this.showEndDialog = false;
      this.endingMode = null;

      // 已经收到报告（音频自然结束路径）→ 仅关闭弹窗
      if (this.modeStates[mode] === "report" && this.report) return;

      this.modeStates[mode] = "report";

      const isFixture = isFixtureSession(this.sessionId);

      if (socket && socket.readyState === WebSocket.OPEN && !isFixture) {
        // 优雅结束：发 stop_session 但不立即关闭 socket，等后端完成会后完整纠偏后下发
        // session_report（由 applyServerEvent 处理）。保持 isCurrentStart 有效以接收该事件。
        this.reportId = null;
        this.report = null;
        this.reportError = null;
        this.reportLoading = true;
        socket.send(JSON.stringify({ type: "stop_session" }));
        void this.stopCapture();
        const endingRequestId = this.startRequestId;
        void this.waitForReportReady(mode, endingRequestId);
      } else {
        // 无后端会话（本地 fixture 演示）→ 客户端合成报告，保证“结束→可看”闭环。
        this.startRequestId += 1;
        if (this.activeMode === mode) this.activeMode = null;
        this.buildLocalReport();
        this.stopSession("stopped");
      }
    },

    resetMode(mode: ProductMode) {
      if (this.activeMode) {
        const previousMode = this.activeMode;
        this.activeMode = null;
        this.modeStates[previousMode] = "setup";
        this.stopSession("idle");
      }
      // 无论该模式此前是否在跑，都清掉上一段的报告/字幕/进度，确保是"干净的下一次任务"。
      this.startRequestId += 1;
      this.resetSessionData();
      this.modeStates[mode] = "setup";
      this.productMode = mode;
      this.showEndDialog = false;
      this.endingMode = null;
      if (mode === "quick") {
        pendingFiles.quick = null;
        revokeLocalPreview("quick");
        this.quickForm = createDefaultQuickForm();
        this.quickInput = { ...defaultSourceInputState };
        this.selectedDisplayMode = "逐句对照";
        this.desktopDownloadPromptOpen = false;
        this.desktopHandoffUrl = null;
        this.desktopLaunchState = "idle";
        this.desktopLaunchMessage = "等待投送到桌面悬浮窗";
        this.loadTestVideoFixturePreview();
      } else {
        pendingFiles.floating = null;
        revokeLocalPreview("floating");
        this.floatingForm = createDefaultFloatingForm();
        this.floatingInput = { ...defaultSourceInputState };
      }
    },

    /** 本地测试视频自然播放结束 → 自动收尾出报告，贴近"音频播放完自动结束"的真实路径。 */
    handleFixtureEnded() {
      if (!["running", "paused"].includes(this.modeStates.quick)) return;
      if (!isFixtureSession(this.sessionId)) {
        this.endingMode = "quick";
        this.confirmEnd();
        return;
      }
      this.revealFixtureSegmentsUpTo(currentFixture(this.quickForm.source).durationMs);
      this.modeStates.quick = "report";
      this.startRequestId += 1;
      this.activeMode = null;
      this.buildLocalReport();
      this.stopSession("stopped");
    },

    buildSessionPayload(mode: ProductMode): CreateSessionPayload {
      const form = mode === "quick" ? this.quickForm : this.floatingForm;
      const input = mode === "quick" ? this.quickInput : this.floatingInput;
      const sourceKey = form.source;

      return {
        inputMode: inputModeBySourceKey[sourceKey] ?? "demo",
        sourceLanguage: toLanguageCode(form.sourceLanguage),
        targetLanguage: toLanguageCode(form.targetLanguage),
        productMode: mode,
        sessionName: mode === "quick" ? this.quickForm.name : "悬浮字幕",
        domain: form.domain,
        modelProfile: form.modelProfile,
        sourceKey,
        sourceFileName: input.fileName || undefined,
        sourceUrl: sourceKey === "url" ? input.url.trim() : undefined,
        sourcePermission: input.permissionState,
        ttsEnabled: form.ttsEnabled
      };
    },

    isCurrentStart(mode: ProductMode, requestId: number, sessionId?: string): boolean {
      return (
        this.activeMode === mode &&
        this.startRequestId === requestId &&
        (sessionId === undefined || this.sessionId === sessionId)
      );
    },

    async startConfiguredSession(mode: ProductMode, requestId: number): Promise<boolean> {
      this.ensureTtsErrorHandler();
      this.stopSession("idle");
      this.resetSessionData();
      if (mode === "quick") this.applyQuickLocalFilePreview();
      this.status = "connecting";
      this.errorMessage = null;
      this.ttsErrorMessage = null;
      if ((mode === "quick" ? this.quickForm : this.floatingForm).ttsEnabled) {
        void ttsPlayback.unlock().catch((error: unknown) => {
          this.ttsErrorMessage =
            error instanceof Error ? error.message : "语音播报初始化失败";
        });
      }

      if (mode === "quick" && isFixtureSource(this.quickForm.source)) {
        this.startTestVideoFixtureSession(requestId);
        return true;
      }

      try {
        const session = await createSession(this.buildSessionPayload(mode));

        if (!this.isCurrentStart(mode, requestId)) return false;

        this.sessionId = session.sessionId;

        this.connectSocket(session.sessionId, session.wsToken, mode, requestId);
        return true;
      } catch (error) {
        if (!this.isCurrentStart(mode, requestId)) return false;

        this.status = "error";
        this.errorMessage =
          error instanceof Error ? error.message : "创建会话失败，请确认后端已启动";
        return false;
      }
    },

    stopSession(nextStatus: SessionStatus = "stopped") {
      void this.stopCapture();
      ttsPlayback.stop();
      stopFixtureSpeech();
      pendingMediaElementCapture = false;
      pendingMediaReadyState = null;
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "stop_session" }));
      }
      socket?.close();
      socket = null;
      this.wsConnected = false;
      this.status = nextStatus;
    },

    pauseSession() {
      if (this.status !== "running") return;
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "pause_session" }));
      }
      pauseFixtureSpeech();
      this.status = "paused";
    },

    resumeSession() {
      if (this.status !== "paused") return;
      if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "resume_session" }));
      }
      resumeFixtureSpeech();
      this.status = "running";
    },

    resetSessionData() {
      this.resetTtsPlayback();
      pendingMediaElementCapture = false;
      pendingMediaReadyState = null;
      stopBoundMediaElement();
      estimatedOutputLatencyMs = SUBTITLE_LATENCY_MS;
      recentOutputLatencies.length = 0;
      sampledOutputLatencySegmentIds.clear();
      this.sessionId = null;
      this.status = "idle";
      this.wsConnected = false;
      this.sourceSyncState = { ...defaultSourceSyncState };
      this.mediaUrl = null;
      this.audioUrl = null;
      this.playbackMs = 0;
      this.activeSegmentId = null;
      this.fixtureAppliedRevisionIds = [];
      this.sourceSegments = [];
      this.translationSegments = [];
      this.revisions = [];
      this.errorMessage = null;
      this.reportId = null;
      this.report = null;
      this.reportLoading = false;
      this.reportError = null;
    },

    setTtsMuted(muted: boolean) {
      this.ttsMuted = muted;
      ttsPlayback.setMuted(muted);
    },

    setTtsVolume(volume: number) {
      this.ttsVolume = Math.min(1, Math.max(0, volume));
      ttsPlayback.setVolume(this.ttsVolume);
    },

    resetTtsPlayback() {
      void ttsPlayback.close();
      stopFixtureSpeech();
      this.ttsErrorMessage = null;
      ttsPlayback.setVolume(this.ttsVolume);
      ttsPlayback.setMuted(this.ttsMuted);
    },

    handleTtsError(message: string) {
      this.ttsErrorMessage = message;
    },

    loadTestVideoFixturePreview() {
      const fixture = currentFixture(this.quickForm.source);
      this.mediaUrl = fixture.videoUrl;
      this.audioUrl = fixture.audioUrl;
      this.playbackMs = 0;
      this.activeSegmentId = fixture.segments[0]?.segmentId ?? null;
      this.fixtureAppliedRevisionIds = [];
      this.sourceSegments = createFixtureSourceSegments(fixture);
      this.translationSegments = createFixtureTranslationSegments(fixture);
      this.revisions = [];
      this.sourceSyncState = {
        status: "listening",
        lagMs: 0,
        message: "本地测试素材已就绪"
      };
    },

    clearLocalMediaPreview() {
      stopBoundMediaElement();
      this.mediaUrl = null;
      this.audioUrl = null;
      this.playbackMs = 0;
      this.activeSegmentId = null;
      this.fixtureAppliedRevisionIds = [];
      this.sourceSegments = [];
      this.translationSegments = [];
      this.revisions = [];
      this.sourceSyncState = { ...defaultSourceSyncState };
    },

    applyQuickLocalFilePreview() {
      const previewUrl = localPreviewUrls.quick;
      if (this.quickForm.source === "video-file") {
        this.mediaUrl = previewUrl;
        this.audioUrl = null;
      } else if (this.quickForm.source === "audio-file") {
        this.mediaUrl = null;
        this.audioUrl = previewUrl;
      }
    },

    setMediaElement(element: HTMLMediaElement | null) {
      mediaElement = element;
      if (element && pendingMediaReadyState && pendingMediaElementCapture && !captureStarted) {
        void this.startMediaElementStreaming(pendingMediaReadyState);
      }
    },

    startTestVideoFixtureSession(requestId: number) {
      if (!this.isCurrentStart("quick", requestId)) return;

      const fixture = currentFixture(this.quickForm.source);
      this.sessionId = fixtureSessionId(fixture);
      this.wsConnected = true;
      this.status = "running";
      lastFixtureSpeechKey = null;
      // 媒体就绪，但字幕不再一次性灌入：跟随左侧播放进度逐句"听到一句、出一句"。
      this.mediaUrl = fixture.videoUrl;
      this.audioUrl = fixture.audioUrl;
      this.revealFixtureSegmentsUpTo(0);
      this.sourceSyncState = {
        status: "syncing",
        lagMs: 0,
        message: "本地素材已就绪，播放即开始实时同传"
      };
    },

    syncPlayback(currentTimeSeconds: number) {
      if (this.status !== "running" || this.modeStates.quick !== "running") return;
      const playbackMs = Math.max(0, Math.round(currentTimeSeconds * 1000));
      const previousPlaybackMs = this.playbackMs;
      this.playbackMs = playbackMs;
      if (!isFixtureSession(this.sessionId)) {
        this.updateActiveSegmentFromPlayback(playbackMs, playbackMs + 500 < previousPlaybackMs);
        return;
      }

      this.revealFixtureSegmentsUpTo(playbackMs);
      this.sourceSyncState = {
        status: "syncing",
        lagMs: 0,
        message: `本地素材同步 ${formatPlaybackTime(playbackMs)}`
      };
    },

    syncFixturePlayback(currentTimeSeconds: number) {
      this.syncPlayback(currentTimeSeconds);
    },

    updateActiveSegmentFromPlayback(playbackMs?: number, allowBackward = false) {
      if (isFixtureSession(this.sessionId)) return;
      const currentPlaybackMs = playbackMs ?? this.playbackMs;
      const candidateId = activeSegmentForPlayback(
        this.sourceSegments,
        this.translationSegments,
        currentPlaybackMs,
        estimatedOutputLatencyMs
      );
      const nextActiveId = candidateId;
      if (
        shouldKeepCurrentActiveSegment(
          this.sourceSegments,
          this.translationSegments,
          this.activeSegmentId,
          nextActiveId,
          currentPlaybackMs,
          allowBackward,
          estimatedOutputLatencyMs,
          ACTIVE_PENDING_TRANSLATION_HOLD_MS
        )
      ) {
        return;
      }
      this.activeSegmentId = nextActiveId;
    },

    isMediaElementCaptureSource(): boolean {
      return (
        this.activeMode === "quick" &&
        !isFixtureSession(this.sessionId) &&
        fileSourceKeys.has(this.quickForm.source)
      );
    },

    /**
     * 按播放进度幂等重算本地测试视频应显示的字幕：
     * 同传有 1~2s 产出延迟——音频说到 startMs 的句子，约 SUBTITLE_LATENCY_MS 后才在右侧产出。
     * 故以「有效进度 effectiveMs = 播放进度 - 延迟」决定显示/活动句：视频未播放(进度0)时右侧为空，
     * 播放后字幕滞后约 1.5s 逐句滚出。刚产出的一句标记 partial（流式光标）；atMs 已到的纠偏即时套用。
     * 幂等设计保证拖动进度条前后都能正确显示/回退，不残留旧状态。
     */
    revealFixtureSegmentsUpTo(playbackMs: number) {
      const fixture = currentFixture(this.quickForm.source);
      const playbackState = fixturePlaybackState(fixture, playbackMs, SUBTITLE_LATENCY_MS);
      this.sourceSegments = playbackState.sourceSegments;
      this.translationSegments = playbackState.translationSegments;
      this.revisions = playbackState.revisions;
      this.fixtureAppliedRevisionIds = playbackState.fixtureAppliedRevisionIds;
      this.activeSegmentId = playbackState.activeSegmentId;
      this.playbackMs = playbackMs;

      if (this.quickForm.ttsEnabled && playbackState.speech) {
        speakFixtureTranslation(
          playbackState.speech.segmentId,
          playbackState.speech.text,
          playbackState.speech.targetLanguage,
          this.ttsVolume,
          this.ttsMuted,
          (message) => {
            this.ttsErrorMessage = message;
          }
        );
      }
    },

    connectSocket(sessionId: string, wsToken: string, mode: ProductMode, requestId: number) {
      socket?.close();
      // 判定本次会话是否需要前端实时采集音频（麦克风/标签页/屏幕/系统音频）。
      const sourceKey = mode === "quick" ? this.quickForm.source : this.floatingForm.source;
      pendingCaptureKind = captureKindBySource[sourceKey] ?? null;
      pendingMediaElementCapture = mode === "quick" && fileSourceKeys.has(sourceKey);
      pendingMediaReadyState = null;
      captureStarted = false;
      let connection: WebSocket;
      connection = createSessionSocket(
        sessionId,
        {
          onOpen: () => {
            if (!this.isCurrentStart(mode, requestId, sessionId)) {
              connection.close();
              return;
            }
            this.wsConnected = true;
            this.status = "running";
          },
          onClose: () => {
            if (!this.isCurrentStart(mode, requestId, sessionId)) return;
            this.wsConnected = false;
            if (this.status === "running") this.status = "stopped";
          },
          onError: (message) => {
            if (!this.isCurrentStart(mode, requestId, sessionId)) return;
            this.status = "error";
            this.errorMessage = message;
          },
          onEvent: (event) => {
            if (this.isCurrentStart(mode, requestId, sessionId)) this.applyServerEvent(event);
          }
        },
        { autoStart: true, token: wsToken }
      );
      socket = connection;
    },

    applyServerEvent(event: ServerEvent) {
      if (event.type === "session_started") {
        this.sessionId = event.sessionId;
        return;
      }

      const liveEventsLocked =
        this.reportLoading || Boolean(this.activeMode && this.modeStates[this.activeMode] === "report");

      if (event.type === "source_sync_state") {
        if (liveEventsLocked) return;
        if (pendingMediaElementCapture && event.state.status === "ready") {
          pendingMediaReadyState = event.state;
          void this.startMediaElementStreaming(event.state);
          return;
        }
        this.sourceSyncState = event.state;
        if (typeof event.state.sourceMs === "number") {
          this.playbackMs = event.state.sourceMs;
        }
        // 后端管线就绪（pcm_queue 已建）后再开始推流，避免早期帧被丢弃。
        if (pendingCaptureKind && !captureStarted && event.state.status === "ready") {
          captureStarted = true;
          void this.startCaptureStreaming(pendingCaptureKind);
        }
        return;
      }

      if (event.type === "transcript_segment") {
        if (liveEventsLocked) return;
        this.sourceSegments = upsertSegment(this.sourceSegments, event.segment);
        this.updateActiveSegmentFromPlayback();
        return;
      }

      if (event.type === "translation_segment") {
        if (liveEventsLocked) return;
        recordOutputLatency(event.segment, this.playbackMs);
        this.translationSegments = upsertSegment(this.translationSegments, event.segment);
        this.updateActiveSegmentFromPlayback();
        return;
      }

      if (event.type === "audio_segment") {
        if (liveEventsLocked) return;
        this.ensureTtsErrorHandler();
        const form = this.activeMode === "floating" ? this.floatingForm : this.quickForm;
        if (!form.ttsEnabled) return;
        void ttsPlayback.enqueue({
          segmentId: event.segmentId,
          audioBase64: event.audioBase64,
          sampleRate: event.sampleRate,
          segmentSequence: event.segmentSequence
        });
        return;
      }

      if (event.type === "revision_event") {
        if (liveEventsLocked) return;
        this.revisions = [event.revision, ...this.revisions].slice(0, 20);
        this.markRevised(event.revision);
        return;
      }

      if (event.type === "session_report") {
        // 会话自然结束或 stop_session 后，后端完成会后完整纠偏并下发报告 id。
        // 适用于「音频播放完自动结束」与「用户手动结束」两条路径。
        this.reportId = event.reportId;
        const mode = this.activeMode ?? "quick";
        this.modeStates[mode] = "report";
        this.status = "stopped";
        this.activeMode = null;
        ttsPlayback.stop();
        void this.loadReport();
        return;
      }

      if (event.type === "error") {
        this.status = "error";
        this.errorMessage = event.message;
      }
    },

    markRevised(revision: RevisionEvent) {
      const updateStatus = (segment: SubtitleSegment): SubtitleSegment =>
        revision.targetSegmentIds.includes(segment.segmentId)
          ? { ...segment, status: "revised" as SegmentStatus }
          : segment;

      this.sourceSegments = this.sourceSegments.map(updateStatus);
      this.translationSegments = this.translationSegments.map((segment) =>
        revision.targetSegmentIds.includes(segment.segmentId)
          ? {
              ...segment,
              text: revision.afterText,
              status: "revised" as SegmentStatus,
              originalText: revision.beforeText,
              revisionReason: revision.reason
            }
          : segment
      );
    },

    async waitForReportReady(mode: ProductMode, requestId: number) {
      const sessionId = this.sessionId;
      if (!sessionId) return;

      const deadline = Date.now() + REPORT_READY_TIMEOUT_MS;
      while (Date.now() < deadline) {
        if (this.startRequestId !== requestId || !this.reportLoading) return;
        if (this.report) return;

        try {
          const report = await getSessionReport(sessionId);
          if (this.startRequestId !== requestId || !this.reportLoading) return;
          this.report = report;
          this.reportId = report.reportId;
          this.reportError = null;
          this.reportLoading = false;
          this.status = "stopped";
          if (this.activeMode === mode) this.activeMode = null;
          this.stopSession("stopped");
          return;
        } catch {
          await wait(REPORT_POLL_INTERVAL_MS);
        }
      }

      if (this.startRequestId === requestId && this.reportLoading && !this.report) {
        this.reportLoading = false;
        this.reportError = "报告仍在生成中，请稍后重试下载";
        if (this.activeMode === mode) this.activeMode = null;
        this.stopSession("stopped");
      }
    },

    async loadReport() {
      if (!this.sessionId || !this.reportId) return;
      this.reportLoading = true;
      this.reportError = null;
      try {
        this.report = await getSessionReport(this.sessionId);
      } catch (error) {
        this.reportError = error instanceof Error ? error.message : "报告拉取失败";
      } finally {
        this.reportLoading = false;
      }
    },

    /** 实时采集类音源：取流 → 16k PCM → WS 二进制推送。后端管线就绪后调用。 */
    async startCaptureStreaming(kind: CaptureSourceKind) {
      try {
        const stream = await acquireStream(kind);
        if (!socket || socket.readyState !== WebSocket.OPEN) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        audioCapture = await startAudioCapture(stream, {
          frameMs: 40,
          onChunk: (chunk) => {
            this.handleCapturedAudioChunk(chunk);
          },
          onEnded: () => {
            // 用户在系统选择器中停止共享 → 通知后端收尾并出报告。
            if (socket && socket.readyState === WebSocket.OPEN) {
              socket.send(JSON.stringify({ type: "audio_end" }));
            }
          },
          onError: (message) => {
            this.errorMessage = message;
          }
        });
      } catch (error) {
        this.status = "error";
        this.errorMessage = error instanceof Error ? error.message : "音频采集启动失败";
        if (socket && socket.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify({ type: "audio_end" }));
        }
      }
    },

    /** 上传视频/音频：从正在播放的媒体元素采集同一份音频，保证模型输入与画面同源。 */
    async startMediaElementStreaming(readyState: SourceSyncState) {
      if (!this.isMediaElementCaptureSource()) {
        this.sourceSyncState = readyState;
        return;
      }
      if (!mediaElement) {
        pendingMediaReadyState = readyState;
        return;
      }
      if (!socket || socket.readyState !== WebSocket.OPEN) return;

      try {
        if (!captureStarted) {
          captureStarted = true;
          audioCapture = await startMediaElementAudioCapture(mediaElement, {
            frameMs: 40,
            monitorMuted: this.quickForm.ttsEnabled,
            onChunk: (chunk) => {
              this.handleCapturedAudioChunk(chunk);
            },
            onClock: (clock) => {
              if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify({ type: "media_clock", ...clock }));
              }
            },
            onEnded: () => {
              if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send(JSON.stringify({ type: "audio_end" }));
              }
            },
            onError: (message) => {
              this.errorMessage = message;
            }
          });
        }
        pendingMediaReadyState = null;
        this.sourceSyncState = readyState;
        this.status = "running";
        this.modeStates.quick = "running";
        try {
          await mediaElement.play();
        } catch {
          // 浏览器可能阻止带声音自动播放；采集链路已就绪，用户手动播放即可同步推流。
        }
      } catch (error) {
        captureStarted = false;
        this.status = "error";
        this.modeStates.quick = "error";
        this.errorMessage =
          error instanceof Error ? error.message : "媒体元素音频采集启动失败";
        if (socket && socket.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify({ type: "audio_end" }));
        }
      }
    },

    handleCapturedAudioChunk(chunk: ArrayBuffer) {
      const result = sendAudioChunk(socket, WebSocket.OPEN, chunk);
      if (result.state === "backpressured") {
        this.sourceSyncState = {
          status: "lagging",
          lagMs: result.estimatedLagMs,
          message: `浏览器发送缓冲约 ${result.estimatedLagMs} ms，已丢弃当前音频帧`
        };
        return;
      }
      if (result.state === "sent" && this.sourceSyncState.message.startsWith("浏览器发送缓冲")) {
        this.sourceSyncState = {
          status: "syncing",
          lagMs: 0,
          message: "浏览器发送缓冲已恢复"
        };
      }
    },

    async stopCapture() {
      pendingCaptureKind = null;
      pendingMediaElementCapture = false;
      pendingMediaReadyState = null;
      captureStarted = false;
      if (audioCapture) {
        const capture = audioCapture;
        audioCapture = null;
        await capture.stop();
      }
    },

    /** 下载报告：真实后端会话走后端直链（含中文文件名）；本地演示走客户端渲染。 */
    downloadReport(format: ReportFormat) {
      if (this.sessionId && this.reportId) {
        triggerDownload(reportDownloadUrl(this.sessionId, format));
        return;
      }
      if (this.report) {
        const { body, mime, ext } = renderReportClient(this.report, format);
        const blob = new Blob([body], { type: mime });
        const url = URL.createObjectURL(blob);
        triggerDownload(url, `${this.report.sessionName || "同传报告"}.${ext}`);
        window.setTimeout(() => URL.revokeObjectURL(url), 4000);
      }
    },

    /** 本地演示（fixture，无后端）合成一份报告，保证“结束→可看可下载”闭环。 */
    buildLocalReport() {
      const segs = this.transcriptPairs
        .filter((pair) => pair.segmentId)
        .map((pair, index) => ({
          segmentId: pair.segmentId ?? `local-${index}`,
          startMs: 0,
          endMs: 0,
          timecode: pair.time,
          sourceText: pair.source,
          liveTranslation: pair.originalTranslation ?? pair.translation,
          finalTranslation: pair.translation,
          revisedRealtime: pair.state === "revised"
        }));
      const finalRevisions = this.revisions.map((rev) => ({
        segmentId: rev.targetSegmentIds[0] ?? "",
        beforeText: rev.beforeText,
        afterText: rev.afterText,
        reason: rev.reason,
        stage: "实时"
      }));
      this.report = {
        reportId: "",
        sessionId: this.sessionId ?? "",
        sessionName: this.quickForm.name || "本地演示报告",
        domain: this.quickForm.domain,
        sourceLanguage: this.quickForm.sourceLanguage,
        targetLanguage: this.quickForm.targetLanguage,
        durationMs: this.playbackMs,
        durationText: formatPlaybackTime(this.playbackMs),
        generatedAt: new Date().toLocaleString(),
        summary: `本地测试素材演示：共 ${segs.length} 句，含 ${finalRevisions.length} 处自动纠偏。`,
        qualityNotes: "本地演示报告由前端依据测试素材合成，未经后端大模型完整纠偏。",
        glossaryHits: [],
        metrics: {
          segments: segs.length,
          realtimeRevisions: finalRevisions.length,
          finalRevisions: 0,
          durationText: formatPlaybackTime(this.playbackMs)
        },
        segments: segs,
        finalRevisions,
        realtimeRevisions: finalRevisions,
        correctionModel: null,
        correctionStatus: "skipped",
        correctionError: "本地演示报告未调用后端会后完整纠偏。",
        correctionElapsedMs: 0
      };
      this.reportId = null;
      this.reportLoading = false;
    }
  }
});
