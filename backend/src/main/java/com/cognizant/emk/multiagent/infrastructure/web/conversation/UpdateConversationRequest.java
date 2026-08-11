package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /conversations/{conversationId}}. Mirror of
 * the openapi {@code UpdateConversationRequest} schema. The bean-validation
 * annotations short-circuit before the controller body; the domain
 * {@code Title} value object re-checks at the boundary.
 */
public record UpdateConversationRequest(
        @NotBlank @Size(max = 32) String title) {
}
