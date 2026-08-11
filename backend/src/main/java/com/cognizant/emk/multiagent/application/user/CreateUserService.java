package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.application.auth.PasswordHasher;
import com.cognizant.emk.multiagent.domain.user.DuplicateEmailException;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link CreateUserUseCase} implementation.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Pre-flight duplicate-email check via {@code userRepository.existsByEmail}; raise
 *   {@link DuplicateEmailException} on a hit.</li>
 *   <li>BCrypt-hash the cleartext via {@link PasswordHasher}.</li>
 *   <li>Persist a new {@link User} with {@code disabled=false},
 *   {@code mustChangePassword=true}, and {@code createdAt/updatedAt = now} sourced from
 *   the injected {@link Clock} (same convention as
 *   {@code ChangeOwnPasswordService}).</li>
 *   <li>Return the persisted aggregate; the cleartext password is never returned and
 *   never logged.</li>
 * </ol>
 *
 * <p>The pre-flight check + unique index together cover the duplicate case; under a
 * race two concurrent admin creates with the same email would surface the
 * {@code DataIntegrityViolationException} from the unique index. That is acceptable at
 * the v1 64-user scale (REQ-NFR-005); a future EPIC can map that exception to
 * {@code DuplicateEmailException} for symmetry if needed.
 */
@Service
public class CreateUserService implements CreateUserUseCase {

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public CreateUserService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            Clock clock) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public User create(CreateUserCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new DuplicateEmailException(command.email());
        }
        String hash = passwordHasher.hash(command.password());
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        User user = new User(
                new UserId(UUID.randomUUID()),
                command.email(),
                hash,
                command.role(),
                /* disabled */ false,
                /* mustChangePassword */ true,
                now,
                now);
        return userRepository.save(user);
    }
}
