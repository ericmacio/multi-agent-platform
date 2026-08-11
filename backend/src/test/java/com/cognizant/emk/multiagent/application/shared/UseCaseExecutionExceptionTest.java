package com.cognizant.emk.multiagent.application.shared;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Java unit test for {@link UseCaseExecutionException} (US-14-001).
 *
 * <p>The type is the application-layer escape hatch for unrecoverable orchestration
 * failures (design §9.1). Two invariants are pinned here:
 * <ul>
 *   <li>The constructor stores message and cause faithfully.</li>
 *   <li>The class is {@code final} — subclassing per bounded context would defeat
 *       the "one typed seam for the catch-all 500 path" intent.</li>
 * </ul>
 */
class UseCaseExecutionExceptionTest {

    @Test
    void constructor_stores_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("contract impossible");
        UseCaseExecutionException ex = new UseCaseExecutionException("orchestration failed", cause);

        assertThat(ex.getMessage()).isEqualTo("orchestration failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void class_is_final() {
        assertThat(Modifier.isFinal(UseCaseExecutionException.class.getModifiers()))
                .as("UseCaseExecutionException must be final per US-14-001")
                .isTrue();
    }
}
