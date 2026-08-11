package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Metadata-only response body for {@code GET /admin/api-keys} and
 * {@code PATCH /admin/api-keys/{clientId}}.
 *
 * <p>The cleartext API key is NOT included — it was shown once at creation in the
 * separate {@link ApiKeyCreatedResponse} DTO and is unrecoverable afterwards
 * (REQ-AUTH-012). The BCrypt hash is also not exposed (REQ-SEC-004).
 *
 * <p>Matches the {@code ApiKey} schema in {@code openapi.yaml}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiKeyResponse(
        String clientId,
        String label,
        boolean disabled,
        OffsetDateTime createdAt) {}
