package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.application.chat.TurnEvent;
import com.cognizant.emk.multiagent.infrastructure.web.error.ProblemDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Translates {@link TurnEvent}s into Server-Sent Event frames on a
 * {@link SseEmitter} (US-11-005). The single point of contact between the
 * sealed-in-process event type and the documented wire shape (design §7.1).
 *
 * <p>Frame mapping:
 * <ul>
 *   <li>{@link TurnEvent.Started} → {@code event: started\ndata: {"userMessageId":"...","conversationId":"..."}}</li>
 *   <li>{@link TurnEvent.Delta} with non-empty text → {@code event: delta\ndata: {"text":"..."}};
 *       empty text is <strong>elided</strong> (no frame written) per §7.1.</li>
 *   <li>{@link TurnEvent.Completed} → {@code event: completed\ndata: {"assistantMessageId":"...","title":"..."|null,"messageCount":N}}</li>
 *   <li>{@link TurnEvent.Error} → {@code event: error\ndata: <ProblemDetails JSON>}</li>
 * </ul>
 *
 * <p>Frame writes propagate {@link IOException} from the emitter (the client
 * disconnected); the controller catches these in its {@code Flux.subscribe}
 * error handler and disposes the upstream subscription.
 */
final class SseFrameWriter {

    private static final Logger log = LoggerFactory.getLogger(SseFrameWriter.class);

    // Private ObjectMapper — keep wire-format encoding fully under our control,
    // matching the CursorCodec pattern (US-04-005). The SSE frame payloads are
    // internal records, so we do not need any Spring-managed Jackson customizations.
    private final ObjectMapper objectMapper;

    SseFrameWriter() {
        this(new ObjectMapper());
    }

    /** Visible for tests. */
    SseFrameWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes one frame for the given event. Empty deltas are silently elided.
     *
     * @throws IOException when the emitter has been completed or the client
     *                     has disconnected.
     */
    void write(SseEmitter emitter, TurnEvent event) throws IOException {
        if (event instanceof TurnEvent.Started s) {
            log.info("Received Started TurnEvent");
            emitter.send(buildFrame("started", new StartedPayload(
                    s.userMessageId().toString(), s.conversationId().toString())));
        } else if (event instanceof TurnEvent.Delta d) {
            log.info("Received Delta TurnEvent");
            if (d.text().isEmpty()) {
                return;  // elide empty delta — §7.1
            }
            emitter.send(buildFrame("delta", new DeltaPayload(d.text())));
        } else if (event instanceof TurnEvent.Completed c) {
            log.info("Received Completed TurnEvent");
            emitter.send(buildFrame("completed", new CompletedPayload(
                    c.assistantMessageId().toString(), c.title(), c.messageCount())));
        } else if (event instanceof TurnEvent.Error e) {
            log.info("Received Error TurnEvent");
            // Error events are emitted only by the direct-wire helper path
            // ({@link #writeError(...)}). The sealed type is exhaustive, so we
            // include the branch for completeness.
            emitter.send(buildFrame("error", e));
        } else {
            throw new IllegalStateException("Unhandled TurnEvent: " + event);
        }
    }

    /**
     * Writes the terminal {@code error} SSE frame from a {@link ProblemDetails}
     * body (used by the controller's reactive-error handler). The caller is
     * responsible for completing the emitter afterwards.
     */
    void writeError(SseEmitter emitter, ProblemDetails problem) throws IOException {
        emitter.send(buildFrame("error", problem));
    }

    private SseEventBuilder buildFrame(String eventName, Object data) {
        try {
            return SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException ex) {
            // Should not happen for the well-defined payload records below.
            throw new IllegalStateException("Failed to serialize SSE payload", ex);
        }
    }

    // ----- wire payloads (kept package-private; not part of the openapi schema names) -----

    record StartedPayload(String userMessageId, String conversationId) {}
    record DeltaPayload(String text) {}
    record CompletedPayload(String assistantMessageId, String title, int messageCount) {}
}
