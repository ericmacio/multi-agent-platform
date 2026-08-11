package com.cognizant.emk.multiagent.infrastructure.persistence.adapter;

import com.cognizant.emk.multiagent.infrastructure.error.DatabaseAccessException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/**
 * Tiny helper that brackets persistence-adapter calls and translates
 * Spring's {@link DataAccessException} hierarchy into the
 * infrastructure-layer {@link DatabaseAccessException} (US-14-002).
 *
 * <p>The recommended pattern for repository adapters:
 *
 * <pre>{@code
 * @Override
 * @Transactional(readOnly = true)
 * public Foo load(FooId id) {
 *     return JpaAccess.run("foo.load", () ->
 *             fooJpaRepository.findById(id.value()).map(this::toDomain).orElseThrow(...));
 * }
 * }</pre>
 *
 * <p>The class is final and the constructor is private — this is a utility, not
 * an extension point.
 */
public final class JpaAccess {

    private JpaAccess() {
        throw new AssertionError("not instantiable");
    }

    /** Wrap a value-returning JPA call. */
    public static <T> T run(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException ex) {
            throw new DatabaseAccessException(operation + " failed", ex);
        }
    }

    /** Wrap a void JPA call. */
    public static void run(String operation, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException ex) {
            throw new DatabaseAccessException(operation + " failed", ex);
        }
    }
}
