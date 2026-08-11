package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.DuplicateEmailException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import java.util.Objects;

/**
 * Use case for {@code POST /admin/users} (REQ-USR-001 / REQ-USR-002 / REQ-USR-003).
 *
 * <p>Creates a new user with the admin-supplied email, password, and role. The new
 * user starts with {@code mustChangePassword=true} so the temporary password the admin
 * set forces a self-change on first login — same pattern as the seeded admin
 * (REQ-USR-007). Failures:
 * <ul>
 *   <li>{@link DuplicateEmailException} → HTTP 409 {@code CONFLICT} when the email is
 *   already taken. The {@link InvalidCredentialsException} import is unrelated; it is
 *   not raised here.</li>
 *   <li>Bean / domain validation errors → 400 {@code VALIDATION_ERROR} (the REST adapter
 *   constructs the {@link Email} and {@link Password} value objects at the boundary,
 *   so policy violations surface there).</li>
 * </ul>
 */
public interface CreateUserUseCase {

    User create(CreateUserCommand command);

    /** Inputs. All fields are required. */
    record CreateUserCommand(Email email, Password password, Role role) {

        public CreateUserCommand {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(role, "role");
        }
    }
}
