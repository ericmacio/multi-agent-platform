package com.cognizant.emk.multiagent.domain.ratelimit;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Global rate-limit configuration aggregate (REQ-RL-004).
 *
 * <p>Carries the two stacked counters consumed by the Bucket4j gate
 * (per-minute and per-hour bandwidths) plus the audit fields. Defaults
 * (10 per-minute / 50 per-hour) are NOT carried by the domain: the
 * runtime reads the live row seeded by Flyway migration V003 — encoding
 * numeric defaults here would mask a missing seed.
 *
 * <p>{@code updatedBy} is an {@link Optional} because the seed row has
 * no admin author; every admin-driven update writes a non-empty value.
 */
public record RateLimitConfig(
        int perMinute,
        int perHour,
        OffsetDateTime updatedAt,
        Optional<UserId> updatedBy) {

    public RateLimitConfig {
        if (perMinute < 1) {
            throw new ValidationException("perMinute", "must be at least 1");
        }
        if (perHour < 1) {
            throw new ValidationException("perHour", "must be at least 1");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(updatedBy, "updatedBy");
    }
}
