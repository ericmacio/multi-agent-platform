package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import java.util.UUID;

/**
 * Raised when a {@link User} lookup by id returns nothing. Mapped to HTTP 404
 * {@code NOT_FOUND} by the {@code GlobalExceptionHandler}.
 *
 * <p>In EPIC-03 / US-03-011 (self password change) this exception is realistically
 * unreachable — the principal id always comes from a verified JWT — but the use case
 * still raises it so a future deletion racing a token-bearing request returns the
 * documented status rather than a 500. In EPIC-05 it is a normal outcome of every
 * admin operation on {@code /admin/users/{userId}}.
 *
 * <p>The message carries only the UUID — the public identifier — and never any
 * sensitive attribute of the underlying user.
 */
public final class UserNotFoundException extends NotFoundException {

    public UserNotFoundException(UUID userId) {
        super("User not found: " + userId);
    }

    public UserNotFoundException(UserId userId) {
        this(userId.value());
    }
}
