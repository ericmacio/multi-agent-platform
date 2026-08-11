package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemorySizeTest {

    @Test
    void accepts_boundary_values_1_and_36() {
        assertThat(new MemorySize(1).value()).isEqualTo(1);
        assertThat(new MemorySize(36).value()).isEqualTo(36);
    }

    @Test
    void default_is_12() {
        assertThat(MemorySize.DEFAULT.value()).isEqualTo(12);
    }

    @Test
    void rejects_zero_with_field_memory_size() {
        assertThatThrownBy(() -> new MemorySize(0))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("memorySize");
                    assertThat(ex.getMessage()).contains("1").contains("36");
                });
    }

    @Test
    void rejects_37_with_field_memory_size() {
        assertThatThrownBy(() -> new MemorySize(37))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("memorySize"));
    }

    @Test
    void rejects_negative_with_field_memory_size() {
        assertThatThrownBy(() -> new MemorySize(-5))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("memorySize"));
    }
}
