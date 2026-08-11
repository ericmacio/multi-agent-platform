package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.MessageId;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryWindowAssemblerTest {

    @Mock private ConversationRepository conversationRepository;

    @Test
    void empty_conversation_returns_empty_list() {
        MemoryWindowAssembler assembler = new MemoryWindowAssembler(conversationRepository);
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findLastN(id, 12)).thenReturn(List.of());

        assertThat(assembler.assemble(id, MemorySize.DEFAULT)).isEmpty();
    }

    @Test
    void returns_repository_messages_unchanged_in_chronological_ascending_order() {
        MemoryWindowAssembler assembler = new MemoryWindowAssembler(conversationRepository);
        ConversationId id = new ConversationId(UUID.randomUUID());
        Message m1 = sample(id, MessageRole.USER, "1", 1);
        Message m2 = sample(id, MessageRole.ASSISTANT, "2", 2);
        Message m3 = sample(id, MessageRole.USER, "3", 3);
        when(conversationRepository.findLastN(id, 12)).thenReturn(List.of(m1, m2, m3));

        List<Message> window = assembler.assemble(id, MemorySize.DEFAULT);

        assertThat(window).containsExactly(m1, m2, m3);
    }

    @Test
    void forwards_memory_size_value_verbatim_to_repository() {
        MemoryWindowAssembler assembler = new MemoryWindowAssembler(conversationRepository);
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findLastN(id, 5)).thenReturn(List.of());

        assembler.assemble(id, new MemorySize(5));

        verify(conversationRepository).findLastN(id, 5);
    }

    @Test
    void rejects_null_conversation_id() {
        MemoryWindowAssembler assembler = new MemoryWindowAssembler(conversationRepository);
        assertThatNullPointerException().isThrownBy(() ->
                assembler.assemble(null, MemorySize.DEFAULT));
    }

    @Test
    void rejects_null_memory_size() {
        MemoryWindowAssembler assembler = new MemoryWindowAssembler(conversationRepository);
        assertThatNullPointerException().isThrownBy(() ->
                assembler.assemble(new ConversationId(UUID.randomUUID()), null));
    }

    @Test
    void returned_list_is_immutable() {
        MemoryWindowAssembler assembler = new MemoryWindowAssembler(conversationRepository);
        ConversationId id = new ConversationId(UUID.randomUUID());
        Message m = sample(id, MessageRole.USER, "1", 1);
        // Repository may return a mutable list; assembler must defensively copy.
        when(conversationRepository.findLastN(id, 12))
                .thenReturn(new java.util.ArrayList<>(List.of(m)));

        List<Message> window = assembler.assemble(id, MemorySize.DEFAULT);

        assertThat(window).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> window.add(m))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ----- helpers -----

    private static Message sample(ConversationId convId, MessageRole role,
                                  String content, int secondsOffset) {
        OffsetDateTime ts = OffsetDateTime.of(2026, 5, 1, 10, 0, secondsOffset, 0, ZoneOffset.UTC);
        return new Message(
                new MessageId(UUID.randomUUID()),
                convId, role, new MessageContent(content), ts);
    }
}
