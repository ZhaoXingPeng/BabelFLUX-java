package com.babelflux.backend.service;

import com.babelflux.backend.config.DashScopeProperties;
import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.provider.dashscope.DashScopeRealtimeClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/** Runs one bounded PCM stream and translates provider events into the client contract. */
@Service
public class RealtimeSessionRunner {
    private static final int PCM_QUEUE_FRAMES = 25; // 1 second at 40 ms/frame.
    private static final Duration PROVIDER_POLL = Duration.ofMillis(5);
    private static final Duration FINAL_DRAIN = Duration.ofSeconds(8);

    private final DashScopeRealtimeClient realtime;
    private final DashScopeProperties properties;
    private final SessionService sessions;
    private final RealtimeRevisionService revisions;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RealtimeSessionRunner(DashScopeRealtimeClient realtime, DashScopeProperties properties,
                                 SessionService sessions) {
        this(realtime, properties, sessions, null);
    }

    @Autowired
    public RealtimeSessionRunner(DashScopeRealtimeClient realtime, DashScopeProperties properties,
                                 SessionService sessions, RealtimeRevisionService revisions) {
        this.realtime = realtime;
        this.properties = properties;
        this.sessions = sessions;
        this.revisions = revisions;
    }

    public RunHandle start(Session session, Sink sink) {
        RunHandle handle = new RunHandle(session, sink);
        handle.future = executor.submit(handle::run);
        return handle;
    }

    @PreDestroy
    void shutdown() { executor.shutdownNow(); }

    @FunctionalInterface
    public interface Sink { void emit(Map<String, Object> event) throws Exception; }

    public final class RunHandle {
        private final Session session;
        private final Sink sink;
        private final ArrayBlockingQueue<AudioFrame> audio = new ArrayBlockingQueue<>(PCM_QUEUE_FRAMES);
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicLong elapsedMs = new AtomicLong();
        private final LinkedHashMap<String, SegmentState> byId = new LinkedHashMap<>();
        private final List<SegmentState> roots = new ArrayList<>();
        private final Map<String, SegmentState> byItem = new LinkedHashMap<>();
        private final Map<String, SegmentState> byResponse = new LinkedHashMap<>();
        private final Deque<Long> revisionCalls = new ArrayDeque<>();
        private final AtomicBoolean revisionInFlight = new AtomicBoolean();
        private final List<Future<?>> revisionTasks = new ArrayList<>();
        private volatile boolean paused;
        private volatile Future<?> future;
        private int segmentNumber;

        private RunHandle(Session session, Sink sink) {
            this.session = session;
            this.sink = sink;
        }

        public void acceptAudio(byte[] pcm) {
            if (ended.get() || pcm == null || pcm.length == 0) return;
            AudioFrame frame = new AudioFrame(pcm, false);
            if (!audio.offer(frame)) {
                audio.poll();
                audio.offer(frame);
                emitQuietly(Map.of("type", "source_sync_state", "state", Map.of(
                        "status", "lagging", "lagMs", 0,
                        "message", "音频输入超过 1 秒缓冲，已丢弃最旧帧")));
            }
        }

        public void endAudio() {
            if (ended.get()) return;
            stopRequested.set(true);
            while (!audio.offer(new AudioFrame(new byte[0], true))) audio.poll();
        }

        public void stop() { endAudio(); }
        public void pause() { paused = true; }
        public void resume() { paused = false; }
        public void await(Duration timeout) throws Exception {
            Future<?> task = future;
            if (task != null) task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void run() {
            try {
                if ("demo".equalsIgnoreCase(session.getInputMode())) runDemo();
                else if (properties.getApiKey() == null || properties.getApiKey().isBlank())
                    throw new DashScopeRealtimeUnavailableException("DASHSCOPE_API_KEY 未配置，无法启动实时同传");
                else runLive();
            } catch (Exception error) {
                emitQuietly(Map.of("type", "error", "message", error.getMessage() == null
                        ? "同传运行异常" : error.getMessage()));
            } finally {
                finalizeSession();
            }
        }

        private void runDemo() throws Exception {
            emit(Map.of("type", "source_sync_state", "state", Map.of(
                    "status", "ready", "lagMs", 0, "message", "演示同传引擎就绪")));
            demoSegment("demo-seg-1", "Welcome to BabelFlux.", "欢迎使用巴别流。", 0, 1800);
            demoSegment("demo-seg-2", "This stream is running on Java.", "这条流正在 Java 后端上运行。", 1800, 3800);
            // Keep demo sessions live until the client explicitly ends the stream.
            while (!stopRequested.get() && !ended.get()) Thread.sleep(20);
        }

        private void demoSegment(String id, String source, String translation, long start, long end) throws Exception {
            SegmentState state = new SegmentState(id, start);
            state.source = source;
            state.translation = translation;
            state.sourceFinal = true;
            state.translationFinal = true;
            roots.add(state);
            byId.put(id, state);
            emitSegment("transcript_segment", state, session.getSourceLanguage(), source, "final");
            emitSegment("translation_segment", state, session.getTargetLanguage(), translation, "final");
            session.upsertSegment(new Session.Segment(id, source, translation, start, end, "final"));
        }

        private void runLive() throws Exception {
            DashScopeRealtimeClient.Request request = new DashScopeRealtimeClient.Request(
                    value(properties.getLiveTranslateModel(), "qwen3.5-livetranslate-flash-realtime"),
                    sourceLanguage(), session.getTargetLanguage(),
                    value(properties.getLiveTranslateAsrModel(), "qwen3-asr-flash-realtime"),
                    session.isTtsEnabled(), "Tina", 16_000, glossary());
            try (DashScopeRealtimeClient.LiveSession provider = realtime.connect(request)) {
                emitQuietly(Map.of("type", "source_sync_state", "state", Map.of(
                        "status", "syncing", "lagMs", 0, "message", "正在连接百炼同传引擎")));
                boolean finished = false;
                while (!finished) {
                    if (!paused || stopRequested.get()) {
                        AudioFrame frame = audio.poll(20, TimeUnit.MILLISECONDS);
                        if (frame != null) {
                            if (frame.end()) {
                                finished = true;
                            } else {
                                provider.sendAudio(frame.pcm());
                                elapsedMs.addAndGet(frameDurationMs(frame.pcm()));
                            }
                        }
                    } else {
                        Thread.sleep(10);
                    }
                    finished = drainProvider(provider) || finished;
                }
                provider.sendSilence(Duration.ofSeconds(1));
                provider.finish();
                long deadline = System.nanoTime() + FINAL_DRAIN.toNanos();
                while (!ended.get() && System.nanoTime() < deadline) {
                    if (drainProvider(provider)) break;
                }
            }
        }

        private boolean drainProvider(DashScopeRealtimeClient.LiveSession provider) {
            DashScopeRealtimeClient.NormalizedEvent event = provider.receive(PROVIDER_POLL);
            if (event == null) return false;
            handleProviderEvent(event);
            return "session_finished".equals(event.kind());
        }

        private void handleProviderEvent(DashScopeRealtimeClient.NormalizedEvent event) {
            switch (event.kind()) {
                case "speech_started" -> begin(event.itemId());
                case "source_partial", "source_final" -> updateSource(event);
                case "response_created" -> bindResponse(event.responseId());
                case "translation_partial", "translation_final" -> updateTranslation(event);
                case "audio" -> emitAudio(event);
                case "error" -> emitQuietly(Map.of("type", "error", "message", event.text()));
                default -> { }
            }
        }

        private void begin(String itemId) {
            SegmentState state = new SegmentState(session.getId() + "-seg-" + (++segmentNumber), elapsedMs.get());
            state.itemId = itemId;
            roots.add(state);
            byId.put(state.id, state);
            if (itemId != null) byItem.put(itemId, state);
        }

        private void updateSource(DashScopeRealtimeClient.NormalizedEvent event) {
            SegmentState state = event.itemId() == null ? current() : byItem.get(event.itemId());
            if (state == null) { begin(event.itemId()); state = current(); }
            state.source = "source_final".equals(event.kind())
                    ? preferFinalSource(state.source, event.text())
                    : mergeSourcePartial(state.source, event.text());
            state.sourceFinal = "source_final".equals(event.kind());
            state.endMs = Math.max(state.startMs, elapsedMs.get());
            emitSegment("transcript_segment", state, sourceLanguage(), state.source,
                    state.sourceFinal ? "final" : "partial");
        }

        private void bindResponse(String responseId) {
            if (responseId == null || responseId.isBlank() || byResponse.containsKey(responseId)) return;
            SegmentState state = current();
            if (state == null || state.responseId != null) state = firstUnbound();
            if (state != null) {
                state.responseId = responseId;
                byResponse.put(responseId, state);
            }
        }

        private void updateTranslation(DashScopeRealtimeClient.NormalizedEvent event) {
            SegmentState state = event.responseId() == null ? firstUnbound() : byResponse.get(event.responseId());
            if (state == null) state = firstUnbound();
            if (state == null) { begin(null); state = current(); }
            state.responseId = event.responseId();
            if (event.responseId() != null) byResponse.put(event.responseId(), state);
            state.translation = event.text();
            state.translationFinal = "translation_final".equals(event.kind());
            state.endMs = Math.max(state.startMs, elapsedMs.get());
            emitSegment("translation_segment", state, session.getTargetLanguage(), state.translation,
                    state.translationFinal ? "final" : "partial");
            if (state.translationFinal && !state.source.isBlank()) {
                session.upsertSegment(new Session.Segment(state.id, state.source, state.translation,
                        state.startMs, state.endMs, "final"));
                scheduleRevision();
            }
        }

        private void scheduleRevision() {
            if (revisions == null || properties.getApiKey() == null || properties.getApiKey().isBlank()
                    || session.getSegments().size() < 2 || !allowRevisionCall()
                    || !revisionInFlight.compareAndSet(false, true)) return;
            List<Session.Segment> snapshot = session.getSegments();
            Future<?> task = executor.submit(() -> {
                try {
                    for (RealtimeRevisionService.Revision revision : revisions.review(session, snapshot)) {
                        applyRevision(revision);
                    }
                } catch (Exception ignored) {
                    // Realtime correction is advisory and must never stop the audio stream.
                } finally {
                    revisionInFlight.set(false);
                }
            });
            synchronized (revisionTasks) { revisionTasks.add(task); }
        }

        private boolean allowRevisionCall() {
            long now = System.nanoTime();
            while (!revisionCalls.isEmpty() && now - revisionCalls.peekFirst() > TimeUnit.MINUTES.toNanos(1)) {
                revisionCalls.removeFirst();
            }
            int limit = Math.max(0, properties.getRealtimeRevisionMaxPerMinute());
            if (revisionCalls.size() >= limit) return false;
            revisionCalls.addLast(now);
            return true;
        }

        private void applyRevision(RealtimeRevisionService.Revision revision) {
            Session.Segment current = session.getSegments().stream()
                    .filter(segment -> revision.segmentId().equals(segment.segmentId())).findFirst().orElse(null);
            if (current == null || !value(current.translationText()).equals(revision.beforeText())) return;
            session.upsertSegment(new Session.Segment(current.segmentId(), current.sourceText(),
                    revision.afterText(), current.startMs(), current.endMs(), "revised"));
            session.addRevision(new Session.Revision(revision.segmentId(), revision.beforeText(),
                    revision.afterText(), revision.reason(), revision.confidence()));
            sessions.saveProgress(session);
            emitQuietly(Map.of("type", "translation_segment", "segment", Map.of(
                    "segmentId", revision.segmentId(), "text", revision.afterText(),
                    "language", session.getTargetLanguage(), "startMs", current.startMs(),
                    "endMs", current.endMs(), "status", "revised")));
            emitQuietly(Map.of("type", "revision_event", "revision", Map.of(
                    "revisionId", session.getId() + "-rev-" + System.nanoTime(),
                    "segmentId", revision.segmentId(), "beforeText", revision.beforeText(),
                    "afterText", revision.afterText(), "reason", revision.reason(),
                    "confidence", revision.confidence())));
        }

        private void emitAudio(DashScopeRealtimeClient.NormalizedEvent event) {
            SegmentState state = event.responseId() == null ? current() : byResponse.get(event.responseId());
            if (state == null || event.audio().length == 0) return;
            emitQuietly(Map.of("type", "audio_segment", "segmentId", state.id,
                    "audioBase64", Base64.getEncoder().encodeToString(event.audio()), "sampleRate", 24_000));
        }

        private void emitSegment(String type, SegmentState state, String language, String text, String status) {
            if (text == null || text.isBlank()) return;
            emitQuietly(Map.of("type", type, "segment", Map.of("segmentId", state.id, "text", text,
                    "language", language, "startMs", state.startMs, "endMs", state.endMs, "status", status)));
        }

        private SegmentState current() { return roots.isEmpty() ? null : roots.getLast(); }
        private SegmentState firstUnbound() {
            return roots.stream().filter(state -> state.responseId == null).findFirst().orElse(null);
        }
        private static String preferFinalSource(String previous, String incoming) {
            if (incoming == null || incoming.isBlank()) return previous;
            if (previous == null || previous.isBlank()) return incoming;
            if (incoming.startsWith(previous) || incoming.contains(previous)) return incoming;
            if (previous.contains(incoming)) return previous;
            return previous + " " + incoming;
        }
        private static String mergeSourcePartial(String previous, String incoming) {
            if (incoming == null || incoming.isBlank()) return previous == null ? "" : previous;
            if (previous == null || previous.isBlank()) return incoming;
            if (incoming.startsWith(previous) || previous.contains(incoming) || previous.endsWith(incoming))
                return incoming.length() >= previous.length() ? incoming : previous;
            if (previous.endsWith(" ") || incoming.matches("^[,.;:!?，。；：！？)].*"))
                return previous + incoming;
            return previous + " " + incoming;
        }
        private String sourceLanguage() { return "auto".equalsIgnoreCase(session.getSourceLanguage()) ? "en" : session.getSourceLanguage(); }
        private Map<String, String> glossary() {
            Map<String, String> result = new LinkedHashMap<>();
            session.getGlossary().forEach(term -> result.put(term.sourceTerm(), term.targetTerm()));
            return result;
        }

        private void finalizeSession() {
            if (!ended.compareAndSet(false, true)) return;
            try {
                awaitRevisions();
                sessions.saveProgress(session);
                SessionReport report = sessions.finish(session.getId());
                emit(Map.of("type", "session_report", "reportId", report.reportId(),
                        "correctionStatus", report.correctionStatus()));
            } catch (Exception error) {
                emitQuietly(Map.of("type", "error", "message", "报告生成失败：" + error.getMessage()));
            }
        }

        private void awaitRevisions() {
            List<Future<?>> tasks;
            synchronized (revisionTasks) { tasks = List.copyOf(revisionTasks); }
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            for (Future<?> task : tasks) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { task.get(remaining, TimeUnit.NANOSECONDS); }
                catch (Exception ignored) { task.cancel(true); }
            }
        }

        private void emit(Map<String, Object> event) throws Exception { sink.emit(event); }
        private void emitQuietly(Map<String, Object> event) { try { emit(event); } catch (Exception ignored) { } }
    }

    private static long frameDurationMs(byte[] pcm) { return Math.max(0L, pcm.length * 1000L / (16_000L * 2L)); }
    private static String value(String value) { return value == null ? "" : value; }
    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
    private record AudioFrame(byte[] pcm, boolean end) {}
    private static final class SegmentState {
        private final String id;
        private final long startMs;
        private long endMs;
        private String source = "";
        private String translation = "";
        private String itemId;
        private String responseId;
        private boolean sourceFinal;
        private boolean translationFinal;
        private SegmentState(String id, long startMs) { this.id = id; this.startMs = startMs; }
    }

    public static class DashScopeRealtimeUnavailableException extends RuntimeException {
        public DashScopeRealtimeUnavailableException(String message) { super(message); }
    }
}
