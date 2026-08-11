package com.cognizant.emk.multiagent.application.apikey;

import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.shared.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListApiKeysUseCase} implementation.
 *
 * <p>Pure forwarder: the REST adapter has already decoded the opaque wire cursor into a
 * domain {@link com.cognizant.emk.multiagent.domain.shared.Cursor}, so this layer just
 * threads the call through to the repository.
 */
@Service
public class ListApiKeysService implements ListApiKeysUseCase {

    private final ApiKeyRepository apiKeyRepository;

    public ListApiKeysService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApiKey> list(ListApiKeysQuery query) {
        return apiKeyRepository.listAll(query.cursor(), query.pageSize().value());
    }
}
