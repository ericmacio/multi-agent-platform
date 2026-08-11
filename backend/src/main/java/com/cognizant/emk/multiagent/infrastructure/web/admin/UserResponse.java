package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.user.Role;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Metadata-only response body for every {@code /admin/users} endpoint.
 *
 * <p>{@code passwordHash} is intentionally absent — this DTO is the only shape the
 * admin user endpoints serialize, so the cleartext hash never reaches a wire format.
 * Matches the {@code User} schema in {@code openapi.yaml}.
 */
public record UserResponse(
        UUID id,
        String email,
        Role role,
        boolean disabled,
        boolean mustChangePassword,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
