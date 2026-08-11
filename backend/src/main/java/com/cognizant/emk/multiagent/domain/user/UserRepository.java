package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import java.util.Optional;

/**
 * Domain repository port for the {@link User} aggregate.
 *
 * <p>Pure Java — no Spring annotations. The adapter implementing this interface lives in
 * {@code infrastructure/persistence/adapter/} and bridges to Spring Data JPA.
 *
 * <p>The original surface (EPIC-03) covered the read/write paths login and self password
 * change need. EPIC-05 / US-05-001 extends it with the admin-side read/write paths:
 * the duplicate-email guard, the keyset-paged list, and hard-delete. Hard-delete
 * cascade through {@code agents → conversations → messages} is provided by the V001
 * FK chain (REQ-USR-006) and is verified by the EPIC-02 cascade contract test; the
 * port contract here is "the row is gone after {@link #delete(UserId)} returns".
 */
public interface UserRepository {

    Optional<User> findByEmail(Email email);

    Optional<User> findById(UserId id);

    User save(User user);

    /**
     * Returns {@code true} iff a user with the given email exists. Callers pass the
     * canonicalized (lowercase) value via {@code Email#value()} — the
     * {@code lower(email)} functional unique index (V004) keeps the lookup O(1).
     * Used by {@code CreateUserService} (US-05-004) for the duplicate-email pre-flight
     * check; the value-object encapsulation is what makes "supply the canonical value"
     * a non-issue here.
     */
    boolean existsByEmail(Email email);

    /**
     * Returns one page of users ordered newest-first ({@code (createdAt, id) DESC}).
     * Pass {@code cursor = null} for the first page. {@code pageSize} is the maximum
     * number of items in the returned page; the adapter is responsible for detecting
     * the presence of a next page and emitting a non-null {@link Page#nextCursor()}
     * accordingly. Used by {@code ListUsersService} (US-05-005).
     */
    Page<User> listAll(Cursor cursor, int pageSize);

    /**
     * Hard-deletes the user identified by {@code id}. The FK cascade chain in V001 is
     * responsible for removing every owned agent, conversation, and message
     * (REQ-USR-006); the integration test in US-05-008 verifies this through the REST
     * surface. A non-existent id is a silent no-op at this layer — the use case
     * (US-05-008) is responsible for the 404 by reading the row first.
     */
    void delete(UserId id);
}
