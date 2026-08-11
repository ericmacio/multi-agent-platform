package com.cognizant.emk.multiagent.infrastructure.ratelimit;

import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate;
import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate.TryAcquireResult;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit tests for {@link Bucket4jRateLimitGate}. Drives the bucket via
 * a virtualized {@link TimeMeter} so per-minute / per-hour boundaries are
 * exercised without {@code Thread.sleep}.
 */
@ExtendWith(MockitoExtension.class)
class Bucket4jRateLimitGateTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 6, 19, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private RateLimitConfigRepository repository;

    private VirtualTimeMeter clock;
    private Bucket4jRateLimitGate gate;

    @Test
    void allowed_under_the_per_minute_limit() {
        givenConfig(10, 50);
        gate.buildInitialBucket();

        for (int i = 0; i < 5; i++) {
            assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        }
    }

    @Test
    void denied_at_the_per_minute_boundary_with_retry_after_at_most_60s() {
        givenConfig(10, 50);
        gate.buildInitialBucket();

        for (int i = 0; i < 10; i++) {
            assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        }
        TryAcquireResult result = gate.tryAcquire();

        assertThat(result).isInstanceOfSatisfying(TryAcquireResult.Denied.class, denied -> {
            assertThat(denied.retryAfterSeconds()).isBetween(1, 60);
        });
    }

    @Test
    void denied_at_the_per_hour_boundary_with_retry_after_at_most_3600s() {
        givenConfig(999, 2);
        gate.buildInitialBucket();

        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        TryAcquireResult third = gate.tryAcquire();

        assertThat(third).isInstanceOfSatisfying(TryAcquireResult.Denied.class, denied ->
                assertThat(denied.retryAfterSeconds()).isBetween(1, 3600));
    }

    @Test
    void listener_rebuild_resets_the_bucket() {
        givenConfig(10, 50);
        gate.buildInitialBucket();
        // exhaust the bucket
        for (int i = 0; i < 10; i++) {
            gate.tryAcquire();
        }
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Denied.class);

        // Live admin update: shrink to (1, 1). The new bucket starts full.
        gate.onRateLimitConfigChanged(new RateLimitConfig(1, 1, NOW, Optional.empty()));
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Denied.class);
    }

    @Test
    void retry_after_is_floored_at_1_second() {
        givenConfig(60, 3600);
        gate.buildInitialBucket();
        // Burn through the bucket and check that the next denied call returns >= 1
        // even though per-minute refill is sub-second per token (1s/token at 60/min).
        for (int i = 0; i < 60; i++) {
            gate.tryAcquire();
        }
        TryAcquireResult result = gate.tryAcquire();
        assertThat(result).isInstanceOfSatisfying(TryAcquireResult.Denied.class, denied ->
                assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1));
    }

    @Test
    void clock_advance_refills_per_minute_bucket() {
        givenConfig(3, 999);
        gate.buildInitialBucket();
        gate.tryAcquire();
        gate.tryAcquire();
        gate.tryAcquire();
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Denied.class);

        clock.advanceSeconds(60);
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
    }

    @Test
    void lazy_initialization_fallback_when_event_was_not_fired() {
        givenConfig(2, 99);
        // Do NOT call buildInitialBucket(); tryAcquire() must build on demand.

        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Allowed.class);
        assertThat(gate.tryAcquire()).isInstanceOf(TryAcquireResult.Denied.class);
    }

    private void givenConfig(int perMinute, int perHour) {
        RateLimitConfig cfg = new RateLimitConfig(perMinute, perHour, NOW, Optional.empty());
        when(repository.load()).thenReturn(cfg);
        clock = new VirtualTimeMeter();
        gate = new Bucket4jRateLimitGate(
                repository,
                config -> Bucket4jRateLimitGate.baseBuilder(config)
                        .withCustomTimePrecision(clock)
                        .build());
    }

    /** A {@link TimeMeter} backed by a mutable monotonic clock — no {@code Thread.sleep}. */
    private static final class VirtualTimeMeter implements TimeMeter {

        private final AtomicLong nanos = new AtomicLong(0L);

        void advanceSeconds(long seconds) {
            nanos.addAndGet(seconds * 1_000_000_000L);
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }
}
