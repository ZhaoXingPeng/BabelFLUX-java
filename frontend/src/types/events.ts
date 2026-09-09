export type SessionStatus = "idle" | "connecting" | "running" | "paused" | "stopped" | "error";

export type SegmentStatus = "partial" | "final" | "revised";

export interface SourceSyncState {
  status: "listening" | "syncing" | "ready" | "lagging" | "missing" | "recovered";
  lagMs: number;
  message: string;
  sourceMs?: number | null;
}

export interface SubtitleSegment {
  segmentId: string;
  text: string;
  language: string;
  startMs: number;
  endMs: number;
  status: SegmentStatus;
  originalText?: string;
  revisionReason?: string;
}

export interface RevisionEvent {
  revisionId: string;
  targetSegmentIds: string[];
  beforeText: string;
  afterText: string;
  reason: string;
  confidence: number;
}

export interface AudioSegmentEvent {
  type: "audio_segment";
  segmentId: string;
  audioBase64: string;
  sampleRate: number;
  /** Monotonic per-session speech generation used to reject interrupted late audio. */
  segmentSequence?: number;
}

export type ServerEvent =
  | { type: "session_started"; sessionId: string }
  | { type: "source_sync_state"; state: SourceSyncState }
  | { type: "transcript_segment"; segment: SubtitleSegment }
  | { type: "translation_segment"; segment: SubtitleSegment }
  | AudioSegmentEvent
  | { type: "revision_event"; revision: RevisionEvent }
  | { type: "session_report"; reportId: string; correctionStatus?: string; historyStatus?: string }
  | { type: "error"; message: string };
