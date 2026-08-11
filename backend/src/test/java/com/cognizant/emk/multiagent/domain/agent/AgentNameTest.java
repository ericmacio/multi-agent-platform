package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentNameTest {

    @Test
    void accepts_a_well_formed_name() {
        AgentName name = new AgentName("research-bot");
        assertThat(name.value()).isEqualTo("research-bot");
    }

    @Test
    void is_case_sensitive_two_names_differing_in_case_are_distinct() {
        // Unlike Email (US-CR1-001), agent name keeps casing — "Alpha" and "alpha"
        // are two different agents.
        assertThat(new AgentName("Alpha")).isNotEqualTo(new AgentName("alpha"));
    }

    @Test
    void rejects_null_with_field_name() {
        assertThatThrownBy(() -> new AgentName(null))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("name");
                    assertThat(ex.getMessage()).isEqualTo("must not be empty");
                });
    }

    @Test
    void rejects_blank_input() {
        assertThatThrownBy(() -> new AgentName("   "))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("name"));
    }

    @Test
    void accepts_32_char_boundary_and_rejects_33() {
        new AgentName("a".repeat(32)); // accepted
        assertThatThrownBy(() -> new AgentName("a".repeat(33)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("name");
                    assertThat(ex.getMessage()).contains("32");
                });
    }
}
