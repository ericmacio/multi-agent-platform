package com.cognizant.emk.multiagent.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito + JUnit 5 exemplar — no Spring context. The template every application-layer test
 * follows: drive a use case and verify it interacts with its mocked technical port the
 * expected number of times.
 *
 * <p>The port and stub use case below are scaffolding local to the test source set; real
 * ports/services arrive with their owning EPICs.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationExemplarTest {

    interface GreetingPort {
        String greet(String name);
    }

    static final class GreetingUseCase {
        private final GreetingPort port;

        GreetingUseCase(GreetingPort port) {
            this.port = port;
        }

        String greetTwice(String name) {
            return port.greet(name) + " / " + port.greet(name);
        }
    }

    @Mock
    private GreetingPort port;

    @InjectMocks
    private GreetingUseCase useCase;

    @Test
    void use_case_invokes_port_expected_number_of_times() {
        when(port.greet("Ada")).thenReturn("hello Ada");

        String result = useCase.greetTwice("Ada");

        assertThat(result).isEqualTo("hello Ada / hello Ada");
        verify(port, times(2)).greet("Ada");
    }
}
