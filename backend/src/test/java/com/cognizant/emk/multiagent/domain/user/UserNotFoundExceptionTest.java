package com.cognizant.emk.multiagent.domain.user;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserNotFoundExceptionTest {

    @Test
    void uuid_constructor_message_contains_the_uuid() {
        UUID id = UUID.fromString("9c4f3b1e-2a8d-4c5b-9e7a-1f2d3e4c5b6a");
        UserNotFoundException ex = new UserNotFoundException(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void user_id_constructor_message_contains_the_uuid() {
        UUID id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        UserNotFoundException ex = new UserNotFoundException(new UserId(id));
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void is_a_not_found_exception_so_the_generic_handler_routes_it_to_404() {
        UserNotFoundException ex = new UserNotFoundException(UUID.randomUUID());
        // GlobalExceptionHandler.handleNotFound is annotated for
        // @ExceptionHandler({NotFoundException.class, ...}). The instanceof check is
        // therefore load-bearing for the 404 routing.
        assertThat(ex).isInstanceOf(NotFoundException.class);
    }
}
