package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteUserServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private DeleteUserService service;

    @Test
    void existing_user_is_deleted_via_the_repository() {
        UserId userId = new UserId(UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        User existing = new User(
                userId,
                new Email("alice@example.test"),
                "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW",
                Role.STANDARD,
                false,
                false,
                now,
                now);
        when(userRepository.findById(userId)).thenReturn(Optional.of(existing));

        service.delete(userId);

        verify(userRepository).delete(userId);
    }

    @Test
    void unknown_id_raises_UserNotFoundException_and_does_not_call_delete() {
        UserId userId = new UserId(UUID.randomUUID());
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(userId))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).delete(userId);
    }
}
