package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;

/**
 * Generic authentication failure raised by the auth use cases and the JWT filter.
 *
 * <p>The message is the static literal {@code "Authentication failed."} so no caller can
 * accidentally embed user-supplied data (typed email, partial password, parsed claim) into
 * the response body. The {@code GlobalExceptionHandler} maps this to HTTP 401 with the
 * machine-readable code {@code INVALID_CREDENTIALS}.
 */
public final class InvalidCredentialsException extends BusinessException {

    private static final String MESSAGE = "Authentication failed.";

    public InvalidCredentialsException() {
        super(MESSAGE);
    }

    public InvalidCredentialsException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
