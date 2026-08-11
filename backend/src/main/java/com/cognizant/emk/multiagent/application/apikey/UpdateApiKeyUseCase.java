package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyNotFoundException;
import com.cognizant.emk.multiagent.domain.auth.ClientId;

/**
 * Use case for {@code PATCH /admin/api-keys/{clientId}} (REQ-AUTH-012).
 *
 * <p>Toggles the {@code disabled} flag on the API key identified by {@code clientId}.
 * A subsequent authentication attempt with a disabled key surfaces the generic 401
 * {@code INVALID_CREDENTIALS} response (handled by the {@code ApiKeyAuthenticationFilter}
 * delivered in US-04-009).
 */
public interface UpdateApiKeyUseCase {

    /**
     * Toggles the {@code disabled} flag and returns the updated metadata.
     *
     * @throws ApiKeyNotFoundException when no API key matches {@code command.clientId()}.
     */
    ApiKey updateDisabled(UpdateApiKeyCommand command);

    record UpdateApiKeyCommand(ClientId clientId, boolean disabled) {}
}
