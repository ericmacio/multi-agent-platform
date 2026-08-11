package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import java.util.Optional;

/**
 * Domain repository port for the {@link ApiKey} aggregate (design §4.1).
 *
 * <p>Pure Java — no Spring annotations. The adapter implementing this interface lives
 * under {@code infrastructure/persistence/adapter/} and bridges to Spring Data JPA.
 *
 * <p>Only the operations consumed by EPIC-04 are declared here. {@link #listAll}
 * supports the cursor-paginated admin listing (US-04-007) with ordering
 * {@code (created_at DESC, client_id DESC)} so newest API keys come first.
 * {@link #updateDisabled} backs the partial-update path of US-04-008 without having to
 * round-trip the BCrypt hash through the domain.
 */
public interface ApiKeyRepository {

    Optional<ApiKey> findByClientId(ClientId clientId);

    ApiKey save(ApiKey apiKey);

    /**
     * Returns one page of API keys in descending {@code (createdAt, clientId)} order.
     * Pass {@code cursor = null} for the first page. {@code pageSize} is the maximum
     * number of items in the returned page; the adapter is responsible for detecting
     * the presence of a next page and emitting a non-null {@link Page#nextCursor()}
     * accordingly.
     */
    Page<ApiKey> listAll(Cursor cursor, int pageSize);

    /**
     * Partial update of the {@code disabled} flag for the API key identified by
     * {@code clientId}. Used by US-04-008 to avoid materializing the full aggregate
     * (and notably its BCrypt hash) on a soft-revoke / re-enable.
     */
    void updateDisabled(ClientId clientId, boolean disabled);
}
