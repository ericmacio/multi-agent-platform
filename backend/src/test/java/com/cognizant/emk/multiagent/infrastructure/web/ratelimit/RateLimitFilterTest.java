package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate;
import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate.TryAcquireResult;
import com.cognizant.emk.multiagent.infrastructure.error.RateLimitedException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock private RateLimitGate gate;
    @Mock private HandlerExceptionResolver resolver;
    @Mock private FilterChain chain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(gate, resolver);
    }

    @Test
    void allowed_proceeds_through_the_chain() throws Exception {
        when(gate.tryAcquire()).thenReturn(new TryAcquireResult.Allowed());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/agents");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(resolver);
    }

    @Test
    void denied_routes_to_resolver_with_RateLimitedException_and_does_not_chain() throws Exception {
        when(gate.tryAcquire()).thenReturn(new TryAcquireResult.Denied(42));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        ArgumentCaptor<Exception> ex = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(eq(req), eq(res), any(), ex.capture());
        assertThat(ex.getValue())
                .isInstanceOfSatisfying(RateLimitedException.class, rl ->
                        assertThat(rl.retryAfterSeconds()).isEqualTo(42));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void actuator_health_is_skipped_and_never_consults_the_gate() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(gate);
        verifyNoInteractions(resolver);
    }

    @Test
    void actuator_subpath_is_skipped() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/info");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(gate);
    }
}
