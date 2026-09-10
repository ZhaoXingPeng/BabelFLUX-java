/**
 * Browser WebSocket buffering is independent from the server-side PCM queue.
 * Keep the browser threshold small so stale audio is discarded before it can
 * make an otherwise healthy realtime session feel delayed.
 */
export const MAX_AUDIO_SOCKET_BUFFER_BYTES = 32_000;
const PCM_16K_MONO_BYTES_PER_MS = 32;

export interface AudioSocket {
  readyState: number;
  bufferedAmount: number;
  send(data: ArrayBuffer): void;
}

export type AudioChunkOutcome =
  | { state: "sent" }
  | { state: "not_open" }
  | { state: "backpressured"; bufferedAmount: number; estimatedLagMs: number };

export function sendAudioChunk(
  socket: AudioSocket | null,
  openState: number,
  chunk: ArrayBuffer
): AudioChunkOutcome {
  if (!socket || socket.readyState !== openState) return { state: "not_open" };

  const bufferedAmount = Math.max(0, socket.bufferedAmount);
  if (bufferedAmount > MAX_AUDIO_SOCKET_BUFFER_BYTES) {
    return {
      state: "backpressured",
      bufferedAmount,
      estimatedLagMs: Math.ceil(bufferedAmount / PCM_16K_MONO_BYTES_PER_MS)
    };
  }

  socket.send(chunk);
  return { state: "sent" };
}
