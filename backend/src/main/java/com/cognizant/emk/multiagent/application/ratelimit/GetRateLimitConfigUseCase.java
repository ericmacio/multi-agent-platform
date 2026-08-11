package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;

/**
 * Use case for {@code GET /admin/rate-limit} (REQ-RL-004).
 *
 * <p>Exists for symmetry with the use-case-per-endpoint pattern used by the rest
 * of the application layer. Keeping a thin use case (rather than letting the
 * controller call the repository directly) keeps {@code @PreAuthorize} concerns
 * out of the domain and makes the call trivially mockable in controller tests.
 */
public interface GetRateLimitConfigUseCase {

    RateLimitConfig load();
}
