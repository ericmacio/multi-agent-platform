package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.application.auth.JwtTokenService.IssuedToken;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.security.JwtTokenServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default {@link LoginUseCase} implementation.
 *
 * <p>Every failure mode (unknown email, wrong password, disabled account) surfaces as the
 * same {@link InvalidCredentialsException} so the response body is byte-identical regardless
 * of which check failed (REQ-AUTH-009). Issuing the JWT does NOT clear
 * {@code mustChangePassword}; the flag flows up to the response body so the frontend can
 * route the user to the password-change screen (REQ-USR-007).
 */
@Service
public class LoginService implements LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService jwtTokenService;

    public LoginService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    public LoginResult login(LoginCommand command) {
        User user = userRepository.findByEmail(command.email())
                .orElseThrow(InvalidCredentialsException::new);
        log.info("Check password for user: {}", command.email());
        if (!passwordHasher.matches(command.password(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isActive()) {
            // Surface 401 (not 403) so a disabled account is indistinguishable from a wrong
            // password or unknown email at the response-body level (REQ-AUTH-009).
            throw new InvalidCredentialsException();
        }
        IssuedToken issued = jwtTokenService.issue(user);
        return new LoginResult(issued.token(), issued.expiresAt(), user.mustChangePassword());
    }
}
