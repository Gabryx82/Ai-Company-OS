package com.aicompany.backend.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The time an entity records, at the precision PostgreSQL keeps ({@code timestamptz}:
 * microseconds). {@code Instant.now()} has more digits on this platform, so the
 * response of a write showed {@code …123456789Z} and the next read {@code …123456Z}:
 * the same state in two different representations, and since PHASE 32 (TD-32) two
 * different entity-tags. Every timestamp an entity sets comes from here.
 */
public final class DatabaseClock {

    private DatabaseClock() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
