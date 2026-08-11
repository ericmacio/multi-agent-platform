package com.cognizant.emk.multiagent.domain.ratelimit;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitConfigTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 6, 19, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void accepts_minimum_valid_values() {
        RateLimitConfig cfg = new RateLimitConfig(1, 1, NOW, Optional.empty());
        assertThat(cfg.perMinute()).isEqualTo(1);
        assertThat(cfg.perHour()).isEqualTo(1);
    }

    @Test
    void accepts_default_seed_values() {
        RateLimitConfig cfg = new RateLimitConfig(10, 50, NOW, Optional.empty());
        assertThat(cfg.perMinute()).isEqualTo(10);
        assertThat(cfg.perHour()).isEqualTo(50);
    }

    @Test
    void rejects_zero_perMinute() {
        assertThatThrownBy(() -> new RateLimitConfig(0, 50, NOW, Optional.empty()))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("perMinute");
                    assertThat(ex.getMessage()).contains("at least 1");
                });
    }

    @Test
    void rejects_negative_perMinute() {
        assertThatThrownBy(() -> new RateLimitConfig(-1, 50, NOW, Optional.empty()))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("perMinute"));
    }

    @Test
    void rejects_zero_perHour() {
        assertThatThrownBy(() -> new RateLimitConfig(10, 0, NOW, Optional.empty()))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("perHour");
                    assertThat(ex.getMessage()).contains("at least 1");
                });
    }

    @Test
    void rejects_negative_perHour() {
        assertThatThrownBy(() -> new RateLimitConfig(10, -10, NOW, Optional.empty()))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("perHour"));
    }

    @Test
    void rejects_null_updatedAt() {
        assertThatThrownBy(() -> new RateLimitConfig(10, 50, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("updatedAt");
    }

    @Test
    void rejects_null_updatedBy_optional() {
        assertThatThrownBy(() -> new RateLimitConfig(10, 50, NOW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("updatedBy");
    }

    @Test
    void accepts_absent_updatedBy() {
        RateLimitConfig cfg = new RateLimitConfig(10, 50, NOW, Optional.empty());
        assertThat(cfg.updatedBy()).isEmpty();
    }

    @Test
    void accepts_present_updatedBy() {
        UserId admin = new UserId(UUID.randomUUID());
        RateLimitConfig cfg = new RateLimitConfig(20, 100, NOW, Optional.of(admin));
        assertThat(cfg.updatedBy()).hasValue(admin);
    }
}
