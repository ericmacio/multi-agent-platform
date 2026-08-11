package com.cognizant.emk.multiagent.infrastructure.web.pagination;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.ratelimit.Bucket4jRateLimitGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the on-the-wire pagination contract for the API (US-14-005).
 *
 * <p>The {@code CursorCodec} and {@code PageDto} unit tests cover the helpers
 * in isolation; this integration test asserts that the documented envelope
 * shape ({@code items}, {@code pageSize}, optional {@code nextCursor}), the
 * documented bounds ({@code pageSize} ∈ [1, 100], default 20), and the
 * cursor opacity all hold end-to-end through a real list endpoint
 * ({@code GET /agents}).
 *
 * <p>The rate-limit bucket is rebuilt generously in {@code @BeforeEach} so the
 * default {@code (10/min, 50/hour)} ceiling cannot make this test flaky.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PaginationContractIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final int FIXTURE_AGENT_COUNT = 30;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private Flyway flyway;
    @Autowired private Bucket4jRateLimitGate gate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserId aliceId;

    @BeforeEach
    void resetSchemaAndBucketAndFixture() {
        flyway.clean();
        flyway.migrate();
        // Generous bucket so the test's ~10–15 calls never hit 429.
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                1000, 10000, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));
        aliceId = seedUser(ALICE_EMAIL, ALICE_PASSWORD);
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        for (int i = 0; i < FIXTURE_AGENT_COUNT; i++) {
            // Stagger created_at so the keyset cursor has a deterministic ordering.
            persistAgent(aliceId, String.format("alice-%02d", i), base.plusSeconds(i));
        }
    }

    @Test
    void default_page_size_is_20_when_pageSize_omitted() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isString());
    }

    @Test
    void explicit_page_size_in_range_is_honored() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/agents?pageSize=5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(5))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void page_size_zero_returns_400_VALIDATION_ERROR_field_pageSize() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/agents?pageSize=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void page_size_above_max_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/agents?pageSize=101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void page_size_negative_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/agents?pageSize=-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void cursor_roundtrip_yields_no_duplicates_or_gaps() throws Exception {
        String token = login();

        MvcResult page1 = mockMvc.perform(get("/api/v1/agents?pageSize=20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body1 = objectMapper.readValue(
                page1.getResponse().getContentAsString(), Map.class);
        String nextCursor = (String) body1.get("nextCursor");

        Set<String> ids = new HashSet<>(idsOf(body1));
        assertThat(ids).hasSize(20);

        MvcResult page2 = mockMvc.perform(get("/api/v1/agents?pageSize=20&cursor=" + nextCursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body2 = objectMapper.readValue(
                page2.getResponse().getContentAsString(), Map.class);

        List<String> page2Ids = idsOf(body2);
        assertThat(page2Ids).hasSize(10);

        // No duplicates across pages.
        Set<String> union = new HashSet<>(ids);
        union.addAll(page2Ids);
        assertThat(union)
                .as("union of page-1 and page-2 ids must equal the fixture size")
                .hasSize(FIXTURE_AGENT_COUNT);
        // No gaps: every fixture agent must appear exactly once.
        assertThat(union).hasSize(FIXTURE_AGENT_COUNT);
    }

    @Test
    void cursor_is_opaque_no_plaintext_id_or_iso_timestamp_leak() throws Exception {
        String token = login();
        MvcResult result = mockMvc.perform(get("/api/v1/agents?pageSize=5")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        String cursor = (String) body.get("nextCursor");

        assertThat(cursor).isNotNull();
        // Opaque: the cursor must NOT expose an ISO-8601 timestamp or a bare UUID
        // through any cleartext substring. The CursorCodec wraps the payload in
        // base64url, so neither pattern can leak.
        assertThat(cursor)
                .as("cursor must not contain a cleartext ISO timestamp")
                .doesNotMatch(".*\\d{4}-\\d{2}-\\d{2}T.*");
        assertThat(cursor)
                .as("cursor must not contain a cleartext UUID")
                .doesNotMatch(
                        ".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*");
    }

    @Test
    void malformed_cursor_returns_400_VALIDATION_ERROR_field_cursor() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/agents?cursor=not!valid!base64!")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    void final_empty_page_omits_nextCursor() throws Exception {
        String token = login();

        // Page through the fixture until we exhaust it.
        String cursor = null;
        int seen = 0;
        for (int safety = 0; safety < 5 && seen < FIXTURE_AGENT_COUNT; safety++) {
            String url = cursor == null
                    ? "/api/v1/agents?pageSize=20"
                    : "/api/v1/agents?pageSize=20&cursor=" + cursor;
            MvcResult res = mockMvc.perform(get(url)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(
                    res.getResponse().getContentAsString(), Map.class);
            seen += idsOf(body).size();
            cursor = (String) body.get("nextCursor");
            if (cursor == null) {
                break;
            }
        }

        assertThat(seen).isEqualTo(FIXTURE_AGENT_COUNT);

        // A subsequent call with a null cursor (i.e. omitted) returns the first page
        // again, not "empty". To assert the empty-page shape, we go via a cursor that
        // is at the tail.
        if (cursor != null) {
            mockMvc.perform(get("/api/v1/agents?pageSize=20&cursor=" + cursor)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(0))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist())
                    .andExpect(jsonPath("$.pageSize").value(20));
        }
    }

    @Test
    void envelope_shape_has_exact_key_set_items_pageSize_nextCursor() throws Exception {
        String token = login();
        MvcResult page1 = mockMvc.perform(get("/api/v1/agents?pageSize=5")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(
                page1.getResponse().getContentAsString(), Map.class);

        // With a non-empty fixture and pageSize=5, the response carries a nextCursor.
        assertThat(body.keySet())
                .as("envelope on a non-final page")
                .containsExactlyInAnyOrder("items", "pageSize", "nextCursor");

        // Final page MUST omit nextCursor entirely (NON_NULL serialization).
        // Drive a final page by requesting pageSize larger than the fixture.
        MvcResult finalPage = mockMvc.perform(get("/api/v1/agents?pageSize=100")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> finalBody = objectMapper.readValue(
                finalPage.getResponse().getContentAsString(), Map.class);
        assertThat(finalBody.keySet())
                .as("envelope on the final page omits nextCursor")
                .containsExactlyInAnyOrder("items", "pageSize");
    }

    // ------- helpers -------

    @SuppressWarnings("unchecked")
    private static List<String> idsOf(Map<String, Object> body) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return items.stream().map(m -> (String) m.get("id")).toList();
    }

    private UserId seedUser(String email, String password) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), BCRYPT.encode(password),
                Role.STANDARD, false, false, now, now));
        return id;
    }

    private void persistAgent(UserId owner, String name, OffsetDateTime when) {
        agentRepository.save(new Agent(
                new AgentId(UUID.randomUUID()), owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                when, when));
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ALICE_EMAIL + "\",\"password\":\""
                                + ALICE_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
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
