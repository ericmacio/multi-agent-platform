package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTest {

    @Test
    void accepts_a_well_formed_address() {
        Email email = new Email("eric.macioszczyk@cognizant.com");
        assertThat(email.value()).isEqualTo("eric.macioszczyk@cognizant.com");
    }

    @Test
    void rejects_null_with_field_email() {
        assertThatThrownBy(() -> new Email(null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("email"));
    }

    @Test
    void rejects_blank_input() {
        assertThatThrownBy(() -> new Email("   "))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("email");
                    assertThat(ex.getMessage()).isEqualTo("must not be empty");
                });
    }

    @Test
    void rejects_address_without_at_sign() {
        assertThatThrownBy(() -> new Email("notanemail"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("email");
                    assertThat(ex.getMessage()).isEqualTo("must be a valid email address");
                });
    }

    @Test
    void rejects_address_without_dot_in_domain() {
        assertThatThrownBy(() -> new Email("a@b"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("email"));
    }

    @Test
    void canonicalizes_mixed_case_input_to_lowercase() {
        Email email = new Email("Alice@Example.Com");
        assertThat(email.value()).isEqualTo("alice@example.com");
    }

    @Test
    void canonicalizes_upper_case_input_to_lowercase() {
        Email email = new Email("ALICE@EXAMPLE.COM");
        assertThat(email.value()).isEqualTo("alice@example.com");
    }

    @Test
    void two_emails_differing_only_in_case_are_equal() {
        assertThat(new Email("Bob@Example.Com")).isEqualTo(new Email("bob@example.com"));
    }

    @Test
    void rejects_input_with_internal_whitespace() {
        // Whitespace is not part of a valid email; the regex forbids it. Reject (do not trim).
        assertThatThrownBy(() -> new Email("alice @example.com"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("email"));
    }

    @Test
    void rejects_address_longer_than_254_chars() {
        // 250-char local part + "@a.b" = 254 chars exactly → accepted; bump to 251 → rejected.
        String localOk = "a".repeat(250);
        new Email(localOk + "@a.b"); // 254 chars — accepted

        String localTooLong = "a".repeat(251);
        assertThatThrownBy(() -> new Email(localTooLong + "@a.b"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("email");
                    assertThat(ex.getMessage()).contains("254");
                });
    }
}
