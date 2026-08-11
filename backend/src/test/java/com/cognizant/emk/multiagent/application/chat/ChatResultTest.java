package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatResultTest {

    @Test
    void accepts_a_normal_assistant_answer() {
        assertThat(new ChatResult("hello, world").text()).isEqualTo("hello, world");
    }

    @Test
    void accepts_the_empty_string() {
        assertThat(new ChatResult("").text()).isEmpty();
    }

    @Test
    void rejects_null_text() {
        assertThatThrownBy(() -> new ChatResult(null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("text"));
    }
}
