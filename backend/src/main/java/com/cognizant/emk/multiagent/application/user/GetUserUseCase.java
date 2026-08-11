package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;

/**
 * Use case for {@code GET /admin/users/{userId}} (REQ-USR-005).
 *
 * <p>No command record: the input is a single value object, mirroring the precedent
 * set in {@code LoginUseCase} where a record is only used when there is more than one
 * field.
 */
public interface GetUserUseCase {

    /**
     * Fetches the user identified by {@code userId}.
     *
     * @throws UserNotFoundException when no user matches.
     */
    User get(UserId userId);
}
