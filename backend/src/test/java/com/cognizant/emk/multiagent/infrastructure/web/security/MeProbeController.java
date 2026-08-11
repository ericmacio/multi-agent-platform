package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.domain.auth.Principal;
import com.cognizant.emk.multiagent.domain.auth.SystemPrincipal;
import com.cognizant.emk.multiagent.domain.auth.UserPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only probe used by {@code JwtAuthenticationFilterIntegrationTest},
 * {@code ApiKeyAuthenticationFilterIntegrationTest}, and the updated
 * {@code SpringSecurityConfigTest}. Returns the resolved {@link Principal}'s shape so
 * the test can assert that the appropriate filter populated the
 * {@code SecurityContext} as expected.
 *
 * <p>Lives on the test classpath under {@code @Profile("dev")}. The {@code WebConfig}
 * {@code /api/v1} prefix makes the effective path {@code /api/v1/__test/me}.
 *
 * <p>The handler accepts the generic {@link Principal} so it can describe either a
 * {@link UserPrincipal} (JWT path) or a {@link SystemPrincipal} (API-key path). The
 * response always carries a {@code principalType} field that the tests assert on.
 */
@RestController
@RequestMapping("/__test")
@Profile("dev")
public class MeProbeController {

    @GetMapping("/me")
    public Map<String, String> me(@AuthenticationPrincipal Principal principal) {
        if (principal instanceof UserPrincipal user) {
            return Map.of(
                    "principalType", "UserPrincipal",
                    "id", user.id().value().toString(),
                    "email", user.email().value(),
                    "role", user.role().name());
        }
        if (principal instanceof SystemPrincipal system) {
            return Map.of(
                    "principalType", "SystemPrincipal",
                    "clientId", system.clientId().value());
        }
        // Should be unreachable: the filter chain guarantees an authenticated principal
        // by the time the handler runs. Surfaces as 500 INTERNAL_ERROR if the invariant
        // ever breaks.
        throw new IllegalStateException(
                "Unexpected principal: " + (principal == null ? "null" : principal.getClass().getName()));
    }
}
