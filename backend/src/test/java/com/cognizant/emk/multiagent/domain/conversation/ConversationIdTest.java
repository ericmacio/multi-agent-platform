package com.cognizant.emk.multiagent.domain.conversation;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ConversationIdTest {

    @Test
    void wraps_a_uuid() {
        UUID id = UUID.randomUUID();
        assertThat(new ConversationId(id).value()).isEqualTo(id);
    }

    @Test
    void rejects_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConversationId(null))
                .withMessage("value");
    }
}
