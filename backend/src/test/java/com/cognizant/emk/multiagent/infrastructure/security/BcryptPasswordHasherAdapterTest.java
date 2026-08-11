package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.domain.user.Password;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies the BCrypt-backed {@link com.cognizant.emk.multiagent.application.auth.PasswordHasher}
 * adapter at the cost factor 10 documented in design §8.5. No Spring context — the adapter
 * is instantiated directly.
 */
class BcryptPasswordHasherAdapterTest {

    private static final String COST10_PATTERN = "^\\$2[aby]\\$10\\$.{53}$";

    private final BcryptPasswordHasherAdapter hasher = new BcryptPasswordHasherAdapter();

    @Test
    void hash_produces_a_cost_factor_10_bcrypt_string() {
        Password password = new Password("Str0ng!Passw0rd");
        String hashed = hasher.hash(password);
        assertThat(hashed).matches(COST10_PATTERN);
    }

    @Test
    void hash_uses_a_random_salt_so_two_hashes_of_the_same_password_differ() {
        Password password = new Password("Str0ng!Passw0rd");
        assertThat(hasher.hash(password)).isNotEqualTo(hasher.hash(password));
    }

    @Test
    void matches_returns_true_for_the_original_password() {
        Password password = new Password("Str0ng!Passw0rd");
        String stored = hasher.hash(password);
        assertThat(hasher.matches(password, stored)).isTrue();
    }

    @Test
    void matches_returns_false_for_a_different_password() {
        String stored = hasher.hash(new Password("Str0ng!Passw0rd"));
        assertThat(hasher.matches(new Password("Other!Passw0rd"), stored)).isFalse();
    }

    @Test
    void matches_returns_false_when_storedHash_is_malformed() {
        Password password = new Password("Str0ng!Passw0rd");
        assertThatCode(() -> assertThat(hasher.matches(password, "not-a-bcrypt-hash")).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void matches_returns_false_when_storedHash_is_empty_or_null() {
        Password password = new Password("Str0ng!Passw0rd");
        assertThat(hasher.matches(password, "")).isFalse();
        assertThat(hasher.matches(password, null)).isFalse();
    }
}
