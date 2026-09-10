package com.babelflux.backend.websocket;

import com.babelflux.backend.domain.Session;
import com.babelflux.backend.domain.SessionReport;
import com.babelflux.backend.service.RealtimeSessionRunner;
import com.babelflux.backend.service.SessionService;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Application coordinator for one realtime session runner; WebSocket parsing stays in the adapter. */
final class RealtimeSessionCoordinator {
    @FunctionalInterface
    interface EventSink { void emit(Map<String, Object> event) throws Exception; }

    record StartRequest(String sourceLanguage, String targetLanguage, String domain, String inputMode,
                        String sourceUrl, String modelProfile) {}
    record CommandResult(String error, SessionReport report) {
        static CommandResult ok() { return new CommandResult(null, null); }
        static CommandResult report(SessionReport report) { return new CommandResult(null, report); }
        static CommandResult error(String error) { return new CommandResult(error, null); }
        boolean failed() { return error != null; }
    }

    private final SessionService sessions;
    private final RealtimeSessionRunner runner;
    private final RunnerLeaseManager leases;
    private final ConcurrentMap<String, RealtimeSessionRunner.RunHandle> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EventSink> eventSinks = new ConcurrentHashMap<>();

    RealtimeSessionCoordinator(SessionService sessions, RealtimeSessionRunner runner, RunnerLeaseManager leases) {
        this.sessions = sessions;
        this.runner = runner;
        this.leases = leases;
    }

    CommandResult start(String sessionId, String socketId, StartRequest request, EventSink sink) {
        if (!SessionService.isSupportedInputMode(request.inputMode())) {
            return CommandResult.error("暂不支持的输入模式：" + request.inputMode());
        }
        synchronized (runs) {
            if (runs.containsKey(socketId)) return CommandResult.ok();
            RunnerLeaseManager.AcquireResult acquired = leases.acquire(sessionId, socketId);
            if (acquired != RunnerLeaseManager.AcquireResult.ACQUIRED) {
                return CommandResult.error(acquireMessage(acquired));
            }
            Session session;
            try {
                session = sessions.get(sessionId);
            } catch (RuntimeException | Error error) {
                leases.release(sessionId, socketId);
                throw error;
            }
            session.applyOverrides(request.sourceLanguage(), request.targetLanguage(), request.domain(),
                    request.inputMode(), request.sourceUrl(), request.modelProfile());
            session.start();
            AtomicBoolean reportEmitted = new AtomicBoolean();
            AtomicReference<RealtimeSessionRunner.RunHandle> handleRef = new AtomicReference<>();
            try {
                // Save the lifecycle fact before another instance can read stale session history.
                sessions.saveProgress(session);
                eventSinks.put(socketId, sink);
                RealtimeSessionRunner.RunHandle handle = runner.start(session, event -> {
                    if ("session_report".equals(event.get("type"))) {
                        reportEmitted.set(true);
                        RealtimeSessionRunner.RunHandle current = handleRef.get();
                        if (current != null) runs.remove(socketId, current);
                        leases.release(sessionId, socketId);
                        eventSinks.remove(socketId, sink);
                    }
                    sink.emit(event);
                });
                handleRef.set(handle);
                runs.put(socketId, handle);
                if (reportEmitted.get()) {
                    runs.remove(socketId, handle);
                    leases.release(sessionId, socketId);
                    eventSinks.remove(socketId, sink);
                } else {
                    leases.scheduleRenewal(sessionId, socketId, () -> leaseLost(sessionId, socketId));
                }
                return CommandResult.ok();
            } catch (RuntimeException | Error error) {
                eventSinks.remove(socketId, sink);
                session.end();
                try {
                    sessions.saveProgress(session);
                } catch (RuntimeException | Error ignored) {
                    error.addSuppressed(ignored);
                }
                leases.release(sessionId, socketId);
                throw error;
            }
        }
    }

    CommandResult stop(String sessionId, String socketId) {
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) {
            handle.stop();
            return CommandResult.ok();
        }
        RunnerLeaseManager.FinishPermission permission = leases.canFinish(sessionId, socketId);
        if (permission != RunnerLeaseManager.FinishPermission.ALLOWED) {
            return CommandResult.error(finishMessage(permission));
        }
        return CommandResult.report(sessions.finish(sessionId));
    }

    void acceptAudio(String socketId, byte[] pcm) {
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) handle.acceptAudio(pcm);
    }

    boolean hasRun(String socketId) { return runs.containsKey(socketId); }

    void updateClock(String socketId, long playbackMs, long sentAudioMs) {
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) handle.updateClientClock(playbackMs, sentAudioMs);
    }

    void pause(String socketId) {
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) handle.pause();
    }

    void resume(String socketId) {
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) handle.resume();
    }

    void close(String sessionId, String socketId) {
        eventSinks.remove(socketId);
        RealtimeSessionRunner.RunHandle handle = runs.remove(socketId);
        if (handle != null) handle.stop();
        leases.release(sessionId, socketId);
    }

    @PreDestroy
    void shutdown() { eventSinks.clear(); }

    private void leaseLost(String sessionId, String socketId) {
        RealtimeSessionRunner.RunHandle handle = runs.get(socketId);
        if (handle != null) handle.stop();
        leases.release(sessionId, socketId);
        EventSink sink = eventSinks.remove(socketId);
        if (sink == null) return;
        try {
            sink.emit(Map.of("type", "error", "message", "会话租约续期失败，已停止实时会话"));
        } catch (Exception ignored) {
            // Socket ownership has already been released; sending the notice is best effort.
        }
    }

    private static String acquireMessage(RunnerLeaseManager.AcquireResult result) {
        return switch (result) {
            case LOCAL_BUSY -> "会话已在其他连接中运行";
            case REMOTE_BUSY -> "会话已在其他实例中运行";
            case UNAVAILABLE -> "会话锁服务暂不可用";
            case ACQUIRED -> throw new IllegalArgumentException("acquired has no failure message");
        };
    }

    private static String finishMessage(RunnerLeaseManager.FinishPermission permission) {
        return switch (permission) {
            case LOCAL_BUSY -> "会话正在其他连接中运行";
            case REMOTE_BUSY -> "会话正在其他实例中运行";
            case UNAVAILABLE -> "会话锁服务暂不可用";
            case ALLOWED -> throw new IllegalArgumentException("allowed has no failure message");
        };
    }
}
