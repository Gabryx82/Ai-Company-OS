package com.aicompany.backend.security;

import com.aicompany.backend.user.service.LoginThrottleProbe;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-024 §2: attempts per client per minute, a sliding window. */
class LoginThrottleTest {

    @Test
    void aSourceOverItsBudgetWaitsForTheWindowToSlideAndOthersAreUnaffected() {
        MutableClock clock = new MutableClock();
        var throttle = LoginThrottleProbe.of(3, clock);

        assertThat(throttle.allow("10.0.0.1")).isTrue();
        assertThat(throttle.allow("10.0.0.1")).isTrue();
        assertThat(throttle.allow("10.0.0.1")).isTrue();
        assertThat(throttle.allow("10.0.0.1")).isFalse();
        assertThat(throttle.allow("10.0.0.2")).isTrue();

        clock.advance(Duration.ofSeconds(61));
        assertThat(throttle.allow("10.0.0.1")).isTrue();
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
