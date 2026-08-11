package com.cognizant.emk.multiagent.infrastructure.persistence.adapter;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ApiKeyJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.mapper.ApiKeyMapper;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.ApiKeyJpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA-backed adapter for the {@link ApiKeyRepository} domain port.
 *
 * <p>Bridges the domain {@link ApiKey} aggregate to the {@link ApiKeyJpa} entity through
 * {@link ApiKeyMapper}. The domain layer never sees JPA types; the application layer
 * never sees Spring Data.
 *
 * <p>{@link #listAll} implements keyset pagination by fetching {@code pageSize + 1}
 * rows: if the result exceeds {@code pageSize}, the surplus row signals a next page and
 * the last item in the trimmed result becomes the encoded continuation cursor.
 */
@Component
public class ApiKeyRepositoryAdapter implements ApiKeyRepository {

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 100;

    private final ApiKeyJpaRepository apiKeyJpaRepository;

    public ApiKeyRepositoryAdapter(ApiKeyJpaRepository apiKeyJpaRepository) {
        this.apiKeyJpaRepository = apiKeyJpaRepository;
    }

    @Override
    public Optional<ApiKey> findByClientId(ClientId clientId) {
        return apiKeyJpaRepository.findById(clientId.value()).map(ApiKeyMapper::toDomain);
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        return ApiKeyMapper.toDomain(apiKeyJpaRepository.save(ApiKeyMapper.toJpa(apiKey)));
    }

    @Override
    public Page<ApiKey> listAll(Cursor cursor, int pageSize) {
        if (pageSize < PAGE_SIZE_MIN || pageSize > PAGE_SIZE_MAX) {
            throw new IllegalArgumentException(
                    "pageSize must be within [" + PAGE_SIZE_MIN + ", " + PAGE_SIZE_MAX + "]");
        }
        int limit = pageSize + 1;
        PageRequest probe = PageRequest.of(0, limit);
        List<ApiKeyJpa> rows = (cursor == null)
                ? apiKeyJpaRepository.findFirstPage(probe)
                : apiKeyJpaRepository.findPageAfter(cursor.lastCreatedAt(), cursor.lastId(), probe);

        boolean hasMore = rows.size() > pageSize;
        List<ApiKey> items = rows.stream()
                .limit(pageSize)
                .map(ApiKeyMapper::toDomain)
                .toList();

        Cursor nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            ApiKey last = items.get(items.size() - 1);
            nextCursor = new Cursor(last.createdAt(), last.clientId().value());
        }
        return new Page<>(items, nextCursor, pageSize);
    }

    @Override
    @Transactional
    public void updateDisabled(ClientId clientId, boolean disabled) {
        apiKeyJpaRepository.updateDisabledByClientId(clientId.value(), disabled);
    }
}
