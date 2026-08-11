package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire response shape for {@code GET /admin/rate-limit} and {@code PUT /admin/rate-limit}.
 * Matches openapi {@code RateLimitConfig}.
 *
 * <p>{@code updatedBy} is nullable on the seed row (no admin has updated the config yet);
 * after the first admin update it carries the calling admin's UUID. Serialized with
 * {@link JsonInclude.Include#ALWAYS} via the default Jackson policy so the contract surface
 * is stable.
 */
public record RateLimitConfigResponse(
        int perMinute,
        int perHour,
        OffsetDateTime updatedAt,
        UUID updatedBy) {
}
