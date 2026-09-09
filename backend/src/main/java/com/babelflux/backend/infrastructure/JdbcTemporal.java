package com.babelflux.backend.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Normalizes scheduler timestamps to the precision of the portable schema.
 * MySQL TIMESTAMP columns without an explicit fractional precision round
 * values to whole seconds; truncating before binding keeps due-time comparisons
 * monotonic across MySQL and H2.
 */
public final class JdbcTemporal {
    private JdbcTemporal() {}

    public static Timestamp now() {
        return from(Instant.now());
    }

    /** Floors a timestamp to the whole-second precision used by the schema. */
    public static Timestamp from(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return Timestamp.from(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /** Rounds a future deadline up so second-precision storage never expires it early. */
    public static Timestamp future(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        Instant truncated = instant.truncatedTo(ChronoUnit.SECONDS);
        if (instant.equals(truncated)) return Timestamp.from(truncated);
        return Timestamp.from(truncated.plusSeconds(1));
    }

    /** Keeps past due times immediately claimable while rounding future times up. */
    public static Timestamp dueAt(Instant instant, Instant reference) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(reference, "reference");
        return instant.isAfter(reference) ? future(instant) : from(instant);
    }
}
