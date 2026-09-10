package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.babelflux.backend.config.BabelFluxProperties;
import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.infrastructure.InMemorySessionRepository;
import com.babelflux.backend.messaging.JdbcSessionEventOutbox;
import com.babelflux.backend.messaging.SessionEventFactory;
import com.babelflux.backend.provider.dashscope.DashScopeClient;
import com.babelflux.backend.provider.dashscope.DashScopeRealtimeClient;
import com.babelflux.backend.search.ReportIndexingPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A repeatable local baseline, not a DashScope or production performance claim.
 * The provider is intentionally simulated so input, queue pressure and response
 * timing remain controlled and never require an API key or recorded user audio.
 */
@Tag("benchmark")
class RealtimePerformanceBaselineTest {
    private static final int RUNS = 5;
    private static final int FRAME_BYTES = 1_280;
    private static final int INPUT_FRAMES = 41;
    private static final int QUEUE_FRAMES = 25;

    @Test
    void printsFiveRunSyntheticPcmBaselineWithQueueDropRate() throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (int run = 1; run <= RUNS; run++) results.add(runOverloadedPcm(run));

        assertEquals(RUNS, results.size());
        assertTrue(results.stream().allMatch(result -> result.providerErrors() == 0));
        assertTrue(results.stream().allMatch(result -> result.droppedFrames() == INPUT_FRAMES - QUEUE_FRAMES - 1));

        List<Long> firstTranscript = results.stream().map(RunResult::firstTranscriptMs).toList();
        List<Long> completion = results.stream().map(RunResult::completionMs).toList();
        int dropped = results.getFirst().droppedFrames();
        System.out.printf("REALTIME_BASELINE runs=%d fixed_pcm=16kHz_mono_s16le_frames=%d "
                        + "first_transcript_ms[p50=%d,p95=%d,p99=%d] "
                        + "completion_ms[p50=%d,p95=%d,p99=%d] dropped_frames=%d/%d (%.1f%%) provider_errors=0%n",
                RUNS, INPUT_FRAMES, percentile(firstTranscript, 0.50), percentile(firstTranscript, 0.95),
                percentile(firstTranscript, 0.99), percentile(completion, 0.50), percentile(completion, 0.95),
                percentile(completion, 0.99), dropped, INPUT_FRAMES, dropped * 100.0 / INPUT_FRAMES);
    }

    @Test
    void simulatedProviderTimeoutRemainsVisibleAndStillFinalizesSession() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        SessionService service = service(repository);
        DashScopeProperties properties = properties();
        Session session = session("baseline-timeout");
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        when(realtime.connect(any())).thenReturn(provider);
        when(provider.receive(any(Duration.class))).thenThrow(
                new DashScopeClient.TimeoutException("simulated provider timeout", null));
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);

        try {
            RealtimeSessionRunner.RunHandle handle = runner.start(session, events::add);
            handle.acceptAudio(fixedPcm());
            handle.endAudio();
            handle.await(Duration.ofSeconds(3));

            assertTrue(events.stream().anyMatch(event -> "error".equals(event.get("type"))
                    && String.valueOf(event.get("message")).contains("simulated provider timeout")));
            assertTrue(events.stream().anyMatch(event -> "session_report".equals(event.get("type"))));
            assertEquals("ended", session.getStatus());
        } finally {
            runner.shutdown();
        }
    }

    private static RunResult runOverloadedPcm(int run) throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        SessionService service = service(repository);
        DashScopeProperties properties = properties();
        Session session = session("baseline-" + run);
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        AtomicInteger providerEvents = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();
        when(realtime.connect(any())).thenReturn(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (firstSendStarted.getCount() > 0) {
                firstSendStarted.countDown();
                assertTrue(releaseFirstSend.await(2, TimeUnit.SECONDS));
            } else {
                Thread.sleep(2);
            }
            return null;
        }).when(provider).sendAudio(any());
        when(provider.receive(any(Duration.class))).thenAnswer(invocation -> {
            int event = providerEvents.getAndIncrement();
            if (event == 0) {
                Thread.sleep(20);
                return new DashScopeRealtimeClient.NormalizedEvent(
                        "speech_started", "", new byte[0], "synthetic-item", null, Map.of());
            }
            if (event == 1) return new DashScopeRealtimeClient.NormalizedEvent(
                    "source_final", "synthetic fixed PCM", new byte[0], "synthetic-item", null, Map.of());
            if (event == 2) return new DashScopeRealtimeClient.NormalizedEvent(
                    "response_created", "", new byte[0], null, "synthetic-response", Map.of());
            if (event == 3) return new DashScopeRealtimeClient.NormalizedEvent(
                    "translation_final", "合成固定 PCM", new byte[0], null, "synthetic-response", Map.of());
            return finished.get() ? new DashScopeRealtimeClient.NormalizedEvent(
                    "session_finished", "", new byte[0], null, null, Map.of()) : null;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            finished.set(true);
            return null;
        }).when(provider).finish();

        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        AtomicLong firstTranscriptNanos = new AtomicLong(-1);
        AtomicLong reportNanos = new AtomicLong(-1);
        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);
        try {
            RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> {
                events.add(event);
                long now = System.nanoTime();
                if ("transcript_segment".equals(event.get("type"))) firstTranscriptNanos.compareAndSet(-1, now);
                if ("session_report".equals(event.get("type"))) reportNanos.compareAndSet(-1, now);
            });
            long startedNanos = System.nanoTime();
            handle.acceptAudio(fixedPcm());
            assertTrue(firstSendStarted.await(1, TimeUnit.SECONDS));
            for (int frame = 1; frame < INPUT_FRAMES; frame++) handle.acceptAudio(fixedPcm());
            releaseFirstSend.countDown();
            handle.endAudio();
            handle.await(Duration.ofSeconds(5));

            assertTrue(firstTranscriptNanos.get() > startedNanos);
            assertTrue(reportNanos.get() > startedNanos);
            assertTrue(events.stream().anyMatch(event -> "session_report".equals(event.get("type"))));
            return new RunResult(
                    TimeUnit.NANOSECONDS.toMillis(firstTranscriptNanos.get() - startedNanos),
                    TimeUnit.NANOSECONDS.toMillis(reportNanos.get() - startedNanos),
                    (int) session.getDroppedInputFrames(),
                    (int) events.stream().filter(event -> "error".equals(event.get("type"))).count());
        } finally {
            releaseFirstSend.countDown();
            runner.shutdown();
        }
    }

    private static DashScopeProperties properties() {
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("benchmark-synthetic-key");
        properties.setBaseUrl("https://benchmark.invalid/api/v1");
        properties.setRealtimeQueueFrames(QUEUE_FRAMES);
        return properties;
    }

    private static SessionService service(InMemorySessionRepository repository) {
        return new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
    }

    private static Session session(String id) {
        return Session.create(id, "synthetic baseline", "en", "zh", "general", "default",
                "quick", "live", "microphone", null, "idle", false, List.of());
    }

    private static byte[] fixedPcm() { return new byte[FRAME_BYTES]; }

    private static long percentile(List<Long> values, double percentile) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get((int) Math.ceil(percentile * sorted.size()) - 1);
    }

    private record RunResult(long firstTranscriptMs, long completionMs, int droppedFrames, int providerErrors) {}
}
