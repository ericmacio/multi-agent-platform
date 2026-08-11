package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService.IssuedToken;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@link ApiKeyAuthenticationFilter} and the updated
 * security chain (US-04-009). Uses the dev-profile {@code MeProbeController} (extended
 * to handle {@code SystemPrincipal}) as the target so the test can assert which
 * principal the filter populated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApiKeyAuthenticationFilterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private Flyway flyway;

    private String clientId;
    private String cleartext;

    @BeforeEach
    void resetAndSeedApiKey() {
        flyway.clean();
        flyway.migrate();
        GeneratedApiKey generated = apiKeyGenerator.generate();
        clientId = generated.clientId().value();
        cleartext = generated.cleartextApiKey();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(cleartext),
                "ci",
                false,
                OffsetDateTime.now(ZoneOffset.UTC)));
    }

    @Test
    void valid_headers_authenticate_as_system_principal() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me")
                        .header("X-Client-Id", clientId)
                        .header("X-Api-Key", cleartext))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalType").value("SystemPrincipal"))
                .andExpect(jsonPath("$.clientId").value(clientId));
    }

    @Test
    void disabled_key_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        apiKeyRepository.updateDisabled(new ClientId(clientId), true);

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("X-Client-Id", clientId)
                        .header("X-Api-Key", cleartext))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void wrong_api_key_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me")
                        .header("X-Client-Id", clientId)
                        .header("X-Api-Key", "wrong-secret-value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknown_client_id_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me")
                        .header("X-Client-Id", "00000000000000000000000000000000")
                        .header("X-Api-Key", cleartext))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void malformed_client_id_header_is_rejected_with_generic_401() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me")
                        .header("X-Client-Id", "invalid/value:with:bad:chars")
                        .header("X-Api-Key", cleartext))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void absent_headers_on_a_protected_endpoint_return_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void jwt_wins_when_both_authentication_paths_are_present() throws Exception {
        // Seed a standard user, issue a JWT, then send a request that also carries an
        // UNRELATED (wrong) api-key pair. The JWT filter runs first and short-circuits
        // the api-key filter; the request is authenticated as the JWT user.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        User user = new User(
                new UserId(UUID.randomUUID()),
                new Email("alice@example.test"),
                "irrelevant-hash",
                Role.STANDARD,
                false,
                false,
                now,
                now);
        userRepository.save(user);
        IssuedToken issued = jwtTokenService.issue(user);

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + issued.token())
                        .header("X-Client-Id", clientId)
                        .header("X-Api-Key", "totally-wrong-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalType").value("UserPrincipal"))
                .andExpect(jsonPath("$.email").value("alice@example.test"));
    }
}
