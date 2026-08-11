package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;

/**
 * Raised by {@code CreateUserService} (US-05-004) when the requested email already
 * belongs to another user (REQ-USR-002).
 *
 * <p>Maps to HTTP 409 {@code CONFLICT} via the {@code GlobalExceptionHandler}
 * {@code ConflictException} branch (US-05-003). The message carries the canonicalized
 * (lowercase) email — admins by definition have access to user data, so surfacing it
 * helps them resolve the conflict; non-admin callers never receive this exception
 * because the {@code /admin/**} URL guard rejects them with 403 before the use case
 * runs.
 */
public final class DuplicateEmailException extends ConflictException {

    public DuplicateEmailException(Email email) {
        super("User with this email already exists: " + email.value());
    }
}
