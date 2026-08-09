package com.ugnay.platform.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleServiceTest {
    @Test
    void locksAfterConfiguredFailuresAndSuccessClearsTheWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-08T12:00:00Z"));
        LoginThrottleService throttle = new LoginThrottleService(3, Duration.ofMinutes(10), Duration.ofMinutes(15), clock);

        throttle.failure("ip|email");
        throttle.failure("ip|email");
        assertThat(throttle.allowed("ip|email")).isTrue();
        throttle.failure("ip|email");
        assertThat(throttle.allowed("ip|email")).isFalse();

        clock.advance(Duration.ofMinutes(16));
        assertThat(throttle.allowed("ip|email")).isTrue();
        throttle.failure("ip|email");
        throttle.success("ip|email");
        assertThat(throttle.allowed("ip|email")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
