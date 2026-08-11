package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;

/**
 * Use case for {@code DELETE /admin/users/{userId}} (REQ-USR-006).
 *
 * <p>Hard-deletes the user and cascades through owned agents, conversations, and
 * messages via the V001 FK chain. The repository's {@code delete} is a silent no-op on
 * a missing id, so this use case must verify existence first to surface the documented
 * 404.
 */
public interface DeleteUserUseCase {

    /**
     * @throws UserNotFoundException when no user matches.
     */
    void delete(UserId userId);
}
