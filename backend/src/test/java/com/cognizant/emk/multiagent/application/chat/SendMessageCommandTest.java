package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.SendMessageUseCase.SendMessageCommand;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SendMessageCommandTest {

    @Test
    void accepts_a_fully_populated_command_with_a_user_owner() {
        ConversationOwner owner = new ConversationOwner.UserOwner(new UserId(UUID.randomUUID()));
        ConversationId id = new ConversationId(UUID.randomUUID());
        MessageContent content = new MessageContent("hello");

        SendMessageCommand cmd = new SendMessageCommand(owner, id, content);

        assertThat(cmd.owner()).isSameAs(owner);
        assertThat(cmd.conversationId()).isEqualTo(id);
        assertThat(cmd.content()).isEqualTo(content);
    }

    @Test
    void accepts_a_system_owner_at_the_type_level() {
        // The runtime contract that SYSTEM cannot reach a chat turn in v1 is enforced
        // upstream by StartConversationService (US-10-005) — the type system here
        // happily accepts a SystemOwner because EPIC-12's sub-agent path and a
        // future SYSTEM-owned-agents EPIC may use it.
        ConversationOwner owner = new ConversationOwner.SystemOwner(new ClientId("svc-a"));
        ConversationId id = new ConversationId(UUID.randomUUID());

        new SendMessageCommand(owner, id, new MessageContent("hi"));
    }

    @Test
    void rejects_null_owner() {
        assertThatNullPointerException().isThrownBy(() -> new SendMessageCommand(
                null,
                new ConversationId(UUID.randomUUID()),
                new MessageContent("hi")))
                .withMessage("owner");
    }

    @Test
    void rejects_null_conversation_id() {
        assertThatNullPointerException().isThrownBy(() -> new SendMessageCommand(
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                null,
                new MessageContent("hi")))
                .withMessage("conversationId");
    }

    @Test
    void rejects_null_content() {
        assertThatNullPointerException().isThrownBy(() -> new SendMessageCommand(
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                new ConversationId(UUID.randomUUID()),
                null))
                .withMessage("content");
    }
}
