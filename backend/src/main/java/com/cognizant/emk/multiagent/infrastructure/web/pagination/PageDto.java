package com.cognizant.emk.multiagent.infrastructure.web.pagination;

import com.cognizant.emk.multiagent.domain.shared.Page;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.function.Function;

/**
 * Wire envelope for cursor-paginated list responses, matching the
 * {@code PageEnvelope} schema in {@code openapi.yaml}.
 *
 * <p>{@code items} carries the projected domain payload; the controller supplies the
 * item-level mapper. {@code nextCursor} is the base64url cursor encoded by
 * {@link CursorCodec}, or {@code null} on the last page (the {@code @JsonInclude(NON_NULL)}
 * keeps it out of the JSON in that case — the OpenAPI schema defines the field as
 * nullable).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageDto<T>(List<T> items, String nextCursor, int pageSize) {

    /**
     * Maps a domain {@link Page} to its DTO form by applying {@code itemMapper} to each
     * item and encoding {@code nextCursor} via {@code codec}.
     */
    public static <D, T> PageDto<T> of(Page<D> page, CursorCodec codec, Function<D, T> itemMapper) {
        List<T> mapped = page.items().stream().map(itemMapper).toList();
        String nextCursor = codec.encode(page.nextCursor());
        return new PageDto<>(mapped, nextCursor, page.pageSize());
    }
}
