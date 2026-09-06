package com.babelflux.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionTokenServiceTest {
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
