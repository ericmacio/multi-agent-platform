/**
 * Rate-limit bounded context — domain layer.
 *
 * <p>Carries {@link com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig}
 * (the immutable global counters + audit fields, REQ-RL-004) and the
 * {@link com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository}
 * port that the EPIC-13 use cases and the Bucket4j adapter consume.
 *
 * <p>Defaults (10 per-minute / 50 per-hour) live exclusively in the Flyway seed
 * (V003) — the domain never encodes numeric defaults so that a missing seed
 * fails loudly rather than silently falling back. REQ-RL-005 (Retry-After
 * surface) is the concern of the bucket adapter, not the domain.
 *
 * <p>US-13-001 / US-13-002 / US-13-003 populate this package.
 */
package com.cognizant.emk.multiagent.domain.ratelimit;
