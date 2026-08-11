package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;

/**
 * Translates the {@link ApiKey} domain aggregate to the metadata-only
 * {@link ApiKeyResponse} DTO.
 *
 * <p>The {@code apiKeyHash} field is intentionally <b>not</b> read by this mapper — it
 * never appears in the mapping expression below, so there is no risk of a future
 * refactor accidentally including it in any list/detail response.
 */
public final class ApiKeyResponseMapper {

    private ApiKeyResponseMapper() {}

    public static ApiKeyResponse toResponse(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.clientId().value(),
                apiKey.label(),
                apiKey.disabled(),
                apiKey.createdAt());
    }
}
