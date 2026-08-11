package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link CreateApiKeyUseCase} implementation.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Generate {@code (clientId, cleartext)} via {@link ApiKeyGenerator}.</li>
 *   <li>BCrypt-hash the cleartext via {@link ApiKeyHasher}.</li>
 *   <li>Persist a fresh {@link ApiKey} aggregate carrying only the hash.</li>
 *   <li>Return the cleartext in the result so the REST adapter can surface it once.</li>
 * </ol>
 *
 * <p>Label length is enforced by the domain (the {@code ApiKey} canonical constructor
 * rejects labels longer than 128 chars with a field-{@code label} {@code ValidationException});
 * the REST DTO also adds a {@code @Size(max = 128)} guard as defense in depth.
 *
 * <p>The cleartext API key is never logged. The hash never leaves the service.
 */
@Service
public class CreateApiKeyService implements CreateApiKeyUseCase {

    private final ApiKeyGenerator apiKeyGenerator;
    private final ApiKeyHasher apiKeyHasher;
    private final ApiKeyRepository apiKeyRepository;
    private final Clock clock;

    public CreateApiKeyService(
            ApiKeyGenerator apiKeyGenerator,
            ApiKeyHasher apiKeyHasher,
            ApiKeyRepository apiKeyRepository,
            Clock clock) {
        this.apiKeyGenerator = apiKeyGenerator;
        this.apiKeyHasher = apiKeyHasher;
        this.apiKeyRepository = apiKeyRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CreateApiKeyResult create(CreateApiKeyCommand command) {
        GeneratedApiKey generated = apiKeyGenerator.generate();
        String hash = apiKeyHasher.hash(generated.cleartextApiKey());
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);

        ApiKey persisted = apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                hash,
                command.label(),
                false,
                now));

        return new CreateApiKeyResult(
                persisted.clientId(),
                generated.cleartextApiKey(),
                persisted.label(),
                persisted.disabled(),
                persisted.createdAt());
    }
}
