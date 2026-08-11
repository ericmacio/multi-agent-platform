package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.Optional;

/**
 * Conversation title (REQ-CHAT-005).
 *
 * <p>Non-blank, capped at {@value #MAX_LENGTH} characters to match the
 * {@code conversations.title varchar(32)} column and the openapi cap. Violations
 * throw {@link ValidationException} with field {@code "title"}.
 *
 * <p>The "when" of derivation (only on the first non-empty user message; not
 * re-derived afterward) is owned by the application layer's
 * {@code SendMessageService} (EPIC-11). This value object only provides the
 * pure building blocks:
 * <ul>
 *   <li>{@link #fromFirstUserMessage(MessageContent)} — auto-deriving the
 *   title from the first user message, ignoring empties and truncating to
 *   the {@value #MAX_LENGTH}-character cap;</li>
 *   <li>{@link #defaultFor(ConversationId)} — the {@code chat-<uuid>}
 *   fallback when no usable title can be derived.</li>
 * </ul>
 */
public record Title(String value) {

    public static final int MAX_LENGTH = 32;

    public Title {
        if (value == null || value.isBlank()) {
            throw new ValidationException("title", "must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException(
                    "title", "must be at most " + MAX_LENGTH + " characters");
        }
    }

    /**
     * Derives a title from the first non-empty user message. Returns
     * {@link Optional#empty()} when the content (after {@link String#strip()})
     * is blank — the caller is then expected to fall back to
     * {@link #defaultFor(ConversationId)}.
     *
     * <p>When the trimmed content exceeds {@value #MAX_LENGTH} characters it
     * is truncated to the cap.
     */
    public static Optional<Title> fromFirstUserMessage(MessageContent firstMessage) {
        String trimmed = firstMessage.value().strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String truncated = trimmed.length() > MAX_LENGTH
                ? trimmed.substring(0, MAX_LENGTH)
                : trimmed;
        return Optional.of(new Title(truncated));
    }

    /**
     * Default title when {@link #fromFirstUserMessage(MessageContent)} returns
     * empty: {@code "chat-" + conversationId}. The full UUID does not fit in
     * {@value #MAX_LENGTH} characters, so the result is truncated to the cap.
     */
    public static Title defaultFor(ConversationId conversationId) {
        String raw = "chat-" + conversationId.value();
        String truncated = raw.length() > MAX_LENGTH
                ? raw.substring(0, MAX_LENGTH)
                : raw;
        return new Title(truncated);
    }
}
