package com.cognizant.emk.multiagent.domain.ratelimit;

import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Instant;

/**
 * Domain repository port for the single {@link RateLimitConfig} row (REQ-RL-004).
 *
 * <p>The {@code rate_limit_config} table is single-row by construction (primary
 * key constrained to {@code id = 1}, seeded by Flyway V003). This port reflects
 * that: there is no listing and no creation — only load and save.
 */
public interface RateLimitConfigRepository {

    /**
     * Reads the live configuration row.
     *
     * @throws IllegalStateException when no row exists. That would mean the
     *         Flyway seed (V003) did not apply; operators must see this loudly
     *         rather than silently defaulting to a hard-coded 10/50 in code.
     */
    RateLimitConfig load();

    /**
     * Persists the new counters along with {@code updatedAt = now} and
     * {@code updatedBy = updatedBy}. Returns the aggregate as it now exists in
     * the database (so callers see the round-tripped {@code OffsetDateTime}
     * truncated by Postgres).
     */
    RateLimitConfig save(RateLimitConfig updated, UserId updatedBy, Instant now);
}
