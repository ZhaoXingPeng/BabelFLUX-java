package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.config.BabelFluxProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MediaPcmSourceTest {
    @Test
    void validatesSchemesAndBlocksLocalNetworkTargets() {
        MediaPcmSource source = new MediaPcmSource(new BabelFluxProperties());

        assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.validateRemoteUrl("file:///tmp/audio.wav"));
        assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.validateRemoteUrl("http://localhost/audio"));
        assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.validateRemoteUrl("http://127.0.0.1/audio"));
        assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.validateRemoteUrl("http://[::1]/audio"));
        assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.validateRemoteUrl("http://[fd00::1]/audio"));
        assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.validateRemoteUrl("http://[::ffff:127.0.0.1]/audio"));
    }

    @Test
    void appliesExactAndSubdomainAllowListsWithoutAllowingTheBareWildcardSuffix() {
        BabelFluxProperties properties = new BabelFluxProperties();
        properties.setAllowedMediaHosts("media.example, *.cdn.example");
        MediaPcmSource source = new MediaPcmSource(properties);

        assertTrue(source.isAllowedHost("MEDIA.EXAMPLE."));
        assertTrue(source.isAllowedHost("voice.cdn.example"));
        assertFalse(source.isAllowedHost("cdn.example"));
        assertFalse(source.isAllowedHost("evil.example"));
    }

    @Test
    void startsConfiguredDecoderAndSurfacesItsExitCodeAndEof() throws Exception {
        BabelFluxProperties properties = new BabelFluxProperties();
        properties.setAllowedMediaHosts("192.0.2.1");
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        properties.setFfmpegPath(java.toString());
        MediaPcmSource source = new MediaPcmSource(properties);

        try (MediaPcmSource.Stream stream = source.open("https://192.0.2.1/audio")) {
            byte[] output = new byte[8];
            assertEquals(-1, stream.read(output));
            assertTrue(stream.awaitExit() != 0);
            assertFalse(stream.isAlive());
            assertArrayEquals(new byte[8], output);
        }
    }

    @Test
    void reportsMissingDecoderExecutable() {
        BabelFluxProperties properties = new BabelFluxProperties();
        properties.setAllowedMediaHosts("192.0.2.1");
        properties.setFfmpegPath("missing-babelflux-ffmpeg");
        MediaPcmSource source = new MediaPcmSource(properties);

        MediaPcmSource.MediaDecodeException error = assertThrows(MediaPcmSource.MediaDecodeException.class,
                () -> source.open("https://192.0.2.1/audio"));
        assertTrue(error.getMessage().contains("ffmpeg"));
    }
}
