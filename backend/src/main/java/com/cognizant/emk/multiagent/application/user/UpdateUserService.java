package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link UpdateUserUseCase} implementation.
 *
 * <p>Reads the user, applies {@code withDisabled}, persists. The {@code Clock} bean
 * supplies "now" so the same virtualized time used by the JWT / denylist test stack
 * also drives {@code updatedAt} here.
 */
@Service
public class UpdateUserService implements UpdateUserUseCase {

    private final UserRepository userRepository;
    private final Clock clock;

    public UpdateUserService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public User updateDisabled(UpdateUserCommand command) {
        User existing = userRepository.findById(command.userId())
                .orElseThrow(() -> new UserNotFoundException(command.userId()));
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        return userRepository.save(existing.withDisabled(command.disabled(), now));
    }
}
