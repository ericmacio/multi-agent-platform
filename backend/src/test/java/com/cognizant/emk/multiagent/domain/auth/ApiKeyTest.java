package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyTest {

    private static final String SAMPLE_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";
    private static final OffsetDateTime SAMPLE_TIME = OffsetDateTime.of(
            2026, 5, 12, 8, 0, 0, 0, ZoneOffset.UTC);

    private static ApiKey sample(boolean disabled) {
        return new ApiKey(
                new ClientId("svc-ci"),
                SAMPLE_HASH,
                "ci",
                disabled,
                SAMPLE_TIME);
    }

    @Test
    void with_disabled_true_returns_a_copy_that_preserves_every_other_field() {
        ApiKey original = sample(false);
        ApiKey toggled = original.withDisabled(true);

        assertThat(toggled.disabled()).isTrue();
        assertThat(toggled.clientId()).isEqualTo(original.clientId());
        assertThat(toggled.apiKeyHash()).isEqualTo(original.apiKeyHash());
        assertThat(toggled.label()).isEqualTo(original.label());
        assertThat(toggled.createdAt()).isEqualTo(original.createdAt());
        // Records are immutable: the original is unchanged.
        assertThat(original.disabled()).isFalse();
    }

    @Test
    void with_disabled_round_trips_symmetrically() {
        ApiKey enabled = sample(false);
        ApiKey disabled = enabled.withDisabled(true);
        ApiKey reenabled = disabled.withDisabled(false);

        assertThat(reenabled).isEqualTo(enabled);
    }

    @Test
    void is_active_is_the_inverse_of_disabled() {
        assertThat(sample(false).isActive()).isTrue();
        assertThat(sample(true).isActive()).isFalse();
    }

    @Test
    void rejects_null_client_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ApiKey(null, SAMPLE_HASH, "ci", false, SAMPLE_TIME))
                .withMessage("clientId");
    }

    @Test
    void rejects_null_hash() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ApiKey(new ClientId("svc"), null, "ci", false, SAMPLE_TIME))
                .withMessage("apiKeyHash");
    }

    @Test
    void rejects_blank_hash() {
        assertThatThrownBy(() ->
                new ApiKey(new ClientId("svc"), "   ", "ci", false, SAMPLE_TIME))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("apiKeyHash"));
    }

    @Test
    void rejects_null_created_at() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ApiKey(new ClientId("svc"), SAMPLE_HASH, "ci", false, null))
                .withMessage("createdAt");
    }

    @Test
    void normalizes_null_label_to_null() {
        ApiKey ak = new ApiKey(new ClientId("svc"), SAMPLE_HASH, null, false, SAMPLE_TIME);
        assertThat(ak.label()).isNull();
    }

    @Test
    void normalizes_blank_label_to_null() {
        ApiKey ak = new ApiKey(new ClientId("svc"), SAMPLE_HASH, "   ", false, SAMPLE_TIME);
        assertThat(ak.label()).isNull();
    }

    @Test
    void trims_surrounding_whitespace_from_label() {
        ApiKey ak = new ApiKey(new ClientId("svc"), SAMPLE_HASH, "  ci  ", false, SAMPLE_TIME);
        assertThat(ak.label()).isEqualTo("ci");
    }

    @Test
    void rejects_label_longer_than_128_chars() {
        String okLabel = "a".repeat(128);
        new ApiKey(new ClientId("svc"), SAMPLE_HASH, okLabel, false, SAMPLE_TIME); // boundary — accepted

        String tooLong = "a".repeat(129);
        assertThatThrownBy(() ->
                new ApiKey(new ClientId("svc"), SAMPLE_HASH, tooLong, false, SAMPLE_TIME))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("label");
                    assertThat(ex.getMessage()).contains("128");
                });
    }
}
