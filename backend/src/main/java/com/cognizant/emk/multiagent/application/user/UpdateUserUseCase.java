package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import java.util.Objects;

/**
 * Use case for {@code PATCH /admin/users/{userId}} (REQ-USR-005).
 *
 * <p>Partial update — currently only the {@code disabled} flag is editable through this
 * surface. The seeded admin's {@code mustChangePassword} flag is cleared exclusively by
 * the self password-change flow (EPIC-03 / US-03-011); an admin cannot bypass that on
 * behalf of another user.
 */
public interface UpdateUserUseCase {

    /**
     * Toggles the {@code disabled} flag and returns the updated aggregate.
     *
     * @throws UserNotFoundException when no user matches {@code command.userId()}.
     */
    User updateDisabled(UpdateUserCommand command);

    record UpdateUserCommand(UserId userId, boolean disabled) {

        public UpdateUserCommand {
            Objects.requireNonNull(userId, "userId");
        }
    }
}
