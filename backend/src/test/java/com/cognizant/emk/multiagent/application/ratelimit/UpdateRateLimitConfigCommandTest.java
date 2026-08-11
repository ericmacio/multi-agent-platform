package com.cognizant.emk.multiagent.application.ratelimit;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateRateLimitConfigCommandTest {

    private static final UserId ADMIN = new UserId(UUID.randomUUID());

    @Test
    void accepts_valid_values() {
        UpdateRateLimitConfigCommand command = new UpdateRateLimitConfigCommand(20, 100, ADMIN);
        assertThat(command.perMinute()).isEqualTo(20);
        assertThat(command.perHour()).isEqualTo(100);
        assertThat(command.admin()).isEqualTo(ADMIN);
    }

    @Test
    void rejects_zero_perMinute_with_field_name() {
        assertThatThrownBy(() -> new UpdateRateLimitConfigCommand(0, 50, ADMIN))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("perMinute"));
    }

    @Test
    void rejects_negative_perMinute_with_field_name() {
        assertThatThrownBy(() -> new UpdateRateLimitConfigCommand(-3, 50, ADMIN))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("perMinute"));
    }

    @Test
    void rejects_zero_perHour_with_field_name() {
        assertThatThrownBy(() -> new UpdateRateLimitConfigCommand(10, 0, ADMIN))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("perHour"));
    }

    @Test
    void rejects_negative_perHour_with_field_name() {
        assertThatThrownBy(() -> new UpdateRateLimitConfigCommand(10, -1, ADMIN))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("perHour"));
    }

    @Test
    void rejects_null_admin() {
        assertThatThrownBy(() -> new UpdateRateLimitConfigCommand(10, 50, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("admin");
    }
}
