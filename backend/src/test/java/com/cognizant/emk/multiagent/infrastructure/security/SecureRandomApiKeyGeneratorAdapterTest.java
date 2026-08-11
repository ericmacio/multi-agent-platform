package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRandomApiKeyGeneratorAdapterTest {

    @Test
    void two_calls_produce_distinct_client_ids_and_distinct_cleartexts() {
        SecureRandomApiKeyGeneratorAdapter generator = new SecureRandomApiKeyGeneratorAdapter();
        GeneratedApiKey first = generator.generate();
        GeneratedApiKey second = generator.generate();

        assertThat(first.clientId()).isNotEqualTo(second.clientId());
        assertThat(first.cleartextApiKey()).isNotEqualTo(second.cleartextApiKey());
    }

    @Test
    void client_id_is_a_32_lowercase_hex_uuid_without_dashes() {
        SecureRandomApiKeyGeneratorAdapter generator = new SecureRandomApiKeyGeneratorAdapter();
        GeneratedApiKey generated = generator.generate();

        assertThat(generated.clientId().value()).matches("^[a-f0-9]{32}$");
    }

    @Test
    void cleartext_uses_url_safe_base64_alphabet_and_is_at_least_43_chars() {
        SecureRandomApiKeyGeneratorAdapter generator = new SecureRandomApiKeyGeneratorAdapter();
        GeneratedApiKey generated = generator.generate();

        // 32 bytes base64url-encoded without padding = ceil(32 * 4 / 3) = 43 chars.
        assertThat(generated.cleartextApiKey()).matches("^[A-Za-z0-9_\\-]+$");
        assertThat(generated.cleartextApiKey().length()).isGreaterThanOrEqualTo(43);
    }

    @Test
    void cleartext_is_deterministic_when_a_fixed_seed_random_is_injected() throws NoSuchAlgorithmException {
        // SecureRandom.setSeed(long) on the default platform PRNG *supplements* the
        // existing seed rather than replacing it, so two default instances seeded with
        // the same value still emit different bytes. SHA1PRNG is the JDK-guaranteed
        // algorithm whose setSeed-before-first-nextBytes path produces a deterministic
        // stream — exactly what we need to exercise the constructor seam.
        SecureRandomApiKeyGeneratorAdapter genA = new SecureRandomApiKeyGeneratorAdapter(seededSha1Prng(42L));
        SecureRandomApiKeyGeneratorAdapter genB = new SecureRandomApiKeyGeneratorAdapter(seededSha1Prng(42L));

        // The client_id draws from a separate UUID source so it is non-deterministic;
        // we exercise the cleartext stream alone.
        assertThat(genA.generate().cleartextApiKey())
                .isEqualTo(genB.generate().cleartextApiKey());
    }

    private static SecureRandom seededSha1Prng(long seed) throws NoSuchAlgorithmException {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed);
        return random;
    }
}
