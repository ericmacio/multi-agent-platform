package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.auth.SystemPrincipal;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates incoming requests carrying the {@code X-Client-Id} + {@code X-Api-Key}
 * header pair (design §8.1, §8.4).
 *
 * <p>Behavior:
 * <ul>
 *   <li>If the {@code SecurityContext} already carries a non-anonymous
 *   {@link Authentication}, the filter short-circuits — the JWT filter ahead of it
 *   already authenticated the request and "JWT wins" per design §8.1.</li>
 *   <li>If either header is missing, the chain continues unauthenticated; the
 *   URL-level rules in {@link SpringSecurityConfig} decide whether 401 is appropriate.</li>
 *   <li>If both headers are present: look up the API key by {@code clientId},
 *   ensure it is enabled, and BCrypt-compare the submitted secret against the stored
 *   hash. On success, populate the {@code SecurityContext} with a
 *   {@link SystemPrincipal} and the authority {@code ROLE_SYSTEM}. On any failure,
 *   dispatch an {@link InvalidCredentialsException} so the {@code GlobalExceptionHandler}
 *   writes the shared 401 {@code application/problem+json} body — byte-for-byte
 *   identical to the JWT failure body (REQ-AUTH-009).</li>
 * </ul>
 *
 * <p>The filter never logs the raw header values or the BCrypt hash (REQ-SEC-004).
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_CLIENT_ID = "X-Client-Id";
    private static final String HEADER_API_KEY = "X-Api-Key";

    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final HandlerExceptionResolver resolver;

    public ApiKeyAuthenticationFilter(
            ApiKeyRepository apiKeyRepository,
            ApiKeyHasher apiKeyHasher,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyHasher = apiKeyHasher;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (alreadyAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String clientIdHeader = request.getHeader(HEADER_CLIENT_ID);
        String apiKeyHeader = request.getHeader(HEADER_API_KEY);
        if (clientIdHeader == null || clientIdHeader.isBlank()
                || apiKeyHeader == null || apiKeyHeader.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            ClientId clientId = parseClientIdOrFail(clientIdHeader);
            Optional<ApiKey> match = apiKeyRepository.findByClientId(clientId);
            if (match.isEmpty()) {
                throw new InvalidCredentialsException();
            }
            ApiKey apiKey = match.get();
            if (!apiKey.isActive()) {
                throw new InvalidCredentialsException();
            }
            if (!apiKeyHasher.matches(apiKeyHeader, apiKey.apiKeyHash())) {
                throw new InvalidCredentialsException();
            }

            SystemPrincipal principal = new SystemPrincipal(apiKey.clientId());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_SYSTEM")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidCredentialsException ex) {
            SecurityContextHolder.clearContext();
            resolver.resolveException(request, response, null, ex);
            return; // resolver wrote the response — stop the chain.
        }
        chain.doFilter(request, response);
    }

    /**
     * Constructs the {@link ClientId}, mapping the value-object's {@link ValidationException}
     * to the generic 401 path so a malformed header does not leak that the format was the
     * problem.
     */
    private static ClientId parseClientIdOrFail(String header) {
        try {
            return new ClientId(header);
        } catch (ValidationException badShape) {
            throw new InvalidCredentialsException(badShape);
        }
    }

    private static boolean alreadyAuthenticated() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current != null
                && current.isAuthenticated()
                && !(current instanceof AnonymousAuthenticationToken);
    }
}
