package com.babelflux.backend.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcTemporalTest {
    @Test
    void truncatesSchedulerValuesToPortableTimestampPrecision() {
        Instant input = Instant.parse("2026-09-09T00:00:01.987654321Z");

        assertEquals(Instant.parse("2026-09-09T00:00:01Z"), JdbcTemporal.from(input).toInstant());
    }

    @Test
    void roundsFutureDeadlineUpToAvoidEarlyClaim() {
        Instant input = Instant.parse("2026-09-09T00:00:01.100000000Z");

        assertEquals(Instant.parse("2026-09-09T00:00:02Z"), JdbcTemporal.future(input).toInstant());
    }

    @Test
    void keepsPastDueRetryImmediatelyClaimable() {
        Instant reference = Instant.parse("2026-09-09T00:00:01.900000000Z");
        Instant input = Instant.parse("2026-09-09T00:00:01.100000000Z");

        assertEquals(Instant.parse("2026-09-09T00:00:01Z"), JdbcTemporal.dueAt(input, reference).toInstant());
    }
}
