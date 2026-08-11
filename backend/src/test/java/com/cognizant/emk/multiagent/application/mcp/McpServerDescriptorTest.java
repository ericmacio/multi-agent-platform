package com.cognizant.emk.multiagent.application.mcp;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServerDescriptorTest {

    @Test
    void accepts_a_well_formed_pair_with_description() {
        McpServerDescriptor d = new McpServerDescriptor("brave-search", "Web search via Brave.");
        assertThat(d.name()).isEqualTo("brave-search");
        assertThat(d.description()).isEqualTo("Web search via Brave.");
    }

    @Test
    void accepts_a_null_description() {
        // Spring AI's MCP stdio configuration has no description field; the adapter
        // returns null for connection names not present in its internal lookup.
        McpServerDescriptor d = new McpServerDescriptor("test-mcp", null);
        assertThat(d.name()).isEqualTo("test-mcp");
        assertThat(d.description()).isNull();
    }

    @Test
    void accepts_64_char_name_and_rejects_65() {
        new McpServerDescriptor("a".repeat(64), "ok");
        assertThatThrownBy(() -> new McpServerDescriptor("a".repeat(65), "ok"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("name");
                    assertThat(ex.getMessage()).contains("64");
                });
    }

    @Test
    void rejects_null_name() {
        assertThatThrownBy(() -> new McpServerDescriptor(null, "ok"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("name"));
    }

    @Test
    void rejects_blank_name() {
        assertThatThrownBy(() -> new McpServerDescriptor("   ", "ok"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("name"));
    }
}
