package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.application.chat.DeleteConversationUseCase;
import com.cognizant.emk.multiagent.application.chat.DeleteConversationUseCase.DeleteConversationCommand;
import com.cognizant.emk.multiagent.application.chat.EditConversationTitleUseCase;
import com.cognizant.emk.multiagent.application.chat.EditConversationTitleUseCase.EditConversationTitleCommand;
import com.cognizant.emk.multiagent.application.chat.GetConversationUseCase;
import com.cognizant.emk.multiagent.application.chat.GetConversationUseCase.GetConversationQuery;
import com.cognizant.emk.multiagent.application.chat.ListConversationsUseCase;
import com.cognizant.emk.multiagent.application.chat.ListConversationsUseCase.ListConversationsQuery;
import com.cognizant.emk.multiagent.application.chat.ListMessagesUseCase;
import com.cognizant.emk.multiagent.application.chat.ListMessagesUseCase.ListMessagesQuery;
import com.cognizant.emk.multiagent.application.chat.SendMessageUseCase;
import com.cognizant.emk.multiagent.application.chat.SendMessageUseCase.SendMessageCommand;
import com.cognizant.emk.multiagent.application.chat.StartConversationUseCase;
import com.cognizant.emk.multiagent.application.chat.StartConversationUseCase.StartConversationCommand;
import com.cognizant.emk.multiagent.application.chat.TurnEvent;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.auth.Principal;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.Title;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import com.cognizant.emk.multiagent.infrastructure.web.error.ProblemDetails;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.CursorCodec;
import com.cognizant.emk.multiagent.infrastructure.web.pagination.PageDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Optional;  // used by other endpoints (US-10-006 listConversations)
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * REST adapter for the {@code /conversations} endpoints (design §6.2.8).
 *
 * <p>The {@code /api/v1} prefix is applied centrally by {@code WebConfig};
 * no class-level {@code @RequestMapping}.
 *
 * <p>Class-level {@code @PreAuthorize} is not needed — the URL guard
 * {@code /api/v1/conversations/** → hasAnyRole("STANDARD", "ADMIN", "SYSTEM")}
 * in {@code SpringSecurityConfig} admits exactly the three principal kinds
 * that may reach this surface (design §8.6). The
 * {@link AuthenticationPrincipal} is the sealed {@link Principal}, and
 * {@link ConversationOwner#from(Principal)} dispatches exhaustively to the
 * matching {@link ConversationOwner} variant — owner-scoping for each
 * specific conversation is enforced inside the per-endpoint service.
 */
@RestController
public class ConversationsController {

    private static final Logger log = LoggerFactory.getLogger(ConversationsController.class);

    private final StartConversationUseCase startConversationUseCase;
    private final ListConversationsUseCase listConversationsUseCase;
    private final GetConversationUseCase getConversationUseCase;
    private final EditConversationTitleUseCase editConversationTitleUseCase;
    private final DeleteConversationUseCase deleteConversationUseCase;
    private final ListMessagesUseCase listMessagesUseCase;
    private final SendMessageUseCase sendMessageUseCase;
    private final CursorCodec cursorCodec;
    private final ApplicationProperties properties;
    private final SseFrameWriter sseFrameWriter;

    public ConversationsController(
            StartConversationUseCase startConversationUseCase,
            ListConversationsUseCase listConversationsUseCase,
            GetConversationUseCase getConversationUseCase,
            EditConversationTitleUseCase editConversationTitleUseCase,
            DeleteConversationUseCase deleteConversationUseCase,
            ListMessagesUseCase listMessagesUseCase,
            SendMessageUseCase sendMessageUseCase,
            CursorCodec cursorCodec,
            ApplicationProperties properties) {
        this.startConversationUseCase = startConversationUseCase;
        this.listConversationsUseCase = listConversationsUseCase;
        this.getConversationUseCase = getConversationUseCase;
        this.editConversationTitleUseCase = editConversationTitleUseCase;
        this.deleteConversationUseCase = deleteConversationUseCase;
        this.listMessagesUseCase = listMessagesUseCase;
        this.sendMessageUseCase = sendMessageUseCase;
        this.cursorCodec = cursorCodec;
        this.properties = properties;
        this.sseFrameWriter = new SseFrameWriter();
    }

    // ------- US-10-005: POST /conversations -------

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(
            @AuthenticationPrincipal Principal principal,
            @Valid @RequestBody CreateConversationRequest request) {
        ConversationOwner owner = ConversationOwner.from(principal);
        Conversation created = startConversationUseCase.start(
                new StartConversationCommand(owner, new AgentId(request.agentId())));
        return ConversationResponseMapper.toResponse(created);
    }

    // ------- US-10-006: GET /conversations -------

    @GetMapping("/conversations")
    public PageDto<ConversationResponse> list(
            @AuthenticationPrincipal Principal principal,
            @RequestParam(name = "cursor",   required = false) String cursor,
            @RequestParam(name = "pageSize", required = false) Integer pageSize,
            @RequestParam(name = "agentId",  required = false) UUID agentId) {
        ConversationOwner owner = ConversationOwner.from(principal);
        Cursor decoded = cursorCodec.decode(cursor);
        PageSize ps = PageSize.fromQueryParam(pageSize);
        Optional<AgentId> filter = Optional.ofNullable(agentId).map(AgentId::new);
        Page<Conversation> page = listConversationsUseCase.list(
                new ListConversationsQuery(owner, filter, decoded, ps));
        return PageDto.of(page, cursorCodec, ConversationResponseMapper::toResponse);
    }

    // ------- US-10-007: GET /conversations/{conversationId} -------

    @GetMapping("/conversations/{conversationId}")
    public ConversationResponse get(
            @AuthenticationPrincipal Principal principal,
            @PathVariable("conversationId") UUID conversationId) {
        Conversation conversation = getConversationUseCase.get(
                new GetConversationQuery(
                        ConversationOwner.from(principal),
                        new ConversationId(conversationId)));
        return ConversationResponseMapper.toResponse(conversation);
    }

    // ------- US-10-008: PATCH /conversations/{conversationId} -------

    @PatchMapping("/conversations/{conversationId}")
    public ConversationResponse patch(
            @AuthenticationPrincipal Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody UpdateConversationRequest request) {
        Conversation updated = editConversationTitleUseCase.edit(
                new EditConversationTitleCommand(
                        ConversationOwner.from(principal),
                        new ConversationId(conversationId),
                        new Title(request.title())));
        return ConversationResponseMapper.toResponse(updated);
    }

    // ------- US-10-009: DELETE /conversations/{conversationId} -------

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal Principal principal,
            @PathVariable("conversationId") UUID conversationId) {
        deleteConversationUseCase.delete(new DeleteConversationCommand(
                ConversationOwner.from(principal),
                new ConversationId(conversationId)));
    }

    // ------- US-10-010: GET /conversations/{conversationId}/messages -------

    @GetMapping("/conversations/{conversationId}/messages")
    public PageDto<MessageResponse> listMessages(
            @AuthenticationPrincipal Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(name = "cursor",   required = false) String cursor,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        ConversationOwner owner = ConversationOwner.from(principal);
        Cursor decoded = cursorCodec.decode(cursor);
        PageSize ps = PageSize.fromQueryParam(pageSize);
        Page<Message> page = listMessagesUseCase.list(new ListMessagesQuery(
                owner, new ConversationId(conversationId), decoded, ps));
        return PageDto.of(page, cursorCodec, MessageResponseMapper::toResponse);
    }

    // ------- US-11-005 / US-11-006: POST /conversations/{conversationId}/messages -------

    /**
     * Streamed chat turn (design §7, §16.2). Returns a {@link SseEmitter}
     * bound to a cold {@link Flux} of {@link TurnEvent}s. The synchronous
     * prefix of {@link SendMessageUseCase#send} runs in this method body —
     * cap / cross-owner / 404 / 400 failures throw before the emitter is
     * created and the global handler writes the matching JSON
     * Problem-Details body (HTTP 4xx/5xx, content type
     * {@code application/problem+json}; the SSE stream is never opened).
     *
     * <p>Reactive errors signaled by the use case AFTER the {@code started}
     * frame are translated by {@link SseErrorTranslator} into an
     * {@code error} SSE frame on the open stream; the HTTP status stays 200
     * (headers already flushed). Client disconnect / timeout disposes the
     * subscription via the Reactor {@link Disposable} → cancellation
     * propagates through the OpenAI adapter's reactive HTTP client and
     * releases the upstream connection (US-11-006 / REQ-STR-003).
     */
    @PostMapping(
            value = "/conversations/{conversationId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter sendMessage(
            @AuthenticationPrincipal Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            HttpServletRequest httpRequest) {
        // ----- Synchronous prefix (sealed-type owner dispatch + use case) -----
        // SendMessageService throws LlmUnavailableException itself if no LLM
        // provider is configured in this environment — kept inside the service
        // so the controller stays transport-only.
        SendMessageCommand command = new SendMessageCommand(
                ConversationOwner.from(principal),
                new ConversationId(conversationId),
                new MessageContent(request.content()));
        Flux<TurnEvent> stream = sendMessageUseCase.send(command);

        // ----- Reactive tail bridged onto an SseEmitter -----
        long timeoutMs = properties.streaming().emitterTimeout().toMillis();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        String instance = httpRequest.getRequestURI();

        Disposable subscription = stream.subscribe(
                event -> writeFrameSafely(emitter, event),
                error -> writeErrorFrameAndComplete(emitter, error, instance),
                emitter::complete);

        // Cancellation hooks (US-11-006). Tomcat fires onCompletion when the
        // client disconnects; onTimeout when the configured emitter timeout is
        // reached. Both dispose the upstream Reactor subscription, propagating
        // cancel through to the OpenAI HTTP client.
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());

        return emitter;
    }

    private void writeFrameSafely(SseEmitter emitter, TurnEvent event) {
        try {
            sseFrameWriter.write(emitter, event);
        } catch (IOException ioex) {
            // Client gone — complete the emitter; the Reactor subscription
            // gets disposed via the onCompletion callback.
            log.debug("client disconnected while writing SSE frame: {}", ioex.getMessage());
            emitter.completeWithError(ioex);
        } catch (RuntimeException rex) {
            log.warn("unexpected error while writing SSE frame", rex);
            emitter.completeWithError(rex);
        }
    }

    private void writeErrorFrameAndComplete(SseEmitter emitter, Throwable error, String instance) {
        try {
            ProblemDetails body = SseErrorTranslator.translate(error, instance);
            log.warn("chat turn stream errored: code={} class={}",
                    body.code(), error.getClass().getName());
            sseFrameWriter.writeError(emitter, body);
            emitter.complete();
        } catch (IOException ioex) {
            log.debug("client disconnected before error frame could be written: {}",
                    ioex.getMessage());
            emitter.completeWithError(ioex);
        }
    }
}
