package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * One incremental fragment of a streamed assistant response (design §12). The
 * empty string is a valid payload — heartbeat / role-only frames from the
 * provider surface as {@code ChatChunk("")} and the SSE emitter (EPIC-11)
 * elides them.
 */
public record ChatChunk(String text) {

    public ChatChunk {
        if (text == null) {
            throw new ValidationException("text", "must not be null");
        }
    }
}
