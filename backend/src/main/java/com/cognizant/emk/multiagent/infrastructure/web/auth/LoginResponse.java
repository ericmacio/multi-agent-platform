package com.cognizant.emk.multiagent.infrastructure.web.auth;

import java.time.OffsetDateTime;

/**
 * Response body for {@code POST /auth/login}, mirroring the {@code LoginResponse} schema in
 * {@code openapi.yaml}. {@code tokenType} is always the literal {@code "Bearer"}.
 */
public record LoginResponse(
        String token,
        String tokenType,
        OffsetDateTime expiresAt,
        boolean mustChangePassword) {
}
