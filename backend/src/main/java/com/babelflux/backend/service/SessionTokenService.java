package com.babelflux.backend.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class SessionTokenService {
    private static final Duration SESSION_TOKEN_TTL = Duration.ofHours(24);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, WebSocketTicket> tokens = new ConcurrentHashMap<>();
    private final Map<String, HandoffTicket> handoffs = new ConcurrentHashMap<>();
    private final Set<String> usedHandoffs = ConcurrentHashMap.newKeySet();

    public String issue(String sessionId) {
        cleanup();
        String token = randomToken("w_");
        tokens.put(token, new WebSocketTicket(sessionId, Instant.now().plus(SESSION_TOKEN_TTL)));
        return token;
    }

    public boolean valid(String sessionId, String token) {
        cleanup();
        WebSocketTicket ticket = token == null ? null : tokens.get(token);
        return ticket != null && ticket.sessionId().equals(sessionId) && ticket.expiresAt().isAfter(Instant.now());
    }

    public HandoffTicket issueHandoff(String sessionId, String source, String sourceLanguage,
                                      String targetLanguage, String displayMode) {
        cleanup();
        String token = randomToken("h_");
        HandoffTicket ticket = new HandoffTicket(token, sessionId, source, sourceLanguage,
                targetLanguage, displayMode, Instant.now().plusSeconds(300));
        handoffs.put(token, ticket);
        return ticket;
    }

    public synchronized HandoffTicket claimHandoff(String token) {
        if (token == null || token.isBlank()) throw new HandoffTokenException("invalid");
        if (usedHandoffs.contains(token)) throw new HandoffTokenException("used");
        HandoffTicket ticket = handoffs.remove(token);
        if (ticket == null) {
            cleanup();
            throw new HandoffTokenException("not_found");
        }
        if (!ticket.expiresAt().isAfter(Instant.now())) throw new HandoffTokenException("expired");
        usedHandoffs.add(token);
        return ticket;
    }

    public String issueHandoffWebSocket(String sessionId, Instant expiresAt) {
        cleanup();
        String token = randomToken("w_");
        tokens.put(token, new WebSocketTicket(sessionId, expiresAt));
        return token;
    }

    private String randomToken(String prefix) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void cleanup() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        handoffs.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    public record HandoffTicket(String token, String sessionId, String source,
                                String sourceLanguage, String targetLanguage,
                                String displayMode, Instant expiresAt) {}

    private record WebSocketTicket(String sessionId, Instant expiresAt) {}

    public static class HandoffTokenException extends RuntimeException {
        private final String code;
        public HandoffTokenException(String code) { super("handoff token " + code); this.code = code; }
        public String getCode() { return code; }
    }
}
