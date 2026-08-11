package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import com.cognizant.emk.multiagent.domain.user.MustChangePasswordException;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Blocks every endpoint except {@code PUT /auth/password} and {@code POST /auth/logout}
 * when the JWT-authenticated end-user has {@code mustChangePassword=true} (REQ-USR-007).
 *
 * <p>Sits immediately after {@link JwtAuthenticationFilter} in the chain. When the security
 * context carries a {@link UserPrincipal}, the filter loads the user via {@link UserRepository}
 * and short-circuits with a {@link MustChangePasswordException} (→ 403
 * {@code MUST_CHANGE_PASSWORD} via the {@code GlobalExceptionHandler}) unless the request
 * targets the allow-list. Anonymous requests, principals other than {@link UserPrincipal}
 * (e.g. the future {@code SystemPrincipal} from EPIC-04), and users whose flag is already
 * cleared pass through untouched.
 *
 * <p>Like {@link JwtAuthenticationFilter}, the filter never writes the response body itself —
 * failures are dispatched through Spring MVC's {@link HandlerExceptionResolver} so the
 * shared {@code application/problem+json} body is produced centrally (REQ-API-004).
 */
@Component
public class ForcedPasswordChangeFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final HandlerExceptionResolver resolver;
    private final String passwordChangePath;
    private final String logoutPath;

    public ForcedPasswordChangeFilter(
            UserRepository userRepository,
            ApplicationProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.userRepository = userRepository;
        this.resolver = resolver;
        // Resolve the allow-list paths once at bean-init time. Single source of truth =
        // REQ-API-006: changing app.api.base-path relocates these in lockstep with the
        // controllers and the rest of the security chain.
        String apiPrefix = stripTrailingSlash(properties.api().basePath());
        this.passwordChangePath = apiPrefix + "/auth/password";
        this.logoutPath = apiPrefix + "/auth/logout";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<User> user = userRepository.findById(principal.id());
        if (user.isEmpty() || !user.get().mustChangePassword()) {
            chain.doFilter(request, response);
            return;
        }
        if (isAllowListed(request)) {
            chain.doFilter(request, response);
            return;
        }
        resolver.resolveException(request, response, null, new MustChangePasswordException());
    }

    private boolean isAllowListed(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (HttpMethod.PUT.matches(method) && passwordChangePath.equals(path)) {
            return true;
        }
        return HttpMethod.POST.matches(method) && logoutPath.equals(path);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
