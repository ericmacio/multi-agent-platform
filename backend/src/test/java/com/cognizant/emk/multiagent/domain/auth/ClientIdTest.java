package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIdTest {

    @Test
    void accepts_a_well_formed_value() {
        ClientId id = new ClientId("abc123_DEF-456");
        assertThat(id.value()).isEqualTo("abc123_DEF-456");
    }

    @Test
    void accepts_a_32_hex_uuid_without_dashes() {
        // The generator delivered in US-04-004 produces this shape; make sure we accept it.
        String uuidNoDashes = "0123456789abcdef0123456789abcdef";
        assertThat(new ClientId(uuidNoDashes).value()).isEqualTo(uuidNoDashes);
    }

    @Test
    void rejects_null_with_field_client_id() {
        assertThatThrownBy(() -> new ClientId(null))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("clientId");
                    assertThat(ex.getMessage()).isEqualTo("must not be empty");
                });
    }

    @Test
    void rejects_blank_input() {
        assertThatThrownBy(() -> new ClientId("   "))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("clientId");
                    assertThat(ex.getMessage()).isEqualTo("must not be empty");
                });
    }

    @Test
    void rejects_value_longer_than_64_chars() {
        String ok = "a".repeat(64);
        new ClientId(ok); // 64 chars — accepted

        String tooLong = "a".repeat(65);
        assertThatThrownBy(() -> new ClientId(tooLong))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("clientId");
                    assertThat(ex.getMessage()).contains("64");
                });
    }

    @Test
    void rejects_internal_whitespace() {
        assertThatThrownBy(() -> new ClientId("abc 123"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("clientId"));
    }

    @Test
    void rejects_forward_slash() {
        assertThatThrownBy(() -> new ClientId("path/like"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("clientId"));
    }

    @Test
    void rejects_colon() {
        assertThatThrownBy(() -> new ClientId("foo:bar"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("clientId"));
    }
}
