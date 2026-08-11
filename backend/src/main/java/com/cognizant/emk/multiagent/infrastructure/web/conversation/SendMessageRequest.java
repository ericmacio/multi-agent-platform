package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /conversations/{id}/messages} (US-11-005).
 * Mirror of the openapi {@code SendMessageRequest} schema.
 *
 * <p>The bean-validation annotations short-circuit before the controller
 * body executes — bad inputs surface as 400 {@code VALIDATION_ERROR} via
 * {@code GlobalExceptionHandler.handleBeanValidation(...)}. The domain
 * {@code MessageContent} value object re-checks the same constraints at
 * the application boundary (defense in depth).
 */
public record SendMessageRequest(
        @NotBlank @Size(max = 1024) String content) {
}
