package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link DeleteUserUseCase} implementation.
 *
 * <p>Pre-flight {@code findById} converts a missing id into 404 — without it, the call
 * would silently no-op (Spring Data {@code deleteById} returns normally on a missing
 * id). The FK cascade chain (V001 → agents → agent_tools / agent_mcp_servers /
 * agent_team / conversations → messages) handles the actual data sweep.
 */
@Service
public class DeleteUserService implements DeleteUserUseCase {

    private final UserRepository userRepository;

    public DeleteUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void delete(UserId userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        userRepository.delete(userId);
    }
}
