package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.application.auth.JwtTokenService.IssuedToken;
import com.cognizant.emk.multiagent.application.auth.LoginUseCase.LoginCommand;
import com.cognizant.emk.multiagent.application.auth.LoginUseCase.LoginResult;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock private com.cognizant.emk.multiagent.domain.user.UserRepository userRepository;
    @Mock private PasswordHasher passwordHasher;
    @Mock private JwtTokenService jwtTokenService;

    @InjectMocks private LoginService loginService;

    private Email email;
    private Password password;
    private User user;

    @BeforeEach
    void setUp() {
        email = new Email("alice@example.test");
        password = new Password("Bootstrap!1A");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user = new User(
                new UserId(UUID.randomUUID()),
                email,
                "stored-bcrypt-hash",
                Role.STANDARD,
                false,
                false,
                now,
                now);
    }

    @Test
    void happy_path_returns_token_expiry_and_mustChangePassword_from_the_user() {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        IssuedToken issued = new IssuedToken("signed.jwt.value", UUID.randomUUID(), expiresAt);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordHasher.matches(password, user.passwordHash())).thenReturn(true);
        when(jwtTokenService.issue(user)).thenReturn(issued);

        LoginResult result = loginService.login(new LoginCommand(email, password));

        assertThat(result.token()).isEqualTo("signed.jwt.value");
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        assertThat(result.mustChangePassword()).isFalse();
    }

    @Test
    void mustChangePassword_flag_flows_to_result_without_being_cleared() {
        User flagged = new User(
                user.id(), user.email(), user.passwordHash(), user.role(),
                false, true, user.createdAt(), user.updatedAt());
        IssuedToken issued = new IssuedToken("t", UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(flagged));
        when(passwordHasher.matches(password, flagged.passwordHash())).thenReturn(true);
        when(jwtTokenService.issue(flagged)).thenReturn(issued);

        LoginResult result = loginService.login(new LoginCommand(email, password));

        assertThat(result.mustChangePassword()).isTrue();
    }

    @Test
    void unknown_email_raises_InvalidCredentialsException_without_calling_hasher_or_jwt() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.login(new LoginCommand(email, password)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordHasher, never()).matches(any(), any());
        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void wrong_password_raises_InvalidCredentialsException_and_skips_token_issuance() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordHasher.matches(password, user.passwordHash())).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(new LoginCommand(email, password)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void disabled_user_raises_401_not_403_to_avoid_leaking_account_existence() {
        User disabled = new User(
                user.id(), user.email(), user.passwordHash(), user.role(),
                true, user.mustChangePassword(), user.createdAt(), user.updatedAt());
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(disabled));
        when(passwordHasher.matches(password, disabled.passwordHash())).thenReturn(true);

        assertThatThrownBy(() -> loginService.login(new LoginCommand(email, password)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtTokenService, never()).issue(any());
    }

    @Test
    void disabled_user_check_runs_after_password_match_so_byte_identical_to_wrong_password() {
        // Sanity: a disabled account with a wrong password must still raise the same
        // InvalidCredentialsException (via the wrong-password branch). Verifies neither
        // branch leaks "account exists but disabled".
        User disabled = new User(
                user.id(), user.email(), user.passwordHash(), user.role(),
                true, user.mustChangePassword(), user.createdAt(), user.updatedAt());
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(disabled));
        when(passwordHasher.matches(eq(password), eq(disabled.passwordHash()))).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(new LoginCommand(email, password)))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
