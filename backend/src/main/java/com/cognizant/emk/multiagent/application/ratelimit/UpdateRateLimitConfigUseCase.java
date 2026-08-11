package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;

/**
 * Use case for {@code PUT /admin/rate-limit} (REQ-RL-004) — replaces the live
 * counters and notifies the registered {@link RateLimitConfigChangeListener} so
 * the in-JVM bucket can rebuild atomically.
 */
public interface UpdateRateLimitConfigUseCase {

    RateLimitConfig update(UpdateRateLimitConfigCommand command);
}
