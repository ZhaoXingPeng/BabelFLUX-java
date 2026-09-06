package com.babelflux.backend.service;

import com.babelflux.backend.config.BabelFluxProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Starts a constrained ffmpeg process that converts a remote media URL to 16 kHz mono PCM. */
@Service
public class MediaPcmSource {
    static final int SAMPLE_RATE = 16_000;
    static final int FRAME_MS = 40;
    static final int FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2;

    private final BabelFluxProperties properties;

    public MediaPcmSource(BabelFluxProperties properties) { this.properties = properties; }

    public Stream open(String source) {
        validateRemoteUrl(source);
        String executable = properties.getFfmpegPath() == null || properties.getFfmpegPath().isBlank()
                ? "ffmpeg" : properties.getFfmpegPath();
        List<String> command = List.of(executable, "-nostdin", "-hide_banner", "-loglevel", "error",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-i", source, "-vn", "-ac", "1", "-ar", String.valueOf(SAMPLE_RATE),
                "-f", "s16le", "pipe:1");
        try {
            Process process = new ProcessBuilder(command).start();
            Thread stderrDrainer = Thread.startVirtualThread(() -> drain(process.getErrorStream()));
            return new Stream(process, process.getInputStream(), stderrDrainer);
        } catch (IOException error) {
            throw new MediaDecodeException("无法启动 ffmpeg 媒体解码器", error);
        }
    }

    void validateRemoteUrl(String source) {
        if (source == null || source.isBlank()) throw new MediaDecodeException("在线媒体 URL 不能为空");
        URI uri;
        try {
            uri = new URI(source.trim());
        } catch (URISyntaxException error) {
            throw new MediaDecodeException("在线媒体 URL 格式无效", error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            throw new MediaDecodeException("在线媒体 URL 仅支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new MediaDecodeException("在线媒体 URL 缺少 host");
        host = normalizeHost(host);
        if (!allowed(host)) throw new MediaDecodeException("在线媒体 URL host 不在白名单内");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (forbidden(address)) throw new MediaDecodeException("在线媒体 URL 不允许访问内网或本机地址");
            }
        } catch (IOException error) {
            throw new MediaDecodeException("在线媒体 URL host 无法解析", error);
        }
    }

    private boolean allowed(String host) {
        String raw = properties.getAllowedMediaHosts();
        if (raw == null || raw.isBlank()) return !localhost(host);
        return Arrays.stream(raw.split(","))
                .map(MediaPcmSource::normalizeHost)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> value.startsWith("*.")
                        ? host.endsWith(value.substring(1)) && !host.equals(value.substring(2))
                : host.equals(value));
    }

    boolean isAllowedHost(String host) { return host != null && allowed(normalizeHost(host)); }

    private static String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (normalized.length() >= 2 && normalized.charAt(0) == '['
                && normalized.charAt(normalized.length() - 1) == ']') {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean localhost(String host) {
        return host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean forbidden(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || isUniqueLocalAddress(address)
                || isMappedPrivateIpv4(address) || address.isMulticastAddress();
    }

    private static boolean isUniqueLocalAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static boolean isMappedPrivateIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        if ((bytes[10] & 0xff) != 0xff || (bytes[11] & 0xff) != 0xff) return false;
        int first = bytes[12] & 0xff;
        int second = bytes[13] & 0xff;
        return first == 10 || first == 127 || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    private static void drain(InputStream stream) {
        try (stream) { stream.transferTo(java.io.OutputStream.nullOutputStream()); }
        catch (IOException ignored) { }
    }

    public static final class Stream implements AutoCloseable {
        private final Process process;
        private final InputStream pcm;
        private final Thread stderrDrainer;

        private Stream(Process process, InputStream pcm, Thread stderrDrainer) {
            this.process = process;
            this.pcm = pcm;
            this.stderrDrainer = stderrDrainer;
        }

        public int read(byte[] frame) throws IOException { return pcm.read(frame); }
        public int read(byte[] frame, int offset, int length) throws IOException {
            return pcm.read(frame, offset, length);
        }
        public int awaitExit() throws InterruptedException { return process.waitFor(); }
        public boolean isAlive() { return process.isAlive(); }
        public int exitCode() { return process.exitValue(); }

        @Override
        public void close() {
            try { pcm.close(); } catch (IOException ignored) { }
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            stderrDrainer.interrupt();
        }
    }

    public static class MediaDecodeException extends RuntimeException {
        public MediaDecodeException(String message) { super(message); }
        public MediaDecodeException(String message, Throwable cause) { super(message, cause); }
    }
}
