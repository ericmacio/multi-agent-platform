package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import java.util.Objects;

/**
 * Use case for the {@code PUT /auth/password} endpoint (REQ-USR-004).
 *
 * <p>An authenticated user supplies their current password plus a policy-compliant new one;
 * the use case verifies the current password against the stored hash, persists the new hash,
 * and clears the {@code mustChangePassword} flag (REQ-USR-007).
 *
 * <p>Failure modes:
 * <ul>
 *   <li>{@link UserNotFoundException} (404) — principal id has no row (race with delete);</li>
 *   <li>{@link InvalidCredentialsException} (401) — current password does not match the
 *       stored hash. We surface 401, not 400, so the failure body matches the login error
 *       semantics (REQ-AUTH-009).</li>
 * </ul>
 *
 * <p>Per REQ-AUTH-006 the current JWT remains valid until natural expiry; a forced re-login
 * is intentionally NOT triggered.
 */
public interface ChangeOwnPasswordUseCase {

    void changePassword(ChangePasswordCommand command);

    /** Inputs to {@link #changePassword(ChangePasswordCommand)}. */
    record ChangePasswordCommand(UserId userId, Password currentPassword, Password newPassword) {

        public ChangePasswordCommand {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(currentPassword, "currentPassword");
            Objects.requireNonNull(newPassword, "newPassword");
        }
    }
}
