package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ForbiddenException;

/**
 * Raised by {@code ForcedPasswordChangeFilter} when a user with {@code mustChangePassword=true}
 * tries to reach an endpoint other than {@code PUT /auth/password} or
 * {@code POST /auth/logout}. The {@code GlobalExceptionHandler} maps this to HTTP 403 with the
 * machine-readable code {@code MUST_CHANGE_PASSWORD}.
 */
public final class MustChangePasswordException extends ForbiddenException {

    public MustChangePasswordException() {
        super("Change your password before performing any other operation.");
    }
}
