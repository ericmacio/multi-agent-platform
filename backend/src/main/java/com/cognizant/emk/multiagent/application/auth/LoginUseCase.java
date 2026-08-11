package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Use case for the {@code POST /auth/login} endpoint (REQ-AUTH-002).
 *
 * <p>Verifies email + password against the stored {@code User}, then issues a signed JWT.
 * Every authentication failure (unknown email, wrong password, disabled account) surfaces as
 * the same {@link InvalidCredentialsException} so the REST adapter can return a generic
 * 401 body — REQ-AUTH-009 forbids leaking whether the email exists or the credential format
 * was wrong.
 */
public interface LoginUseCase {

    /**
     * Authenticates {@code command} and returns a {@link LoginResult}.
     *
     * @throws InvalidCredentialsException on any authentication failure.
     */
    LoginResult login(LoginCommand command);

    /** Inputs to {@link #login(LoginCommand)}. */
    record LoginCommand(Email email, Password password) {

        public LoginCommand {
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(password, "password");
        }
    }

    /**
     * Outputs of a successful login. {@code mustChangePassword} flows up to the response body
     * so the frontend can route the seeded admin (or any user with the flag set) to the
     * password-change screen — issuing the JWT does NOT clear the flag (REQ-USR-007).
     */
    record LoginResult(String token, OffsetDateTime expiresAt, boolean mustChangePassword) {

        public LoginResult {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
