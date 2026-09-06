package com.babelflux.backend.service;

import com.babelflux.backend.infrastructure.RedisSessionRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SessionTokenService {
    private static final Duration SESSION_TOKEN_TTL = Duration.ofHours(24);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, WebSocketTicket> tokens = new ConcurrentHashMap<>();
    private final Map<String, HandoffTicket> handoffs = new ConcurrentHashMap<>();
    private final Map<String, Instant> usedHandoffs = new ConcurrentHashMap<>();
    private final RedisSessionRepository redis;

    public SessionTokenService() { this(Clock.systemUTC()); }

    @Autowired
    public SessionTokenService(ObjectProvider<RedisSessionRepository> redisProvider) {
        this(Clock.systemUTC(), redisProvider.getIfAvailable());
    }

    SessionTokenService(Clock clock) { this(clock, null); }

    SessionTokenService(Clock clock, RedisSessionRepository redis) {
        this.clock = clock;
        this.redis = redis;
    }

    public String issue(String sessionId) {
        cleanup();
        String token = randomToken("w_");
        WebSocketTicket ticket = new WebSocketTicket(token, sessionId, Instant.now(clock).plus(SESSION_TOKEN_TTL));
        try {
            if (redis == null) tokens.put(token, ticket);
            else redis.saveWebSocket(ticket, SESSION_TOKEN_TTL);
        } catch (RuntimeException error) {
            throw new TokenStateUnavailableException(error);
        }
        return token;
    }

    public boolean valid(String sessionId, String token) {
        cleanup();
        Optional<WebSocketTicket> ticket;
        try {
            ticket = redis == null ? Optional.ofNullable(token == null ? null : tokens.get(token))
                    : redis.findWebSocket(token);
        } catch (RuntimeException error) {
            throw new TokenStateUnavailableException(error);
        }
        return ticket.isPresent() && ticket.get().sessionId().equals(sessionId)
                && ticket.get().expiresAt().isAfter(Instant.now(clock));
    }

    public HandoffTicket issueHandoff(String sessionId, String source, String sourceLanguage,
                                      String targetLanguage, String displayMode) {
        cleanup();
        String token = randomToken("h_");
        HandoffTicket ticket = new HandoffTicket(token, sessionId, source, sourceLanguage,
                targetLanguage, displayMode, Instant.now(clock).plusSeconds(300));
        try {
            if (redis == null) handoffs.put(token, ticket);
            else redis.saveHandoff(ticket, Duration.ofSeconds(300));
        } catch (RuntimeException error) {
            throw new TokenStateUnavailableException(error);
        }
        return ticket;
    }

    public HandoffTicket claimHandoff(String token) {
        if (token == null || token.isBlank()) throw new HandoffTokenException("invalid");
        if (redis != null) return claimDistributed(token);
        return claimLocal(token);
    }

    private synchronized HandoffTicket claimLocal(String token) {
        cleanupUsedHandoffs();
        if (usedHandoffs.containsKey(token)) throw new HandoffTokenException("used");
        HandoffTicket ticket = handoffs.remove(token);
        if (ticket == null) {
            throw new HandoffTokenException("not_found");
        }
        if (!ticket.expiresAt().isAfter(Instant.now(clock))) throw new HandoffTokenException("expired");
        usedHandoffs.put(token, ticket.expiresAt());
        return ticket;
    }

    private HandoffTicket claimDistributed(String token) {
        RedisSessionRepository.ClaimResult result;
        try {
            result = redis.claimHandoff(token);
        } catch (RuntimeException error) {
            throw new TokenStateUnavailableException(error);
        }
        return switch (result.status()) {
            case CLAIMED -> result.ticket();
            case USED -> throw new HandoffTokenException("used");
            case EXPIRED -> throw new HandoffTokenException("expired");
            case NOT_FOUND -> throw new HandoffTokenException("not_found");
        };
    }

    public String issueHandoffWebSocket(String sessionId, Instant expiresAt) {
        cleanup();
        String token = randomToken("w_");
        WebSocketTicket ticket = new WebSocketTicket(token, sessionId, expiresAt);
        Duration ttl = Duration.between(Instant.now(clock), expiresAt);
        try {
            if (redis == null) tokens.put(token, ticket);
            else if (!ttl.isNegative() && !ttl.isZero()) redis.saveWebSocket(ticket, ttl);
        } catch (RuntimeException error) {
            throw new TokenStateUnavailableException(error);
        }
        return token;
    }

    private String randomToken(String prefix) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void cleanup() {
        Instant now = Instant.now(clock);
        tokens.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        handoffs.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        cleanupUsedHandoffs(now);
    }

    private void cleanupUsedHandoffs() { cleanupUsedHandoffs(Instant.now(clock)); }

    private void cleanupUsedHandoffs(Instant now) {
        usedHandoffs.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    public record HandoffTicket(String token, String sessionId, String source,
                                String sourceLanguage, String targetLanguage,
                                String displayMode, Instant expiresAt) {}

    public record WebSocketTicket(String token, String sessionId, Instant expiresAt) {}

    public static class HandoffTokenException extends RuntimeException {
        private final String code;
        public HandoffTokenException(String code) { super("handoff token " + code); this.code = code; }
        public String getCode() { return code; }
    }

    public static class TokenStateUnavailableException extends RuntimeException {
        public TokenStateUnavailableException(Throwable cause) {
            super("token state store unavailable", cause);
        }
    }
}
