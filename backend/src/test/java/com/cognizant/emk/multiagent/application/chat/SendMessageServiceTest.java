package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.SendMessageUseCase.SendMessageCommand;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationFullException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendMessageServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);

    @Mock private ConversationRepository conversationRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private MemoryWindowAssembler memoryWindowAssembler;
    @Mock private ChatRequestBuilder chatRequestBuilder;
    @Mock private LlmChatClient llmChatClient;
    @Mock private ChatTurnContext chatTurnContext;

    private SendMessageService service;

    private UserId ownerId;
    private AgentId agentId;
    private ConversationId convId;
    private ConversationOwner owner;

    @BeforeEach
    void setUp() {
        // Simple direct-call TransactionTemplate that just executes the callback
        // — no actual transaction; sufficient for unit-testing the call shape.
        TransactionTemplate tx = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new NoopTxStatus());
            }
        };
        service = new SendMessageService(
                conversationRepository, agentRepository, memoryWindowAssembler,
                chatRequestBuilder, Optional.of(llmChatClient), tx,
                chatTurnContext, CLOCK);
        ownerId = new UserId(UUID.randomUUID());
        agentId = new AgentId(UUID.randomUUID());
        convId = new ConversationId(UUID.randomUUID());
        owner = new ConversationOwner.UserOwner(ownerId);
    }

    @Test
    void happy_path_single_chunk_emits_started_delta_completed_and_persists_both() {
        Conversation empty = freshConversation();
        when(conversationRepository.findById(convId))
                .thenReturn(Optional.of(empty))
                .thenReturn(Optional.of(empty
                        .incrementMessageCount(now())
                        .withTitle(new com.cognizant.emk.multiagent.domain.conversation.Title("hello"),
                                now())));
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(memoryWindowAssembler.assemble(convId, agent.memorySize()))
                .thenReturn(List.of(userMsg("hello")));
        ChatRequest req = sampleRequest();
        when(chatRequestBuilder.build(any(), any(), any())).thenReturn(req);
        when(llmChatClient.stream(req)).thenReturn(Flux.just(new ChatChunk("hi there")));
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(conversationRepository.appendMessage(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Flux<TurnEvent> flux = service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hello")));

        StepVerifier.create(flux)
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(TurnEvent.Started.class);
                    TurnEvent.Started s = (TurnEvent.Started) e;
                    assertThat(s.conversationId()).isEqualTo(convId.value());
                })
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(TurnEvent.Delta.class);
                    assertThat(((TurnEvent.Delta) e).text()).isEqualTo("hi there");
                })
                .assertNext(e -> {
                    assertThat(e).isInstanceOf(TurnEvent.Completed.class);
                    TurnEvent.Completed c = (TurnEvent.Completed) e;
                    assertThat(c.title()).isEqualTo("hello");      // first turn, auto-derived
                    assertThat(c.messageCount()).isEqualTo(2);     // user + assistant
                })
                .verifyComplete();

        // Both USER and ASSISTANT messages persisted exactly once each.
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationRepository, org.mockito.Mockito.times(2)).appendMessage(msgCaptor.capture());
        List<Message> persisted = msgCaptor.getAllValues();
        assertThat(persisted.get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(persisted.get(1).role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(persisted.get(1).content().value()).isEqualTo("hi there");
    }

    @Test
    void multi_chunk_response_emits_started_three_deltas_then_completed() {
        Conversation existing = freshConversation()
                .incrementMessageCount(now())   // simulate already at message 1
                .incrementMessageCount(now())   // .. 2
                .incrementMessageCount(now())   // .. 3
                .withTitle(new com.cognizant.emk.multiagent.domain.conversation.Title("X"), now());
        when(conversationRepository.findById(convId))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing.incrementMessageCount(now())));
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(memoryWindowAssembler.assemble(convId, agent.memorySize())).thenReturn(List.of());
        ChatRequest req = sampleRequest();
        when(chatRequestBuilder.build(any(), any(), any())).thenReturn(req);
        when(llmChatClient.stream(req)).thenReturn(Flux.just(
                new ChatChunk("Hel"), new ChatChunk("lo "), new ChatChunk("world")));
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(conversationRepository.appendMessage(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Flux<TurnEvent> flux = service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hi")));

        StepVerifier.create(flux)
                .expectNextMatches(e -> e instanceof TurnEvent.Started)
                .expectNextMatches(e -> e instanceof TurnEvent.Delta
                        && ((TurnEvent.Delta) e).text().equals("Hel"))
                .expectNextMatches(e -> e instanceof TurnEvent.Delta
                        && ((TurnEvent.Delta) e).text().equals("lo "))
                .expectNextMatches(e -> e instanceof TurnEvent.Delta
                        && ((TurnEvent.Delta) e).text().equals("world"))
                .expectNextMatches(e -> {
                    if (!(e instanceof TurnEvent.Completed c)) return false;
                    return c.title() == null;  // subsequent turn
                })
                .verifyComplete();

        // Assistant accumulated text was "Hello world".
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        verify(conversationRepository, org.mockito.Mockito.times(2)).appendMessage(msgCaptor.capture());
        assertThat(msgCaptor.getAllValues().get(1).content().value()).isEqualTo("Hello world");
    }

    @Test
    void cap_reached_pre_flight_throws_synchronously_and_does_not_persist_user_message() {
        Conversation atCap = freshConversation();
        for (int i = 0; i < 64; i++) {
            atCap = atCap.incrementMessageCount(now());
        }
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(atCap));

        assertThatThrownBy(() -> service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hi"))))
                .isInstanceOf(ConversationFullException.class);

        verify(conversationRepository, never()).appendMessage(any());
    }

    @Test
    void cross_owner_throws_conversation_not_found_synchronously() {
        UserId other = new UserId(UUID.randomUUID());
        Conversation othersConv = new Conversation(
                convId, agentId, new ConversationOwner.UserOwner(other),
                null, MessageCount.EMPTY, now(), now());
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(othersConv));

        assertThatThrownBy(() -> service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hi"))))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).appendMessage(any());
    }

    @Test
    void unknown_conversation_throws_conversation_not_found_synchronously() {
        when(conversationRepository.findById(convId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hi"))))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).appendMessage(any());
    }

    @Test
    void llm_error_mid_stream_emits_started_one_delta_then_errors_and_assistant_not_persisted() {
        Conversation empty = freshConversation();
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(empty));
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(memoryWindowAssembler.assemble(convId, agent.memorySize())).thenReturn(List.of());
        when(chatRequestBuilder.build(any(), any(), any())).thenReturn(sampleRequest());
        when(llmChatClient.stream(any())).thenReturn(Flux.concat(
                Flux.just(new ChatChunk("part-one")),
                Flux.error(new LlmUnavailableException("openai provider failure: http_5xx 503"))));
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(conversationRepository.appendMessage(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Flux<TurnEvent> flux = service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hi")));

        StepVerifier.create(flux)
                .expectNextMatches(e -> e instanceof TurnEvent.Started)
                .expectNextMatches(e -> e instanceof TurnEvent.Delta
                        && ((TurnEvent.Delta) e).text().equals("part-one"))
                .expectErrorMatches(t -> t instanceof LlmUnavailableException)
                .verify();

        // USER message was persisted (in the sync prefix); ASSISTANT was NOT
        // (the assistant-persist mono is never reached after the error signal).
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(conversationRepository).appendMessage(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(MessageRole.USER);
    }

    @Test
    void llm_error_before_first_chunk_still_emits_started_then_errors() {
        Conversation empty = freshConversation();
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(empty));
        Agent agent = sampleAgent();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(memoryWindowAssembler.assemble(convId, agent.memorySize())).thenReturn(List.of());
        when(chatRequestBuilder.build(any(), any(), any())).thenReturn(sampleRequest());
        when(llmChatClient.stream(any())).thenReturn(Flux.error(
                new LlmUnavailableException("openai provider failure: http_4xx 401")));
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(conversationRepository.appendMessage(any(Message.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Flux<TurnEvent> flux = service.send(new SendMessageCommand(
                owner, convId, new MessageContent("hi")));

        StepVerifier.create(flux)
                .expectNextMatches(e -> e instanceof TurnEvent.Started)
                .expectErrorMatches(t -> t instanceof LlmUnavailableException)
                .verify();
    }

    // ----- helpers -----

    private Conversation freshConversation() {
        return new Conversation(
                convId, agentId, owner,
                null, MessageCount.EMPTY, now(), now());
    }

    private Agent sampleAgent() {
        return new Agent(
                agentId, ownerId, new AgentName("a"),
                "d", "s",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now(), now());
    }

    private ChatRequest sampleRequest() {
        return new ChatRequest(
                "gpt-4o-mini", "system",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(), List.of(),
                SamplingParameters.none(),
                ownerId.value());
    }

    private Message userMsg(String content) {
        return new Message(
                new com.cognizant.emk.multiagent.domain.conversation.MessageId(UUID.randomUUID()),
                convId, MessageRole.USER, new MessageContent(content), now());
    }

    private OffsetDateTime now() {
        return CLOCK.instant().atOffset(ZoneOffset.UTC);
    }

    /** Minimal no-op TransactionStatus for the in-test TransactionTemplate. */
    private static final class NoopTxStatus implements TransactionStatus {
        @Override public boolean isNewTransaction() { return true; }
        @Override public boolean hasSavepoint() { return false; }
        @Override public void setRollbackOnly() {}
        @Override public boolean isRollbackOnly() { return false; }
        @Override public void flush() {}
        @Override public boolean isCompleted() { return false; }
        @Override public Object createSavepoint() { return null; }
        @Override public void rollbackToSavepoint(Object savepoint) {}
        @Override public void releaseSavepoint(Object savepoint) {}
    }
}
