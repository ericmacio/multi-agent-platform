package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.application.auth.JwtDenylist;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService.TokenClaims;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authenticates incoming requests carrying an {@code Authorization: Bearer <token>} header.
 *
 * <p>Behavior (design §8.1, §8.2):
 * <ul>
 *   <li>No header (or non-Bearer): the chain continues unauthenticated. The URL-level
 *   rules in {@code SpringSecurityConfig} decide whether 401 is appropriate.</li>
 *   <li>Bearer header: parse + verify the token, then check the logout denylist. On
 *   success, populate the {@code SecurityContext} with a {@link UserPrincipal}. On any
 *   failure, dispatch an {@link InvalidCredentialsException} through the Spring MVC
 *   {@link HandlerExceptionResolver} so the {@code GlobalExceptionHandler} writes the
 *   shared {@code application/problem+json} body — the filter never writes the response
 *   itself (REQ-AUTH-009 / REQ-API-004).</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final JwtDenylist jwtDenylist;
    private final HandlerExceptionResolver resolver;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            JwtDenylist jwtDenylist,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.jwtTokenService = jwtTokenService;
        this.jwtDenylist = jwtDenylist;
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        try {
            TokenClaims claims = jwtTokenService.verify(rawToken);
            if (jwtDenylist.contains(claims.jti())) {
                throw new InvalidCredentialsException();
            }
            UserPrincipal principal = new UserPrincipal(
                    claims.userId(), claims.email(), claims.role(), claims.jti(), claims.expiresAt());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidCredentialsException ex) {
            // Do NOT pollute the SecurityContext with a half-built authentication.
            SecurityContextHolder.clearContext();
            resolver.resolveException(request, response, null, ex);
            return; // resolver wrote the response — stop the chain.
        }
        chain.doFilter(request, response);
    }
}
