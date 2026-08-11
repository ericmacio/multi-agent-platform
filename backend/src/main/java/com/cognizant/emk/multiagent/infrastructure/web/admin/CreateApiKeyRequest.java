package com.cognizant.emk.multiagent.infrastructure.web.admin;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /admin/api-keys}.
 *
 * <p>{@code label} is optional; when present it is bounded at 128 characters by both
 * this bean-validation guard and the domain {@code ApiKey} aggregate.
 */
public record CreateApiKeyRequest(@Size(max = 128) String label) {}
