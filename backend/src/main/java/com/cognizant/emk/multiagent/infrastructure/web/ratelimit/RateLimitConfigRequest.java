package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Wire request body for {@code PUT /admin/rate-limit}. Matches openapi
 * {@code RateLimitConfigRequest}.
 *
 * <p>{@code @Min(1)} + {@code @NotNull} are caught by Spring Bean Validation
 * before the controller body runs; the EPIC-14 problem-details mapper surfaces
 * either as 400 {@code VALIDATION_ERROR} with {@code errors[]}. The domain
 * record and the use-case command re-validate (defense in depth).
 */
public record RateLimitConfigRequest(
        @NotNull @Min(1) Integer perMinute,
        @NotNull @Min(1) Integer perHour) {
}
