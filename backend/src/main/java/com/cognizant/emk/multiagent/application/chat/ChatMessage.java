package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * One past message in the memory window passed to the LLM (design §12).
 *
 * <p>The 1024-character cap on {@code content} mirrors the persistence cap from
 * REQ-CHAT-009 — past persisted messages already satisfy it, so the check here is
 * defense-in-depth against a misuse of the adapter from new use-case code.
 */
public record ChatMessage(Role role, String content) {

    private static final int MAX_CONTENT_LENGTH = 1024;

    public ChatMessage {
        if (role == null) {
            throw new ValidationException("role", "must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new ValidationException("content", "must not be empty");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new ValidationException(
                    "content", "must be at most " + MAX_CONTENT_LENGTH + " characters");
        }
    }
}
