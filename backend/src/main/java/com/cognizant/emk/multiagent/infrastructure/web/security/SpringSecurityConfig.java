package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import com.cognizant.emk.multiagent.infrastructure.web.observability.CorrelationIdFilter;
import com.cognizant.emk.multiagent.infrastructure.web.ratelimit.RateLimitFilter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security configuration for the JWT and API-key authenticated chain (design §8.1).
 *
 * <p>Filter order, outermost to innermost:
 * <ol>
 *   <li>{@link RateLimitFilter} — global Bucket4j gate. Unauthenticated traffic counts
 *   too (REQ-RL-003). Skips {@code /actuator/**} via {@code shouldNotFilter}.</li>
 *   <li>{@link JwtAuthenticationFilter} — populates {@link com.cognizant.emk.multiagent.domain.auth.UserPrincipal}
 *   when {@code Authorization: Bearer ...} is valid.</li>
 *   <li>{@link ApiKeyAuthenticationFilter} — populates {@link com.cognizant.emk.multiagent.domain.auth.SystemPrincipal}
 *   when {@code X-Client-Id} + {@code X-Api-Key} validate AND no JWT-driven authentication
 *   is already on the context. "JWT wins" per design §8.1.</li>
 *   <li>{@link ForcedPasswordChangeFilter} — blocks most endpoints when the current
 *   user has {@code mustChangePassword=true}; transparent to {@code SystemPrincipal}.</li>
 * </ol>
 *
 * <p>{@code /api/v1/admin/**} requires {@code ROLE_ADMIN} at the URL layer (design §8.6);
 * STANDARD JWTs and SYSTEM API-key callers are both 403 there. The class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} on {@code ApiKeysAdminController} is defense
 * in depth on top of this rule, which is why {@link EnableMethodSecurity} is on.
 */
@Configuration
@EnableMethodSecurity
public class SpringSecurityConfig {

    private final ApplicationProperties properties;

    public SpringSecurityConfig(ApplicationProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorrelationIdFilter correlationIdFilter,
            RateLimitFilter rateLimitFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            ForcedPasswordChangeFilter forcedPasswordChangeFilter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        // Read the API prefix once at bean-init time. Single source of truth = REQ-API-006.
        String apiPrefix = stripTrailingSlash(properties.api().basePath());
        String loginPath = apiPrefix + "/auth/login";
        String adminPattern = apiPrefix + "/admin/**";
        String agentsPattern = apiPrefix + "/agents/**";
        String conversationsPattern = apiPrefix + "/conversations/**";
        String apiPattern = apiPrefix + "/**";

        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Async dispatches re-enter the filter chain when an SSE
                        // stream writes a frame (EPIC-11). The initial REQUEST
                        // dispatch has already authorized the call; the ASYNC
                        // dispatch carries no SecurityContext because Tomcat
                        // runs it on a recycled worker thread. Permit ASYNC
                        // unconditionally so the AuthorizationFilter does not
                        // deny it (and try to write a 403 onto a response whose
                        // headers were already flushed by the SseEmitter).
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.POST, loginPath).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // Admin URL guard (design §8.6 / US-04-009): STANDARD JWTs and
                        // SYSTEM API-key callers both 403 here.
                        .requestMatchers(adminPattern).hasRole("ADMIN")
                        // Agent URL guard (design §8.6 / US-06-004): STANDARD and ADMIN
                        // JWTs admitted; SYSTEM API-key callers 403 (agents have no
                        // SYSTEM ownership concept).
                        .requestMatchers(agentsPattern).hasAnyRole("STANDARD", "ADMIN")
                        // Conversation URL guard (design §8.6 / US-10-005): STANDARD,
                        // ADMIN, AND SYSTEM admitted — the only feature surface that
                        // SYSTEM principals may reach (REQ-AUTH-007). Owner-scoping
                        // (cross-owner reads → 404 via existence-hiding) is enforced
                        // in each use case, not at the URL layer.
                        .requestMatchers(conversationsPattern).hasAnyRole("STANDARD", "ADMIN", "SYSTEM")
                        .requestMatchers(apiPattern).authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // RateLimitFilter is the outermost authn/throttling filter (REQ-RL-003):
                // unauthenticated traffic (login attempts, malformed Authorization headers)
                // also counts against the global bucket, so credential-stuffing cannot bypass
                // throttling. Placed BEFORE jwtAuthenticationFilter via addFilterBefore.
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                // CorrelationIdFilter sits BEFORE RateLimitFilter (US-15-002 / REQ-OBS-001):
                // a 429 response (bucket exhausted) MUST still carry the X-Correlation-Id
                // header and the MDC value, so log lines attributed to the rejected request
                // share the correlation ID with whatever else the request would have touched.
                .addFilterBefore(correlationIdFilter, RateLimitFilter.class)
                .addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(forcedPasswordChangeFilter, ApiKeyAuthenticationFilter.class)
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .build();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, authException) ->
                resolver.resolveException(request, response, null, new InvalidCredentialsException(authException));
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, accessDeniedException) ->
                resolver.resolveException(request, response, null, accessDeniedException);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept",
                "X-Client-Id", "X-Api-Key", "X-Correlation-Id", "X-Requested-With"));
        // Exposed headers are kept alphabetical so additions are deterministic and
        // the US-14-004 CORS contract test's containment assertions stay stable.
        config.setExposedHeaders(List.of("Retry-After", "X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
