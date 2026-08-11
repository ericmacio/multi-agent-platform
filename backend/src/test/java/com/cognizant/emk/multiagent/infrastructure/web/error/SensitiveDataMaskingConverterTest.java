package com.cognizant.emk.multiagent.infrastructure.web.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link SensitiveDataMaskingConverter#mask(String)} masks each documented
 * token shape while leaving surrounding text intact (REQ-SEC-004).
 */
class SensitiveDataMaskingConverterTest {

    private static final String JWT_SAMPLE =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0LXVzZXJAZXhhbXBsZS5jb20iLCJyb2xlIjoiQURNSU4ifQ.signature_part_xxxxxxxxxx";
    private static final String BCRYPT_SAMPLE = "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Test
    void masks_a_jwt_in_the_middle_of_the_message() {
        String input = "Issued token " + JWT_SAMPLE + " for user";
        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("Issued token *** for user");
    }

    @Test
    void leaves_short_dotted_strings_alone() {
        // Three short dotted segments under the 40-char threshold — not a JWT.
        String input = "package com.foo.bar today";
        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("package com.foo.bar today");
    }

    @Test
    void masks_a_bcrypt_hash() {
        String input = "stored hash=" + BCRYPT_SAMPLE + " for the bootstrap admin";
        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("stored hash=*** for the bootstrap admin");
    }

    @Test
    void masks_bearer_prefixed_value_including_the_prefix() {
        String input = "Got header Authorization: Bearer abc.def.ghi.jkl rest";
        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("Got header Authorization: *** rest");
    }

    @Test
    void masks_bearer_with_a_real_jwt_value() {
        String input = "header=Bearer " + JWT_SAMPLE + " end";
        // The Bearer pattern consumes the prefix and the token value as one match.
        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("header=*** end");
    }

    @Test
    void masks_multiple_tokens_in_one_message() {
        String input = "jwt=" + JWT_SAMPLE + " bcrypt=" + BCRYPT_SAMPLE;
        assertThat(SensitiveDataMaskingConverter.mask(input))
                .isEqualTo("jwt=*** bcrypt=***");
    }

    @Test
    void null_and_empty_input_return_unchanged() {
        assertThat(SensitiveDataMaskingConverter.mask(null)).isNull();
        assertThat(SensitiveDataMaskingConverter.mask("")).isEqualTo("");
    }
}
