package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageContentTest {

    @Test
    void accepts_a_well_formed_message() {
        MessageContent content = new MessageContent("Hello, agent.");
        assertThat(content.value()).isEqualTo("Hello, agent.");
    }

    @Test
    void rejects_null_with_field_content() {
        assertThatThrownBy(() -> new MessageContent(null))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("content");
                    assertThat(ex.getMessage()).isEqualTo("must not be empty");
                });
    }

    @Test
    void rejects_blank_input() {
        assertThatThrownBy(() -> new MessageContent("   "))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("content"));
    }

    @Test
    void accepts_1024_char_boundary_and_rejects_1025() {
        new MessageContent("a".repeat(1024)); // accepted
        assertThatThrownBy(() -> new MessageContent("a".repeat(1025)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("content");
                    assertThat(ex.getMessage()).contains("1024");
                });
    }
}
