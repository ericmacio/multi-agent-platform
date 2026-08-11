package com.cognizant.emk.multiagent.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BcryptApiKeyHasherAdapterTest {

    private static final String CLEARTEXT = "rZ3vQpUbVyL4xV-LzgT4qrQ2vMsf9eL2x1cLfYn8wXM";

    private final BcryptApiKeyHasherAdapter hasher = new BcryptApiKeyHasherAdapter();

    @Test
    void hash_produces_a_bcrypt_string_with_cost_factor_10() {
        String hashed = hasher.hash(CLEARTEXT);
        assertThat(hashed).matches("^\\$2[aby]\\$10\\$.{53}$");
    }

    @Test
    void matches_returns_true_for_the_original_cleartext() {
        String hashed = hasher.hash(CLEARTEXT);
        assertThat(hasher.matches(CLEARTEXT, hashed)).isTrue();
    }

    @Test
    void matches_returns_false_for_an_altered_cleartext() {
        String hashed = hasher.hash(CLEARTEXT);
        assertThat(hasher.matches(CLEARTEXT + "X", hashed)).isFalse();
        assertThat(hasher.matches("entirely-different", hashed)).isFalse();
    }

    @Test
    void matches_returns_false_and_does_not_throw_on_malformed_hash() {
        // BCryptPasswordEncoder.matches throws IllegalArgumentException on a hash that
        // does not match the BCrypt shape; the adapter must swallow that and return false.
        assertThat(hasher.matches(CLEARTEXT, "not-a-bcrypt-hash")).isFalse();
        assertThat(hasher.matches(CLEARTEXT, "")).isFalse();
        assertThat(hasher.matches(CLEARTEXT, null)).isFalse();
    }

    @Test
    void matches_returns_false_when_cleartext_is_null() {
        String hashed = hasher.hash(CLEARTEXT);
        assertThat(hasher.matches(null, hashed)).isFalse();
    }
}
