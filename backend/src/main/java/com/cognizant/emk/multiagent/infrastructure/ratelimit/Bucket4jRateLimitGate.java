package com.cognizant.emk.multiagent.infrastructure.ratelimit;

import com.cognizant.emk.multiagent.application.ratelimit.RateLimitConfigChangeListener;
import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.local.LocalBucketBuilder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Single in-JVM Bucket4j bucket with two stacked bandwidths (per-minute and
 * per-hour). REQ-RL-002 / REQ-RL-003 / REQ-RL-004 / REQ-RL-005.
 *
 * <p><b>Two stacked bandwidths</b> in one Bucket: Bucket4j's
 * {@code tryConsumeAndReturnRemaining} returns a {@link ConsumptionProbe} that
 * aggregates over all bandwidths. When a request is denied, the probe's
 * {@code getNanosToWaitForRefill()} reports the wait until the
 * <i>most-restrictive</i> bandwidth refills — exactly what {@code Retry-After}
 * should reflect.
 *
 * <p><b>Live rebuild</b>. The current {@link Bucket} sits behind a
 * {@code volatile} reference. {@link #onRateLimitConfigChanged} swaps it
 * atomically when an admin updates the configuration. The previously-consumed
 * tokens are NOT preserved across rebuild — see {@code DESIGN-CHOICES.md} for
 * the trade-off ("the new bucket starts full; in-flight clients get a one-shot
 * grace allotment up to {@code perMinute}"). At the v1 sizing (REQ-NFR-005)
 * this is harmless.
 *
 * <p><b>Cold start</b>. The bucket is built eagerly on
 * {@link ApplicationReadyEvent} so the first request never pays the
 * construction cost. If the seed row is missing, application startup fails
 * loudly via the repository's {@code IllegalStateException} — operators must
 * see it.
 *
 * <p><b>Retry-After rounding</b>. Bucket4j returns nanoseconds; we ceil to the
 * nearest second and floor at {@code 1} so the client never sees
 * {@code Retry-After: 0} on a denied request.
 */
@Component
public class Bucket4jRateLimitGate implements RateLimitGate, RateLimitConfigChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jRateLimitGate.class);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final RateLimitConfigRepository repository;
    private final BucketFactory bucketFactory;
    private volatile Bucket bucket;

    // @Autowired is required here because the class has two constructors (the
    // package-private one below is reserved for unit tests with a virtualized
    // TimeMeter); without it Spring cannot disambiguate. Documented exception to
    // the "no @Autowired on the sole constructor" project convention — same
    // pattern as JjwtTokenServiceAdapter.
    @Autowired
    public Bucket4jRateLimitGate(RateLimitConfigRepository repository) {
        this(repository, Bucket4jRateLimitGate::defaultBuild);
    }

    /**
     * Test-friendly constructor that lets a test inject a custom {@link BucketFactory}
     * (typically a {@code LocalBucketBuilder} with a virtualized {@code TimeMeter}).
     * Package-private — production code uses the public single-arg constructor.
     */
    Bucket4jRateLimitGate(RateLimitConfigRepository repository, BucketFactory bucketFactory) {
        this.repository = repository;
        this.bucketFactory = bucketFactory;
    }

    /**
     * Test-only factory that builds a gate using the supplied {@link TimeMeter} for
     * every bucket it constructs (initial build and listener-triggered rebuilds).
     * The integration test in US-13-007 uses this entry point to drive the bucket
     * through a virtualized clock without {@code Thread.sleep}.
     */
    public static Bucket4jRateLimitGate withCustomTimeMeter(
            RateLimitConfigRepository repository, TimeMeter timeMeter) {
        return new Bucket4jRateLimitGate(
                repository,
                config -> baseBuilder(config).withCustomTimePrecision(timeMeter).build());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void buildInitialBucket() {
        RateLimitConfig cfg = repository.load();
        this.bucket = bucketFactory.build(cfg);
        LOG.info("Rate-limit bucket initialized: {} per minute, {} per hour",
                cfg.perMinute(), cfg.perHour());
    }

    @Override
    public TryAcquireResult tryAcquire() {
        Bucket current = bucket;
        if (current == null) {
            // Should not happen — ApplicationReadyEvent fires before any request reaches
            // the filter chain. Build lazily as a defense-in-depth fallback so a test
            // that bypasses the event lifecycle does not NPE.
            synchronized (this) {
                if (bucket == null) {
                    bucket = bucketFactory.build(repository.load());
                }
                current = bucket;
            }
        }
        ConsumptionProbe probe = current.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new TryAcquireResult.Allowed();
        }
        return new TryAcquireResult.Denied(toRetryAfterSeconds(probe.getNanosToWaitForRefill()));
    }

    @Override
    public synchronized void onRateLimitConfigChanged(RateLimitConfig updated) {
        this.bucket = bucketFactory.build(updated);
        LOG.info("Rate-limit bucket rebuilt: {} per minute, {} per hour",
                updated.perMinute(), updated.perHour());
    }

    private static int toRetryAfterSeconds(long nanos) {
        if (nanos <= 0L) {
            return 1;
        }
        long seconds = (nanos + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND;
        if (seconds < 1L) {
            return 1;
        }
        if (seconds > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) seconds;
    }

    private static Bucket defaultBuild(RateLimitConfig cfg) {
        return baseBuilder(cfg).build();
    }

    /**
     * Package-private factory of a {@link LocalBucketBuilder} with the two
     * stacked bandwidths already attached. Tests pass a custom
     * {@link BucketFactory} that calls {@code withCustomTimePrecision(TimeMeter)}
     * on the builder to virtualize the clock.
     */
    static LocalBucketBuilder baseBuilder(RateLimitConfig cfg) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(cfg.perMinute())
                        .refillIntervally(cfg.perMinute(), Duration.ofMinutes(1))
                        .build())
                .addLimit(Bandwidth.builder()
                        .capacity(cfg.perHour())
                        .refillIntervally(cfg.perHour(), Duration.ofHours(1))
                        .build());
    }

    /** Strategy used to (re)build the live {@link Bucket} from a config snapshot. */
    @FunctionalInterface
    interface BucketFactory {
        Bucket build(RateLimitConfig config);
    }
}
