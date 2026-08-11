package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.application.auth.ChangeOwnPasswordUseCase.ChangePasswordCommand;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeOwnPasswordServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordHasher passwordHasher;

    private Clock clock;
    private ChangeOwnPasswordService service;

    private UserId userId;
    private Password currentPassword;
    private Password newPassword;
    private User user;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-06T12:00:00Z"), ZoneOffset.UTC);
        service = new ChangeOwnPasswordService(userRepository, passwordHasher, clock);

        userId = new UserId(UUID.randomUUID());
        currentPassword = new Password("Bootstrap!1A");
        newPassword = new Password("Brand!New2Z");
        OffsetDateTime now = OffsetDateTime.parse("2026-04-01T09:00:00Z");
        user = new User(
                userId,
                new Email("admin@example.test"),
                "stored-bcrypt-hash",
                Role.ADMIN,
                false,
                true,
                now,
                now);
    }

    @Test
    void happy_path_persists_new_hash_and_clears_mustChangePassword() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordHasher.matches(currentPassword, user.passwordHash())).thenReturn(true);
        when(passwordHasher.hash(newPassword)).thenReturn("new-bcrypt-hash");

        service.changePassword(new ChangePasswordCommand(userId, currentPassword, newPassword));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User result = saved.getValue();
        assertThat(result.passwordHash()).isEqualTo("new-bcrypt-hash");
        assertThat(result.mustChangePassword()).isFalse();
        assertThat(result.updatedAt()).isEqualTo(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        assertThat(result.id()).isEqualTo(userId);
        assertThat(result.email()).isEqualTo(user.email());
        assertThat(result.role()).isEqualTo(user.role());
    }

    @Test
    void unknown_user_id_raises_UserNotFoundException() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, currentPassword, newPassword)))
                .isInstanceOf(UserNotFoundException.class);

        verify(passwordHasher, never()).matches(any(), any());
        verify(passwordHasher, never()).hash(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void wrong_current_password_raises_InvalidCredentialsException_and_skips_save() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(passwordHasher.matches(currentPassword, user.passwordHash())).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, currentPassword, newPassword)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordHasher, never()).hash(any());
        verify(userRepository, never()).save(any());
    }
}
