package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyNotFoundException;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link UpdateApiKeyUseCase} implementation.
 *
 * <p>Verifies the {@code clientId} exists (404 {@code NOT_FOUND} via {@link ApiKeyNotFoundException}
 * otherwise), then writes the new {@code disabled} flag via the repository's partial-update
 * path so the BCrypt hash never round-trips through the domain. Returns
 * {@code existing.withDisabled(...)} rather than re-reading from the database — both
 * approaches are valid per the story; this one saves a round trip and matches what the
 * row will look like after the transaction commits.
 */
@Service
public class UpdateApiKeyService implements UpdateApiKeyUseCase {

    private final ApiKeyRepository apiKeyRepository;

    public UpdateApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    @Transactional
    public ApiKey updateDisabled(UpdateApiKeyCommand command) {
        ApiKey existing = apiKeyRepository.findByClientId(command.clientId())
                .orElseThrow(() -> new ApiKeyNotFoundException(command.clientId()));
        apiKeyRepository.updateDisabled(command.clientId(), command.disabled());
        return existing.withDisabled(command.disabled());
    }
}
