package com.cognizant.emk.multiagent.infrastructure.web.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.ratelimit.Bucket4jRateLimitGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Locks the REQ-SEC-004 redaction contract on top of the human-readable
 * {@code logback-spring.xml} pattern encoder: every log record's message is
 * routed through {@code %redactedMsg} so the three sensitive patterns from
 * {@code SensitiveDataMaskingConverter} (BCrypt hashes, {@code Bearer <jwt>},
 * JWT-shaped substrings) NEVER reach the encoded output.
 *
 * <p>The harness reuses the production encoder by discovering the CONSOLE
 * appender's {@link PatternLayoutEncoder} on the root logger (Spring Boot's
 * LoggingSystem applies {@code logback-spring.xml} at context refresh), then
 * attaches a sibling {@link OutputStreamAppender} that writes into a
 * test-owned {@link ByteArrayOutputStream}. Assertions run substring checks
 * on the captured text — no JSON parsing, matching the plain-line format.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SensitiveDataRedactionIntegrationTest {

    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    private static final String RAW_BEARER = "Bearer eyJhbGciOiJIUzI1NiJ9.test.signature";
    private static final String RAW_BCRYPT = "$2b$12$abcdefghijklmnopqrstuv";
    private static final String RAW_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0";

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> ADMIN_PASSWORD_HASH);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private Flyway flyway;
    @Autowired private Bucket4jRateLimitGate gate;

    private ByteArrayOutputStream captured;
    private OutputStreamAppender<ILoggingEvent> probeAppender;
    private Logger rootLogger;

    @BeforeEach
    void resetSchemaAndAttachAppender() {
        flyway.clean();
        flyway.migrate();
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                1000, 10000, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = ctx.getLogger(Logger.ROOT_LOGGER_NAME);

        Encoder<ILoggingEvent> encoder = findPatternEncoder(rootLogger);
        assertThat(encoder)
                .as("production logback-spring.xml must configure PatternLayoutEncoder")
                .isNotNull();

        captured = new ByteArrayOutputStream();
        probeAppender = new OutputStreamAppender<>();
        probeAppender.setName("REDACTION_PROBE");
        probeAppender.setContext(ctx);
        probeAppender.setEncoder(encoder);
        probeAppender.setOutputStream(captured);
        probeAppender.start();
        rootLogger.addAppender(probeAppender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(probeAppender);
        probeAppender.stop();
    }

    @Test
    void bearer_jwt_substring_is_redacted_in_encoded_output() throws Exception {
        String token = login();

        mockMvc.perform(post("/api/v1/_redaction_probe/log-bearer")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(capturedText())
                .as("no raw Bearer-JWT substring should reach the encoded log stream")
                .doesNotContain("Bearer eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void bcrypt_substring_is_redacted_in_encoded_output() throws Exception {
        String token = login();

        mockMvc.perform(post("/api/v1/_redaction_probe/log-bcrypt")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(capturedText())
                .as("no raw BCrypt hash should reach the encoded log stream")
                .doesNotContain(RAW_BCRYPT);
    }

    @Test
    void bare_jwt_substring_is_redacted_in_encoded_output() throws Exception {
        String token = login();

        mockMvc.perform(post("/api/v1/_redaction_probe/log-jwt")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(capturedText())
                .as("no raw JWT-shaped substring should reach the encoded log stream")
                .doesNotContain(RAW_JWT);
    }

    @Test
    void clean_message_passes_through_unchanged() throws Exception {
        String token = login();

        mockMvc.perform(post("/api/v1/_redaction_probe/log-clean")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(capturedText())
                .as("non-sensitive content should pass through %redactedMsg untouched")
                .contains("non-sensitive content passes through");
    }

    @Test
    void failed_login_does_not_leak_authorization_or_password_payload() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Authorization", "Bearer " + RAW_JWT + ".signature-bytes-long-enough")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.test\",\"password\":\"WrongPassw0rd!\"}"))
                .andReturn();

        assertThat(capturedText())
                .as("captured log stream must not contain a raw Bearer-JWT substring (failed login)")
                .doesNotContain("Bearer eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    void every_captured_line_ends_with_a_newline_and_is_non_empty() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/v1/_redaction_probe/log-bearer")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        mockMvc.perform(post("/api/v1/_redaction_probe/log-bcrypt")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        mockMvc.perform(post("/api/v1/_redaction_probe/log-jwt")
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        // Pattern-encoder output: one line per event, trailing newline per line,
        // never blank. This is the plain-text equivalent of the previous
        // "one JSON object per line" contract.
        String text = capturedText();
        assertThat(text).endsWith(System.lineSeparator());
        for (String line : text.split("\\R")) {
            assertThat(line).isNotBlank();
        }
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return body.get("token").asText();
    }

    private String capturedText() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static Encoder<ILoggingEvent> findPatternEncoder(Logger root) {
        java.util.Iterator<ch.qos.logback.core.Appender<ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            ch.qos.logback.core.Appender<ILoggingEvent> appender = it.next();
            if (appender instanceof OutputStreamAppender<ILoggingEvent> osa) {
                Encoder<ILoggingEvent> enc = osa.getEncoder();
                if (enc instanceof PatternLayoutEncoder) {
                    return enc;
                }
            }
        }
        return null;
    }
}
