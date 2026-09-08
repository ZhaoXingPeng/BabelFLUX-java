package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.config.BabelFluxProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.infrastructure.JdbcSessionRepository;
import com.babelflux.backend.infrastructure.InMemorySessionRepository;
import com.babelflux.backend.provider.dashscope.DashScopeRealtimeClient;
import com.babelflux.backend.messaging.JdbcSessionEventOutbox;
import com.babelflux.backend.messaging.SessionEventFactory;
import com.babelflux.backend.search.ReportIndexingPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RealtimeSessionRunnerTest {
    @Test
    void demoRunProducesClientEventsAndPersistedReport() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        // The demo path never touches provider, outbox, or search adapters.
        Session session = Session.create("demo-run", "demo", "en", "zh", "通用", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        repository.save(session);
        RealtimeSessionRunner runner = new RealtimeSessionRunner(
                new DashScopeRealtimeClient(properties, new ObjectMapper()), properties, service);
        List<Map<String, Object>> events = new ArrayList<>();

        RealtimeSessionRunner.RunHandle handle = runner.start(session, events::add);
        Thread.sleep(100);
        assertNull(service.get("demo-run").getReport());
        handle.stop();
        handle.await(Duration.ofSeconds(3));

        assertTrue(events.stream().anyMatch(event -> "transcript_segment".equals(event.get("type"))));
        assertTrue(events.stream().anyMatch(event -> "translation_segment".equals(event.get("type"))));
        assertEquals("demo-run-report", service.get("demo-run").getReport().reportId());
        runner.shutdown();
    }

    @Test
    void retriesFinalizationAfterTransientJdbcFailureAndKeepsLatestSegments() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:runner-finalize-retry;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table babelflux_sessions ("
                + "session_id varchar(64) primary key, created_at_epoch bigint not null, ended_at_epoch bigint, "
                + "status varchar(32) not null, session_name varchar(255) not null, source_language varchar(32) not null, "
                + "target_language varchar(32) not null, domain varchar(128) not null, model_profile varchar(128) not null, "
                + "product_mode varchar(32) not null, input_mode varchar(64) not null, source_label varchar(512) not null, "
                + "source_url varchar(2048), source_permission varchar(32) not null, tts_enabled boolean not null, "
                + "glossary_json text not null, segments_json text not null, report_json text)");
        FlakyJdbcSessionRepository repository = new FlakyJdbcSessionRepository(jdbc,
                JsonMapper.builder().addModule(new JavaTimeModule()).build());
        Session session = Session.create("finalize-retry", "retry", "en", "zh", "通用", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        repository.save(session);
        repository.failNextFinalSave();
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        RealtimeSessionRunner runner = new RealtimeSessionRunner(
                new DashScopeRealtimeClient(new DashScopeProperties(), new ObjectMapper()),
                new DashScopeProperties(), service);
        List<Map<String, Object>> events = new ArrayList<>();

        RealtimeSessionRunner.RunHandle handle = runner.start(session, events::add);
        Thread.sleep(100);
        handle.stop();
        handle.await(Duration.ofSeconds(3));

        assertTrue(events.stream().anyMatch(event -> "session_report".equals(event.get("type"))));
        Session restored = repository.findById(session.getId()).orElseThrow();
        assertEquals("ended", restored.getStatus());
        assertEquals(2, restored.getReport().metrics().segments());
        assertEquals(3, repository.saveCalls());
        runner.shutdown();
    }

    @Test
    void liveRunMergesSourcePartialsAndBindsResponseToActiveSegment() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        properties.setLiveTranslateModel("custom-live-model");
        properties.setLiveTranslateAsrModel("custom-asr-model");
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        Session session = Session.create("live-run", "live", "en", "zh", "通用", "默认",
                "quick", "live", "microphone", null, "idle", false, List.of());
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        when(realtime.connect(any())).thenReturn(provider);
        List<DashScopeRealtimeClient.NormalizedEvent> incoming = List.of(
                new DashScopeRealtimeClient.NormalizedEvent("speech_started", "", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("source_partial", "Hello", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("source_partial", "world", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("response_created", "", new byte[0], null, "response-1", Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("source_final", "Hello world", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("translation_final", "你好世界", new byte[0], null, "response-1", Map.of()));
        AtomicInteger index = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();
        when(provider.receive(any(Duration.class))).thenAnswer(invocation -> {
            int current = index.getAndIncrement();
            if (current < incoming.size()) return incoming.get(current);
            return finished.get() ? new DashScopeRealtimeClient.NormalizedEvent(
                    "session_finished", "", new byte[0], null, null, Map.of()) : null;
        });
        org.mockito.Mockito.doAnswer(invocation -> { finished.set(true); return null; }).when(provider).finish();

        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);
        List<Map<String, Object>> events = new ArrayList<>();
        RealtimeSessionRunner.RunHandle handle = runner.start(session, events::add);
        handle.acceptAudio(new byte[1_280]);
        handle.stop();
        handle.await(Duration.ofSeconds(3));

        assertEquals(1, session.getSegments().size());
        assertEquals("Hello world", session.getSegments().getFirst().sourceText());
        assertEquals("你好世界", session.getSegments().getFirst().translationText());
        assertTrue(events.stream().anyMatch(event -> "session_report".equals(event.get("type"))));
        ArgumentCaptor<DashScopeRealtimeClient.Request> request =
                ArgumentCaptor.forClass(DashScopeRealtimeClient.Request.class);
        verify(realtime).connect(request.capture());
        assertEquals("custom-live-model", request.getValue().model());
        assertEquals("custom-asr-model", request.getValue().asrModel());
        runner.shutdown();
    }

    @Test
    void liveRunRefreshesPersistedSourceWhenTranslationFinalArrivesFirst() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        Session session = Session.create("event-order-run", "live", "en", "zh", "通用", "默认",
                "quick", "live", "microphone", null, "idle", false, List.of());
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        when(realtime.connect(any())).thenReturn(provider);
        List<DashScopeRealtimeClient.NormalizedEvent> incoming = List.of(
                new DashScopeRealtimeClient.NormalizedEvent("speech_started", "", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("source_partial", "Hello", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("response_created", "", new byte[0], null, "response-1", Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("translation_final", "你好世界", new byte[0], null, "response-1", Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("source_final", "Hello world", new byte[0], "item-1", null, Map.of()));
        AtomicInteger index = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();
        when(provider.receive(any(Duration.class))).thenAnswer(invocation -> {
            int current = index.getAndIncrement();
            if (current < incoming.size()) return incoming.get(current);
            return finished.get() ? new DashScopeRealtimeClient.NormalizedEvent(
                    "session_finished", "", new byte[0], null, null, Map.of()) : null;
        });
        org.mockito.Mockito.doAnswer(invocation -> { finished.set(true); return null; }).when(provider).finish();

        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);
        RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> { });
        handle.acceptAudio(new byte[1_280]);
        handle.stop();
        handle.await(Duration.ofSeconds(3));

        assertEquals(1, session.getSegments().size());
        assertEquals("Hello world", session.getSegments().getFirst().sourceText());
        assertEquals("你好世界", session.getSegments().getFirst().translationText());
        runner.shutdown();
    }

    @Test
    void liveRunReplacesCumulativeProviderStashInsteadOfDuplicatingText() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        Session session = Session.create("stash-run", "live", "en", "zh", "通用", "默认",
                "quick", "live", "microphone", null, "idle", false, List.of());
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        when(realtime.connect(any())).thenReturn(provider);
        Map<String, Object> stash = Map.of("stash", "The");
        Map<String, Object> stashExpanded = Map.of("stash", "The bell");
        List<DashScopeRealtimeClient.NormalizedEvent> incoming = List.of(
                new DashScopeRealtimeClient.NormalizedEvent("speech_started", "", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("source_partial", "The", new byte[0], "item-1", null, stash),
                new DashScopeRealtimeClient.NormalizedEvent("source_partial", "The bell", new byte[0], "item-1", null, stashExpanded),
                new DashScopeRealtimeClient.NormalizedEvent("source_final", "The bell", new byte[0], "item-1", null, Map.of()),
                new DashScopeRealtimeClient.NormalizedEvent("translation_final", "钟声", new byte[0], null, "response-1", Map.of()));
        AtomicInteger index = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();
        when(provider.receive(any(Duration.class))).thenAnswer(invocation -> {
            int current = index.getAndIncrement();
            if (current < incoming.size()) return incoming.get(current);
            return finished.get() ? new DashScopeRealtimeClient.NormalizedEvent(
                    "session_finished", "", new byte[0], null, null, Map.of()) : null;
        });
        org.mockito.Mockito.doAnswer(invocation -> { finished.set(true); return null; }).when(provider).finish();

        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);
        RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> { });
        handle.acceptAudio(new byte[1_280]);
        handle.stop();
        handle.await(Duration.ofSeconds(3));

        assertEquals("The bell", session.getSegments().getFirst().sourceText());
        runner.shutdown();
    }

    @Test
    void mediaClockEmitsBoundedLagUpdates() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        Session session = Session.create("clock-run", "clock", "en", "zh", "通用", "默认",
                "quick", "demo", "demo", null, "idle", false, List.of());
        repository.save(session);
        RealtimeSessionRunner runner = new RealtimeSessionRunner(
                new DashScopeRealtimeClient(properties, new ObjectMapper()), properties, service);
        List<Map<String, Object>> events = new ArrayList<>();

        RealtimeSessionRunner.RunHandle handle = runner.start(session, events::add);
        Thread.sleep(80);
        handle.updateClientClock(300, 200);
        handle.updateClientClock(400, 300);
        long firstCount = events.stream().filter(RealtimeSessionRunnerTest::isClockEvent).count();
        assertEquals(1, firstCount);
        Map<String, Object> first = events.stream().filter(RealtimeSessionRunnerTest::isClockEvent).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> state = (Map<String, Object>) first.get("state");
        assertEquals(300L, state.get("sourceMs"));
        assertEquals("syncing", state.get("status"));

        Thread.sleep(270);
        handle.updateClientClock(2_000, 1_800);
        assertEquals(2, events.stream().filter(RealtimeSessionRunnerTest::isClockEvent).count());
        Map<String, Object> lagging = events.stream().filter(RealtimeSessionRunnerTest::isClockEvent)
                .reduce((firstEvent, lastEvent) -> lastEvent).orElseThrow();
        @SuppressWarnings("unchecked") Map<String, Object> laggingState =
                (Map<String, Object>) lagging.get("state");
        assertEquals("lagging", laggingState.get("status"));
        handle.stop();
        handle.await(Duration.ofSeconds(3));
        runner.shutdown();
    }

    @Test
    void reportsMeasuredQueueLagWhenInputOverrunsProvider() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("https://dashscope.aliyuncs.com/api/v1");
        properties.setRealtimeQueueFrames(25);
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        Session session = Session.create("lag-run", "lag", "en", "zh", "通用", "默认",
                "quick", "live", "microphone", null, "idle", false, List.of());
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        when(realtime.connect(any())).thenReturn(provider);
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            sendStarted.countDown();
            releaseSend.await(2, TimeUnit.SECONDS);
            return null;
        }).when(provider).sendAudio(any());
        when(provider.receive(any(Duration.class))).thenAnswer(invocation -> finished.get()
                ? new DashScopeRealtimeClient.NormalizedEvent("session_finished", "", new byte[0], null, null, Map.of())
                : null);
        org.mockito.Mockito.doAnswer(invocation -> { finished.set(true); return null; }).when(provider).finish();

        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);
        List<Map<String, Object>> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        RealtimeSessionRunner.RunHandle handle = runner.start(session, events::add);
        handle.acceptAudio(new byte[1_280]);
        assertTrue(sendStarted.await(1, TimeUnit.SECONDS));
        for (int i = 0; i < 40; i++) handle.acceptAudio(new byte[1_280]);
        releaseSend.countDown();
        handle.stop();
        handle.await(Duration.ofSeconds(3));

        List<Map<String, Object>> lagEvents = events.stream()
                .filter(event -> "source_sync_state".equals(event.get("type")))
                .filter(event -> {
                    @SuppressWarnings("unchecked") Map<String, Object> state = (Map<String, Object>) event.get("state");
                    return state != null && "lagging".equals(state.get("status"));
                }).toList();
        assertTrue(lagEvents.stream().anyMatch(event -> {
            @SuppressWarnings("unchecked") Map<String, Object> state = (Map<String, Object>) event.get("state");
            return Long.valueOf(1_000L).equals(state.get("lagMs"));
        }));
        assertTrue(events.stream().anyMatch(event -> "session_report".equals(event.get("type"))));
        assertTrue(session.getDroppedInputFrames() > 0);
        assertTrue(session.getReport().qualityNotes().contains("实时输入曾丢弃"));
        runner.shutdown();
    }

    @Test
    void endAudioDrainsQueuedFramesBeforeFinalizing() throws Exception {
        InMemorySessionRepository repository = new InMemorySessionRepository();
        DashScopeProperties properties = new DashScopeProperties();
        properties.setApiKey("test-key");
        properties.setRealtimeQueueFrames(25);
        SessionService service = new SessionService(repository, new SessionReportService(), new BabelFluxProperties(),
                mock(JdbcSessionEventOutbox.class), mock(SessionEventFactory.class), mock(ReportIndexingPort.class));
        Session session = Session.create("drain-run", "drain", "en", "zh", "通用", "默认",
                "quick", "live", "microphone", null, "idle", false, List.of());
        repository.save(session);

        DashScopeRealtimeClient realtime = mock(DashScopeRealtimeClient.class);
        DashScopeRealtimeClient.LiveSession provider = mock(DashScopeRealtimeClient.LiveSession.class);
        when(realtime.connect(any())).thenReturn(provider);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger sent = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> { sent.incrementAndGet(); return null; })
                .when(provider).sendAudio(any());
        when(provider.receive(any(Duration.class))).thenAnswer(invocation -> finished.get()
                ? new DashScopeRealtimeClient.NormalizedEvent("session_finished", "", new byte[0], null, null, Map.of())
                : null);
        org.mockito.Mockito.doAnswer(invocation -> { finished.set(true); return null; }).when(provider).finish();

        RealtimeSessionRunner runner = new RealtimeSessionRunner(realtime, properties, service);
        RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> { });
        for (int i = 0; i < 10; i++) handle.acceptAudio(new byte[1_280]);
        handle.endAudio();
        handle.await(Duration.ofSeconds(3));

        assertEquals(10, sent.get());
        assertEquals(0, session.getDroppedInputFrames());
        assertTrue(!session.getReport().qualityNotes().contains("实时输入曾丢弃"));
        runner.shutdown();
    }

    private static boolean isClockEvent(Map<String, Object> event) {
        if (!"source_sync_state".equals(event.get("type"))) return false;
        @SuppressWarnings("unchecked") Map<String, Object> state = (Map<String, Object>) event.get("state");
        return state != null && String.valueOf(state.get("message")).startsWith("媒体同步：");
    }

    private static final class FlakyJdbcSessionRepository extends JdbcSessionRepository {
        private final AtomicBoolean failFinalSave = new AtomicBoolean();
        private final AtomicInteger saves = new AtomicInteger();

        private FlakyJdbcSessionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
            super(jdbc, mapper);
        }

        private void failNextFinalSave() { failFinalSave.set(true); }
        private int saveCalls() { return saves.get(); }

        @Override
        public Session save(Session session) {
            saves.incrementAndGet();
            if (failFinalSave.compareAndSet(true, false)
                    && "ended".equals(session.getStatus()) && session.getReport() != null) {
                throw new IllegalStateException("transient JDBC write failure");
            }
            return super.save(session);
        }
    }

}
