package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.application.auth.JwtTokenService;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService.IssuedToken;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@link ForcedPasswordChangeFilter}.
 *
 * <p>Drives the full Spring Security chain using the seeded admin produced by
 * {@code V002__seed_admin.sql} (with {@code mustChangePassword=true}). Each test re-runs
 * Flyway clean+migrate so it starts from the documented seeded state, independent of
 * preceding tests.
 *
 * <p>For the allow-listed paths the assertion is that the request reaches its handler
 * (rather than being short-circuited with {@code MUST_CHANGE_PASSWORD} by the filter).
 * The bodies sent by these tests intentionally drive the handler down a benign path —
 * 204 for logout, 401 for change-password (the seeded placeholder hash matches no
 * cleartext) — that does not depend on knowing the seeded admin's actual password.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ForcedPasswordChangeFilterIntegrationTest {

    private static final String SEEDED_ADMIN_EMAIL = "bootstrap@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Flyway flyway;

    private User seededAdmin;
    private String adminToken;

    @BeforeEach
    void resetSchemaAndIssueToken() {
        flyway.clean();
        flyway.migrate();
        seededAdmin = userRepository.findByEmail(new Email(SEEDED_ADMIN_EMAIL))
                .orElseThrow(() -> new AssertionError("seeded admin not found in users table"));
        assertThat(seededAdmin.mustChangePassword())
                .as("seeded admin must start with mustChangePassword=true (REQ-USR-007)")
                .isTrue();
        IssuedToken issued = jwtTokenService.issue(seededAdmin);
        adminToken = issued.token();
    }

    @Test
    void protected_endpoint_is_blocked_with_403_MUST_CHANGE_PASSWORD() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MUST_CHANGE_PASSWORD"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Password change required"));
    }

    @Test
    void logout_path_is_allow_listed_so_filter_does_not_short_circuit() throws Exception {
        // POST /auth/logout is on the allow-list. The handler shipped with US-03-010 returns
        // 204 once the filter passes the request through.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void password_change_path_is_allow_listed_so_filter_does_not_short_circuit() throws Exception {
        // PUT /auth/password is on the allow-list. The handler shipped with US-03-011 will
        // reject this request with 401 because the seeded admin's password hash in the test
        // configuration is a placeholder that no cleartext can match — what we assert here is
        // simply that the filter does NOT short-circuit with 403 MUST_CHANGE_PASSWORD before
        // the controller even runs.
        mockMvc.perform(put("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"WrongPass!1A\",\"newPassword\":\"Brand!New2Z\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void clearing_the_flag_unblocks_the_same_JWT_on_protected_endpoints() throws Exception {
        // Flip the flag at the repository level, mirroring what US-03-011 will do on
        // a successful password change. The same JWT (still valid) must now reach the
        // protected endpoint without the filter raising.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        userRepository.save(seededAdmin.withNewPasswordHash(seededAdmin.passwordHash(), now));

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(SEEDED_ADMIN_EMAIL))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void anonymous_request_is_handled_by_jwt_filter_not_by_password_change_filter() throws Exception {
        // No Authorization header → JwtAuthenticationFilter chain returns 401 INVALID_CREDENTIALS;
        // the password-change filter must not interfere because there is no UserPrincipal.
        mockMvc.perform(get("/api/v1/__test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
