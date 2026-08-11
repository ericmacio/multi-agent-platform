package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.user.UserId;

/**
 * Maps the domain {@link RateLimitConfig} aggregate to the wire
 * {@link RateLimitConfigResponse}. The domain {@code Optional<UserId>} becomes a
 * nullable {@code UUID} so the JSON shape matches openapi exactly.
 */
final class RateLimitConfigResponseMapper {

    private RateLimitConfigResponseMapper() {}

    static RateLimitConfigResponse toResponse(RateLimitConfig config) {
        return new RateLimitConfigResponse(
                config.perMinute(),
                config.perHour(),
                config.updatedAt(),
                config.updatedBy().map(UserId::value).orElse(null));
    }
}
