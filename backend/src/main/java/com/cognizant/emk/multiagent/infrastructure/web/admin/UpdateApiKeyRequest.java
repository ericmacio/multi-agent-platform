package com.cognizant.emk.multiagent.infrastructure.web.admin;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /admin/api-keys/{clientId}}.
 *
 * <p>{@code disabled} is mandatory; both {@code true} (soft-revoke) and {@code false}
 * (re-enable) are accepted per the OpenAPI contract.
 */
public record UpdateApiKeyRequest(@NotNull Boolean disabled) {}
