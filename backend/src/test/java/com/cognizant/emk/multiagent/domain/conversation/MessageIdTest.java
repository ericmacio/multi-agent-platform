package com.cognizant.emk.multiagent.domain.conversation;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MessageIdTest {

    @Test
    void wraps_a_uuid() {
        UUID id = UUID.randomUUID();
        assertThat(new MessageId(id).value()).isEqualTo(id);
    }

    @Test
    void rejects_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new MessageId(null))
                .withMessage("value");
    }
}
