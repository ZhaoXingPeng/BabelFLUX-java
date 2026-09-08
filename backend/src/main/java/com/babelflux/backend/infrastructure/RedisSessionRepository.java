package com.babelflux.backend.infrastructure;

import com.babelflux.backend.service.SessionTokenService.HandoffTicket;
import com.babelflux.backend.service.SessionTokenService.WebSocketTicket;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Distributed TTL store for handoff and WebSocket authentication tickets. */
@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "redis-enabled", havingValue = "true")
public class RedisSessionRepository {
    private static final String HANDOFF_PREFIX = "babelflux:token:handoff:";
    private static final String HANDOFF_USED_PREFIX = "babelflux:token:handoff:used:";
    private static final String WEBSOCKET_PREFIX = "babelflux:token:websocket:";
    private static final String RUNNER_LEASE_PREFIX = "babelflux:lease:runner:";
    private static final DefaultRedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("local used = redis.call('GET', KEYS[2]) "
            + "if used then return '__USED__' end "
            + "local value = redis.call('GET', KEYS[1]) "
            + "if value then "
            + "local ttl = redis.call('PTTL', KEYS[1]) "
            + "if ttl > 0 then redis.call('SET', KEYS[2], '1', 'PX', ttl) end "
            + "redis.call('DEL', KEYS[1]) "
            + "redis.call('DEL', KEYS[3]) "
            + "return '__CLAIMED__' .. value "
            + "end "
            + "if redis.call('GET', KEYS[3]) then redis.call('DEL', KEYS[3]); return '__EXPIRED__' end "
            + "return '__NOT_FOUND__'", String.class);
    private static final DefaultRedisScript<Long> ACQUIRE_RUNNER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE_RUNNER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW_RUNNER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisSessionRepository(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void saveHandoff(HandoffTicket ticket, Duration ttl) {
        String tokenKey = tokenKey(ticket.token());
        put(HANDOFF_PREFIX + tokenKey, ticket, ttl);
        // Keep a short tombstone so an unclaimed, expired ticket can still return 410.
        put(HANDOFF_USED_PREFIX + "expiry:" + tokenKey, "1", ttl.plusSeconds(60));
    }

    public ClaimResult claimHandoff(String token) {
        String tokenKey = tokenKey(token);
        String value = redis.execute(
                CLAIM_SCRIPT,
                java.util.List.of(HANDOFF_PREFIX + tokenKey, HANDOFF_USED_PREFIX + tokenKey,
                        HANDOFF_USED_PREFIX + "expiry:" + tokenKey));
        if (value == null || value.equals("__NOT_FOUND__")) return new ClaimResult(Status.NOT_FOUND, null);
        if (value.equals("__USED__")) return new ClaimResult(Status.USED, null);
        if (value.equals("__EXPIRED__")) return new ClaimResult(Status.EXPIRED, null);
        if (value.startsWith("__CLAIMED__")) return new ClaimResult(Status.CLAIMED,
                read(value.substring("__CLAIMED__".length()), HandoffTicket.class));
        throw new IllegalStateException("unexpected Redis handoff claim result");
    }

    public void saveWebSocket(WebSocketTicket ticket, Duration ttl) {
        put(WEBSOCKET_PREFIX + tokenKey(ticket.token()), ticket, ttl);
    }

    public Optional<WebSocketTicket> findWebSocket(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String value = redis.opsForValue().get(WEBSOCKET_PREFIX + tokenKey(token));
        return value == null ? Optional.empty() : Optional.of(read(value, WebSocketTicket.class));
    }

    /** Atomically acquires a primary realtime-runner lease for one session. */
    public boolean tryAcquireRunnerLease(String sessionId, String owner, Duration ttl) {
        validateLeaseArguments(sessionId, owner, ttl);
        Long result = redis.execute(ACQUIRE_RUNNER_SCRIPT, List.of(runnerKey(sessionId)), owner,
                Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    /** Extends a lease only when the caller still owns it. */
    public boolean renewRunnerLease(String sessionId, String owner, Duration ttl) {
        validateLeaseArguments(sessionId, owner, ttl);
        Long result = redis.execute(RENEW_RUNNER_SCRIPT, List.of(runnerKey(sessionId)), owner,
                Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    /** Releases a lease only when the owner token matches, preventing stale owners deleting new leases. */
    public boolean releaseRunnerLease(String sessionId, String owner) {
        if (sessionId == null || sessionId.isBlank() || owner == null || owner.isBlank()) return false;
        Long result = redis.execute(RELEASE_RUNNER_SCRIPT, List.of(runnerKey(sessionId)), owner);
        return Long.valueOf(1L).equals(result);
    }

    /** Returns whether any live instance currently holds the session runner lease. */
    public boolean runnerLeaseHeld(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return Boolean.TRUE.equals(redis.hasKey(runnerKey(sessionId)));
    }

    private static String tokenKey(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("token is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String runnerKey(String sessionId) { return RUNNER_LEASE_PREFIX + sessionId; }

    private static void validateLeaseArguments(String sessionId, String owner, Duration ttl) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("runner owner is required");
        if (ttl == null || ttl.isZero() || ttl.isNegative())
            throw new IllegalArgumentException("runner lease TTL must be positive");
    }

    private void put(String key, Object value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Redis ticket TTL must be positive");
        }
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("token cannot be serialized", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("token state is invalid", error);
        }
    }

    public enum Status { CLAIMED, USED, EXPIRED, NOT_FOUND }

    public record ClaimResult(Status status, HandoffTicket ticket) {}
}
