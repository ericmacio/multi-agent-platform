package com.cognizant.emk.multiagent.infrastructure.web.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cognizant.emk.multiagent.application.shared.UseCaseExecutionException;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.CrossOwnerTeamMemberException;
import com.cognizant.emk.multiagent.domain.agent.DuplicateAgentNameException;
import com.cognizant.emk.multiagent.domain.agent.NestedTeamForbiddenException;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationFullException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import com.cognizant.emk.multiagent.domain.shared.ForbiddenException;
import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.MustChangePasswordException;
import com.cognizant.emk.multiagent.infrastructure.error.DatabaseAccessException;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.error.McpServerException;
import com.cognizant.emk.multiagent.infrastructure.error.RateLimitedException;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import java.io.IOException;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.validation.BeanPropertyBindingResult;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that every branch of {@link GlobalExceptionHandler} maps to the documented
 * {@link ProblemDetails} shape, status, and {@code application/problem+json} content type.
 *
 * <p>Uses a standalone {@link MockMvc} setup with a tiny test controller that throws each
 * exception type. Exceptions that do not naturally bubble up through MVC dispatching
 * ({@code NoHandlerFoundException}, {@code NoResourceFoundException},
 * {@code HttpRequestMethodNotSupportedException}, {@code MethodArgumentNotValidException})
 * are exercised by invoking the handler method directly with a mock request.
 */
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestErrorController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void domain_validation_exception_maps_to_400() throws Exception {
        mockMvc.perform(get("/throw/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("name is too long"))
                .andExpect(jsonPath("$.instance").value("/throw/validation"))
                .andExpect(jsonPath("$.type").value("https://errors.multi-agent-platform/validation-error"));
    }

    @Test
    void domain_validation_with_field_populates_errors_array() throws Exception {
        mockMvc.perform(get("/throw/validation-with-field"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("One or more fields failed validation."))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a valid email address"));
    }

    @Test
    void invalid_credentials_maps_to_401_with_static_detail() throws Exception {
        mockMvc.perform(get("/throw/invalid-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication failed."));
    }

    @Test
    void forbidden_maps_to_403() throws Exception {
        mockMvc.perform(get("/throw/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void access_denied_also_maps_to_403_FORBIDDEN() throws Exception {
        mockMvc.perform(get("/throw/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void must_change_password_maps_to_403_MUST_CHANGE_PASSWORD() throws Exception {
        mockMvc.perform(get("/throw/must-change-password"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MUST_CHANGE_PASSWORD"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void domain_not_found_maps_to_404() throws Exception {
        mockMvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("agent 42 missing"));
    }

    @Test
    void domain_conflict_maps_to_409_CONFLICT() throws Exception {
        mockMvc.perform(get("/throw/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("duplicated"))
                .andExpect(jsonPath("$.instance").value("/throw/conflict"))
                .andExpect(jsonPath("$.type").value("https://errors.multi-agent-platform/conflict"));
    }

    @Test
    void conversation_full_maps_to_409_CONVERSATION_FULL_with_static_detail() throws Exception {
        // US-10-004: the subclass-specific handler must win over the generic
        // ConflictException one — `code` is CONVERSATION_FULL, not CONFLICT.
        // The `detail` is the documented static string (no conversation id leak).
        mockMvc.perform(get("/throw/conversation-full"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONVERSATION_FULL"))
                .andExpect(jsonPath("$.title").value("Conversation full"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("Conversation has reached the 64-message cap."))
                .andExpect(jsonPath("$.instance").value("/throw/conversation-full"))
                .andExpect(jsonPath("$.type")
                        .value("https://errors.multi-agent-platform/conversation-full"));
    }

    @Test
    void parent_conflict_still_routes_to_generic_handler_after_conversation_full_handler_lands()
            throws Exception {
        // US-10-004 handler-priority assertion: a plain ConflictException must
        // continue to surface as the generic CONFLICT code (not be swallowed by
        // the new ConversationFullException-specific handler).
        mockMvc.perform(get("/throw/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void duplicate_agent_name_maps_to_409_DUPLICATE_AGENT_NAME() throws Exception {
        mockMvc.perform(get("/throw/duplicate-agent-name"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("DUPLICATE_AGENT_NAME"))
                .andExpect(jsonPath("$.title").value("Duplicate agent name"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("alpha")))
                .andExpect(jsonPath("$.type")
                        .value("https://errors.multi-agent-platform/duplicate-agent-name"));
    }

    @Test
    void nested_team_forbidden_maps_to_409_NESTED_TEAM_FORBIDDEN() throws Exception {
        mockMvc.perform(get("/throw/nested-team-forbidden"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NESTED_TEAM_FORBIDDEN"))
                .andExpect(jsonPath("$.title").value("Nested team forbidden"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type")
                        .value("https://errors.multi-agent-platform/nested-team-forbidden"));
    }

    @Test
    void cross_owner_team_member_maps_to_409_CROSS_OWNER_TEAM_MEMBER() throws Exception {
        mockMvc.perform(get("/throw/cross-owner-team-member"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CROSS_OWNER_TEAM_MEMBER"))
                .andExpect(jsonPath("$.title").value("Cross-owner team member"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type")
                        .value("https://errors.multi-agent-platform/cross-owner-team-member"));
    }

    @Test
    void mcp_server_exception_maps_to_502_MCP_SERVER_ERROR() throws Exception {
        mockMvc.perform(get("/throw/mcp-server-error"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MCP_SERVER_ERROR"))
                .andExpect(jsonPath("$.title").value("MCP server error"))
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail").value("The MCP server is currently unavailable."))
                .andExpect(jsonPath("$.instance").value("/throw/mcp-server-error"))
                .andExpect(jsonPath("$.type")
                        .value("https://errors.multi-agent-platform/mcp-server-error"));
    }

    @Test
    void llm_unavailable_exception_maps_to_502_LLM_UNAVAILABLE() throws Exception {
        mockMvc.perform(get("/throw/llm-unavailable"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("LLM unavailable"))
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.detail")
                        .value("The language-model provider is currently unavailable."))
                .andExpect(jsonPath("$.instance").value("/throw/llm-unavailable"))
                .andExpect(jsonPath("$.type")
                        .value("https://errors.multi-agent-platform/llm-unavailable"));
    }

    @Test
    void llm_unavailable_exception_with_cause_does_not_leak_cause_into_response_body()
            throws Exception {
        // The handler is required to log the cause but never expose it to the client
        // (REQ-SEC-004 / REQ-API-004). The response body is byte-identical with or
        // without the cause.
        mockMvc.perform(get("/throw/llm-unavailable-with-cause"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail")
                        .value("The language-model provider is currently unavailable."));
    }

    @Test
    void mcp_server_exception_with_cause_does_not_leak_cause_into_response_body() throws Exception {
        // The handler is required to log the cause but never expose it to the client
        // (REQ-SEC-004 / REQ-API-004). The response body is byte-identical with or
        // without the cause.
        mockMvc.perform(get("/throw/mcp-server-error-with-cause"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MCP_SERVER_ERROR"))
                .andExpect(jsonPath("$.detail").value("The MCP server is currently unavailable."));
    }

    @Test
    void rate_limited_exception_maps_to_429_with_retry_after_header() throws Exception {
        mockMvc.perform(get("/throw/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Global rate limit exceeded; retry later."))
                .andExpect(jsonPath("$.instance").value("/throw/rate-limited"))
                .andExpect(jsonPath("$.type").value("https://errors.multi-agent-platform/rate-limited"));
    }

    @Test
    void unexpected_throwable_maps_to_500_INTERNAL_ERROR() throws Exception {
        mockMvc.perform(get("/throw/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @Test
    void handleUseCaseExecution_withCause_returns500_andLogsCauseClass() throws Exception {
        // US-14-001: the application-layer escape hatch maps to 500 INTERNAL_ERROR.
        // The cause's class name appears in the ERROR log; the response body never
        // exposes the cause (REQ-SEC-004 / REQ-API-004).
        ListAppender<ILoggingEvent> appender = attachListAppender();
        try {
            mockMvc.perform(get("/throw/use-case-execution-with-cause"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.ERROR)
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains(
                                IllegalStateException.class.getName());
                        assertThat(event.getFormattedMessage()).contains(
                                "Use-case execution failure");
                    });
        } finally {
            detachListAppender(appender);
        }
    }

    @Test
    void handleUseCaseExecution_withoutCause_returns500_andLogs() throws Exception {
        ListAppender<ILoggingEvent> appender = attachListAppender();
        try {
            mockMvc.perform(get("/throw/use-case-execution-no-cause"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.ERROR)
                    .hasSize(1)
                    .first()
                    .satisfies(event ->
                            assertThat(event.getFormattedMessage())
                                    .contains("Use-case execution failure")
                                    .doesNotContain(IllegalStateException.class.getName()));
        } finally {
            detachListAppender(appender);
        }
    }

    @Test
    void handleDatabaseAccess_returns500_andLogsCauseClass() throws Exception {
        // US-14-002: the persistence-adapter wrapper maps to 500 INTERNAL_ERROR.
        // The Spring DataAccessException stays inside the adapter; only its class
        // name reaches the log line, never its message payload.
        ListAppender<ILoggingEvent> appender = attachListAppender();
        try {
            mockMvc.perform(get("/throw/database-access"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.ERROR)
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains(DataIntegrityViolationException.class.getName());
                        assertThat(event.getFormattedMessage()).contains("Database access failure");
                    });
        } finally {
            detachListAppender(appender);
        }
    }

    @Test
    void handleDatabaseAccess_withoutCause_logs_no_cause_sentinel() throws Exception {
        ListAppender<ILoggingEvent> appender = attachListAppender();
        try {
            mockMvc.perform(get("/throw/database-access-no-cause"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.ERROR)
                    .hasSize(1)
                    .first()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains("Database access failure")
                            .contains("no-cause"));
        } finally {
            detachListAppender(appender);
        }
    }

    private static ListAppender<ILoggingEvent> attachListAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachListAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAppender(appender);
    }

    @Test
    void bean_validation_failure_populates_field_errors() throws Exception {
        mockMvc.perform(post("/throw/bean-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    /** Direct invocation: MockMvc standalone short-circuits to 404 without raising the exception. */
    @Test
    void no_handler_found_maps_to_404() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/missing");
        ResponseEntity<ProblemDetails> response = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/api/v1/missing", null), req);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType().toString()).startsWith(PROBLEM_JSON);
        ProblemDetails body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("NOT_FOUND");
        assertThat(body.detail()).isEqualTo("The requested resource does not exist.");
        assertThat(body.instance()).isEqualTo("/api/v1/missing");
    }

    @Test
    void no_resource_found_maps_to_404() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/nope");
        ResponseEntity<ProblemDetails> response = handler.handleNotFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/v1/nope", "/api/v1/nope"), req);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void method_not_supported_maps_to_405() {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/v1/agents");
        ResponseEntity<ProblemDetails> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("DELETE"), req);
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().detail()).contains("DELETE");
    }

    /**
     * Direct invocation of {@code handleBeanValidation} so we can assert the per-field
     * {@code errors[]} structure without going through Spring MVC binding plumbing.
     */
    @Test
    void method_argument_not_valid_carries_field_errors() throws Exception {
        Method method = TestErrorController.class.getMethod("beanValidation", Payload.class);
        HandlerMethod handlerMethod = new HandlerMethod(new TestErrorController(), method);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(""), "payload");
        binding.rejectValue("name", "NotBlank", "must not be blank");
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                handlerMethod.getMethodParameters()[0], binding);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/foo");
        ResponseEntity<ProblemDetails> response = handler.handleBeanValidation(ex, req);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ProblemDetails body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.errors()).hasSize(1);
        assertThat(body.errors().get(0).field()).isEqualTo("name");
        assertThat(body.errors().get(0).message()).isEqualTo("must not be blank");
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------

    public record Payload(@NotBlank String name) {}

    @RestController
    public static class TestErrorController {

        @GetMapping("/throw/validation")
        public void throwValidation() {
            throw new ValidationException("name is too long");
        }

        @GetMapping("/throw/validation-with-field")
        public void throwValidationWithField() {
            throw new ValidationException("email", "must be a valid email address");
        }

        @GetMapping("/throw/invalid-credentials")
        public void throwInvalidCredentials() {
            throw new InvalidCredentialsException();
        }

        @GetMapping("/throw/forbidden")
        public void throwForbidden() {
            throw new ForbiddenException("nope");
        }

        @GetMapping("/throw/access-denied")
        public void throwAccessDenied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/throw/must-change-password")
        public void throwMustChangePassword() {
            throw new MustChangePasswordException();
        }

        @GetMapping("/throw/not-found")
        public void throwNotFound() {
            throw new NotFoundException("agent 42 missing");
        }

        @GetMapping("/throw/conflict")
        public void throwConflict() {
            throw new ConflictException("duplicated");
        }

        @GetMapping("/throw/conversation-full")
        public void throwConversationFull() {
            throw new ConversationFullException(new ConversationId(
                    UUID.fromString("a9b9bb11-1234-4abc-9def-1234567890ab")));
        }

        @GetMapping("/throw/duplicate-agent-name")
        public void throwDuplicateAgentName() {
            throw new DuplicateAgentNameException(new AgentName("alpha"));
        }

        @GetMapping("/throw/nested-team-forbidden")
        public void throwNestedTeamForbidden() {
            throw new NestedTeamForbiddenException(new AgentId(UUID.randomUUID()));
        }

        @GetMapping("/throw/cross-owner-team-member")
        public void throwCrossOwnerTeamMember() {
            throw new CrossOwnerTeamMemberException(new AgentId(UUID.randomUUID()));
        }

        @GetMapping("/throw/boom")
        public void throwUnexpected() {
            throw new IllegalStateException("kaboom (should be hidden)");
        }

        @GetMapping("/throw/mcp-server-error")
        public void throwMcpServerError() {
            throw new McpServerException("npx subprocess died unexpectedly");
        }

        @GetMapping("/throw/mcp-server-error-with-cause")
        public void throwMcpServerErrorWithCause() {
            throw new McpServerException(
                    "filesystem MCP root creation failed",
                    new IOException("disk full"));
        }

        @GetMapping("/throw/llm-unavailable")
        public void throwLlmUnavailable() {
            throw new LlmUnavailableException("openai provider failure: http_5xx 503");
        }

        @GetMapping("/throw/llm-unavailable-with-cause")
        public void throwLlmUnavailableWithCause() {
            throw new LlmUnavailableException(
                    "openai connection refused",
                    new IOException("Connection refused"));
        }

        @GetMapping("/throw/rate-limited")
        public void throwRateLimited() {
            throw new RateLimitedException(7);
        }

        @GetMapping("/throw/use-case-execution-with-cause")
        public void throwUseCaseExecutionWithCause() {
            throw new UseCaseExecutionException(
                    "orchestration broke",
                    new IllegalStateException("ports disagree"));
        }

        @GetMapping("/throw/use-case-execution-no-cause")
        public void throwUseCaseExecutionNoCause() {
            throw new UseCaseExecutionException("orchestration broke", null);
        }

        @GetMapping("/throw/database-access")
        public void throwDatabaseAccess() {
            throw new DatabaseAccessException(
                    "users.findByEmail failed",
                    new DataIntegrityViolationException("duplicate key"));
        }

        @GetMapping("/throw/database-access-no-cause")
        public void throwDatabaseAccessNoCause() {
            throw new DatabaseAccessException("users.findByEmail failed", null);
        }

        @PostMapping("/throw/bean-validation")
        public String beanValidation(@jakarta.validation.Valid @RequestBody Payload payload) {
            return "ok " + payload.name();
        }
    }
}
