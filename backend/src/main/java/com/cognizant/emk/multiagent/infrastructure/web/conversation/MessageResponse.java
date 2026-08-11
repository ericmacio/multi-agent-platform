package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response body item for {@code GET /conversations/{conversationId}/messages}.
 * Matches the openapi {@code Message} schema:
 * {@code id, role, content, createdAt}.
 *
 * <p>{@code role} is the enum name on the wire ({@code "USER"} /
 * {@code "ASSISTANT"}) — matches the openapi enum and what
 * {@code MessageRole.valueOf(...)} round-trips.
 */
public record MessageResponse(
        UUID id,
        String role,
        String content,
        OffsetDateTime createdAt) {
}
