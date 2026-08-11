package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Persisted message body (REQ-CHAT-009).
 *
 * <p>Non-blank; capped at {@value #MAX_LENGTH} characters to match the
 * {@code messages.content varchar(1024)} column and the openapi
 * {@code Message.content} maxLength. Violations throw
 * {@link ValidationException} with field {@code "content"}.
 */
public record MessageContent(String value) {

    public static final int MAX_LENGTH = 1024;

    public MessageContent {
        if (value == null || value.isBlank()) {
            throw new ValidationException("content", "must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException(
                    "content", "must be at most " + MAX_LENGTH + " characters");
        }
    }
}
