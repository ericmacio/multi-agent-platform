package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateEmailExceptionTest {

    @Test
    void message_contains_the_canonicalized_lowercase_email() {
        DuplicateEmailException ex = new DuplicateEmailException(new Email("Alice@Example.Com"));
        // The Email value object lowercases at construction (US-CR1-001), so the
        // message carries the canonical form regardless of the input casing.
        assertThat(ex.getMessage()).contains("alice@example.com");
    }

    @Test
    void is_a_conflict_exception_so_the_generic_handler_routes_it_to_409() {
        DuplicateEmailException ex = new DuplicateEmailException(new Email("a@b.c"));
        // GlobalExceptionHandler.handleConflict (US-05-003) is annotated for
        // @ExceptionHandler(ConflictException.class). The instanceof relationship is
        // therefore load-bearing for the 409 routing.
        assertThat(ex).isInstanceOf(ConflictException.class);
    }
}
