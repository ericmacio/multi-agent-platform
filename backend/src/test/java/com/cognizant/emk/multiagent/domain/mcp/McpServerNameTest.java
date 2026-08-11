package com.cognizant.emk.multiagent.domain.mcp;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerNameTest {

    @Test
    void accepts_a_well_formed_value_and_returns_it_verbatim() {
        McpServerName name = new McpServerName("brave-search");
        assertThat(name.value()).isEqualTo("brave-search");
    }

    @Test
    void preserves_case_verbatim() {
        // Spring AI matches connection keys case-sensitively against the configured map,
        // so the value object MUST NOT canonicalize to lowercase.
        McpServerName name = new McpServerName("Brave-Search");
        assertThat(name.value()).isEqualTo("Brave-Search");
    }

    @Test
    void accepts_64_char_value_and_rejects_65() {
        new McpServerName("a".repeat(64));
        assertThatThrownBy(() -> new McpServerName("a".repeat(65)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("enabledMcpServers");
                    assertThat(ex.getMessage()).contains("64");
                });
    }

    @Test
    void rejects_null_value() {
        assertThatThrownBy(() -> new McpServerName(null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("enabledMcpServers"));
    }

    @Test
    void rejects_blank_value() {
        assertThatThrownBy(() -> new McpServerName("   "))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("enabledMcpServers"));
    }
}
