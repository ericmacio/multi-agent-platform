package com.cognizant.emk.multiagent.domain.shared;

import java.util.List;
import java.util.Objects;

/**
 * Generic page envelope for keyset-paginated list ports (design §10).
 *
 * <p>{@code nextCursor} is {@code null} on the last page. {@code pageSize} carries the
 * requested page size (not the actual {@code items.size()}) so the controller can echo
 * it back in the {@code PageDto} response (REQ-API-005).
 *
 * <p>Lives under {@code domain/shared} for the same reason as {@link Cursor}: the
 * domain repository ports return it directly, and the hexagonal layering rule forbids
 * the domain from referencing the application or infrastructure layers.
 */
public record Page<T>(List<T> items, Cursor nextCursor, int pageSize) {

    public Page {
        Objects.requireNonNull(items, "items");
        items = List.copyOf(items);
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
    }
}
