package com.cognizant.emk.multiagent.infrastructure.web.auth;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.security.InMemoryJwtDenylistAdapter;
import com.cognizant.emk.multiagent.infrastructure.security.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code POST /auth/logout} (US-03-010).
 *
 * <p>Overrides the {@code Clock} bean with a {@link MutableClock} so the test can advance
 * time past a JWT's natural expiry and verify the denylist sweep. Since US-CR1-003, both
 * {@link InMemoryJwtDenylistAdapter} and the JJWT adapter read from this same bean, so
 * "now" is consistently virtualized across token issuance, verification, and denylist
 * eviction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LogoutEndpointIntegrationTest {

    private static final String SEEDED_ADMIN_EMAIL = "bootstrap@example.test";
    private static final String SEEDED_ADMIN_PASSWORD = "Bootstrap!1A";
    private static final String SEEDED_ADMIN_PASSWORD_HASH =
            new BCryptPasswordEncoder().encode(SEEDED_ADMIN_PASSWORD);

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> SEEDED_ADMIN_PASSWORD_HASH);
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(Instant.now(), ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private Flyway flyway;
    @Autowired private InMemoryJwtDenylistAdapter denylistAdapter;
    @Autowired private Clock clock;

    @BeforeEach
    void resetSchemaAndDenylist() {
        flyway.clean();
        flyway.migrate();
        // Wipe any denylist entries left over from a previous test by jumping the clock far
        // ahead, sweeping, then resetting it to a fresh "now".
        MutableClock mutable = (MutableClock) clock;
        Instant fresh = Instant.now();
        mutable.setInstant(fresh.plus(Duration.ofDays(365)));
        denylistAdapter.sweep();
        mutable.setInstant(fresh);

        // Clear mustChangePassword on the seeded admin so subsequent calls don't get blocked
        // by the ForcedPasswordChangeFilter — logout is allow-listed, but the probe endpoint
        // we use to verify the token is no longer accepted is not.
        User admin = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void logout_returns_204_invalidates_the_token_and_is_idempotent_at_the_filter() throws Exception {
        String token = loginAndExtractToken();

        // First logout: 204, denylist gains exactly one entry.
        assertThat(denylistAdapter.size()).isZero();
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(denylistAdapter.size()).isOne();

        // Re-using the same token on a protected endpoint → 401 INVALID_CREDENTIALS.
        mockMvc.perform(get("/api/v1/__test/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        // Calling logout again with the now-invalidated token → 401: the JWT filter
        // rejects on the denylist hit before the controller is reached.
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void denylist_entry_is_evicted_by_sweep_after_clock_advances_past_jwt_exp() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(denylistAdapter.size()).isOne();

        // Advance the virtualized clock past the JWT's exp (default lifetime PT30M).
        ((MutableClock) clock).advance(Duration.ofMinutes(31));
        denylistAdapter.sweep();

        assertThat(denylistAdapter.size()).isZero();
    }

    private String loginAndExtractToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonCredentials()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    private static String jsonCredentials() {
        return "{\"email\":\"" + SEEDED_ADMIN_EMAIL + "\",\"password\":\"" + SEEDED_ADMIN_PASSWORD + "\"}";
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("field '" + field + "' not found in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
