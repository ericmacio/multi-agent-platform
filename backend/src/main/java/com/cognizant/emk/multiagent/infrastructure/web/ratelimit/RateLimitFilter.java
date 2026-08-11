package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate;
import com.cognizant.emk.multiagent.application.ratelimit.RateLimitGate.TryAcquireResult;
import com.cognizant.emk.multiagent.infrastructure.error.RateLimitedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Outermost filter in the Spring Security chain — global Bucket4j gate
 * (REQ-RL-001 / REQ-RL-003).
 *
 * <p>Every incoming request consumes one token via {@link RateLimitGate}. A
 * denied request is bridged into {@code GlobalExceptionHandler} through the
 * standard {@link HandlerExceptionResolver} so the 429 + {@code Retry-After} +
 * {@code application/problem+json} envelope is produced by a single code path
 * (same pattern as {@code JwtAuthenticationFilter} for 401). The filter never
 * writes the response itself.
 *
 * <p>{@link #shouldNotFilter(HttpServletRequest)} excludes
 * {@code /actuator/**} so the operator health probe is never throttled
 * (REQ-OBS-003). Login attempts and other unauthenticated traffic DO count —
 * the filter sits before {@code JwtAuthenticationFilter}, which is what
 * REQ-RL-003 requires.
 */
@Component
public final class RateLimitFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PATH_PREFIX = "/actuator";

    private final RateLimitGate gate;
    private final HandlerExceptionResolver resolver;

    public RateLimitFilter(
            RateLimitGate gate,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.gate = gate;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        TryAcquireResult result = gate.tryAcquire();
        if (result instanceof TryAcquireResult.Denied denied) {
            resolver.resolveException(
                    request, response, null, new RateLimitedException(denied.retryAfterSeconds()));
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getRequestURI() is reliable both in a real container (where it equals
        // servletPath when there is no context path) and in MockMvc (where
        // servletPath defaults to empty). Both paths are tested in US-13-007.
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(ACTUATOR_PATH_PREFIX);
    }
}
