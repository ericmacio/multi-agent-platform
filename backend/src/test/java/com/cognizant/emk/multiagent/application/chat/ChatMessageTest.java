package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageTest {

    @Test
    void accepts_a_well_formed_pair() {
        ChatMessage m = new ChatMessage(Role.USER, "hello");
        assertThat(m.role()).isEqualTo(Role.USER);
        assertThat(m.content()).isEqualTo("hello");
    }

    @Test
    void accepts_1024_char_content_and_rejects_1025() {
        new ChatMessage(Role.ASSISTANT, "x".repeat(1024));
        assertThatThrownBy(() -> new ChatMessage(Role.ASSISTANT, "x".repeat(1025)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("content");
                    assertThat(ex.getMessage()).contains("1024");
                });
    }

    @Test
    void rejects_null_role() {
        assertThatThrownBy(() -> new ChatMessage(null, "hello"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("role"));
    }

    @Test
    void rejects_null_content() {
        assertThatThrownBy(() -> new ChatMessage(Role.USER, null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("content"));
    }

    @Test
    void rejects_blank_content() {
        assertThatThrownBy(() -> new ChatMessage(Role.USER, "   "))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("content"));
    }
}
