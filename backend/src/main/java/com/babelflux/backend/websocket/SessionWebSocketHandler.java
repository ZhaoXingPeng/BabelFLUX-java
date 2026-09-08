package com.babelflux.backend.websocket;

import com.babelflux.backend.service.SessionService;
import com.babelflux.backend.service.SessionTokenService;
import com.babelflux.backend.service.RealtimeSessionRunner;
import com.babelflux.backend.infrastructure.RedisSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SessionWebSocketHandler extends TextWebSocketHandler implements WebSocketHandler {
    private static final long RUNNER_LEASE_TTL_SECONDS = 30;
    private static final long RUNNER_LEASE_RENEW_SECONDS = 10;
    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final SessionTokenService tokens;
    private final RealtimeSessionRunner runner;
    private final SessionEventHub eventHub;
    private final ConcurrentMap<String, RealtimeSessionRunner.RunHandle> runs = new ConcurrentHashMap<>();
    /** One primary realtime runner is allowed per session; handoff sockets are read-only. */
    private final ConcurrentMap<String, String> activeSessionSockets = new ConcurrentHashMap<>();
    private final RedisSessionRepository redis;
    private final ConcurrentMap<String, ScheduledFuture<?>> leaseRenewals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionEventHub.Subscription> handoffSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> handoffTasks = new ConcurrentHashMap<>();
    private final ExecutorService handoffExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService leaseExecutor;

    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner) {
        this(mapper, sessions, tokens, runner, new SessionEventHub(), (RedisSessionRepository) null);
    }

    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner, SessionEventHub eventHub) {
        this(mapper, sessions, tokens, runner, eventHub, (RedisSessionRepository) null);
    }

    @Autowired
    public SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                                   RealtimeSessionRunner runner, SessionEventHub eventHub,
                                   ObjectProvider<RedisSessionRepository> redisProvider) {
        this(mapper, sessions, tokens, runner, eventHub, (RedisSessionRepository) redisProvider.getIfAvailable());
    }

    SessionWebSocketHandler(ObjectMapper mapper, SessionService sessions, SessionTokenService tokens,
                            RealtimeSessionRunner runner, SessionEventHub eventHub,
                            RedisSessionRepository redis) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.tokens = tokens;
        this.runner = runner;
        this.eventHub = eventHub;
        this.redis = redis;
        this.leaseExecutor = redis == null ? null
                : Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("runner-lease-renew").factory());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        String id = pathVariable(socket, "sessionId");
        String token = query(socket, "token");
        if (token == null || token.isBlank()) {
            send(socket, Map.of("type", "error", "message", "Missing WebSocket token"));
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        boolean handoff = tokens.valid(id, token, "handoff");
        if (!handoff && !tokens.valid(id, token, "session")) {
            send(socket, Map.of("type", "error", "message", "Invalid WebSocket token"));
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.get(id);
        if (handoff) startHandoff(socket, id);
        else send(socket, Map.of("type", "session_started", "sessionId", id));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) throws Exception {
        JsonNode payload;
        try {
            payload = mapper.readTree(message.getPayload());
        } catch (Exception error) {
            send(socket, Map.of("type", "error", "message", "Invalid JSON message"));
            return;
        }
        String type = payload.path("type").asText();
        String id = pathVariable(socket, "sessionId");
        if (handoffSubscriptions.containsKey(socket.getId())) {
            if ("stop_session".equals(type)) socket.close(CloseStatus.NORMAL);
            return;
        }
        var session = sessions.get(id);
        if ("start_session".equals(type)) {
            String requestedInputMode = text(payload, "inputMode");
            if (!SessionService.isSupportedInputMode(requestedInputMode)) {
                send(socket, Map.of("type", "error", "message",
                        "暂不支持的输入模式：" + requestedInputMode));
                return;
            }
            synchronized (runs) {
                if (runs.containsKey(socket.getId())) return;
                if (activeSessionSockets.putIfAbsent(id, socket.getId()) != null) {
                    send(socket, Map.of("type", "error", "message", "会话已在其他连接中运行"));
                    return;
                }
                if (redis != null) {
                    boolean acquired;
                    try {
                        acquired = redis.tryAcquireRunnerLease(id, socket.getId(),
                                Duration.ofSeconds(RUNNER_LEASE_TTL_SECONDS));
                    } catch (RuntimeException error) {
                        activeSessionSockets.remove(id, socket.getId());
                        send(socket, Map.of("type", "error", "message", "会话锁服务暂不可用"));
                        return;
                    }
                    if (!acquired) {
                        activeSessionSockets.remove(id, socket.getId());
                        send(socket, Map.of("type", "error", "message", "会话已在其他实例中运行"));
                        return;
                    }
                }
                session.applyOverrides(text(payload, "sourceLanguage"), text(payload, "targetLanguage"),
                        text(payload, "domain"), text(payload, "inputMode"), text(payload, "sourceUrl"),
                        text(payload, "modelProfile"));
                session.start();
                AtomicBoolean reportEmitted = new AtomicBoolean();
                AtomicReference<RealtimeSessionRunner.RunHandle> handleRef = new AtomicReference<>();
                try {
                    RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> {
                        if ("session_report".equals(event.get("type"))) {
                            reportEmitted.set(true);
                            RealtimeSessionRunner.RunHandle current = handleRef.get();
                            if (current != null) runs.remove(socket.getId(), current);
                            releaseRunnerOwner(id, socket.getId());
                        }
                        eventHub.publish(id, event);
                        if ("session_report".equals(event.get("type"))) eventHub.complete(id);
                        send(socket, event);
                    });
                    handleRef.set(handle);
                    runs.put(socket.getId(), handle);
                    if (reportEmitted.get()) {
                        runs.remove(socket.getId(), handle);
                        releaseRunnerOwner(id, socket.getId());
                    } else {
                        scheduleLeaseRenewal(id, socket);
                    }
                } catch (RuntimeException | Error error) {
                    releaseRunnerOwner(id, socket.getId());
                    throw error;
                }
            }
        } else if ("stop_session".equals(type) || "audio_end".equals(type)) {
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle == null) {
                String owner = activeSessionSockets.get(id);
                if (owner != null && !owner.equals(socket.getId())) {
                    send(socket, Map.of("type", "error", "message", "会话正在其他连接中运行"));
                    return;
                }
                if (owner == null && redis != null) {
                    boolean leaseHeld;
                    try {
                        leaseHeld = redis.runnerLeaseHeld(id);
                    } catch (RuntimeException error) {
                        send(socket, Map.of("type", "error", "message", "会话锁服务暂不可用"));
                        return;
                    }
                    if (leaseHeld) {
                        send(socket, Map.of("type", "error", "message", "会话正在其他实例中运行"));
                        return;
                    }
                }
                var report = sessions.finish(id);
                send(socket, Map.of("type", "session_report", "reportId", report.reportId(),
                        "correctionStatus", report.correctionStatus()));
            } else {
                handle.stop();
            }
        } else if ("media_clock".equals(type)) {
            Long playbackMs = clockValue(payload.get("playbackMs"));
            Long sentAudioMs = clockValue(payload.get("sentAudioMs"));
            if (playbackMs == null || sentAudioMs == null) {
                send(socket, Map.of("type", "error", "message",
                        "media_clock 的 playbackMs 和 sentAudioMs 必须是非负整数"));
                return;
            }
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle != null) handle.updateClientClock(playbackMs, sentAudioMs);
        } else if ("pause_session".equals(type)) {
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle != null) handle.pause();
            send(socket, Map.of("type", "source_sync_state", "state", Map.of("status", "missing", "lagMs", 0, "message", "会话已暂停")));
        } else if ("resume_session".equals(type)) {
            RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
            if (handle != null) handle.resume();
            send(socket, Map.of("type", "source_sync_state", "state", Map.of("status", "listening", "lagMs", 0, "message", "会话已恢复")));
        } else {
            send(socket, Map.of("type", "error", "message", "Unsupported client event"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        if (handoffSubscriptions.containsKey(socket.getId())) return;
        RealtimeSessionRunner.RunHandle handle = runs.get(socket.getId());
        if (handle == null) {
            try {
                send(socket, Map.of("type", "error", "message", "Start the session before sending audio"));
            } catch (IOException error) {
                throw new IllegalStateException("failed to send audio state", error);
            }
            return;
        }
        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        handle.acceptAudio(bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        RealtimeSessionRunner.RunHandle handle = runs.remove(socket.getId());
        if (handle != null) handle.stop();
        releaseRunnerOwner(pathVariable(socket, "sessionId"), socket.getId());
        SessionEventHub.Subscription subscription = handoffSubscriptions.remove(socket.getId());
        if (subscription != null) eventHub.unsubscribe(subscription);
        Future<?> task = handoffTasks.remove(socket.getId());
        if (task != null) task.cancel(true);
    }

    private void startHandoff(WebSocketSession socket, String sessionId) throws IOException {
        SessionEventHub.Subscription subscription = eventHub.subscribe(sessionId);
        handoffSubscriptions.put(socket.getId(), subscription);
        send(socket, Map.of("type", "session_started", "sessionId", sessionId));
        for (Map<String, Object> event : subscription.replay()) send(socket, event);
        Future<?> task = handoffExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Map<String, Object> event = subscription.await(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (event != null) send(socket, event);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // The display-only socket may close while the primary session continues.
            }
        });
        handoffTasks.put(socket.getId(), task);
    }

    private void scheduleLeaseRenewal(String sessionId, WebSocketSession socket) {
        if (redis == null || leaseExecutor == null) return;
        ScheduledFuture<?> task = leaseExecutor.scheduleAtFixedRate(
                () -> renewLease(sessionId, socket), RUNNER_LEASE_RENEW_SECONDS,
                RUNNER_LEASE_RENEW_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = leaseRenewals.put(sessionId, task);
        if (previous != null) previous.cancel(false);
    }

    private void renewLease(String sessionId, WebSocketSession socket) {
        String socketId = socket.getId();
        if (!socketId.equals(activeSessionSockets.get(sessionId))) return;
        boolean renewed;
        try {
            renewed = redis.renewRunnerLease(sessionId, socketId,
                    Duration.ofSeconds(RUNNER_LEASE_TTL_SECONDS));
        } catch (RuntimeException error) {
            renewed = false;
        }
        if (renewed) return;
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) handle.stop();
        releaseRunnerOwner(sessionId, socketId);
        try {
            send(socket, Map.of("type", "error", "message", "会话租约续期失败，已停止实时会话"));
        } catch (IOException ignored) {
            // The socket may already be closed while the lease task is running.
        }
    }

    private void releaseRunnerOwner(String sessionId, String socketId) {
        String current = activeSessionSockets.get(sessionId);
        if (current != null && !current.equals(socketId)) return;
        activeSessionSockets.remove(sessionId, socketId);
        ScheduledFuture<?> renewal = leaseRenewals.remove(sessionId);
        if (renewal != null) renewal.cancel(false);
        if (redis != null) {
            try {
                redis.releaseRunnerLease(sessionId, socketId);
            } catch (RuntimeException ignored) {
                // The lease has a TTL and will expire if Redis is unavailable during cleanup.
            }
        }
    }

    @PreDestroy
    void shutdown() {
        handoffTasks.values().forEach(task -> task.cancel(true));
        leaseRenewals.values().forEach(task -> task.cancel(true));
        if (leaseExecutor != null) leaseExecutor.shutdownNow();
        handoffExecutor.shutdownNow();
    }

    private void send(WebSocketSession socket, Object body) throws IOException {
        synchronized (socket) {
            socket.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
        }
    }
    private static String text(JsonNode payload, String field) {
        JsonNode value = payload == null ? null : payload.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
    private static Long clockValue(JsonNode value) {
        if (value == null || value.isNull()) return 0L;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) return null;
        return value.longValue();
    }
    private static String query(WebSocketSession socket, String key) { return socket.getUri() == null ? null : org.springframework.web.util.UriComponentsBuilder.fromUri(socket.getUri()).build().getQueryParams().getFirst(key); }
    private static String pathVariable(WebSocketSession socket, String key) {
        Object attribute = socket.getAttributes().get(key);
        if (attribute != null) return attribute.toString();
        if (socket.getUri() == null) throw new IllegalArgumentException("missing WebSocket path variable: " + key);
        String path = socket.getUri().getPath();
        int marker = path.lastIndexOf("/sessions/");
        if (marker < 0) throw new IllegalArgumentException("invalid WebSocket path");
        String value = path.substring(marker + "/sessions/".length());
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }
}
