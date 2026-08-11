package com.cognizant.emk.multiagent.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Mutable {@link Clock} used by time-aware adapter tests to advance "now" without
 * {@code Thread.sleep}.
 *
 * <p>Pulled up into a single shared utility (US-CR1-003) so the JJWT adapter, the JWT
 * denylist adapter, and the logout end-to-end test all observe the same virtualized
 * notion of time. Previously each test rolled its own variant, which prevented expressing
 * round-trip "issued at T, verified at T+lifetime+1s" assertions in a single clock.
 */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(initial, ZoneOffset.UTC);
    }

    public MutableClock(Instant initial, ZoneId zone) {
        this.instant = initial;
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration delta) {
        this.instant = instant.plus(delta);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }
}
