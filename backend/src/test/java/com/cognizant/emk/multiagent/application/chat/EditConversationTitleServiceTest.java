package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.EditConversationTitleUseCase.EditConversationTitleCommand;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.conversation.Title;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditConversationTitleServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = CLOCK.instant().atOffset(ZoneOffset.UTC);

    @Mock private ConversationRepository conversationRepository;

    @Test
    void owner_match_persists_renamed_conversation_with_bumped_updated_at() {
        EditConversationTitleService service = new EditConversationTitleService(
                conversationRepository, CLOCK);
        UserId userId = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        Conversation existing = sample(id, new ConversationOwner.UserOwner(userId));
        when(conversationRepository.findById(id)).thenReturn(Optional.of(existing));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation result = service.edit(new EditConversationTitleCommand(
                new ConversationOwner.UserOwner(userId), id, new Title("Renamed")));

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        Conversation saved = captor.getValue();
        assertThat(saved.title()).isEqualTo(new Title("Renamed"));
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        assertThat(result).isEqualTo(saved);
    }

    @Test
    void cross_owner_throws_conversation_not_found_and_never_persists() {
        EditConversationTitleService service = new EditConversationTitleService(
                conversationRepository, CLOCK);
        UserId actualOwner = new UserId(UUID.randomUUID());
        UserId requester = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(sample(id, new ConversationOwner.UserOwner(actualOwner))));

        assertThatThrownBy(() -> service.edit(new EditConversationTitleCommand(
                new ConversationOwner.UserOwner(requester), id, new Title("Renamed"))))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).save(any());
    }

    @Test
    void unknown_id_throws_conversation_not_found() {
        EditConversationTitleService service = new EditConversationTitleService(
                conversationRepository, CLOCK);
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.edit(new EditConversationTitleCommand(
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                id, new Title("Renamed"))))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).save(any());
    }

    // ----- helpers -----

    private static Conversation sample(ConversationId id, ConversationOwner owner) {
        OffsetDateTime created = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        return new Conversation(
                id,
                new AgentId(UUID.randomUUID()),
                owner,
                null,
                MessageCount.EMPTY,
                created, created);
    }
}
