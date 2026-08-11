package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.application.user.UpdateUserUseCase.UpdateUserCommand;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateUserServiceTest {

    @Mock private UserRepository userRepository;

    private Clock clock;
    private UpdateUserService service;

    private UserId userId;
    private User existing;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-12T11:00:00Z"), ZoneOffset.UTC);
        service = new UpdateUserService(userRepository, clock);

        userId = new UserId(UUID.fromString("9c4f3b1e-2a8d-4c5b-9e7a-1f2d3e4c5b6a"));
        OffsetDateTime earlier = OffsetDateTime.parse("2026-05-10T08:00:00Z");
        existing = new User(
                userId,
                new Email("alice@example.test"),
                "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW",
                Role.STANDARD,
                false,
                false,
                earlier,
                earlier);
    }

    @Test
    void disables_existing_user_and_bumps_updated_at_via_clock() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.updateDisabled(new UpdateUserCommand(userId, true));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.disabled()).isTrue();
        assertThat(saved.updatedAt()).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(saved.createdAt()).isEqualTo(existing.createdAt());
        assertThat(saved.passwordHash()).isEqualTo(existing.passwordHash());
        assertThat(result).isSameAs(saved);
    }

    @Test
    void re_enable_round_trip_is_symmetric() {
        User disabled = existing.withDisabled(true, OffsetDateTime.parse("2026-05-11T00:00:00Z"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(disabled));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.updateDisabled(new UpdateUserCommand(userId, false));

        assertThat(result.disabled()).isFalse();
    }

    @Test
    void unknown_user_raises_UserNotFoundException_without_writing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDisabled(new UpdateUserCommand(userId, true)))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).save(any());
    }
}
