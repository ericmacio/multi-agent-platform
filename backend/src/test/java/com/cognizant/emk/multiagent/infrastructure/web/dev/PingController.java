package com.cognizant.emk.multiagent.infrastructure.web.dev;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only smoke endpoint used by {@code BasePathConfigTest},
 * {@code BasePathConfigOverrideTest}, and {@code SpringSecurityConfigTest} to verify the
 * centralized {@code /api/v1} base-path wiring and the security chain's default-deny rule.
 *
 * <p>Lives on the test classpath only (US-CR1-002). The previous production-classpath
 * variant was removed because gating debug endpoints by {@code @Profile("dev")} alone left
 * a footgun: running the deployed JAR with {@code SPRING_PROFILES_ACTIVE=dev} would expose
 * them. Test classpath is the scoping mechanism — no {@code @Profile} guard needed.
 *
 * <p>The response shape is byte-identical to the prior production-classpath controller so
 * existing test assertions ({@code $.ok == true}, plain 200 OK) continue to hold.
 */
@RestController
@RequestMapping("/ping")
public class PingController {

    @GetMapping
    public Map<String, Boolean> ping() {
        return Map.of("ok", true);
    }
}
