package com.cognizant.emk.multiagent.infrastructure.web.auth;

import com.cognizant.emk.multiagent.application.auth.ChangeOwnPasswordUseCase;
import com.cognizant.emk.multiagent.application.auth.ChangeOwnPasswordUseCase.ChangePasswordCommand;
import com.cognizant.emk.multiagent.application.auth.LoginUseCase;
import com.cognizant.emk.multiagent.application.auth.LoginUseCase.LoginCommand;
import com.cognizant.emk.multiagent.application.auth.LoginUseCase.LoginResult;
import com.cognizant.emk.multiagent.application.auth.LogoutUseCase;
import com.cognizant.emk.multiagent.application.auth.LogoutUseCase.LogoutCommand;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.infrastructure.web.error.GlobalExceptionHandler;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the auth endpoints (REQ-AUTH-002, REQ-AUTH-011, REQ-USR-004).
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is applied centrally
 * by {@link com.cognizant.emk.multiagent.infrastructure.config.WebConfig} (REQ-API-006).
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String BEARER = "Bearer";

    private final LoginUseCase loginUseCase;
    private final LogoutUseCase logoutUseCase;
    private final ChangeOwnPasswordUseCase changeOwnPasswordUseCase;

    public AuthController(
            LoginUseCase loginUseCase,
            LogoutUseCase logoutUseCase,
            ChangeOwnPasswordUseCase changeOwnPasswordUseCase) {
        this.loginUseCase = loginUseCase;
        this.logoutUseCase = logoutUseCase;
        this.changeOwnPasswordUseCase = changeOwnPasswordUseCase;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        log.info("Received login request for email: {}", request.email());
        // Construct the value objects at the boundary so any policy / format violation
        // surfaces as a per-field 400 VALIDATION_ERROR via the GlobalExceptionHandler.
        LoginCommand command = new LoginCommand(new Email(request.email()), new Password(request.password()));
        LoginResult result = loginUseCase.login(command);
        return new LoginResponse(result.token(), BEARER, result.expiresAt(), result.mustChangePassword());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal UserPrincipal principal) {
        // jti / expiresAt come from the verified JWT via the JwtAuthenticationFilter, which
        // copies them into the principal (see UserPrincipal Javadoc). No URL-level @PreAuthorize
        // is needed: the security chain already requires authentication on /api/v1/**.
        logoutUseCase.logout(new LogoutCommand(principal.jti(), principal.expiresAt()));
    }

    @PutMapping("/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        Password current = parsePassword("currentPassword", request.currentPassword());
        Password fresh = parsePassword("newPassword", request.newPassword());
        changeOwnPasswordUseCase.changePassword(new ChangePasswordCommand(principal.id(), current, fresh));
    }

    /**
     * Constructs a {@link Password}, rethrowing any policy violation under {@code field}
     * (instead of the value object's default {@code "password"}) so the response error
     * mentions {@code currentPassword} or {@code newPassword} explicitly.
     */
    private static Password parsePassword(String field, String cleartext) {
        try {
            return new Password(cleartext);
        } catch (ValidationException ex) {
            throw new ValidationException(field, ex.getMessage());
        }
    }
}
