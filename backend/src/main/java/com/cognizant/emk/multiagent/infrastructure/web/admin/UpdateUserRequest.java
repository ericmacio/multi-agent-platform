package com.cognizant.emk.multiagent.infrastructure.web.admin;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /admin/users/{userId}}.
 *
 * <p>{@code disabled} is mandatory; both {@code true} (disable) and {@code false}
 * (re-enable) are accepted.
 */
public record UpdateUserRequest(@NotNull Boolean disabled) {
}
