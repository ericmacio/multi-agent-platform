package com.cognizant.emk.multiagent.domain.shared;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Opaque continuation token for keyset pagination (design §10).
 *
 * <p>Carries the {@code (createdAt, id)} pair of the last item returned by the previous
 * page so the next query can fetch strictly older items via the keyset condition
 * {@code (createdAt, id) < (lastCreatedAt, lastId)}. {@code lastId} is modeled as a
 * {@link String} so the cursor can describe either a UUID (most aggregates) or a
 * {@code ClientId} value without forcing a generic type parameter.
 *
 * <p>Lives under {@code domain/shared} because the domain repository ports (notably
 * {@code ApiKeyRepository.listAll}) take it directly: the hexagonal layering rule
 * forbids the domain from referencing {@code application} or {@code infrastructure}.
 * The HTTP-layer codec and DTO envelope ({@code CursorCodec}, {@code PageDto}) live
 * in {@code infrastructure/web/pagination} (US-04-005).
 */
public record Cursor(OffsetDateTime lastCreatedAt, String lastId) {

    public Cursor {
        Objects.requireNonNull(lastCreatedAt, "lastCreatedAt");
        Objects.requireNonNull(lastId, "lastId");
        if (lastId.isBlank()) {
            throw new IllegalArgumentException("lastId must not be blank");
        }
    }
}
