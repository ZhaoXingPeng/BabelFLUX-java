package com.babelflux.backend.websocket;

import com.babelflux.backend.infrastructure.RedisSessionRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Coordinates local ownership and the optional Redis runner lease without touching WebSocket protocol code. */
final class RunnerLeaseManager {
    private static final long TTL_SECONDS = 30;
    private static final long RENEW_SECONDS = 10;

    enum AcquireResult { ACQUIRED, LOCAL_BUSY, REMOTE_BUSY, UNAVAILABLE }
    enum FinishPermission { ALLOWED, LOCAL_BUSY, REMOTE_BUSY, UNAVAILABLE }

    private final RedisSessionRepository redis;
    private final ConcurrentMap<String, String> owners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;

    RunnerLeaseManager(RedisSessionRepository redis) {
        this.redis = redis;
        this.executor = redis == null ? null
                : Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("runner-lease-renew").factory());
    }

    AcquireResult acquire(String sessionId, String socketId) {
        if (owners.putIfAbsent(sessionId, socketId) != null) return AcquireResult.LOCAL_BUSY;
        if (redis == null) return AcquireResult.ACQUIRED;
        try {
            if (redis.tryAcquireRunnerLease(sessionId, socketId, Duration.ofSeconds(TTL_SECONDS))) {
                return AcquireResult.ACQUIRED;
            }
            owners.remove(sessionId, socketId);
            return AcquireResult.REMOTE_BUSY;
        } catch (RuntimeException error) {
            owners.remove(sessionId, socketId);
            return AcquireResult.UNAVAILABLE;
        }
    }

    FinishPermission canFinish(String sessionId, String socketId) {
        String owner = owners.get(sessionId);
        if (owner != null && !owner.equals(socketId)) return FinishPermission.LOCAL_BUSY;
        if (owner != null || redis == null) return FinishPermission.ALLOWED;
        try {
            return redis.runnerLeaseHeld(sessionId) ? FinishPermission.REMOTE_BUSY : FinishPermission.ALLOWED;
        } catch (RuntimeException error) {
            return FinishPermission.UNAVAILABLE;
        }
    }

    void scheduleRenewal(String sessionId, String socketId, Runnable onRenewalLost) {
        if (redis == null || executor == null) return;
        ScheduledFuture<?> task = executor.scheduleAtFixedRate(() -> {
            if (!socketId.equals(owners.get(sessionId))) return;
            boolean renewed;
            try {
                renewed = redis.renewRunnerLease(sessionId, socketId, Duration.ofSeconds(TTL_SECONDS));
            } catch (RuntimeException error) {
                renewed = false;
            }
            if (!renewed) onRenewalLost.run();
        }, RENEW_SECONDS, RENEW_SECONDS, TimeUnit.SECONDS);
        ScheduledFuture<?> previous = renewals.put(sessionId, task);
        if (previous != null) previous.cancel(false);
    }

    void release(String sessionId, String socketId) {
        if (!owners.remove(sessionId, socketId)) return;
        ScheduledFuture<?> renewal = renewals.remove(sessionId);
        if (renewal != null) renewal.cancel(false);
        if (redis == null) return;
        try {
            redis.releaseRunnerLease(sessionId, socketId);
        } catch (RuntimeException ignored) {
            // Redis TTL expires a lease that cannot be released during cleanup.
        }
    }

    @PreDestroy
    void shutdown() {
        renewals.values().forEach(task -> task.cancel(true));
        if (executor != null) executor.shutdownNow();
    }
}
