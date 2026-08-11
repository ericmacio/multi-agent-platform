package com.cognizant.emk.multiagent.application.ratelimit;

/**
 * Application-layer port consumed by {@code RateLimitFilter} (US-13-005). The
 * Bucket4j adapter (US-13-004) implements it.
 *
 * <p>Keeping the filter behind a small, testable port keeps Spring MVC and
 * Bucket4j on opposite sides of the seam: the filter never imports
 * {@code io.github.bucket4j.*}; the adapter never imports
 * {@code jakarta.servlet.*}.
 */
public interface RateLimitGate {

    /**
     * Attempt to consume one token from the global bucket.
     *
     * @return {@link TryAcquireResult.Allowed} if both stacked bandwidths
     *         (per-minute and per-hour) had capacity; otherwise a
     *         {@link TryAcquireResult.Denied} carrying the wait until the
     *         most-restrictive bandwidth refills, so the filter can populate
     *         the {@code Retry-After} header (REQ-RL-005).
     */
    TryAcquireResult tryAcquire();

    /** Outcome of {@link #tryAcquire()}. */
    sealed interface TryAcquireResult {

        /** Token consumed; the request proceeds. */
        record Allowed() implements TryAcquireResult {}

        /**
         * Token denied. {@code retryAfterSeconds} is {@code >= 1} (Bucket4j
         * nanos-to-wait ceil'd to seconds, with a floor of 1 so the client
         * never sees {@code Retry-After: 0}).
         */
        record Denied(int retryAfterSeconds) implements TryAcquireResult {
            public Denied {
                if (retryAfterSeconds < 1) {
                    throw new IllegalArgumentException(
                            "retryAfterSeconds must be at least 1, was " + retryAfterSeconds);
                }
            }
        }
    }
}
