package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.babelflux.backend.infrastructure.RedisSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionTokenServiceTest {
    @Test
    void delegatesTicketsToRedisAndMapsAtomicClaimStatuses() {
        RedisSessionRepository redis = mock(RedisSessionRepository.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T00:00:00Z"));
        SessionTokenService tokens = new SessionTokenService(new MutableClock(now), redis);

        SessionTokenService.HandoffTicket issued = tokens.issueHandoff("session-redis", "mic", "en", "zh", "bilingual");
        verify(redis).saveHandoff(issued, Duration.ofSeconds(300));

        when(redis.claimHandoff(issued.token())).thenReturn(
                new RedisSessionRepository.ClaimResult(RedisSessionRepository.Status.CLAIMED, issued));
        assertEquals(issued, tokens.claimHandoff(issued.token()));

        when(redis.claimHandoff("used")).thenReturn(
                new RedisSessionRepository.ClaimResult(RedisSessionRepository.Status.USED, null));
        SessionTokenService.HandoffTokenException used = assertThrows(
                SessionTokenService.HandoffTokenException.class, () -> tokens.claimHandoff("used"));
        assertEquals("used", used.getCode());

        when(redis.claimHandoff("expired")).thenReturn(
                new RedisSessionRepository.ClaimResult(RedisSessionRepository.Status.EXPIRED, null));
        SessionTokenService.HandoffTokenException expired = assertThrows(
                SessionTokenService.HandoffTokenException.class, () -> tokens.claimHandoff("expired"));
        assertEquals("expired", expired.getCode());
    }

    @Test
    void writesWebSocketTicketToRedisForCrossInstanceValidation() {
        RedisSessionRepository redis = mock(RedisSessionRepository.class);
        Instant now = Instant.parse("2026-09-07T00:00:00Z");
        SessionTokenService tokens = new SessionTokenService(Clock.fixed(now, ZoneId.of("UTC")), redis);

        String token = tokens.issue("session-redis");
        verify(redis).saveWebSocket(any(SessionTokenService.WebSocketTicket.class), any(Duration.class));
        when(redis.findWebSocket(token)).thenReturn(java.util.Optional.of(
                new SessionTokenService.WebSocketTicket(token, "session-redis", now.plusSeconds(60))));
        org.junit.jupiter.api.Assertions.assertTrue(tokens.valid("session-redis", token));
    }

    @Test
    void doesNotSilentlyFallBackWhenRedisIsUnavailable() {
        RedisSessionRepository redis = mock(RedisSessionRepository.class);
        doThrow(new IllegalStateException("connection refused")).when(redis)
                .saveWebSocket(any(SessionTokenService.WebSocketTicket.class), any(Duration.class));
        SessionTokenService tokens = new SessionTokenService(Clock.systemUTC(), redis);

        assertThrows(SessionTokenService.TokenStateUnavailableException.class,
                () -> tokens.issue("session-redis"));
    }

    @Test
    void keepsReplayConflictOnlyUntilHandoffExpiry() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T00:00:00Z"));
        SessionTokenService tokens = new SessionTokenService(new MutableClock(now));

        String handoff = tokens.issueHandoff("session-1", null, "en", "zh", "bilingual").token();
        tokens.claimHandoff(handoff);
        SessionTokenService.HandoffTokenException replay = assertThrows(
                SessionTokenService.HandoffTokenException.class, () -> tokens.claimHandoff(handoff));
        assertEquals("used", replay.getCode());

        now.set(Instant.parse("2026-09-07T00:05:01Z"));
        SessionTokenService.HandoffTokenException afterExpiry = assertThrows(
                SessionTokenService.HandoffTokenException.class, () -> tokens.claimHandoff(handoff));
        assertEquals("not_found", afterExpiry.getCode());
    }

    @Test
    void expiredUnclaimedHandoffKeepsExpiredContract() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-07T00:00:00Z"));
        SessionTokenService tokens = new SessionTokenService(new MutableClock(now));
        String handoff = tokens.issueHandoff("session-1", null, "en", "zh", "bilingual").token();

        now.set(Instant.parse("2026-09-07T00:05:01Z"));
        SessionTokenService.HandoffTokenException expired = assertThrows(
                SessionTokenService.HandoffTokenException.class, () -> tokens.claimHandoff(handoff));
        assertEquals("expired", expired.getCode());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(AtomicReference<Instant> now) { this.now = now; }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
