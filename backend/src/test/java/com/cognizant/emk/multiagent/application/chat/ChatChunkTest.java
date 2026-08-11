package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatChunkTest {

    @Test
    void accepts_a_normal_text_chunk() {
        assertThat(new ChatChunk("hello").text()).isEqualTo("hello");
    }

    @Test
    void accepts_the_empty_string() {
        // Heartbeat / role-only frames from the provider surface as empty-text chunks;
        // the SSE emitter (EPIC-11) is responsible for eliding them.
        assertThat(new ChatChunk("").text()).isEmpty();
    }

    @Test
    void rejects_null_text() {
        assertThatThrownBy(() -> new ChatChunk(null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("text"));
    }
}
