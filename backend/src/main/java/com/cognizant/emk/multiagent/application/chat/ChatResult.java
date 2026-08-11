package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Non-streaming LLM result (design §12). The empty string is a valid payload —
 * a model can legitimately answer with no content.
 */
public record ChatResult(String text) {

    public ChatResult {
        if (text == null) {
            throw new ValidationException("text", "must not be null");
        }
    }
}
