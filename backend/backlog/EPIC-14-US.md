# EPIC-14-US.md — User stories for EPIC-14

EPIC-14 — **Cross-cutting API concerns (errors, paging, CORS)**

This file lists the user stories that close EPIC-14. The EPIC ships the
shared API plumbing every controller relies on: the
`GlobalExceptionHandler`, the RFC 7807 `ProblemDetails` mapping, the
cursor-paging helper, the CORS configuration, and the sensitive-data
log redaction.

> **EPIC-14 was always meant to be incremental.** Per the build-order
> rationale in `EPICS.md`, "cross-cutting EPIC-14 should ideally be
> implemented incrementally alongside the first feature EPICs rather
> than treated as a strict prerequisite — a minimal
> `GlobalExceptionHandler` must exist before the first endpoint is
> shipped, but the full set of error codes grows as feature EPICs
> land." That is exactly what happened: the bulk of EPIC-14 was
> delivered piecewise by EPIC-03 → EPIC-13 as each feature added the
> error codes, pagination wiring, or CORS allowance it needed.
>
> **Already shipped by earlier EPICs.** The seven items below are
> already in the codebase and MUST NOT be re-implemented by this EPIC:
> - `BusinessException` hierarchy in `domain/shared/` —
>   `ValidationException`, `NotFoundException`, `ConflictException`,
>   `ForbiddenException` (US-03-001), plus per-context concrete
>   subclasses delivered by their owning EPICs
>   (`DuplicateAgentNameException`, `NestedTeamForbiddenException`,
>   `CrossOwnerTeamMemberException` from EPIC-06;
>   `ConversationFullException` from EPIC-10;
>   `MustChangePasswordException` from EPIC-03;
>   `InvalidCredentialsException` from EPIC-03;
>   `UnknownMcpServerException` from EPIC-08;
>   `InvalidDelegationTargetException` from EPIC-12).
> - `infrastructure/error/ExternalServiceException` (abstract, US-09-003
>   shipped its first subclass `LlmUnavailableException`; EPIC-08 added
>   `McpServerException`).
> - `@RestControllerAdvice GlobalExceptionHandler` covering every code
>   in the openapi `ProblemDetails.code` enum **except**
>   `UseCaseExecutionException` and `DatabaseAccessException` (the two
>   gaps this EPIC closes — see US-14-001 / US-14-002), and including
>   Spring framework exceptions (`MethodArgumentNotValidException`,
>   `ConstraintViolationException`, `MethodArgumentTypeMismatchException`,
>   `HttpRequestMethodNotSupportedException`,
>   `HttpMediaTypeNotAcceptableException`, `NoHandlerFoundException`,
>   `NoResourceFoundException`, `AccessDeniedException`).
> - `ProblemDetails` record under `infrastructure/web/error/`
>   (`code`, `title`, `status`, `detail`, `instance`, `type`,
>   `errors[]`), serialized with `application/problem+json` and
>   `@JsonInclude(NON_NULL)`.
> - `CursorCodec` + `PageDto<T>` under
>   `infrastructure/web/pagination/` (US-04-005). Default page size
>   20, max 100. Cursor wire format is opaque base64url over
>   `{"t":"<iso>","i":"<id>"}`.
> - CORS configuration in
>   `infrastructure/web/security/SpringSecurityConfig.corsConfigurationSource()`
>   driven by `app.cors.allowed-origins`. Exposes `Retry-After` so the
>   browser can surface the rate-limit countdown.
> - `SensitiveDataMaskingConverter` (Logback converter) under
>   `infrastructure/web/error/`, registered in `logback-spring.xml`,
>   masking BCrypt / `Bearer <…>` / JWT-shaped substrings.
>
> **What is NOT shipped yet** — the four gaps EPIC-14 closes:
> 1. The two missing exception types in design §9.1 —
>    `UseCaseExecutionException` (application) and
>    `DatabaseAccessException` (infrastructure) — and their
>    handler entries.
> 2. The **parity contract test** that asserts every `code` value
>    documented in `openapi.yaml#/components/schemas/ProblemDetails/properties/code/enum`
>    is emitted by at least one `@ExceptionHandler` path, and vice
>    versa — so a future contributor cannot quietly introduce a
>    `code` the openapi spec does not document, nor delete a handler
>    the openapi spec still advertises.
> 3. The **CORS regression test** that pins preflight behavior,
>    exposed headers (`Retry-After`), allowed methods, and the
>    interaction with the rate-limit filter (preflight must not be
>    throttled in a way that locks browsers out — Spring's
>    `CorsFilter` runs before the security chain by default; this
>    EPIC pins the assertion).
> 4. The **pagination contract test** that pins the on-the-wire
>    envelope shape, the default / max page size, the opacity of
>    cursors, and the 400 `VALIDATION_ERROR` on a malformed cursor —
>    so the frontend can rely on the contract regardless of which
>    endpoint it consumes.
>
> **Out of scope (deferred).** Distributed tracing / Micrometer
> metrics are EPIC-15. JSON structured-logging layout (replacing the
> default pattern with a Logstash-style JSON encoder) is also
> EPIC-15. HTTPS / TLS termination is EPIC-16. The
> `ProblemDetails.type` URI tree (currently
> `https://errors.multi-agent-platform/<kebab-code>`) is documented
> but not resolvable — turning it into a hosted documentation site is
> out of scope and tracked under EPIC-16.

## Conventions

- **ID format**: `US-14-<nnn>` — `14` matches the EPIC number; `<nnn>`
  is a sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories
  start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. Every story in this EPIC
  is `MUST` — the two exception types are required by the design's
  exception layering (§9.1, `REQ-ARC-007`), and the three regression
  tests lock the openapi contract that the frontend already consumes.
- Each story contains: a narrative ("As a … I want … so that …"), a
  short description, a bullet list of testable acceptance criteria,
  the out-of-scope items, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                                                          | Priority | Status | Depends on                                                                                  |
|------------|--------------------------------------------------------------------------------------------------------------------------------|----------|--------|---------------------------------------------------------------------------------------------|
| US-14-001  | `UseCaseExecutionException` (application) + `GlobalExceptionHandler` 500 `INTERNAL_ERROR` branch + use-case wrapping convention  | MUST     | Done   | US-03-001 (`GlobalExceptionHandler` base), every existing application use case               |
| US-14-002  | `DatabaseAccessException` (infrastructure) + Spring `DataAccessException` translation at the persistence boundary + 500 handler  | MUST     | Done   | US-03-001, EPIC-02 (every JPA repository adapter)                                            |
| US-14-003  | OpenAPI ↔ `ProblemDetails.code` parity regression test                                                                          | MUST     | Done   | US-14-001, US-14-002 (the two gaps must be closed before parity can be asserted clean)       |
| US-14-004  | CORS preflight + exposed-headers regression integration test                                                                    | MUST     | Done   | EPIC-01 (CORS config), EPIC-13 (`Retry-After` exposure)                                      |
| US-14-005  | Pagination contract regression test — envelope shape, defaults, opacity, malformed-cursor 400                                   | MUST     | Done   | US-04-005 (`CursorCodec` + `PageDto`), every list endpoint (EPIC-04 / 05 / 06 / 10)          |

---

## US-14-001 — `UseCaseExecutionException` (application) + 500 `INTERNAL_ERROR` branch

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `UseCaseExecutionException` defined by design §9.1
("application — `UseCaseExecutionException` → 500 (rare; wraps
unexpected orchestration failures)") to exist in
`application/shared/` with a `@ExceptionHandler` branch in
`GlobalExceptionHandler` that maps it to
500 `INTERNAL_ERROR`, **without** swallowing the cause
**So that** the layering rule from `EXCEPTIONS.md` ("no technical
exceptions in the domain; application owns orchestration; HTTP
mapping lives in the REST adapter") is implementable: a use case
that catches a domain `BusinessException` it cannot meaningfully
recover from, or that catches an infrastructure exception that does
NOT already have its own translation path (e.g. an unchecked
`RuntimeException` from a port implementation), has a typed exception
to re-throw — instead of letting an opaque `RuntimeException` flow
through the catch-all handler.

### Description

Today the application layer has no shared exception type. Use cases
let domain exceptions propagate (correct — these become 4xx via the
handler) and let infrastructure exceptions propagate (also correct
for the documented ones — `LlmUnavailableException` /
`McpServerException` become 502 via the handler). What is missing is
the **escape hatch** for orchestration failures that are neither
"the business rule was violated" nor "an external service failed" —
e.g., a transaction-boundary failure inside a use case, a
contract-impossible state reached because two ports returned
inconsistent results, or a wrapping path the use case explicitly
wants the handler to surface as 500 with a sanitized message rather
than leaking the underlying type.

The catch-all `@ExceptionHandler(Throwable.class)` already returns
500 `INTERNAL_ERROR` for these today; the gap is *typing*:

1. The current behavior is "fall through to the generic catch-all,
   which logs at `ERROR` with the full stack trace and emits the
   generic `INTERNAL_ERROR` body." That is correct as a safety net
   but is silent about whether the failure is expected (a known
   orchestration corner case) or unexpected (a programmer error).
2. `UseCaseExecutionException` lets use cases declare "I cannot
   recover from this; surface as 500" without ambiguity, and lets
   the handler log at `ERROR` with a use-case-aware prefix
   (`"Use-case execution failure in {}: {}"`) the catch-all can't
   produce.

The exception:

```java
package com.cognizant.emk.multiagent.application.shared;

/**
 * Wraps an unrecoverable orchestration failure inside an application use case
 * (design §9.1, REQ-ARC-007). Surfaces as 500 INTERNAL_ERROR at the REST
 * boundary; the wrapped cause is logged at ERROR but never returned in the
 * response body (REQ-API-004, REQ-SEC-004).
 *
 * <p>Use cases SHOULD let domain {@link BusinessException} and infrastructure
 * {@link ExternalServiceException} flow through unchanged — they have their
 * own typed handlers. Reach for this type only when neither applies, e.g.,
 * a contract-impossible state between two ports.
 */
public final class UseCaseExecutionException extends RuntimeException {
    public UseCaseExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

The handler entry:

```java
@ExceptionHandler(UseCaseExecutionException.class)
public ResponseEntity<ProblemDetails> handleUseCaseExecution(
        UseCaseExecutionException ex, HttpServletRequest req) {
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
```

Placement: in `GlobalExceptionHandler`, between the 502 branch and
the catch-all `@ExceptionHandler(Throwable.class)`. This preserves
the "most-specific first" ordering already used in the handler.

The story does **not** force any existing use case to start wrapping.
It introduces the type and the handler; concrete adoption is left to
the use cases that need it. The convention is documented in the
class Javadoc and in `EXCEPTIONS.md`.

### Acceptance criteria

- `application/shared/UseCaseExecutionException.java` exists as a
  `public final class` extending `RuntimeException`, with the
  two-argument `(String, Throwable)` constructor and the Javadoc
  above. Single-argument constructors are NOT added — the wrapping
  cause is part of the contract; a use case calling this without a
  cause should use a domain exception instead.
- The class lives in `application/shared/` (next to other
  application-shared utilities). It is **not** under
  `application/<context>/` — the type is cross-cutting.
- `GlobalExceptionHandler` gains the `handleUseCaseExecution` method
  exactly as above. Placement: between
  `handleMcpServerError(...)` (the last 502 branch) and
  `handleUnexpected(...)` (the catch-all).
- The log statement at `ERROR` includes the cause's class name when
  present and never logs the cause's `getMessage()` payload (per
  `REQ-SEC-004` — provider error payloads MUST NOT reach logs in a
  form a regex-redactor cannot mask).
- `GlobalExceptionHandlerTest` is extended with two assertions:
  - `handleUseCaseExecution_withCause_returns500_andLogsCauseClass`
    — verifies the body is the standard `INTERNAL_ERROR` envelope,
    the response status is 500, and a Logback list-appender captures
    a single ERROR-level record containing the cause's class name.
  - `handleUseCaseExecution_withoutCause_returns500_andLogs` —
    verifies the no-cause branch.
- `EXCEPTIONS.md` is updated: a new paragraph under
  "## Application exceptions" documents
  `UseCaseExecutionException` and clarifies *when* to reach for it
  (the "neither business nor known infra failure" corner case).
- A pure-Java unit test
  `UseCaseExecutionExceptionTest` asserts:
  - The constructor stores the message and cause.
  - The class is `final` and cannot be subclassed (compile-time, not
    runtime — verified via `Modifier.isFinal(...)`).
- ArchUnit (`LayeringArchTest`) is extended with a rule:
  > Classes named `UseCaseExecutionException` MUST live under
  > `application.shared..` only.
  This prevents per-context copies from sprouting.
- No existing use case is rewritten in this story to throw the new
  type — that is intentional. Each owning EPIC can adopt it when a
  concrete need arises; this story ships the seam.

### Out of scope

- Rewriting existing use cases (e.g.,
  `StartConversationUseCase`) to wrap their failures in
  `UseCaseExecutionException`. The seam exists; concrete adoption is
  left to per-context follow-ups.
- A 503 variant (`SERVICE_UNAVAILABLE`). Design §9.1 lists 500 only
  for this type; the 502 / 429 surfaces own the "service unavailable"
  semantics already.

### Requirements coverage

`REQ-ARC-007` (exception layering), `REQ-API-004` (single error
envelope), `REQ-SEC-004` (sensitive-data redaction in logs).

### Design references

§9.1 exception hierarchy, §9.2 GlobalExceptionHandler, §9.3 error
response shape. `backend/docs/EXCEPTIONS.md` — application
exceptions section.

### Dependencies

US-03-001 (`GlobalExceptionHandler` base, `ProblemDetails`,
`GlobalExceptionHandlerTest` harness). The `application/shared/`
package already exists (EPIC-04 shipped `Page` and `Cursor`-adjacent
shared types).

---

## US-14-002 — `DatabaseAccessException` (infrastructure) + Spring `DataAccessException` translation

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a `DatabaseAccessException` under `infrastructure/error/`
that wraps Spring's `org.springframework.dao.DataAccessException` at
the persistence-adapter boundary, plus a `GlobalExceptionHandler`
branch mapping it to 500 `INTERNAL_ERROR` (per design §9.1, which
allows 503 or 500 — 500 is chosen here)
**So that** raw Spring / JPA / Hibernate exception types stop
escaping into the application layer, the error path through the
`@RestControllerAdvice` does not depend on the catch-all
`@ExceptionHandler(Throwable.class)` for the most common
infrastructure failure mode, and the openapi `INTERNAL_ERROR` code
has a typed source it can be statically asserted against in
US-14-003.

### Description

Today the persistence adapters in
`infrastructure/persistence/adapter/` let Spring Data JPA exceptions
(transient connection failures, constraint-violation surfaces other
than the documented ones, optimistic-lock failures the v1 design has
nowhere to surface, etc.) flow up the stack. They bypass the
hexagonal contract: an application use case sees a
`DataAccessException` from `org.springframework.dao` and is forced
either to:

1. Let it propagate to the `@ExceptionHandler(Throwable.class)`
   catch-all — works, but loses the "this is a DB failure, not a
   programmer error" signal in logs and in the future Micrometer
   counter for `db_errors_total`.
2. Catch it and rewrap — duplicated across every adapter.

This story centralizes the wrapping at the adapter boundary via a
new `DatabaseAccessException` and a small `JpaAccess` helper that
adapters use to bracket their JPA calls. Adapters that already exist
keep working unchanged (option 1 above); the new helper is offered
as the recommended pattern going forward, and one **canary adapter**
adopts it as a worked example.

The exception:

```java
package com.cognizant.emk.multiagent.infrastructure.error;

/**
 * Wraps a Spring {@link org.springframework.dao.DataAccessException} at the
 * persistence-adapter boundary so the application layer never sees Spring
 * types directly (REQ-ARC-007, REQ-ARC-003).
 *
 * <p>Surfaces as 500 INTERNAL_ERROR at the REST boundary. The cause is logged
 * at ERROR with class name only; the response body carries the sanitized
 * generic detail (REQ-API-004, REQ-SEC-004).
 */
public class DatabaseAccessException extends RuntimeException {
    public DatabaseAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

The helper (lives in `infrastructure/persistence/adapter/`):

```java
public final class JpaAccess {
    private JpaAccess() {}

    public static <T> T run(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException ex) {
            throw new DatabaseAccessException(operation + " failed", ex);
        }
    }

    public static void run(String operation, Runnable action) {
        try {
            action.run();
        } catch (DataAccessException ex) {
            throw new DatabaseAccessException(operation + " failed", ex);
        }
    }
}
```

The handler entry mirrors the 502 branches in shape — log the
cause's class name at ERROR, return the generic 500 envelope:

```java
@ExceptionHandler(DatabaseAccessException.class)
public ResponseEntity<ProblemDetails> handleDatabaseAccess(
        DatabaseAccessException ex, HttpServletRequest req) {
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
```

The canary adapter that adopts the helper in this story:
`RateLimitConfigRepositoryAdapter` (US-13-002) — it has exactly two
methods (`load`, `save`), both already have a documented "this can
only fail in pathological cases" error path
(`IllegalStateException` when the row is missing — KEPT, that
exception is a startup invariant, not a DB-access failure), and
it is independently regression-tested. The remaining adapters
(`UserRepositoryAdapter`, `AgentRepositoryAdapter`,
`ConversationRepositoryAdapter`, `ApiKeyRepositoryAdapter`) are
**not** rewritten in this story — adoption is a per-EPIC follow-up.

### Acceptance criteria

- `infrastructure/error/DatabaseAccessException.java` exists, is
  `public`, extends `RuntimeException`, has the two-argument
  `(String, Throwable)` constructor.
- `infrastructure/persistence/adapter/JpaAccess.java` exists as a
  `public final class` with a private constructor and the two
  static `run(...)` overloads above. The class is `final` and the
  constructor is `private` — it is a utility, not an extension
  point.
- `GlobalExceptionHandler` gains the `handleDatabaseAccess(...)`
  method. Placement: in the 500 section, before the catch-all
  `handleUnexpected(...)` and after `handleUseCaseExecution(...)`
  from US-14-001.
- `RateLimitConfigRepositoryAdapter` is updated to bracket its JPA
  calls with `JpaAccess.run(...)`:
  - `load()`: `JpaAccess.run("rate_limit_config.load", () -> ...)`.
  - `save(...)`: `JpaAccess.run("rate_limit_config.save", () -> ...)`.
  The `IllegalStateException("rate_limit_config row missing")` is
  thrown **outside** the `JpaAccess.run` block — it is a domain
  startup invariant, not a DB failure.
- `RateLimitConfigRepositoryAdapterIntegrationTest` is extended:
  a new test
  `wraps_DataAccessException_as_DatabaseAccessException` simulates a
  transient JPA failure (e.g., by passing a `Connection` that
  forces a `DataAccessException`, or by mocking the
  `RateLimitConfigJpaRepository.findById` to throw a
  `DataIntegrityViolationException`) and asserts a
  `DatabaseAccessException` is thrown with the original as cause.
- `GlobalExceptionHandlerTest` gains a `handleDatabaseAccess`
  test verifying the 500 status, the standard envelope, the
  `application/problem+json` Content-Type, and that a Logback
  list-appender records exactly one ERROR with the cause's class
  name.
- ArchUnit (`LayeringArchTest`) is extended with:
  > Classes named `DatabaseAccessException` MUST live under
  > `infrastructure.error..` only.
  > Classes in `application..` MUST NOT import
  > `org.springframework.dao..`.
  The second rule pins the layering contract.
- `EXCEPTIONS.md` gains one paragraph under "## Technical
  exceptions": "When persistence adapters wrap Spring Data calls,
  use the `JpaAccess` helper to translate `DataAccessException`
  into `DatabaseAccessException`; the global handler maps the
  latter to 500 `INTERNAL_ERROR` without leaking the Spring type."

### Out of scope

- Rewriting the four other repository adapters
  (`User`, `Agent`, `Conversation`, `ApiKey`) to use `JpaAccess` —
  follow-up work; the seam is established here.
- A 503 variant (e.g., distinguishing "DB transiently unavailable"
  from "DB constraint violation we did not anticipate"). The
  current single 500 mapping matches design §9.1's "(or 500)"
  fallback; if v2 needs to differentiate, a sealed sub-hierarchy
  can be added without breaking this contract.

### Requirements coverage

`REQ-ARC-002` (hexagonal layering — Spring types don't leak),
`REQ-ARC-003` (separation of concerns), `REQ-ARC-007` (exception
layering), `REQ-API-004` (single error envelope),
`REQ-PRS-003` (transactional integrity preserved — the helper is
inside any `@Transactional` boundary the adapter declares).

### Design references

§9.1 exception hierarchy, §9.2 GlobalExceptionHandler, §9.3 error
response shape. `EXCEPTIONS.md` — technical exceptions section.

### Dependencies

US-03-001 (`GlobalExceptionHandler` base). EPIC-02 (every
persistence adapter — only `RateLimitConfigRepositoryAdapter`
is rewired in this story; the others stay as-is). US-14-001 (the
handler ordering after `UseCaseExecutionException`).

---

## US-14-003 — OpenAPI ↔ `ProblemDetails.code` parity regression test

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a regression test that statically asserts the set of
`code` values documented in
`openapi.yaml#/components/schemas/ProblemDetails/properties/code/enum`
is exactly the set of `code` strings actually emitted by the
`GlobalExceptionHandler`
**So that** a future contributor cannot quietly introduce a `code`
the openapi spec does not document (frontend would not know how to
handle it), nor delete a handler the openapi spec still advertises
(frontend would handle a code the backend no longer emits), and so
that the documented `RateLimited` / `Conflict` / `Forbidden`
example responses in `openapi.yaml` are guaranteed to remain
truthful as the handler evolves.

### Description

The openapi spec already enumerates the contract today (the
`ProblemDetails.code.enum` block, plus the per-response
`examples:` blocks in `responses/RateLimited`, `Conflict`,
`Forbidden`, `BadRequest`, `NotFound`, `InvalidCredentials`). The
handler emits codes via `ProblemDetails.of(code, …)` and via the
`body(...)` helper. There is no test today that ties the two sides
together; nothing prevents the next contributor from:

- Adding a new business exception with a fresh `code` and a fresh
  handler entry — but forgetting to update `openapi.yaml`.
- Removing a handler entry while the openapi spec still lists the
  corresponding `code` in the enum.

The test lives in
`backend/src/test/java/.../infrastructure/web/error/ProblemDetailsOpenApiContractTest.java`
and runs as a pure JUnit 5 test (no Spring context). Its harness:

1. **Read the openapi enum**: parse `openapi.yaml` (already
   reachable from the test classpath via the build's
   `<testResources>` block, the same way the existing
   `OpenApiContractTest`s do — verified to exist in the codebase).
   Extract the `enum:` list under
   `components.schemas.ProblemDetails.properties.code`.
2. **Discover the handler-emitted codes**: scan
   `GlobalExceptionHandler.class` via reflection — every method
   annotated with `@ExceptionHandler` is invoked through a small
   helper that captures the `ProblemDetails.code()` it returns
   for a mocked `HttpServletRequest`. The cause / wrapped values
   are stubbed with no-op `Throwable`s; we only care about the
   `code` field of the body.

   Alternative implementation (preferred for robustness): **AST
   scan** of `GlobalExceptionHandler.java` source — find every
   string-literal argument to `ProblemDetails.of(` or to the
   private `body(` helper at the `code` position. This avoids
   running the handler methods (some need a real `HttpServletRequest`
   for `getRequestURI()`) and stays decoupled from the actual
   exception subclass internals.

3. **Assert exact set equality**:
   - Set difference (handler − openapi) MUST be empty → the
     handler emits a code the spec does not document.
   - Set difference (openapi − handler) MUST be empty → the spec
     advertises a code the handler does not emit.

4. **Bonus assertion**: every `ProblemDetails.code` enum value is
   referenced by at least one `@ExceptionHandler` in the handler
   class, AND every `examples:` block in
   `openapi.yaml#/components/responses` whose payload contains
   `code: <STRING>` references a code in the enum (the latter
   pin guards against typos in example bodies).

### Acceptance criteria

- The new test class
  `infrastructure/web/error/ProblemDetailsOpenApiContractTest`
  exists under `src/test/java/`.
- The test runs in under 200ms (no Spring context;
  pure file I/O + a YAML parser + AST scan or reflection).
- The openapi file is read from `../openapi.yaml` (root of the
  multi-module workspace) — the test's harness resolves the path
  via the same convention the project's other openapi-contract
  tests use (verify by reading at least one existing
  `*OpenApiContractTest` in the codebase first; if no such helper
  exists, introduce a minimal one in the same file).
- A YAML library available in the existing dependency tree
  (`snakeyaml`, pulled transitively by Spring Boot's web starter)
  is used — no new Maven dependency is added.
- The test asserts:
  - **`handler_emits_no_code_outside_openapi_enum`** — set
    difference (handler − openapi) is empty.
  - **`openapi_enum_has_no_code_unhandled`** — set difference
    (openapi − handler) is empty.
  - **`every_openapi_response_example_code_is_in_the_enum`** —
    every `code:` value found in any
    `components.responses.*.content.application/problem+json.examples.*.value.code`
    appears in the `ProblemDetails.code.enum`.
- The current red/green state on green: with the two new types
  shipped in US-14-001 / US-14-002 (both emit `INTERNAL_ERROR`,
  already in the enum), the parity is exact. Each of the 17 codes
  in the openapi enum
  (`VALIDATION_ERROR`, `INVALID_CREDENTIALS`,
  `MUST_CHANGE_PASSWORD`, `FORBIDDEN`, `NOT_FOUND`,
  `METHOD_NOT_ALLOWED`, `CONFLICT`, `DUPLICATE_AGENT_NAME`,
  `NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER`,
  `CONVERSATION_FULL`, `RATE_LIMITED`, `LLM_UNAVAILABLE`,
  `MCP_SERVER_ERROR`, `NOT_ACCEPTABLE`, `INTERNAL_ERROR`,
  `VALIDATION_ERROR`) is emitted by at least one
  `@ExceptionHandler`.
- Failure mode: if a contributor adds a handler emitting a new
  code without updating `openapi.yaml`, the test fails with a
  clear AssertJ message: "`code='FOO_BAR'` is emitted by
  `GlobalExceptionHandler.handleFoo` but is not in
  `openapi.yaml#/components/schemas/ProblemDetails/properties/code/enum`.
  Add it to the openapi enum or remove the handler."
- The test class Javadoc documents the *why* (single source of
  truth between handler and openapi spec).

### Out of scope

- Generating `GlobalExceptionHandler` from `openapi.yaml`
  (codegen) — overkill for v1, and the manual mapping carries
  the per-exception logging / Retry-After / cause-class
  decisions that codegen could not produce.
- Asserting that every `@ExceptionHandler` method has a
  corresponding `responses` entry on the openapi paths that can
  reach it — that is a path-level contract, much larger; not in
  this EPIC.

### Requirements coverage

`REQ-API-002` (openapi is the single source of truth),
`REQ-API-004` (single error envelope across the API),
`REQ-NFR-002` (regression test).

### Design references

§9.3 error response shape, §9.2 GlobalExceptionHandler, the
`ProblemDetails.code.enum` block in `openapi.yaml`.

### Dependencies

US-14-001 (`UseCaseExecutionException` handler, emitting
`INTERNAL_ERROR`), US-14-002 (`DatabaseAccessException` handler,
emitting `INTERNAL_ERROR`). The test assertions go green once
both gaps are closed.

---

## US-14-004 — CORS preflight + exposed-headers regression integration test

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a regression test that pins the CORS configuration —
preflight (`OPTIONS`) behavior for an allowed origin, the
`Access-Control-Expose-Headers` value (must include
`Retry-After` so the browser can read the rate-limit countdown),
the allowed methods, the allowed request headers (`Authorization`,
`Content-Type`, `Accept`, `X-Client-Id`, `X-Api-Key`,
`X-Requested-With`), and the interaction with the rate-limit
filter
**So that** a frontend developer can trust the cross-origin
contract regardless of which endpoint they call, the rate-limit
countdown header (`Retry-After`) remains reachable from the
browser, and a future change to `SpringSecurityConfig` cannot
silently break preflight in a way only end-to-end browser tests
would catch.

### Description

The CORS configuration is already in place
(`SpringSecurityConfig.corsConfigurationSource()`,
`app.cors.allowed-origins`). Today there is no integration test
for it — preflight behavior is untested, the
`Access-Control-Expose-Headers` set is untested (a regression that
drops `Retry-After` would silently break frontend US-07-005's
countdown handling), and the interaction with the rate-limit
filter is undocumented.

The test class lives in
`infrastructure/web/security/CorsConfigurationIntegrationTest`
and extends `PostgresIntegrationTest`. Its harness uses MockMvc.
Origins are injected via `@TestPropertySource` (the
`app.cors.allowed-origins` list is small enough to override per
test class) — one allowed origin (`http://localhost:5173`, the
project's dev default) and one **disallowed** origin
(`https://evil.example`) so the negative path is also exercised.

Scenarios:

- **Preflight succeeds for an allowed origin**: `OPTIONS
  /api/v1/agents` with
  `Origin: http://localhost:5173`,
  `Access-Control-Request-Method: POST`,
  `Access-Control-Request-Headers: Authorization, Content-Type`.
  Expected: 200 (or 204, whichever Spring MVC's preflight handler
  emits — the test asserts on the headers, not the exact
  status), `Access-Control-Allow-Origin:
  http://localhost:5173`,
  `Access-Control-Allow-Credentials: true`,
  `Access-Control-Allow-Methods` contains `POST`,
  `Access-Control-Allow-Headers` contains both `Authorization`
  and `Content-Type`,
  `Access-Control-Expose-Headers` contains `Retry-After`,
  `Access-Control-Max-Age` is `3600`.
- **Preflight rejected for a disallowed origin**: same call with
  `Origin: https://evil.example`. Expected: response carries NO
  `Access-Control-Allow-Origin` header (the browser will then
  block the actual request — exactly the behavior we want).
- **Actual GET with allowed origin** (the "simple" CORS path):
  `GET /api/v1/agents` with `Authorization: Bearer <valid>` and
  `Origin: http://localhost:5173`. Expected: 200 with
  `Access-Control-Allow-Origin: http://localhost:5173`.
- **`Retry-After` reachable on 429**: with the rate-limit
  bucket exhausted (use the
  `RateLimitFilterIntegrationTest` harness pattern — virtual
  `TimeMeter`, low limits), a GET on `/api/v1/_rl_probe` with
  `Origin: http://localhost:5173` returns 429 AND the response
  includes both
  `Retry-After: <int>` and
  `Access-Control-Expose-Headers: ... Retry-After ...`. The
  latter is what makes the former *readable* from the browser
  via `fetch().headers.get("Retry-After")`.
- **Preflight is NOT rate-limited in a way that locks the
  browser out**: preflight requests with the same `Origin`
  succeed even with the bucket exhausted, OR if they ARE
  rate-limited, the 429 carries the CORS allow-origin header so
  the browser surfaces the 429 to the frontend code (instead of
  a CORS error that the frontend cannot detect). The test
  asserts whichever behavior is in effect today and pins it.
  Reference: §8.1 "RateLimitFilter is outermost"; preflight is
  routed through the filter chain because Spring's `CorsFilter`
  is wired into the security chain (not standalone) per the
  current `SpringSecurityConfig`.
- **`X-Client-Id` + `X-Api-Key` are allowed**: a preflight
  declaring `Access-Control-Request-Headers: X-Client-Id,
  X-Api-Key` for an API-key client returns
  `Access-Control-Allow-Headers` containing both — this is the
  baseline for external programmatic clients per
  `REQ-AUTH-001`.

### Acceptance criteria

- `CorsConfigurationIntegrationTest` extends
  `PostgresIntegrationTest`, uses `@SpringBootTest` +
  `MockMvc`, and overrides
  `app.cors.allowed-origins=http://localhost:5173` via
  `@TestPropertySource`.
- The six scenarios above all pass.
- The assertion on `Access-Control-Expose-Headers` uses
  containment (`contains("Retry-After")`), not exact equality —
  the EPIC-15 observability work may add a `X-Correlation-Id`
  exposed header; the test must not block that.
- The assertion on `Access-Control-Allow-Headers` uses
  containment for the same reason.
- The disallowed-origin test does NOT use a different security
  policy (the harness allows exactly one origin via property);
  it confirms Spring's `CorsFilter` enforces the allow-list and
  silently strips the CORS response headers when the origin
  does not match.
- The 429-with-CORS scenario uses the
  `RateLimitProbeController` (US-13-007's test-only controller)
  + the `TimeMeter`-virtualized harness already in place — NO
  `Thread.sleep`.
- `DESIGN-CHOICES.md` gains a one-paragraph note: "CORS
  behavior under rate-limiting" — captures the choice (preflight
  is throttled together with regular traffic; the 429 response
  carries the CORS allow-origin header so the browser can read
  the body and the `Retry-After`). This is the load-bearing
  decision pinned by the test.

### Out of scope

- Multiple allowed origins (e.g., one dev + one staging). The
  config supports a list; the test uses a single-origin
  configuration. Adding a multi-origin scenario is a one-line
  follow-up if needed.
- Wildcard subdomains. The current contract is exact-match
  origins; supporting `*.example.com` is a v2 concern.
- Browser-end testing (Playwright). The MockMvc harness covers
  the contract Spring enforces; browser conformance is implicit.

### Requirements coverage

`REQ-API-003` (CORS allow-list via Spring properties),
`REQ-AUTH-001` (`X-Client-Id` + `X-Api-Key` headers are part of
the API contract), `REQ-RL-005` (`Retry-After` accessible to the
client), `REQ-NFR-004` (compatibility with the ReactJS
frontend), `REQ-NFR-002` (regression test).

### Design references

§6.1 conventions, §8.1 filter chain (the rate-limit-vs-preflight
interaction), §15 configuration (`app.cors.allowed-origins`).

### Dependencies

EPIC-01 (the original `corsConfigurationSource()` bean),
EPIC-13 (`RateLimitProbeController`, virtualized `TimeMeter`
harness — both already in `src/test/java`), EPIC-03 (a working
JWT path so the "actual GET with allowed origin" scenario can
authenticate).

---

## US-14-005 — Pagination contract regression test

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a regression test that pins the pagination contract at
the HTTP boundary — the on-the-wire envelope shape
(`{items, nextCursor, pageSize}`), the default page size (20),
the maximum page size (100), the opacity of cursors (no
client-visible structure), the 400 `VALIDATION_ERROR` on a
malformed `cursor` parameter, and the `pageSize` clamping /
validation behavior
**So that** the frontend can rely on the contract regardless of
which list endpoint it consumes (agents, conversations,
messages, users, api-keys), and a future change to `CursorCodec`
or `PageDto` cannot silently break the openapi
`PageEnvelope` shape that frontend US-04-* through US-07-*
already consume.

### Description

The pagination plumbing is already in place
(`infrastructure/web/pagination/CursorCodec`,
`PageDto<T>`) and every list endpoint (EPIC-04 / 05 / 06 / 10)
consumes it. Today the unit tests on `CursorCodec` and `PageDto`
exist (`CursorCodecTest`, `PageDtoTest`) but they exercise the
helper in isolation; no integration test asserts the on-the-wire
envelope shape matches the openapi `PageEnvelope` schema.

The test class lives in
`infrastructure/web/pagination/PaginationContractIntegrationTest`
and extends `PostgresIntegrationTest`. Its harness hits a single,
stable list endpoint —
`GET /api/v1/agents` — populated with a deterministic fixture
(30 agents owned by the test-fixture standard user) so the
default page size, the second page, and the final-empty-page
behaviors are all reachable.

Scenarios:

- **Default page size**: `GET /api/v1/agents` with no `pageSize`
  query parameter returns a body whose
  `pageSize == 20` and `items.length == 20`. A `nextCursor`
  is present (string, non-empty).
- **Explicit page size in range**: `GET /api/v1/agents?pageSize=5`
  returns `pageSize == 5`, `items.length == 5`.
- **`pageSize=0` is rejected**: 400 `VALIDATION_ERROR` with
  `errors[0].field == "pageSize"`.
- **`pageSize=101` is rejected**: 400 `VALIDATION_ERROR` (the
  openapi spec documents `maximum: 100`).
- **`pageSize=-1` is rejected**: 400 `VALIDATION_ERROR`.
- **Cursor round-trip**: take the `nextCursor` from page 1,
  pass it as `cursor=<verbatim>` to page 2, get the next 20
  rows; the union of page 1 and page 2 contains no duplicates
  and no gaps (the fixture is sorted by `created_at DESC, id
  DESC`, matching design §10).
- **Cursor opacity**: the `nextCursor` value is treated as a
  byte-identical opaque string by the test; the test does NOT
  decode it (verifies that no contributor introduced a
  client-visible structure, e.g. JSON in cleartext).
- **Malformed cursor**: `GET /api/v1/agents?cursor=not-a-base64`
  returns 400 `VALIDATION_ERROR` with
  `errors[0].field == "cursor"`. This is the `CursorCodec`
  contract documented in
  `CursorCodec.decode(...)` — the test asserts it surfaces
  through the controller and the `GlobalExceptionHandler`
  unchanged.
- **Final empty page**: after consuming all 30 fixture rows,
  the next `cursor` request returns
  `items.length == 0` AND `nextCursor == null` (JSON-absent
  field thanks to `@JsonInclude(NON_NULL)` on `PageDto`).
- **Envelope shape parity with openapi**: the response body's
  top-level JSON has **exactly** the fields `items`, `pageSize`,
  and optionally `nextCursor` — no extras. Verified by parsing
  the response with Jackson into a `Map<String, Object>` and
  asserting the key set is `{items, pageSize}` (when
  `nextCursor=null`) or `{items, pageSize, nextCursor}`
  otherwise.

### Acceptance criteria

- `PaginationContractIntegrationTest` extends
  `PostgresIntegrationTest`, uses `@SpringBootTest` + `MockMvc`,
  and seeds 30 agents for a fresh user via the existing
  fixture helpers used by `ListAgentsEndpointIntegrationTest`.
- The nine scenarios above all pass.
- The test does not depend on any specific agent ordering
  inside a page (it asserts on counts, not on identities, except
  for the round-trip dedup check which compares ID sets).
- The "envelope shape parity" assertion uses `Map<String,
  Object>` exact-key comparison — a future addition of a
  `totalCount` field (if v2 ever adds it) MUST update this
  test in lockstep.
- The malformed-cursor 400 envelope matches the openapi
  `BadRequest` example shape: `code="VALIDATION_ERROR"`,
  `title="Validation error"`, `status=400`,
  `errors=[{field:"cursor", message:<non-empty>}]`.
- The test is named to make its scope obvious in CI failure
  reports: each `@Test` method name is the scenario name
  above, snake_cased
  (`default_page_size_is_20_when_pageSize_omitted`, …).

### Out of scope

- Repeating the contract test against every list endpoint. One
  list endpoint (`/agents`) is sufficient: the
  `CursorCodec` + `PageDto` plumbing is shared, and per-endpoint
  scopes (filtering by `agentId`, etc.) are tested in their
  owning EPIC's integration tests. If any endpoint diverges
  from the shape, its own integration test catches it.
- Asserting on `Content-Type` (`application/json`) — every list
  endpoint already does this.
- Property-based testing of the cursor codec (round-trip
  arbitrary `(OffsetDateTime, UUID)` pairs) — that is unit-test
  scope, already covered by `CursorCodecTest`.

### Requirements coverage

`REQ-API-005` (cursor-based pagination, default 20, max 100),
`REQ-API-002` (openapi is the single source of truth),
`REQ-API-004` (single error envelope on 400),
`REQ-NFR-002` (regression test).

### Design references

§10 pagination, §9.3 error response shape,
`openapi.yaml#/components/schemas/PageEnvelope`,
`openapi.yaml#/components/parameters/PageSize`.

### Dependencies

US-04-005 (`CursorCodec` + `PageDto` — already shipped).
EPIC-06 (the `/agents` list endpoint the test calls).
The `PostgresIntegrationTest` base and agent-fixture helpers
shipped by EPIC-02 / EPIC-06.

---

## Summary

| ID         | Title                                                                                                                          | Priority | Status |
|------------|--------------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-14-001  | `UseCaseExecutionException` (application) + `GlobalExceptionHandler` 500 `INTERNAL_ERROR` branch                                | MUST     | Done   |
| US-14-002  | `DatabaseAccessException` (infrastructure) + Spring `DataAccessException` translation at the persistence boundary + 500 handler  | MUST     | Done   |
| US-14-003  | OpenAPI ↔ `ProblemDetails.code` parity regression test                                                                          | MUST     | Done   |
| US-14-004  | CORS preflight + exposed-headers regression integration test                                                                    | MUST     | Done   |
| US-14-005  | Pagination contract regression test — envelope shape, defaults, opacity, malformed-cursor 400                                   | MUST     | Done   |

EPIC-14 is **Done** when all five stories above are `Done`. At that
point the exception hierarchy from design §9.1 is complete
(domain / application / infrastructure layered), and the three
cross-cutting contracts (error envelope, CORS, pagination) are
regression-locked against the openapi spec. The next step is
EPIC-15 (observability & health), which builds on the redaction
converter and structured-logging foundation EPIC-14 has matured.
