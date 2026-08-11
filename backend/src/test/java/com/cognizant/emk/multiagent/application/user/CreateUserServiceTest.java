package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.application.auth.PasswordHasher;
import com.cognizant.emk.multiagent.application.user.CreateUserUseCase.CreateUserCommand;
import com.cognizant.emk.multiagent.domain.user.DuplicateEmailException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Password;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordHasher passwordHasher;

    private Clock clock;
    private CreateUserService service;

    private Email email;
    private Password password;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        service = new CreateUserService(userRepository, passwordHasher, clock);
        email = new Email("alice@example.test");
        password = new Password("Standard!1A");
    }

    @Test
    void happy_path_hashes_password_persists_user_with_must_change_true_and_returns_aggregate() {
        String hash = "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(passwordHasher.hash(password)).thenReturn(hash);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.create(new CreateUserCommand(email, password, Role.STANDARD));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User persisted = captor.getValue();
        assertThat(persisted.email()).isEqualTo(email);
        assertThat(persisted.passwordHash()).isEqualTo(hash);
        assertThat(persisted.role()).isEqualTo(Role.STANDARD);
        assertThat(persisted.disabled()).isFalse();
        assertThat(persisted.mustChangePassword()).isTrue();
        assertThat(persisted.createdAt()).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(persisted.updatedAt()).isEqualTo(persisted.createdAt());
        assertThat(persisted.id().value()).isNotNull();
        assertThat(result).isSameAs(persisted);
    }

    @Test
    void duplicate_email_raises_DuplicateEmailException_without_hashing_or_saving() {
        when(userRepository.existsByEmail(email)).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateUserCommand(email, password, Role.STANDARD)))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining(email.value());

        verify(passwordHasher, never()).hash(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void admin_role_is_carried_through_unchanged() {
        when(userRepository.existsByEmail(email)).thenReturn(false);
        when(passwordHasher.hash(password)).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.create(new CreateUserCommand(email, password, Role.ADMIN));

        assertThat(result.role()).isEqualTo(Role.ADMIN);
    }
}
