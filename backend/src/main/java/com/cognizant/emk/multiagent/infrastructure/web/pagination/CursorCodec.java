package com.cognizant.emk.multiagent.infrastructure.web.pagination;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Encodes / decodes a {@link Cursor} as a base64url-encoded compact JSON payload
 * (design §10).
 *
 * <p>The wire format is intentionally opaque to clients: they round-trip the value
 * verbatim from a previous {@code nextCursor} into a follow-up {@code cursor} query
 * parameter. Internally, the payload is the JSON {@code {"t":"<iso>","i":"<id>"}}
 * base64url-encoded without padding (URL-safe, no escaping needed).
 *
 * <p>Decoding is strict: any IO / parse / format error surfaces as a 400
 * {@code VALIDATION_ERROR} with field {@code cursor} via the
 * {@code GlobalExceptionHandler}. A null or blank input decodes to {@code null}
 * (caller is asking for the first page).
 */
@Component
public class CursorCodec {

    // A private ObjectMapper, configured with JavaTimeModule for ISO-8601 OffsetDateTime
    // round-tripping. We do not inject the Spring-managed mapper: the cursor wire-format is
    // fully internal (clients treat it as opaque), so we want full control over its
    // serialization features and we don't want a future Spring configuration tweak to
    // change cursor encodings under us.
    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    public CursorCodec() {}

    public String encode(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(new Wire(cursor.lastCreatedAt(), cursor.lastId()));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (JsonProcessingException ex) {
            // Encoding a domain Cursor cannot fail under any non-exceptional condition;
            // surface as a 500 rather than masking the bug.
            throw new IllegalStateException("Failed to encode cursor", ex);
        }
    }

    public Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("cursor", "invalid cursor");
        }
        Wire wire;
        try {
            wire = objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), Wire.class);
        } catch (Exception ex) {
            throw new ValidationException("cursor", "invalid cursor");
        }
        if (wire == null || wire.lastCreatedAt() == null || wire.lastId() == null || wire.lastId().isBlank()) {
            throw new ValidationException("cursor", "invalid cursor");
        }
        return new Cursor(wire.lastCreatedAt(), wire.lastId());
    }

    /** Compact JSON wire-shape: {@code t} for created-at, {@code i} for id. */
    record Wire(@JsonProperty("t") OffsetDateTime lastCreatedAt, @JsonProperty("i") String lastId) {}
}
