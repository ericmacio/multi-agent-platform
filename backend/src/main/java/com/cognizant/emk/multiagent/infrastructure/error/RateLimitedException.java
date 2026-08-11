package com.cognizant.emk.multiagent.infrastructure.error;

/**
 * Thrown by {@code RateLimitFilter} (US-13-005) when the global Bucket4j gate
 * denies a request. Mapped to HTTP 429 with {@code code = RATE_LIMITED} and a
 * {@code Retry-After} header by {@code GlobalExceptionHandler}
 * (REQ-RL-005 / REQ-API-004).
 *
 * <p>{@code retryAfterSeconds} is supplied by {@code RateLimitGate} (Bucket4j
 * nanos-to-wait ceil'd to seconds, with a floor of 1 — see
 * {@code Bucket4jRateLimitGate}). The handler surfaces it verbatim on the
 * response header; messages MUST NOT contain principal-identifying data
 * (REQ-SEC-004).
 */
public final class RateLimitedException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitedException(int retryAfterSeconds) {
        super("Global rate limit exceeded; retry in " + retryAfterSeconds + "s.");
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException(
                    "retryAfterSeconds must be at least 1, was " + retryAfterSeconds);
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
