package com.cognizant.emk.multiagent.domain.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRoleTest {

    @Test
    void has_exactly_user_and_assistant_in_that_order() {
        // Order matters — must match the openapi MessageRole enum declaration
        // and the PostgreSQL check constraint in V001__init_schema.sql.
        assertThat(MessageRole.values())
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
    }

    @Test
    void does_not_have_a_tool_or_system_variant() {
        // REQ-CHAT-012: tool-call requests/results are NEVER persisted.
        assertThatThrownBy(() -> MessageRole.valueOf("TOOL"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageRole.valueOf("SYSTEM"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
