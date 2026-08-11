package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Response body for a successful {@code POST /admin/api-keys}.
 *
 * <p>The {@code apiKey} field carries the <b>cleartext</b> API-key secret — it is
 * surfaced exactly once at creation time and is unrecoverable from the server thereafter
 * (REQ-AUTH-007). Every subsequent admin endpoint ({@code GET}, {@code PATCH}) uses the
 * sibling {@link ApiKeyResponse}, which omits the cleartext.
 *
 * <p>Matches the {@code ApiKeyCreated} schema in {@code openapi.yaml}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiKeyCreatedResponse(
        String clientId,
        String apiKey,
        String label,
        boolean disabled,
        OffsetDateTime createdAt) {}
