package com.cognizant.emk.multiagent.application.user;

import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListUsersUseCase} implementation. Pure forwarder — the REST adapter
 * has already decoded the opaque wire cursor into a domain {@code Cursor}, so this
 * layer just threads the call through to the repository (same shape as
 * {@code ListApiKeysService}).
 */
@Service
public class ListUsersService implements ListUsersUseCase {

    private final UserRepository userRepository;

    public ListUsersService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<User> list(ListUsersQuery query) {
        return userRepository.listAll(query.cursor(), query.pageSize().value());
    }
}
