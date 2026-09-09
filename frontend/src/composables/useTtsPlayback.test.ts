import { beforeEach, describe, expect, it, vi } from "vitest";
import { createTtsPlayback } from "./useTtsPlayback";

interface MockSource {
  start: ReturnType<typeof vi.fn>;
  stop: ReturnType<typeof vi.fn>;
  connect: ReturnType<typeof vi.fn>;
  addEventListener: ReturnType<typeof vi.fn>;
  buffer: unknown;
}

const sources: MockSource[] = [];
let gainNode: { gain: { value: number }; connect: ReturnType<typeof vi.fn> };

class MockAudioContext {
  state = "running";
  currentTime = 0;
  destination = {};
  createGain = vi.fn(() => gainNode);
  createBuffer = vi.fn((_channels: number, length: number, sampleRate: number) => ({
    duration: length / sampleRate,
    getChannelData: () => new Float32Array(length)
  }));
  createBufferSource = vi.fn(() => {
    const source: MockSource = {
      start: vi.fn(),
      stop: vi.fn(),
      connect: vi.fn(),
      addEventListener: vi.fn(),
      buffer: null
    };
    sources.push(source);
    return source;
  });
  resume = vi.fn().mockResolvedValue(undefined);
  close = vi.fn().mockResolvedValue(undefined);
}

describe("createTtsPlayback", () => {
  beforeEach(() => {
    sources.length = 0;
    gainNode = { gain: { value: 0 }, connect: vi.fn() };
    vi.stubGlobal("AudioContext", MockAudioContext);
  });

  it("queues pcm16 base64 audio and controls gain", async () => {
    const playback = createTtsPlayback();

    await playback.enqueue({
      segmentId: "seg-1",
      audioBase64: "AQIDBA==",
      sampleRate: 24000
    });

    expect(sources).toHaveLength(1);
    expect(sources[0].connect).toHaveBeenCalledWith(gainNode);
    expect(sources[0].start).toHaveBeenCalledWith(0);

    playback.setVolume(0.25);
    expect(gainNode.gain.value).toBe(0.25);

    playback.setMuted(true);
    expect(gainNode.gain.value).toBe(0);

    playback.setMuted(false);
    expect(gainNode.gain.value).toBe(0.25);
  });

  it("stops scheduled sources", async () => {
    const playback = createTtsPlayback();
    await playback.enqueue({ segmentId: "seg-1", audioBase64: "AQIDBA==", sampleRate: 24000 });

    playback.stop();

    expect(sources[0].stop).toHaveBeenCalled();
  });

  it("interrupts old speech for a newer segment and rejects late audio from the old segment", async () => {
    const playback = createTtsPlayback();

    await playback.enqueue({
      segmentId: "seg-1",
      segmentSequence: 1,
      audioBase64: "AQIDBA==",
      sampleRate: 24000
    });
    await playback.enqueue({
      segmentId: "seg-1",
      segmentSequence: 1,
      audioBase64: "AQIDBA==",
      sampleRate: 24000
    });
    await playback.enqueue({
      segmentId: "seg-2",
      segmentSequence: 2,
      audioBase64: "AQIDBA==",
      sampleRate: 24000
    });
    await playback.enqueue({
      segmentId: "seg-1",
      segmentSequence: 1,
      audioBase64: "AQIDBA==",
      sampleRate: 24000
    });

    expect(sources).toHaveLength(3);
    expect(sources[0].stop).toHaveBeenCalledTimes(1);
    expect(sources[1].stop).toHaveBeenCalledTimes(1);
    expect(sources[2].start).toHaveBeenCalledWith(0);
  });
});
