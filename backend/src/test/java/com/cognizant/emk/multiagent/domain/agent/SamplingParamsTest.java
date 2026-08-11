package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplingParamsTest {

    @Test
    void all_null_accepts_the_platform_defaults_case() {
        SamplingParams p = new SamplingParams(null, null, null, null);
        assertThat(p.llmModel()).isNull();
        assertThat(p.temperature()).isNull();
        assertThat(p.maxOutputTokens()).isNull();
        assertThat(p.topP()).isNull();
    }

    @Test
    void defaults_constant_is_reachable_and_all_null() {
        assertThat(SamplingParams.DEFAULTS).isEqualTo(new SamplingParams(null, null, null, null));
    }

    @Test
    void accepts_llm_model_at_64_chars_and_rejects_65() {
        new SamplingParams("a".repeat(64), null, null, null); // accepted
        assertThatThrownBy(() -> new SamplingParams("a".repeat(65), null, null, null))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("llmModel");
                    assertThat(ex.getMessage()).contains("64");
                });
    }

    @Test
    void accepts_max_output_tokens_1_and_rejects_zero() {
        new SamplingParams(null, null, 1, null); // accepted
        assertThatThrownBy(() -> new SamplingParams(null, null, 0, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("maxOutputTokens"));
    }

    @Test
    void accepts_any_temperature_and_top_p_value_pending_tbd4_design() {
        // Range validation for temperature / topP is deferred to TBD-4 in the design;
        // the canonical constructor currently accepts any non-null number.
        new SamplingParams(null, 2.5, null, 1.7);
        new SamplingParams(null, -0.1, null, -0.1);
    }
}
