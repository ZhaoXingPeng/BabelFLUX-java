import { describe, expect, it, vi } from "vitest";
import {
  MAX_AUDIO_SOCKET_BUFFER_BYTES,
  sendAudioChunk,
  type AudioSocket
} from "./audioBackpressure";

function socket(bufferedAmount: number, readyState = 1): AudioSocket {
  return { readyState, bufferedAmount, send: vi.fn() };
}

describe("browser audio backpressure", () => {
  it("sends a PCM frame while the browser buffer is within one second", () => {
    const target = socket(MAX_AUDIO_SOCKET_BUFFER_BYTES);

    const result = sendAudioChunk(target, 1, new ArrayBuffer(1_280));

    expect(result).toEqual({ state: "sent" });
    expect(target.send).toHaveBeenCalledTimes(1);
  });

  it("drops the current PCM frame and reports visible lag above one second", () => {
    const target = socket(MAX_AUDIO_SOCKET_BUFFER_BYTES + 1_280);

    const result = sendAudioChunk(target, 1, new ArrayBuffer(1_280));

    expect(result).toEqual({
      state: "backpressured",
      bufferedAmount: 33_280,
      estimatedLagMs: 1_040
    });
    expect(target.send).not.toHaveBeenCalled();
  });

  it("does not send after a socket closes", () => {
    const target = socket(0, 3);

    expect(sendAudioChunk(target, 1, new ArrayBuffer(1_280))).toEqual({ state: "not_open" });
    expect(target.send).not.toHaveBeenCalled();
  });
});
