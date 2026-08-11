package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ChangeOwnPasswordUseCase} implementation.
 *
 * <p>Wrapped in a single transaction so the load-verify-save sequence is atomic with the
 * forthcoming admin endpoints in EPIC-05 that may concurrently read or update the same
 * row. The {@link Clock} bean is injected so the new {@code updatedAt} can be controlled
 * from tests.
 */
@Service
public class ChangeOwnPasswordService implements ChangeOwnPasswordUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public ChangeOwnPasswordService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new UserNotFoundException(command.userId().value()));
        if (!passwordHasher.matches(command.currentPassword(), user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        String newHash = passwordHasher.hash(command.newPassword());
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        userRepository.save(user.withNewPasswordHash(newHash, now));
    }
}
