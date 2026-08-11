package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SamplingParametersTest {

    @Test
    void accepts_all_null_fields() {
        SamplingParameters p = new SamplingParameters(null, null, null);
        assertThat(p.temperature()).isNull();
        assertThat(p.maxOutputTokens()).isNull();
        assertThat(p.topP()).isNull();
    }

    @Test
    void none_returns_an_all_null_instance() {
        SamplingParameters p = SamplingParameters.none();
        assertThat(p.temperature()).isNull();
        assertThat(p.maxOutputTokens()).isNull();
        assertThat(p.topP()).isNull();
    }

    @Test
    void accepts_valid_overrides() {
        SamplingParameters p = new SamplingParameters(0.7, 256, 0.9);
        assertThat(p.temperature()).isEqualTo(0.7);
        assertThat(p.maxOutputTokens()).isEqualTo(256);
        assertThat(p.topP()).isEqualTo(0.9);
    }

    @Test
    void accepts_temperature_bounds_0_and_2() {
        new SamplingParameters(0.0, null, null);
        new SamplingParameters(2.0, null, null);
    }

    @Test
    void rejects_negative_temperature() {
        assertThatThrownBy(() -> new SamplingParameters(-0.1, null, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("temperature"));
    }

    @Test
    void rejects_temperature_above_2() {
        assertThatThrownBy(() -> new SamplingParameters(2.01, null, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("temperature"));
    }

    @Test
    void rejects_zero_or_negative_maxOutputTokens() {
        assertThatThrownBy(() -> new SamplingParameters(null, 0, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("maxOutputTokens"));
        assertThatThrownBy(() -> new SamplingParameters(null, -1, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("maxOutputTokens"));
    }

    @Test
    void accepts_topP_upper_bound_1() {
        new SamplingParameters(null, null, 1.0);
    }

    @Test
    void rejects_topP_zero_and_below() {
        assertThatThrownBy(() -> new SamplingParameters(null, null, 0.0))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("topP"));
        assertThatThrownBy(() -> new SamplingParameters(null, null, -0.1))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("topP"));
    }

    @Test
    void rejects_topP_above_1() {
        assertThatThrownBy(() -> new SamplingParameters(null, null, 1.01))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("topP"));
    }
}
