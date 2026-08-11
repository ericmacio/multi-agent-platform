package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /conversations}. Mirror of the openapi
 * {@code CreateConversationRequest} schema.
 */
public record CreateConversationRequest(@NotNull UUID agentId) {
}
