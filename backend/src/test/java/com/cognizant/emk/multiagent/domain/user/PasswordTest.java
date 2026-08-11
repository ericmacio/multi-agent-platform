package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordTest {

    @Test
    void accepts_a_policy_compliant_password() {
        Password password = new Password("Str0ng!Passw0rd");
        assertThat(password.cleartext()).isEqualTo("Str0ng!Passw0rd");
    }

    @Test
    void rejects_null_input() {
        assertThatThrownBy(() -> new Password(null))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("password");
                    assertThat(ex.getMessage()).contains("at least 10");
                });
    }

    @Test
    void rejects_password_shorter_than_10_chars() {
        assertThatThrownBy(() -> new Password("Sh0rt!"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("password");
                    assertThat(ex.getMessage()).contains("at least 10");
                });
    }

    @Test
    void rejects_password_without_uppercase() {
        assertThatThrownBy(() -> new Password("nouppercase!"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("password");
                    assertThat(ex.getMessage()).contains("uppercase");
                });
    }

    @Test
    void rejects_password_without_special_char() {
        assertThatThrownBy(() -> new Password("NoSpecial1234"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("password");
                    assertThat(ex.getMessage()).contains("special character");
                });
    }

    @Test
    void toString_does_not_leak_the_cleartext() {
        Password password = new Password("Str0ng!Passw0rd");
        assertThat(password.toString()).isEqualTo("Password{***}");
    }
}
