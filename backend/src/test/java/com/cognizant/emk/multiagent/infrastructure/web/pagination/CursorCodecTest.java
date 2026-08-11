package com.cognizant.emk.multiagent.infrastructure.web.pagination;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private CursorCodec codec;

    @BeforeEach
    void setUp() {
        codec = new CursorCodec();
    }

    @Test
    void round_trips_a_cursor_through_encode_then_decode() {
        OffsetDateTime when = OffsetDateTime.of(2026, 5, 12, 8, 30, 0, 0, ZoneOffset.UTC);
        Cursor original = new Cursor(when, "abc123");

        String encoded = codec.encode(original);
        assertThat(encoded).isNotNull().isNotBlank();

        Cursor decoded = codec.decode(encoded);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void encode_null_cursor_returns_null() {
        assertThat(codec.encode(null)).isNull();
    }

    @Test
    void decode_null_or_blank_returns_null_for_first_page() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("")).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void decode_rejects_non_base64_input_with_field_cursor() {
        assertThatThrownBy(() -> codec.decode("not!valid!base64!"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("cursor");
                    assertThat(ex.getMessage()).isEqualTo("invalid cursor");
                });
    }

    @Test
    void decode_rejects_garbage_json_with_field_cursor() {
        // Valid base64 but not valid JSON.
        String junk = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "this is not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(junk))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("cursor"));
    }

    @Test
    void decode_rejects_well_formed_json_with_missing_fields() {
        String empty = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(empty))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("cursor"));
    }

    @Test
    void encoded_payload_uses_url_safe_alphabet() {
        Cursor cursor = new Cursor(OffsetDateTime.now(ZoneOffset.UTC), "abc-123_def");
        String encoded = codec.encode(cursor);
        // base64url alphabet: [A-Za-z0-9_-]; no padding.
        assertThat(encoded).matches("^[A-Za-z0-9_\\-]+$");
    }
}
