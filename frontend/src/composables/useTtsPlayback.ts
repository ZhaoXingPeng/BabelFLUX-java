export interface TtsPlaybackOptions {
  onError?: (message: string) => void;
}

export interface TtsAudioSegment {
  segmentId: string;
  audioBase64: string;
  sampleRate: number;
  segmentSequence?: number;
}

const DEFAULT_VOLUME = 0.5;

function base64ToBytes(value: string): Uint8Array {
  const binary = window.atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function pcm16ToAudioBuffer(context: AudioContext, bytes: Uint8Array, sampleRate: number) {
  const sampleCount = Math.floor(bytes.byteLength / 2);
  const audioBuffer = context.createBuffer(1, sampleCount, sampleRate);
  const channel = audioBuffer.getChannelData(0);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let index = 0; index < sampleCount; index += 1) {
    channel[index] = view.getInt16(index * 2, true) / 32768;
  }
  return audioBuffer;
}

export function createTtsPlayback(options: TtsPlaybackOptions = {}) {
  let context: AudioContext | null = null;
  let gainNode: GainNode | null = null;
  let nextStartTime = 0;
  let muted = false;
  let volume = DEFAULT_VOLUME;
  let activeSegmentId: string | null = null;
  let activeSegmentSequence: number | null = null;
  const sources = new Set<AudioBufferSourceNode>();

  function getAudioContextCtor(): typeof AudioContext {
    const audioWindow = window as typeof window & {
      webkitAudioContext?: typeof AudioContext;
    };
    return audioWindow.AudioContext ?? audioWindow.webkitAudioContext!;
  }

  async function init(sampleRate: number) {
    if (context) return context;
    const AudioContextCtor = getAudioContextCtor();
    context = new AudioContextCtor({ sampleRate });
    gainNode = context.createGain();
    gainNode.gain.value = muted ? 0 : volume;
    gainNode.connect(context.destination);
    return context;
  }

  async function unlock(sampleRate = 24000) {
    const audioContext = await init(sampleRate);
    if (audioContext.state === "suspended") {
      await audioContext.resume();
    }
  }

  async function enqueue(segment: TtsAudioSegment) {
    try {
      const audioContext = await init(segment.sampleRate);
      if (audioContext.state === "suspended") {
        await audioContext.resume();
      }
      if (!acceptSegment(segment)) return;
      const bytes = base64ToBytes(segment.audioBase64);
      const buffer = pcm16ToAudioBuffer(audioContext, bytes, segment.sampleRate);
      const source = audioContext.createBufferSource();
      source.buffer = buffer;
      source.connect(gainNode!);
      source.addEventListener(
        "ended",
        () => {
          sources.delete(source);
        },
        { once: true }
      );

      const now = audioContext.currentTime;
      const startAt = Math.max(now, nextStartTime);
      source.start(startAt);
      nextStartTime = startAt + buffer.duration;
      sources.add(source);
    } catch (error) {
      options.onError?.(error instanceof Error ? error.message : "语音播报失败");
    }
  }

  function setVolume(nextVolume: number) {
    volume = Math.min(1, Math.max(0, nextVolume));
    if (!muted && gainNode) gainNode.gain.value = volume;
  }

  function setMuted(nextMuted: boolean) {
    muted = nextMuted;
    if (gainNode) gainNode.gain.value = muted ? 0 : volume;
  }

  function stop() {
    stopScheduledSources();
    activeSegmentId = null;
    activeSegmentSequence = null;
  }

  /**
   * A new sentence supersedes queued speech. The backend provides a monotonic
   * sequence, so audio chunks from an interrupted sentence cannot resume later.
   */
  function acceptSegment(segment: TtsAudioSegment) {
    const sequence = segment.segmentSequence;
    if (Number.isInteger(sequence) && sequence! >= 0) {
      if (activeSegmentSequence !== null) {
        if (sequence! < activeSegmentSequence) return false;
        if (sequence! > activeSegmentSequence) stopScheduledSources();
        if (sequence === activeSegmentSequence && segment.segmentId !== activeSegmentId) return false;
      } else if (activeSegmentId !== null && activeSegmentId !== segment.segmentId) {
        stopScheduledSources();
      }
      activeSegmentSequence = sequence!;
      activeSegmentId = segment.segmentId;
      return true;
    }

    // During a rolling deployment, do not let a legacy event without a
    // sequence interrupt a newer sequenced generation.
    if (activeSegmentSequence !== null && segment.segmentId !== activeSegmentId) return false;

    // Older backends do not include segmentSequence. Keep their behavior safe
    // by treating a new segmentId as a new generation.
    if (activeSegmentId !== null && activeSegmentId !== segment.segmentId) stopScheduledSources();
    activeSegmentId = segment.segmentId;
    activeSegmentSequence = null;
    return true;
  }

  function stopScheduledSources() {
    for (const source of sources) {
      try {
        source.stop();
      } catch {
        // Source may already be stopped.
      }
    }
    sources.clear();
    if (context) nextStartTime = context.currentTime;
  }

  async function close() {
    stop();
    if (context) {
      const current = context;
      context = null;
      gainNode = null;
      await current.close().catch(() => undefined);
    }
  }

  return {
    enqueue,
    unlock,
    setVolume,
    setMuted,
    stop,
    close
  };
}
