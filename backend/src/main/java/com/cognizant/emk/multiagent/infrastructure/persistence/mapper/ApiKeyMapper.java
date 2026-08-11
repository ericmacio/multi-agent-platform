package com.cognizant.emk.multiagent.infrastructure.persistence.mapper;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ApiKeyJpa;

/**
 * Translates between the {@link ApiKey} domain aggregate and the {@link ApiKeyJpa}
 * entity.
 *
 * <p>Pure Java — no Spring stereotypes, no JPA imports beyond the entity itself. The
 * adapter ({@code ApiKeyRepositoryAdapter}) is the single entry point that uses this
 * mapper.
 */
public final class ApiKeyMapper {

    private ApiKeyMapper() {}

    public static ApiKey toDomain(ApiKeyJpa jpa) {
        return new ApiKey(
                new ClientId(jpa.getClientId()),
                jpa.getApiKeyHash(),
                jpa.getLabel(),
                jpa.isDisabled(),
                jpa.getCreatedAt());
    }

    public static ApiKeyJpa toJpa(ApiKey apiKey) {
        return new ApiKeyJpa(
                apiKey.clientId().value(),
                apiKey.apiKeyHash(),
                apiKey.label(),
                apiKey.disabled(),
                apiKey.createdAt());
    }
}
