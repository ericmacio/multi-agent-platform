package com.cognizant.emk.multiagent.infrastructure.persistence.adapter;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.mapper.UserMapper;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA-backed adapter for the {@link UserRepository} domain port.
 *
 * <p>Bridges the domain {@link User} aggregate to the {@code UserJpa} entity through
 * {@link UserMapper}. The domain layer never sees JPA types; the application layer
 * never sees Spring Data.
 *
 * <p>{@link #listAll} uses the same keyset paging strategy as
 * {@code ApiKeyRepositoryAdapter}: fetch {@code pageSize + 1} rows ordered
 * {@code (createdAt DESC, id DESC)}, trim the surplus, and emit a non-null
 * {@code nextCursor} when more rows exist.
 */
@Component
public class UserRepositoryAdapter implements UserRepository {

    private static final int PAGE_SIZE_MIN = 1;
    private static final int PAGE_SIZE_MAX = 100;

    private final UserJpaRepository userJpaRepository;

    public UserRepositoryAdapter(UserJpaRepository userJpaRepository) {
        this.userJpaRepository = userJpaRepository;
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return userJpaRepository.findByEmail(email.value()).map(UserMapper::toDomain);
    }

    @Override
    public Optional<User> findById(UserId id) {
        return userJpaRepository.findById(id.value()).map(UserMapper::toDomain);
    }

    @Override
    public User save(User user) {
        return UserMapper.toDomain(userJpaRepository.save(UserMapper.toJpa(user)));
    }

    @Override
    public boolean existsByEmail(Email email) {
        return userJpaRepository.existsByEmail(email.value());
    }

    @Override
    public Page<User> listAll(Cursor cursor, int pageSize) {
        if (pageSize < PAGE_SIZE_MIN || pageSize > PAGE_SIZE_MAX) {
            throw new IllegalArgumentException(
                    "pageSize must be within [" + PAGE_SIZE_MIN + ", " + PAGE_SIZE_MAX + "]");
        }
        int limit = pageSize + 1;
        PageRequest probe = PageRequest.of(0, limit);
        List<UserJpa> rows = (cursor == null)
                ? userJpaRepository.findFirstPage(probe)
                : userJpaRepository.findPageAfter(
                        cursor.lastCreatedAt(),
                        UUID.fromString(cursor.lastId()),
                        probe);

        boolean hasMore = rows.size() > pageSize;
        List<User> items = rows.stream()
                .limit(pageSize)
                .map(UserMapper::toDomain)
                .toList();

        Cursor nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            User last = items.get(items.size() - 1);
            // Cursor MUST encode createdAt (the ORDER BY key), not updatedAt, otherwise
            // the keyset condition in findPageAfter would skip the wrong row.
            nextCursor = new Cursor(last.createdAt(), last.id().value().toString());
        }
        return new Page<>(items, nextCursor, pageSize);
    }

    @Override
    @Transactional
    public void delete(UserId id) {
        userJpaRepository.deleteById(id.value());
    }
}
