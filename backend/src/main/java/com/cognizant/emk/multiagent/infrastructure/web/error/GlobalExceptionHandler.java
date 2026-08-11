package com.cognizant.emk.multiagent.infrastructure.web.error;

import com.cognizant.emk.multiagent.application.shared.UseCaseExecutionException;
import com.cognizant.emk.multiagent.domain.agent.CrossOwnerTeamMemberException;
import com.cognizant.emk.multiagent.domain.agent.DuplicateAgentNameException;
import com.cognizant.emk.multiagent.domain.agent.NestedTeamForbiddenException;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationFullException;
import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import com.cognizant.emk.multiagent.domain.shared.ForbiddenException;
import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.MustChangePasswordException;
import com.cognizant.emk.multiagent.infrastructure.error.DatabaseAccessException;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.error.McpServerException;
import com.cognizant.emk.multiagent.infrastructure.error.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized REST error mapping.
 *
 * <p>Maps every domain and Spring exception this EPIC emits to the {@link ProblemDetails}
 * shape documented in design §9.3. Subsequent feature EPICs add new {@code @ExceptionHandler}
 * methods for their own business exceptions; EPIC-14 consolidates the full taxonomy.
 *
 * <p>Stack traces are logged at ERROR but never returned to the client (REQ-API-004).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------- 400 -------

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetails> handleValidation(ValidationException ex, HttpServletRequest req) {
        if (ex.field().isPresent()) {
            ProblemDetails body = ProblemDetails.of(
                    "VALIDATION_ERROR",
                    "Validation error",
                    HttpStatus.BAD_REQUEST.value(),
                    "One or more fields failed validation.",
                    req.getRequestURI(),
                    List.of(new ProblemDetails.FieldError(ex.field().get(), ex.getMessage())));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(body);
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation error", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetails> handleBeanValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ProblemDetails.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        ProblemDetails body = ProblemDetails.of(
                "VALIDATION_ERROR",
                "Validation error",
                HttpStatus.BAD_REQUEST.value(),
                "One or more fields failed validation.",
                req.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetails> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        List<ProblemDetails.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        ProblemDetails body = ProblemDetails.of(
                "VALIDATION_ERROR",
                "Validation error",
                HttpStatus.BAD_REQUEST.value(),
                "One or more parameters failed validation.",
                req.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetails> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        // Raised when Spring cannot convert a path / query parameter to the controller
        // method's declared type — most commonly a malformed UUID in a path variable
        // (e.g. `GET /admin/users/not-a-uuid`). Surface as 400 VALIDATION_ERROR with the
        // parameter name in `errors[]` so client behavior is consistent with body /
        // bean-validation failures.
        ProblemDetails body = ProblemDetails.of(
                "VALIDATION_ERROR",
                "Validation error",
                HttpStatus.BAD_REQUEST.value(),
                "One or more parameters failed validation.",
                req.getRequestURI(),
                List.of(new ProblemDetails.FieldError(
                        ex.getName(),
                        "invalid value for path or query parameter")));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    // ------- 401 -------

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetails> handleInvalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest req) {
        return body(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid credentials",
                "Authentication failed.",
                req);
    }

    // ------- 403 (most-specific first) -------

    @ExceptionHandler(MustChangePasswordException.class)
    public ResponseEntity<ProblemDetails> handleMustChangePassword(
            MustChangePasswordException ex, HttpServletRequest req) {
        return body(
                HttpStatus.FORBIDDEN,
                "MUST_CHANGE_PASSWORD",
                "Password change required",
                ex.getMessage(),
                req);
    }

    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ProblemDetails> handleForbidden(Exception ex, HttpServletRequest req) {
        return body(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "Forbidden",
                "You are not allowed to perform this action.",
                req);
    }

    // ------- 404 -------

    @ExceptionHandler({NotFoundException.class, NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ProblemDetails> handleNotFound(Exception ex, HttpServletRequest req) {
        String detail = ex instanceof NotFoundException ? ex.getMessage() : "The requested resource does not exist.";
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", detail, req);
    }

    // ------- 409 (most-specific first) -------

    @ExceptionHandler(ConversationFullException.class)
    public ResponseEntity<ProblemDetails> handleConversationFull(
            ConversationFullException ex, HttpServletRequest req) {
        // Hitting the 64-message cap is a documented user-facing constraint
        // (REQ-CHAT-010), not an error condition — log at INFO so operators
        // can correlate without drowning the signal in WARN noise. The
        // user-facing `detail` is the static string below; the conversation
        // id stays in the exception message (logged but not exposed).
        log.info("Conversation full while processing {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.getMessage());
        return body(HttpStatus.CONFLICT, "CONVERSATION_FULL", "Conversation full",
                "Conversation has reached the 64-message cap.", req);
    }

    @ExceptionHandler(DuplicateAgentNameException.class)
    public ResponseEntity<ProblemDetails> handleDuplicateAgentName(
            DuplicateAgentNameException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "DUPLICATE_AGENT_NAME", "Duplicate agent name",
                ex.getMessage(), req);
    }

    @ExceptionHandler(NestedTeamForbiddenException.class)
    public ResponseEntity<ProblemDetails> handleNestedTeamForbidden(
            NestedTeamForbiddenException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "NESTED_TEAM_FORBIDDEN", "Nested team forbidden",
                ex.getMessage(), req);
    }

    @ExceptionHandler(CrossOwnerTeamMemberException.class)
    public ResponseEntity<ProblemDetails> handleCrossOwnerTeamMember(
            CrossOwnerTeamMemberException ex, HttpServletRequest req) {
        return body(HttpStatus.CONFLICT, "CROSS_OWNER_TEAM_MEMBER", "Cross-owner team member",
                ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetails> handleConflict(ConflictException ex, HttpServletRequest req) {
        // Generic 409 fallback for any business-invariant violation that doesn't have a
        // dedicated handler above. The three EPIC-06 agent codes
        // (DUPLICATE_AGENT_NAME / NESTED_TEAM_FORBIDDEN / CROSS_OWNER_TEAM_MEMBER) have
        // their own subclass handlers immediately above this one; EPIC-11 will add
        // CONVERSATION_FULL alongside them. Precedent: MustChangePasswordException's
        // specific handler sits ahead of the generic ForbiddenException one.
        return body(HttpStatus.CONFLICT, "CONFLICT", "Conflict", ex.getMessage(), req);
    }

    // ------- 405 -------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetails> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return body(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Method not allowed",
                "HTTP method " + ex.getMethod() + " is not supported for this endpoint.",
                req);
    }

    // ------- 406 (Accept negotiation — relevant for the SSE streaming endpoint EPIC-11) -------

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetails> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, HttpServletRequest req) {
        // The streaming send-message endpoint declares produces=text/event-stream;
        // clients that ask for application/json get 406 here (EPIC-11 / openapi spec).
        return body(
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE",
                "Not acceptable",
                "Accept header does not match the endpoint's supported media type(s).",
                req);
    }

    // ------- 429 (rate limit) -------

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetails> handleRateLimited(
            RateLimitedException ex, HttpServletRequest req) {
        // Thrown by RateLimitFilter via the HandlerExceptionResolver bridge
        // (US-13-005). The 429 envelope mirrors the openapi `RateLimited` example
        // (REQ-RL-005); `Retry-After` carries the bucket's nanos-to-wait ceil'd to
        // seconds (floor 1) computed by the Bucket4j adapter.
        ProblemDetails body = ProblemDetails.of(
                "RATE_LIMITED",
                "Too many requests",
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Global rate limit exceeded; retry later.",
                req.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.retryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    // ------- 502 (external service failures) -------

    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<ProblemDetails> handleLlmUnavailable(
            LlmUnavailableException ex, HttpServletRequest req) {
        // Operators want the cause; the response body is sanitized per REQ-SEC-004 /
        // REQ-API-004 — no payloads, no prompt text, no API-key fragments.
        Throwable cause = ex.getCause();
        if (cause != null) {
            log.warn("LLM provider error while processing {} {}: {} ({})",
                    req.getMethod(), req.getRequestURI(),
                    ex.getMessage(), cause.getClass().getName());
        } else {
            log.warn("LLM provider error while processing {} {}: {}",
                    req.getMethod(), req.getRequestURI(), ex.getMessage());
        }
        return body(
                HttpStatus.BAD_GATEWAY,
                "LLM_UNAVAILABLE",
                "LLM unavailable",
                "The language-model provider is currently unavailable.",
                req);
    }

    @ExceptionHandler(McpServerException.class)
    public ResponseEntity<ProblemDetails> handleMcpServerError(
            McpServerException ex, HttpServletRequest req) {
        // Operators want the cause; the response body is sanitized per REQ-SEC-004 /
        // REQ-API-004 — no payloads, no paths, no stack trace.
        Throwable cause = ex.getCause();
        if (cause != null) {
            log.warn("MCP server error while processing {} {}: {} ({})",
                    req.getMethod(), req.getRequestURI(),
                    ex.getMessage(), cause.getClass().getName());
        } else {
            log.warn("MCP server error while processing {} {}: {}",
                    req.getMethod(), req.getRequestURI(), ex.getMessage());
        }
        return body(
                HttpStatus.BAD_GATEWAY,
                "MCP_SERVER_ERROR",
                "MCP server error",
                "The MCP server is currently unavailable.",
                req);
    }

    // ------- 500 (typed orchestration / persistence failures, then catch-all) -------

    @ExceptionHandler(UseCaseExecutionException.class)
    public ResponseEntity<ProblemDetails> handleUseCaseExecution(
            UseCaseExecutionException ex, HttpServletRequest req) {
        // US-14-001: typed escape hatch for unrecoverable orchestration failures inside
        // an application use case. The cause's class name is logged at ERROR; the body
        // carries the sanitized INTERNAL_ERROR envelope (REQ-API-004, REQ-SEC-004).
        Throwable cause = ex.getCause();
        if (cause != null) {
            log.error("Use-case execution failure while processing {} {}: {} ({})",
                    req.getMethod(), req.getRequestURI(), ex.getMessage(),
                    cause.getClass().getName(), cause);
        } else {
            log.error("Use-case execution failure while processing {} {}: {}",
                    req.getMethod(), req.getRequestURI(), ex.getMessage());
        }
        return body(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal error",
                "An unexpected error occurred.",
                req);
    }

    @ExceptionHandler(DatabaseAccessException.class)
    public ResponseEntity<ProblemDetails> handleDatabaseAccess(
            DatabaseAccessException ex, HttpServletRequest req) {
        // US-14-002: a persistence-adapter wrapped a Spring DataAccessException. The
        // Spring type stays inside the adapter; the client sees the sanitized 500
        // envelope (REQ-ARC-007, REQ-API-004, REQ-SEC-004).
        Throwable cause = ex.getCause();
        log.error("Database access failure while processing {} {}: {} ({})",
                req.getMethod(), req.getRequestURI(), ex.getMessage(),
                cause == null ? "no-cause" : cause.getClass().getName(), cause);
        return body(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal error",
                "An unexpected error occurred.",
                req);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetails> handleUnexpected(Throwable ex, HttpServletRequest req) {
        log.error("Unhandled exception while processing {} {}", req.getMethod(), req.getRequestURI(), ex);
        return body(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal error",
                "An unexpected error occurred.",
                req);
    }

    // ------- helpers -------

    private static ResponseEntity<ProblemDetails> body(
            HttpStatus status, String code, String title, String detail, HttpServletRequest req) {
        ProblemDetails body = ProblemDetails.of(code, title, status.value(), detail, req.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static ProblemDetails.FieldError toFieldError(FieldError fe) {
        return new ProblemDetails.FieldError(fe.getField(), fe.getDefaultMessage());
    }

    private static ProblemDetails.FieldError toFieldError(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
        String field = path.isEmpty() ? "" : path.substring(path.lastIndexOf('.') + 1);
        return new ProblemDetails.FieldError(field, v.getMessage());
    }
}
