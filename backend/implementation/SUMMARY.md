# SUMMARY.md — Implementation summaries

One short entry per implementation task, newest first. Lists only the files
created or modified. Implementation choices go in `DESIGN-CHOICES.md`.

---

## 2026-07-03 — Log format switch (JSON → human-readable)

Reverts US-15-003's JSON encoder to a Spring-Boot-style single-line pattern, per operator preference. REQ-SEC-004 (redaction) is preserved: the pattern still routes the message through `%redactedMsg`, so all three sensitive substrings (BCrypt / Bearer / bare JWT) continue to be masked before any byte is emitted. REQ-OBS-001 is downgraded from JSON structured output to a fixed pattern with correlationId. REQ-OBS-002 (per-package levels) is unchanged.

Modified:
- `src/main/resources/logback-spring.xml` — replace `LoggingEventCompositeJsonEncoder` with `PatternLayoutEncoder`. Pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{40} [%mdc{correlationId:--}] : %redactedMsg%n%wEx`. `--` literal outside a request scope, `%wEx` appends stack trace when a Throwable is attached. `SensitiveDataMaskingConverter` conversion rule for `%redactedMsg` unchanged.
- `pom.xml` — remove `net.logstash.logback:logstash-logback-encoder` dependency and its `${logstash-logback-encoder.version}` property (no longer used).
- `src/test/java/.../infrastructure/web/observability/SensitiveDataRedactionIntegrationTest.java` — rewritten to work on raw pattern output: discovers the CONSOLE appender's `PatternLayoutEncoder` instead of the JSON encoder, runs substring assertions on the captured `ByteArrayOutputStream` text (no JSON parsing). Same 6 scenarios, same REQ-SEC-004 substring-absence guarantees.

Deleted:
- `src/test/java/.../infrastructure/web/observability/JsonLoggingEncoderIntegrationTest.java` — the JSON-encoder contract it pinned no longer applies.

## 2026-07-03 — MCP tool-callback wiring in the OpenAI adapter (bug fix)

Closes the last-mile gap flagged by `OpenAiChatClientAdapter`'s own Javadoc: `ChatRequest.enabledMcpServers` was flowing through the layers but never attached to the Spring AI `Prompt`, so adding an MCP server to an agent (via create or update) had no runtime effect (REQ-AGT-009 / REQ-MCP-004 / REQ-AGT-014). Targeted runs green: **6 resolver + 4 MCP-wiring + 3 tool-wiring + 5 translator + 13 call + 11 stream + 18 layering = 60 tests**, 0 failures. DB-backed integration tests were not exercised (local Postgres auth env issue, unrelated to this change).

Created:
- `infrastructure/mcp/McpToolCallbackResolver.java` — Spring bean. At startup it correlates each `McpSyncClient` (injected as `ObjectProvider<List<McpSyncClient>>`) to its yaml connection name by matching `client.getClientInfo().name()` against the suffix of the keys in `McpStdioClientProperties.getConnections()`. `resolve(enabledMcpServers)` returns the union of `ToolCallback`s produced by `SyncMcpToolCallbackProvider.builder().mcpClients(selected).build()` for the enabled subset. Unknown/missing names degrade to skipped-with-warning. A `noop()` static factory returns an instance that always resolves to `List.of()`, used across test contexts where the MCP autoconfig is excluded.
- `infrastructure/llm/openai/OpenAiChatClientAdapterMcpWiringTest.java` — 4 tests: enabled MCP → callback on options; empty list → plain `ChatOptions`; static tool + MCP callbacks merged; `stream(...)` path also wired.
- `infrastructure/mcp/McpToolCallbackResolverTest.java` — 6 tests: empty/null enabled list, absent client bean, absent properties bean, unknown name skipped, only enabled clients queried.

Modified:
- `infrastructure/llm/openai/OpenAiChatOptionsTranslator.java` — `toPrompt` / `toOptions` accept an extra `List<ToolCallback> mcpCallbacks` and merge it with the tool-bean callbacks; the plain-`ChatOptions` fast path fires only when both lists are empty.
- `infrastructure/llm/openai/OpenAiChatClientAdapter.java` — constructor takes `McpToolCallbackResolver`; both `call(...)` and `stream(...)` pass `resolver.resolve(request.enabledMcpServers())` to the translator. Javadoc updated ("MCP wiring per agent … still pending" → live wiring).
- `infrastructure/llm/openai/OpenAiConfig.java` — `fallbackOpenAiLlmChatClient` now injects and forwards the resolver.
- `infrastructure/llm/openai/OpenAiChatOptionsTranslatorTest.java` — call sites updated (extra `List.of()` arg).
- `infrastructure/llm/openai/OpenAiChatClientAdapter{Call,Stream,ToolWiring}Test.java` — 27 call sites updated to pass `McpToolCallbackResolver.noop()`.
- `infrastructure/web/conversation/SendMessageEndpointIntegrationTest.java` — `TestConfig.testLlmChatClient` injects and forwards the resolver.

## 2026-06-19 — EPIC-15 / US-15-001 → US-15-004 — Observability & health

Closes EPIC-15. Ships Spring Boot Actuator's `/actuator/health` outside `/api/v1`, a `CorrelationIdFilter` that propagates `X-Correlation-Id` through MDC + response header + CORS expose-list, a JSON Logback encoder (`LoggingEventCompositeJsonEncoder`) that preserves the `%redactedMsg` conversion word, and an end-to-end redaction regression test that locks REQ-SEC-004 across the encoder swap. Targeted runs all green: **6 actuator + 14 correlation-id (7 unit + 7 integration) + 8 JSON encoder/per-package + 6 redaction regression + 18 layering + 6 CORS + 6 rate-limit + 17 login/logout = 81 tests**, 0 failures.

### US-15-001 — Spring Boot Actuator + `GET /actuator/health` outside `/api/v1`
Modified:
- `pom.xml` — added `spring-boot-starter-actuator` (BOM-managed version).
- `src/main/resources/application.yaml` — `management.endpoints.web.exposure.include=health` + `management.endpoint.health.show-details=never` (REQ-SEC-004: no DB host / schema leak).
- `src/test/resources/application.yaml` — same `management.*` block for test-profile parity.

Tests (`infrastructure/web/observability/`):
- `ActuatorHealthEndpointIntegrationTest.java` — 5 scenarios: 200 + `{"status":"UP"}`; `/api/v1/actuator/health` does NOT resolve (security chain returns 401 — proves the prefix is not applied); only `/actuator/health` is exposed (metrics/loggers/info/env/beans return 401); `show-details=never` omits `components`/`details`; anonymous access permitted (matcher in place since US-04-009).
- `ActuatorHealthDownWhenDataSourceFailsTest.java` — registers a `HealthIndicator` bean (`Health.down().build()`) via `@Import` of a `@TestConfiguration`; asserts the composite resolves to 503 + `{"status":"DOWN"}` and still omits contributor details. Uses Spring Boot 4's new `org.springframework.boot.health.contributor.*` API (the actuator health module was split out from `spring-boot-actuator` in 4.x).

ArchUnit (`LayeringArchTest.java`):
- `no_custom_health_indicators_under_infrastructure_web_dev` — prevents a "diagnostic" indicator from sneaking onto the main classpath under the dev package.

### US-15-002 — `CorrelationIdFilter` (MDC + response header + CORS expose)
Created (`infrastructure/web/observability/`):
- `CorrelationIdFilter.java` — `OncePerRequestFilter`. Reads `X-Correlation-Id` if it matches `^[A-Za-z0-9_.:-]{1,128}$` (broad enough for AWS X-Ray, OpenTelemetry hex segments); otherwise generates `UUID.randomUUID().toString()`. Sets `MDC.put("correlationId", ...)` and `response.setHeader("X-Correlation-Id", ...)` BEFORE `chain.doFilter(...)`; clears MDC in a `finally` block (Tomcat reuses worker threads).

Modified:
- `infrastructure/web/security/SpringSecurityConfig.java` — filter registered with `addFilterBefore(correlationIdFilter, RateLimitFilter.class)`. CORS configuration: added `X-Correlation-Id` to `setAllowedHeaders(...)` and `setExposedHeaders(...)`; exposed-headers list kept alphabetical (`Retry-After, X-Correlation-Id`) so the US-14-004 CORS contract test's containment assertions stay stable.

Tests:
- `CorrelationIdFilterTest.java` — 7 unit scenarios: well-formed inbound echoed; malformed silently regenerated; absent generates UUID v4; MDC cleared even when downstream throws; empty string regenerated; max-length (128) accepted; over-max (129) regenerated.
- `CorrelationIdFilterIntegrationTest.java` — 7 integration scenarios: absent inbound → UUID v4 in response header; well-formed inbound echoed verbatim; malformed silently regenerated; 429 response carries `X-Correlation-Id` (pins filter ordering BEFORE `RateLimitFilter`); CORS preflight echoes `X-Correlation-Id` in `Access-Control-Allow-Headers`; allowed-origin simple GET exposes `X-Correlation-Id` in `Access-Control-Expose-Headers`; two consecutive requests get distinct UUIDs.

ArchUnit (`LayeringArchTest.java`):
- `correlation_id_filter_lives_only_under_infrastructure_web_observability` — pins the canonical location.

### US-15-003 — JSON Logback encoder + per-package log-level smoke test
Modified:
- `pom.xml` — added `logstash-logback-encoder` 7.4 via `<logstash-logback-encoder.version>` property.
- `src/main/resources/logback-spring.xml` — replaced the plain `PatternLayout` `<encoder>` with `LoggingEventCompositeJsonEncoder`. Providers: `timestamp` (ISO-8601 with tz), `logLevel`, `threadName`, `loggerName`, a nested `<pattern>` for `application` (from `spring.application.name`), `message` (routed through `%redactedMsg` — REQ-SEC-004 preserved by construction), `correlationId` (from `%mdc{correlationId:-}` so non-request scopes emit empty string), and `<stackTrace>` (root-cause-first, max 4096 chars).

Tests (`infrastructure/web/observability/`):
- `JsonLoggingEncoderIntegrationTest.java` — 6 scenarios on a sibling `OutputStreamAppender` writing into a `ByteArrayOutputStream` (Spring Boot's `LoggingSystem` applies `logback-spring.xml`; the harness discovers the production encoder by `instanceof LoggingEventCompositeJsonEncoder` rather than by name). Asserts: required JSON fields; empty MDC → `"correlationId":""`; populated MDC propagates; exception → `stackTrace` field present; Bearer-JWT substring redacted to `***`; each event is one valid JSON object per line.
- `PerPackageLogLevelSmokeIntegrationTest.java` — 2 scenarios with `@TestPropertySource(properties = "logging.level.com.cognizant.emk.multiagent.application.shared=DEBUG")`. Confirms `log.debug(...)` is captured at level DEBUG (property override applied to Logback via Spring's `LoggingSystem`); `log.info(...)` still passes through.

ArchUnit (`LayeringArchTest.java`):
- `observability_classes_do_not_depend_on_actuator_packages` — forecloses a regression where the filter or encoder accidentally depends on `org.springframework.boot.actuate..` / `org.springframework.boot.health..`. The observability layer must work on a packaged JAR independently of Actuator's classpath.

### US-15-004 — Sensitive-data redaction regression integration test
Created (`src/test/java/.../infrastructure/web/observability/`):
- `RedactionProbeController.java` — test-only `@RestController` with four endpoints (`/_redaction_probe/log-bearer`, `/log-bcrypt`, `/log-jwt`, `/log-clean`); each emits one INFO log statement carrying a deliberately-sensitive substring or a clean control message. Lives on the test classpath only (US-CR1-002 convention).
- `SensitiveDataRedactionIntegrationTest.java` — 6 scenarios: drains a sibling `OutputStreamAppender` whose encoder is the production `LoggingEventCompositeJsonEncoder`; asserts no captured JSON line contains the raw `Bearer eyJ…`, `$2b$12$…`, or `eyJ…eyJ…` substrings; clean message passes through unchanged; failed-login path with a sensitive `Authorization` header is also redacted; every captured line is valid JSON after redaction.

ArchUnit (`LayeringArchTest.java`):
- `no_rest_controllers_live_under_infrastructure_web_observability_on_main_classpath` — sibling of the existing `..web.dev..` rule; locks the convention that the redaction probe stays in `src/test/java`.

### Bookkeeping
- `backend/backlog/EPIC-15-US.md` — all 4 stories flipped Draft → Done.
- `backend/backlog/US-STATUS.md` — EPIC-15 section + aggregate row updated to **108 total, 0 Draft, 108 Done**.
- `CLAUDE.md` — added "EPIC-15 has been implemented" status line.

---

## 2026-06-19 — EPIC-14 / US-14-001 → US-14-005 — Cross-cutting API concerns (errors, paging, CORS)

Closes EPIC-14. Lands the two missing exception types from design §9.1 (`UseCaseExecutionException`, `DatabaseAccessException`) plus three cross-cutting contract regression tests (openapi-code parity, CORS preflight + exposed headers, pagination envelope). Targeted runs all green: **50 fast tests (US-14-001/002/003) + 6 CORS + 10 pagination + 23 EPIC-13 regression tests**, 0 failures.

### US-14-001 — `UseCaseExecutionException` + 500 handler branch
Created (`application/shared/`):
- `UseCaseExecutionException.java` — `public final class extends RuntimeException`, two-arg `(String, Throwable)` constructor. Application-layer escape hatch for unrecoverable orchestration failures; surfaces as 500 `INTERNAL_ERROR`. The class is `final` to prevent per-context copies. No `{@link}` to the infra `ExternalServiceException` to keep application layer Spring-free per hexagonal layering.

Modified (`infrastructure/web/error/GlobalExceptionHandler.java`):
- New `@ExceptionHandler(UseCaseExecutionException.class)` between the 502 branches and the catch-all `Throwable` handler. Logs the cause's class name at `ERROR` (never the cause's message — REQ-SEC-004); body is the standard `INTERNAL_ERROR` envelope.

Tests:
- `application/shared/UseCaseExecutionExceptionTest.java` — 2 cases: constructor stores `(message, cause)`; class is `final` (asserted via reflection).
- `GlobalExceptionHandlerTest.java` — 2 new cases (`handleUseCaseExecution_withCause_returns500_andLogsCauseClass`, `..._withoutCause_returns500_andLogs`) using a Logback `ListAppender` to confirm the cause class name appears on the ERROR line and the response body never exposes it.

ArchUnit (`LayeringArchTest.java`):
- `use_case_execution_exception_lives_only_under_application_shared` — pins the canonical location.
- `application_does_not_import_spring_dao` — pre-emptive guard for US-14-002.

### US-14-002 — `DatabaseAccessException` + `JpaAccess` helper
Created (`infrastructure/error/`):
- `DatabaseAccessException.java` — `public class extends RuntimeException`, two-arg constructor. Wraps Spring's `DataAccessException` at the persistence-adapter boundary; mapped to 500 `INTERNAL_ERROR`.

Created (`infrastructure/persistence/adapter/`):
- `JpaAccess.java` — `public final class` with private constructor + two `static run(...)` overloads (`Supplier<T>` and `Runnable`). Catches `org.springframework.dao.DataAccessException` and rethrows it wrapped as `DatabaseAccessException(operation + " failed", cause)`. The throwing constructor (`throw new AssertionError("not instantiable")`) prevents reflective instantiation.

Modified (`infrastructure/persistence/adapter/RateLimitConfigRepositoryAdapter.java`):
- Canary adoption: `load()` and `save(...)` now bracket their JPA calls in `JpaAccess.run("rate_limit_config.load", ...)` / `("rate_limit_config.save", ...)`. The `IllegalStateException("...V003 did not apply")` is thrown OUTSIDE the wrapper — startup invariant, not a DB failure.

Modified (`infrastructure/web/error/GlobalExceptionHandler.java`):
- New `@ExceptionHandler(DatabaseAccessException.class)` between the use-case-execution branch and the catch-all. Logs `class name` of the cause or `"no-cause"` sentinel; body is the standard `INTERNAL_ERROR` envelope.

Modified (`infrastructure/error/package-info.java`):
- Updated Javadoc to document the two distinct families housed here (`ExternalServiceException` family → 502, `DatabaseAccessException` → 500, `RateLimitedException` → 429).

Tests:
- `persistence/RateLimitConfigRepositoryAdapterWrapTest.java` — Mockito-based: `wraps_DataAccessException_thrown_from_findById_on_load` confirms `DataIntegrityViolationException` → `DatabaseAccessException` with the original as cause; `IllegalStateException_for_missing_seed_row_is_not_wrapped` confirms the V003-sentinel path stays as `IllegalStateException`.
- `GlobalExceptionHandlerTest.java` — 2 new cases (`handleDatabaseAccess_returns500_andLogsCauseClass`, `..._withoutCause_logs_no_cause_sentinel`).

ArchUnit:
- `database_access_exception_lives_only_under_infrastructure_error` — pins canonical location.
- `application_does_not_import_spring_dao` (also covers US-14-001 layering).

### US-14-003 — OpenAPI ↔ `ProblemDetails.code` parity regression test
Created (`infrastructure/web/error/`):
- `ProblemDetailsOpenApiContractTest.java` — pure-Java test (no Spring context, no DB). Reads `openapi.yaml` from the workspace root via SnakeYAML (already on classpath via Spring Boot). Scans `GlobalExceptionHandler.java` source with two regexes: `body(HttpStatus.X, "CODE", ...)` and `ProblemDetails.of("CODE", ...)`. Four assertions: (1) handler emits no code outside the openapi enum, (2) openapi enum has no code unhandled, (3) every `code:` value in any `application/problem+json` example body is in the enum, (4) sanity-runnable check that both sides are non-empty (defends against a future path-resolution regression making both sides empty and trivially passing).

### US-14-004 — CORS preflight + exposed-headers regression integration test
Created (`infrastructure/web/security/`):
- `CorsConfigurationIntegrationTest.java` — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev") @TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173")`. Six scenarios: preflight succeeds for allowed origin (Allow-Origin + Allow-Credentials + Max-Age=3600 + Allow-Methods contains POST + Allow-Headers contains Authorization/Content-Type); preflight rejected for disallowed origin (no Allow-Origin header); actual GET echoes Origin; 429 carries Allow-Origin AND `Access-Control-Expose-Headers` contains `Retry-After`; preflight not rate-limited (CorsFilter short-circuits OPTIONS before the bucket); `X-Client-Id` + `X-Api-Key` allowed in preflight (`REQ-AUTH-001`). Uses the `Bucket4jRateLimitGate.onRateLimitConfigChanged` seam to tighten the bucket without a virtual `TimeMeter`.

### US-14-005 — Pagination contract regression test
Created (`infrastructure/web/pagination/`):
- `PaginationContractIntegrationTest.java` — `@SpringBootTest` against `GET /api/v1/agents` with a 30-agent fixture for a fresh standard user. Ten scenarios: default `pageSize=20`; explicit `pageSize=5` honored; `pageSize=0` / `pageSize=101` / `pageSize=-1` all return 400 `VALIDATION_ERROR` with `errors[0].field == "pageSize"`; cursor round-trip yields no duplicates / gaps; cursor is opaque (no cleartext ISO timestamp or UUID in the base64url payload); malformed-cursor 400; final empty page omits `nextCursor`; envelope key set is exactly `{items, pageSize, nextCursor}` on a non-final page and exactly `{items, pageSize}` on the final page. Rate-limit bucket rebuilt generously per test (`onRateLimitConfigChanged((1000, 10000))`) so the default `(10, 50)` ceiling cannot make this test flaky.

### Bookkeeping
- `backend/backlog/EPIC-14-US.md` — all five stories flipped from `Draft` to `Done`.
- `backend/backlog/US-STATUS.md` — EPIC-14 row updated; aggregate now **104 / 0 Draft / 104 Done**.

### Local-environment note
The two `@SpringBootTest` integration tests above require the local PostgreSQL `postgres` user password (per `SPECS.md`: `olinka`). Run with `$env:TEST_DB_PASSWORD = 'olinka'` (PowerShell) or the equivalent for the shell in use. The unit tests (US-14-001/002 unit assertions, US-14-003 parity test) have no DB dependency.

---

## 2026-06-19 — EPIC-13 / US-13-004 + US-13-005 + US-13-006 + US-13-007 — Bucket4j gate, filter, admin endpoints, end-to-end test

Closes EPIC-13. Lands the runtime side of global rate limiting: the Bucket4j adapter, the outermost servlet filter, the admin REST endpoints under `/admin/rate-limit`, and the end-to-end regression test. Full backend suite: **870 tests, 0 failures, 0 errors**.

### US-13-004 — `RateLimitGate` port + `Bucket4jRateLimitGate` adapter
Created (`application/ratelimit/`):
- `RateLimitGate.java` — port with sealed `TryAcquireResult` (`Allowed` / `Denied(int retryAfterSeconds)`). `Denied` constructor enforces `retryAfterSeconds >= 1`.

Created (`infrastructure/ratelimit/`):
- `Bucket4jRateLimitGate.java` — `@Component` implementing both `RateLimitGate` and `RateLimitConfigChangeListener` (US-13-003 listener seam). Two stacked Bucket4j bandwidths (`perMinute` / 1 min, `perHour` / 1 hour) in a single `Bucket`. `volatile` reference + `synchronized` rebuild so the listener swaps the bucket atomically. Built eagerly on `ApplicationReadyEvent`; defense-in-depth lazy fallback for tests that bypass the event lifecycle. `tryAcquire()` uses `tryConsumeAndReturnRemaining(1)` so the probe's `getNanosToWaitForRefill()` reflects the most-restrictive bandwidth — exactly what `Retry-After` should carry. Retry-After is ceil'd to seconds with a floor of 1. Public `withCustomTimeMeter(...)` factory unlocks a virtualized `TimeMeter` for integration tests; package-private two-arg constructor backs the unit tests. `@Autowired` on the production constructor disambiguates from the two-arg test constructor (same pattern as `JjwtTokenServiceAdapter`).
- `package-info.java` — documents the Bucket4j scoping.

Created (`infrastructure/error/`):
- `RateLimitedException.java` — runtime exception carrying `retryAfterSeconds >= 1`. Mapped to 429 by `GlobalExceptionHandler`.

Tests (`src/test/.../infrastructure/ratelimit/`):
- `Bucket4jRateLimitGateTest.java` — 7 cases driven by a `VirtualTimeMeter` (in-test `TimeMeter` implementation): under-limit allows, per-minute boundary, per-hour boundary, listener rebuild after exhaustion, Retry-After floor of 1, clock-advance refill, lazy-init fallback.

ArchUnit (`src/test/.../arch/LayeringArchTest.java`):
- New rule `bucket4j_imports_only_in_infrastructure_ratelimit` — `io.github.bucket4j.*` MUST NOT leak outside the adapter package.

### US-13-005 — `RateLimitFilter` + Spring Security wiring + 429 mapping
Created (`infrastructure/web/ratelimit/`):
- `RateLimitFilter.java` — `@Component` extending `OncePerRequestFilter`. Calls `gate.tryAcquire()`; on `Denied`, throws `RateLimitedException` through the shared `HandlerExceptionResolver` so the `@RestControllerAdvice` writes the 429 envelope (same pattern as `JwtAuthenticationFilter` does for 401). `shouldNotFilter` returns true when the request URI starts with `/actuator` so the operator health probe is never throttled (REQ-OBS-003).

Modified:
- `infrastructure/web/security/SpringSecurityConfig.java` — registered `RateLimitFilter` via `.addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)`. Updated Javadoc to document the new outermost filter. Unauthenticated traffic (login, malformed Authorization) now counts toward the global bucket per REQ-RL-003.
- `infrastructure/web/error/GlobalExceptionHandler.java` — added `@ExceptionHandler(RateLimitedException.class)` returning 429 + `Retry-After: <int>` + `application/problem+json` body matching the openapi `RateLimited` example (code `RATE_LIMITED`, title "Too many requests").

Tests:
- `RateLimitFilterTest.java` — 4 cases: allowed proceeds; denied calls the resolver with `RateLimitedException(42)` and doesn't chain; `/actuator/health` and `/actuator/info` both skipped without consulting the gate.
- `GlobalExceptionHandlerTest.java` — added `rate_limited_exception_maps_to_429_with_retry_after_header` (envelope assertions + `Retry-After: 7`).

### US-13-006 — Admin REST endpoints
Created (`infrastructure/web/ratelimit/`):
- `RateLimitAdminController.java` — `@RestController` with class-level `@PreAuthorize("hasRole('ADMIN')")` (defense in depth on top of the URL-level rule). `GET /admin/rate-limit` and `PUT /admin/rate-limit`. No class-level `@RequestMapping` — the `/api/v1` prefix is applied centrally per REQ-API-006.
- `RateLimitConfigRequest.java` — record `(@NotNull @Min(1) Integer perMinute, @NotNull @Min(1) Integer perHour)`. Bean Validation catches bad input before the controller body runs; the domain re-validates.
- `RateLimitConfigResponse.java` — record matching openapi `RateLimitConfig` (UUID `updatedBy` nullable).
- `RateLimitConfigResponseMapper.java` — package-private static mapper. Domain `Optional<UserId>` becomes nullable `UUID` via `.map(UserId::value).orElse(null)`.

Test (`src/test/.../infrastructure/web/ratelimit/`):
- `RateLimitAdminControllerIntegrationTest.java` — 8 cases: GET as admin / STANDARD / unauthenticated / SYSTEM API-key (verifies 200/403/401/403 respectively); PUT as admin happy path (counters update + `updatedBy=admin.id`, subsequent GET sees same); PUT zero `perMinute` → 400 `VALIDATION_ERROR` with field `perMinute`; PUT empty body → 400 (Bean Validation `@NotNull`); PUT as STANDARD → 403.

### US-13-007 — End-to-end integration test
Created (`src/test/.../infrastructure/web/ratelimit/`):
- `RateLimitProbeController.java` — test-classpath-only `@RestController` mounted at `/_rl_probe` (full path `/api/v1/_rl_probe`). Per project convention from US-CR1-002, dev/probe controllers live in `src/test/java` only.
- `RateLimitFilterIntegrationTest.java` — boots the full Spring context, swaps the production `Bucket4jRateLimitGate` for one constructed via `Bucket4jRateLimitGate.withCustomTimeMeter(...)` with a `VirtualTimeMeter` (`@TestConfiguration` + `@Primary`). Six scenarios: (1) per-minute boundary eviction with clock advance; (2) per-hour boundary eviction; (3) 429 envelope matches openapi `RateLimited` example; (4) unauthenticated traffic counts (verifies filter is outermost); (5) live admin PUT takes effect on the next request (uses the full REST PUT path so the listener seam from US-13-003 is exercised end-to-end); (6) `/actuator/health` excluded (response is NOT 429 — endpoint itself is provided by EPIC-15 which is still Draft).

Test scaffolding:
- `src/test/resources/db/migration-test/V900__bump_rate_limit_for_tests.sql` — test-only override that bumps the V003 seed to `per_minute=100000`, `per_hour=1000000`. The 10 req/min production default would otherwise deny most existing tests because Spring Test caches contexts and the in-JVM bucket is shared across requests in the same context.
- `src/test/resources/application.yaml` — `spring.flyway.locations` extended to `classpath:db/migration,classpath:db/migration-test`. Tests that need the production seed override the locations back via `@DynamicPropertySource`.
- `src/test/.../persistence/SeedMigrationsTest.java`, `RateLimitConfigRepositoryAdapterIntegrationTest.java`, `RepositoriesContextTest.java` — `@DynamicPropertySource` overrides `spring.flyway.locations` to drop the migration-test path so they observe the production V003 seed (10, 50).
- `infrastructure/web/ratelimit/RateLimitAdminControllerIntegrationTest.java` — `get_as_admin_*` renamed to acknowledge the bumped seed values (still asserts `updatedBy=null` — the load-bearing assertion).

### Story-spec deviations worth flagging
- **Test seed bump via migration-test path** — the EPIC-13 spec didn't anticipate that a global rate limiter would interfere with the entire integration suite. The fix is surgical: a test-classpath-only V900 migration raises the seed for all tests except the three that explicitly assert the production (10, 50) defaults. The production V003 seed is unchanged. Documented in `src/test/resources/db/migration-test/V900__bump_rate_limit_for_tests.sql` and in the test `application.yaml`.
- **Bucket rebuild discards remaining tokens** — flagged in `DESIGN-CHOICES.md`. Bucket4j's `replaceConfiguration` API exists but adds complexity (`TokensInheritanceStrategy` modes); for the v1 sizing the rebuild-from-full approach is harmless.
- **Filter-to-handler bridge** — flagged in `DESIGN-CHOICES.md`. Option A (HandlerExceptionResolver bridge, the same one used by `JwtAuthenticationFilter`) over Option B (filter writes the body directly) keeps the 429 envelope produced by a single code path.
- **Listener seam swallows exceptions** — flagged in `DESIGN-CHOICES.md`. WARN-and-continue; the row is already committed, rolling back because the cache failed to refresh would be confusing for the admin.
- **`/actuator/**` skipped via `shouldNotFilter`** — flagged in `DESIGN-CHOICES.md`. Monitoring traffic must not starve real users.

### Local test results
- New unit tests (Bucket4jGate + Filter + ExceptionHandler delta): all green.
- New integration tests (Bucket4j gate Postgres / admin controller / end-to-end filter): all green with `TEST_DB_PASSWORD=olinka`.
- Full backend suite: **870 tests, 0 failures, 0 errors**.

### Backlog state
EPIC-13 is **Done** (all 7 user stories merged). Aggregate: **99/99 stories Done**. Remaining EPICs: EPIC-14 (cross-cutting concerns), EPIC-15 (observability), EPIC-16 (build/deploy).

---

## 2026-06-19 — EPIC-13 / US-13-001 + US-13-002 + US-13-003 — Rate-limit domain, JPA adapter, and application use cases

Lands the first three stories of EPIC-13: Bucket4j on the classpath, the `RateLimitConfig` domain aggregate + repository port, the JPA adapter on top of the EPIC-02 single-row `rate_limit_config` table, and the two admin-facing use cases (`GetRateLimitConfigUseCase`, `UpdateRateLimitConfigUseCase`) bridged to the future bucket adapter via a `RateLimitConfigChangeListener` seam. No filter / no admin endpoints yet — those are US-13-004 → US-13-006.

### US-13-001 — Bucket4j + domain
Modified:
- `pom.xml` — `<bucket4j.version>8.10.1</bucket4j.version>` + `com.bucket4j:bucket4j-core` dependency. Core only — no JCache/Hazelcast/Redis transitive baggage.

Created (`domain/ratelimit/`):
- `RateLimitConfig.java` — record `(int perMinute, int perHour, OffsetDateTime updatedAt, Optional<UserId> updatedBy)`. Compact constructor enforces `>= 1` on both counters via `ValidationException` (with `"perMinute"` / `"perHour"` field names so the EPIC-14 problem-details mapper can populate `errors[]`). No numeric defaults — the runtime reads the V003 seed.
- `RateLimitConfigRepository.java` — port. `load()` throws `IllegalStateException` (infra-error) when the seed row is missing — operators must see it loudly. `save(updated, updatedBy, now)` returns the round-tripped aggregate.
- `package-info.java` — updated Javadoc referencing US-13-001/002/003.

Test (`src/test/.../domain/ratelimit/`):
- `RateLimitConfigTest.java` — 10 cases covering minimum/seed values, zero/negative rejection on both counters with field-name assertions, null `updatedAt`, null `Optional`, empty `Optional`, present `Optional`.

### US-13-002 — JPA adapter + Postgres integration test
Created (`infrastructure/persistence/adapter/`):
- `RateLimitConfigRepositoryAdapter.java` — `@Component` implementing `RateLimitConfigRepository`. Hydrates row `id=1` and mutates in place (the schema constrains `id = 1` so an accidental insert would fail loudly). `save(...)` uses `userJpaRepository.getReferenceById(...)` to attach the `updated_by` FK without an extra user-row read. Domain `Optional<UserId>` ↔ JPA `UserJpa.id` mapping is private to the adapter file. `load()` / `save()` are `@Transactional(readOnly=true)` / `@Transactional` respectively.

Test (`src/test/.../persistence/`):
- `RateLimitConfigRepositoryAdapterIntegrationTest.java` — extends `PostgresIntegrationTest`. 3 cases: seeded-row load returns 10/50 with empty `updatedBy`; save-then-reload round-trips counters and stamps `updatedBy = bootstrap admin id`; row-missing triggers `IllegalStateException` (with seed restore for hygiene).

### US-13-003 — Application use cases + listener seam
Created (`application/ratelimit/`):
- `GetRateLimitConfigUseCase.java` — interface (single `load()` method).
- `GetRateLimitConfigService.java` — `@Service` delegating to the repository.
- `UpdateRateLimitConfigUseCase.java` — interface (single `update(...)` method).
- `UpdateRateLimitConfigCommand.java` — record `(int perMinute, int perHour, UserId admin)` validating both counters via `ValidationException` and rejecting null `admin`. Defense in depth on top of the future controller's `@Min(1)` and the domain record's checks.
- `UpdateRateLimitConfigService.java` — `@Service`, `@Transactional`. Reads `now` from the injected `Clock` bean (US-CR1-003 pattern). Saves the requested config, then fires the listener AFTER the save. Listener exceptions are logged at WARN and swallowed — the row is already committed, and rolling back because the cache failed to refresh would confuse the admin.
- `RateLimitConfigChangeListener.java` — port. The future `Bucket4jRateLimitGate` (US-13-004) implements it. Injected as `List<>` so the application service boots cleanly while US-13-004 is unimplemented; the bucket adapter joins the list once it lands.
- `package-info.java` — updated Javadoc.

Tests (`src/test/.../application/ratelimit/`):
- `GetRateLimitConfigServiceTest.java` — delegation verified.
- `UpdateRateLimitConfigCommandTest.java` — 6 cases: valid construction; zero/negative rejection on both counters; null `admin`.
- `UpdateRateLimitConfigServiceTest.java` — 3 cases: happy path (canonicalized `OffsetDateTime` from the injected `Clock`, listener invoked, persisted result returned); listener throws → WARN log captured via Logback `ListAppender`, no propagation, persisted result still returned; zero listeners registered → completes cleanly.

### Local test results
- New unit tests (`RateLimitConfigTest`, `UpdateRateLimitConfigCommandTest`, `UpdateRateLimitConfigServiceTest`, `GetRateLimitConfigServiceTest`): **20 tests, 0 failures, 0 errors**.
- New Postgres integration test (`RateLimitConfigRepositoryAdapterIntegrationTest`): **3 tests, 0 failures, 0 errors** (with `TEST_DB_PASSWORD=olinka`).
- `LayeringArchTest` (hexagonal guard): **10 tests, 0 failures** — the new classes land in the correct layers; the `domain.ratelimit` package was already registered as a known bounded context.

### Backlog state
EPIC-13 is partially landed: US-13-001/002/003 are **Done**, US-13-004 → US-13-007 remain `Draft`. Aggregate: **95/99 stories Done**.

---

## 2026-06-02 — EPIC-12 / US-12-004 — End-to-end delegation integration test

Closes EPIC-12. Verifies every load-bearing invariant of REQ-AGT-011 / REQ-AGT-013 / REQ-AGT-015 against the real Spring context: SSE wire shape, persistence side effects, runtime team-membership rejection, sub-agent error isolation, and log sanitization. 7 cases / all green.

Created:
- `src/test/java/.../infrastructure/web/conversation/SendMessageDelegationIntegrationTest.java` — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev")`. `@MockitoBean LlmChatClient` replaces the real adapter; the mock's `stream(...)` answer invokes `delegateTool.delegate(...)` inline to simulate the Spring AI tool-callback loop. Reuses `SseFrameParser` from US-11-007. Cases:
  1. **`delegate` descriptor present for parent with team** — captures `ChatRequest.tools` via ArgumentCaptor; asserts `delegate` is listed.
  2. **`delegate` descriptor absent for leaf agent** — the runtime guarantee on top of REQ-AGT-013.
  3. **Tool-loop happy path** — parent stream → tool invocation → sub-agent sync call → parent resumes with text incorporating sub-result. Asserts SSE frames are exactly `started + delta + completed`, no frame mentions "delegate" / "tool_call" (REQ-AGT-015 user only sees parent's aggregate answer), parent has exactly 2 DB messages, sub-agent has zero, sub-agent's `ChatRequest` has M's system prompt + only the task as history (no parent context), and `ownerUserId` is the parent's owner.
  4. **Runtime team-membership rejection** — LLM "emits" a tool call to a non-team agent; `InvalidDelegationTargetException` is wrapped as `LlmUnavailableException` in the mock answer (simulating Spring AI's framework). SSE ends with `error LLM_UNAVAILABLE`, USER persisted, ASSISTANT not, sub-agent LLM call never made.
  5. **Sub-agent LLM failure isolation** — `llmChatClient.call(...)` throws `LlmUnavailableException`; parent stream ends with `error LLM_UNAVAILABLE`, USER persisted, ASSISTANT not. Detail message sanitized — no provider classification leakage.
  6. **Service-level runtime single-level rejection** — force-saves a member with a non-empty team (bypassing EPIC-06's write-time validators); calls `DelegationService.delegate(...)` directly. Asserts `InvalidDelegationTargetException` with `"non-empty team"`. Verifies the runtime defense-in-depth catches what the write path could in principle miss.
  7. **Log sanitization** — Logback `ListAppender` on the root logger; asserts no log message contains the test `OPENAI_API_KEY` fragment (`test-openai-key`).

### Story-spec deviations worth flagging

- **WireMock → mocked `LlmChatClient`** — same reason US-11-007 took the same path. The OpenAI autoconfig is excluded across the suite (Spring AI 1.1.0 / Spring 7 binary incompat); a `@MockitoBean LlmChatClient` replaces the real adapter directly. This is a tighter seam than mocking `ChatModel` — appropriate here because EPIC-12's assertions are about the application-layer `ChatRequest.tools` content, not the adapter's translation logic.
- **Tool-loop simulated by the mock's `Answer`** — the production `OpenAiChatClientAdapter` does NOT yet run a Spring AI `ChatClient` with tool-callback dispatch (US-09-005's Javadoc documents tool wiring is queued). Our mock's `stream(...)` answer invokes `delegateTool.delegate(...)` inline to model what Spring AI would do once that wiring lands. Net effect on EPIC-12 coverage: every invariant of REQ-AGT-011 / 013 / 015 is exercised against real Spring beans, but the literal HTTP-level "the OpenAI server emits a tool-call SSE frame" path is not. A follow-up that wires `ChatClient` into the adapter would convert this test to a true WireMock-driven end-to-end test without changing the assertions.
- **`@MockitoBean` not `@TestConfiguration`** — Spring Boot 4 introduced `@MockitoBean` as the modern replacement for `@MockBean`. It cleanly overrides the bean in the application context without a nested `@Configuration` class, matching the simpler integration-test surface EPIC-12 needs.

### DB integration tests

Same caveat as the EPIC-12 / US-12-001+002+003 entry: the local PostgreSQL `postgres` user password must be set via the `TEST_DB_PASSWORD` env var (`olinka` per CLAUDE.md).

### Test suite

Full backend suite: **817 tests, 0 failures, 0 errors** (+7 vs US-12-003).

### Backlog state

EPIC-12 is **Done** (all 4 user stories merged). Aggregate: **92/92 stories Done**. Remaining EPICs: EPIC-13 (rate limiting), EPIC-14 (cross-cutting concerns), EPIC-15 (observability), EPIC-16 (build/deploy).

---

## 2026-06-02 — EPIC-12 / US-12-002 + US-12-003 — Delegation runtime: `DelegationServiceImpl` + Spring AI `@Tool` wiring

Closes the runtime side of EPIC-12: parent agents with a non-empty team get the `delegate` tool descriptor in their `ChatRequest.tools`; when the LLM emits a `delegate(...)` tool call, the request is bridged through a Spring AI tool callback into `DelegationServiceImpl`, which runs a synchronous sub-agent turn against `LlmChatClient.call(...)` and returns the text back to the parent's stream. Zero conversation persistence on the sub-agent side — guaranteed structurally by an ArchUnit rule + reflection check.

### US-12-002 — `DelegationServiceImpl`
Created (`application/chat/`):
- `DelegationServiceImpl.java` — `@Service` implementing `DelegationService`. Algorithm: parent re-fetch (REQ-AGT-014 live) → runtime team-membership check (REQ-AGT-013 runtime side) → target resolution → cross-owner check (REQ-AGT-012 runtime side) → single-level rule re-check → minimal target-only `ChatRequest` (NO parent history per REQ-AGT-015) → sync `LlmChatClient.call(...)` → `DelegationResult`. Tool/MCP names resolved against the same catalogs `ChatRequestBuilder` uses; drift surfaces as `AgentConfigurationDriftException`. `LlmChatClient` injected as `Optional<>` mirroring `SendMessageService` so test profiles boot when no provider is configured.

Added rule:
- `LayeringArchTest.delegation_service_impl_does_not_depend_on_conversation_repository` — load-bearing structural guarantee that `DelegationServiceImpl` has no path to persist sub-agent turns (REQ-AGT-015).

Tests:
- `DelegationServiceImplTest` (10) — happy path; tools/MCPs catalog resolution; filesystem MCP per-parent-owner scoping; parent vanished; target not in team; target with non-empty team (asserts nested member id NOT in exception message — sanitized); target owned by different user; target lookup failure; model-override precedence; reflection check that no field of type `ConversationRepository` exists (mirror of the ArchUnit rule).

### US-12-003 — `DelegateTool` Spring AI bridge + `ChatRequestBuilder` wiring

Created (`application/chat/`):
- `ChatTurnContext.java` — `@Component @Scope("request", proxyMode=TARGET_CLASS)` carrying the in-flight turn's parent agent id and parent owner. Throws `IllegalStateException` if accessed without `enter()`. Populated by `SendMessageService` immediately before invoking `ChatRequestBuilder.build(...)` and cleared via `Flux.doFinally(...)` on every terminal signal.

Created (`infrastructure/tool/`):
- `DelegateTool.java` — `@Component` bean with a single `@Tool(name = DelegationService.TOOL_NAME, description = DelegationService.TOOL_DESCRIPTION)` method accepting `targetMemberId: String` (UUID) + `task: String`. Reads parent context from `ChatTurnContext`, builds a typed `DelegationCommand`, and forwards to `DelegationService.delegate(...)`. Deliberately NOT annotated with `@ToolGroup` — does not appear in `GET /tools`; it is a special-case bean injected only when the parent agent has a team. Malformed UUIDs raise `IllegalArgumentException`, propagated through Spring AI's tool dispatch into the SSE error frame as `LLM_UNAVAILABLE` 502 (the model emitted a malformed tool call).

Modified:
- `DelegationService.java` — added compile-time `TOOL_NAME` / `TOOL_DESCRIPTION` `String` constants and a `DESCRIPTOR` `ToolDescriptor` constant (single source of truth shared between `DelegateTool`'s `@Tool` annotation and `ChatRequestBuilder`'s tool-list wiring; placed on the application-layer interface so both layers can read it without violating the hexagonal rule).
- `ChatRequestBuilder.java` — appends `DelegationService.DESCRIPTOR` to `ChatRequest.tools` iff the agent's `team` is non-empty. Leaf agents (empty team) never see the descriptor → the LLM cannot call `delegate(...)` for them (runtime guarantee on top of REQ-AGT-013 static rule).
- `SendMessageService.java` — added `ChatTurnContext` constructor injection; populates the context inside the `Mono.fromCallable(...)` that builds the request (only for `UserOwner` — `SystemOwner` cannot reach delegation since `ChatRequestBuilder` rejects SYSTEM owners upstream); clears the context via `Flux.doFinally(...)`.
- `SendMessageServiceTest.java` — added `@Mock ChatTurnContext` field; updated constructor invocation to the new 8-arg shape. All 7 prior cases still green.
- `ChatRequestBuilderTest.java` — added 3 new cases: empty team → no `delegate` descriptor; non-empty team → `delegate` is the only tool; non-empty team + catalog tools → `delegate` appended last.

Tests:
- `DelegateToolTest` (5) — happy path (returns target text + captures parent context in the command); empty `ChatTurnContext` (IllegalStateException before `DelegationService` invoked); malformed UUID; null `targetMemberId`; blank `task` (propagates `ValidationException` from the `DelegationCommand` record).

### DESIGN-CHOICES entries (TBD-3 resolution + structural no-persistence guarantee)

Added to `backend/implementation/DESIGN-CHOICES.md`:
- **TBD-3 resolution** — Spring AI `@Tool` path chosen over server-side marker parsing. Reasons: tool-calling already works through EPIC-09's adapter unchanged; composes with streaming by construction; REQ-CHAT-012 inherits naturally (tool turns transient — never persisted); smaller test surface.
- **`DelegationServiceImpl` has zero conversation-persistence dependencies** — enforced by the new ArchUnit rule + reflection check in `DelegationServiceImplTest`. Future audit-logging needs land as an operator-only log stream, not a `messages` write.

### Story-spec deviations worth flagging

- **`DESCRIPTOR` constant location**: the story spec called for `DelegateTool.DESCRIPTOR` in `infrastructure/tool/`. Hexagonal rule (`application_does_not_depend_on_infrastructure`) would forbid `ChatRequestBuilder` from reading it there. Resolved by hoisting the three constants (`TOOL_NAME`, `TOOL_DESCRIPTION`, `DESCRIPTOR`) onto the `DelegationService` interface in `application/chat/` — the port owns its LLM-facing schema. Both `DelegateTool` (uses the strings as `@Tool` annotation params via compile-time constant references) and `ChatRequestBuilder` (uses the descriptor) read from the same source.
- **`LlmChatClient` injection**: the story spec described a required constructor arg. Switched to `Optional<LlmChatClient>` for the same reason `SendMessageService` did in EPIC-11 — test profiles where Spring AI's OpenAI autoconfig is excluded must still boot the context.

### Test suite

Full backend suite: **810 tests, 0 failures, 0 errors** (+35 vs EPIC-11). The DB integration tests require the local PostgreSQL `postgres` user password to be set via the `TEST_DB_PASSWORD` env var (`olinka` per CLAUDE.md; default of `postgres` does not match the local server).

---

## 2026-06-02 — EPIC-12 / US-12-001 — `DelegationService` port + records + runtime invariant exception

Application-layer port introducing EPIC-12's seam between the Spring AI tool callback (US-12-003) and the future `DelegationServiceImpl` (US-12-002). No implementation, no Spring AI types — pure Java records and a single domain exception, in line with `no_spring_ai_imports_in_application_chat`.

Created:
- `application/chat/DelegationService.java` — port with `delegate(DelegationCommand) → DelegationResult` plus nested records. `DelegationCommand` validates `task` non-blank / ≤1024 chars (field=`task` to surface a per-field problem-detail entry); `DelegationResult` rejects null target id / null text but accepts empty text. Javadoc spells out the four implementation responsibilities and links REQ-AGT-011 / 013 / 015.
- `domain/agent/InvalidDelegationTargetException.java` — `BusinessException` carrying `parentAgentId` + `targetMemberId` accessors and a `reason` string. Message is UUID-only (no agent names / descriptions) for log redaction. Surfaces as 500 via the generic handler during a normal REST call, and as `LLM_UNAVAILABLE` 502 inside the SSE error frame when raised from the Spring AI tool callback (US-12-004).
- `src/test/java/.../application/chat/DelegationCommandTest.java` — 9 cases (happy path, exactly-1024-char task, null parent id / owner / target id, null task, blank task, empty task, >1024 task).
- `src/test/java/.../application/chat/DelegationResultTest.java` — 4 cases (happy path, empty-text accepted, null target id, null text).
- `src/test/java/.../domain/agent/InvalidDelegationTargetExceptionTest.java` — 3 cases (message contains both UUIDs + reason; accessors typed; extends `BusinessException`).

Verification: `mvn -Dtest='DelegationCommandTest,DelegationResultTest,InvalidDelegationTargetExceptionTest,LayeringArchTest' test` → 25 tests green (incl. the 9 ArchUnit rules — `no_spring_ai_imports_in_application_chat` still holds).

Updated:
- `backend/backlog/US-STATUS.md` — US-12-001 → Done; EPIC-12 progress 4/3/0/0/1; aggregate 92/3/0/0/89.
- `backend/backlog/EPIC-12-US.md` — story header + index table flipped to Done.

---

## 2026-06-01 — EPIC-11 / US-11-001..007 — SSE streaming chat (full EPIC)

Implemented all 7 user stories of EPIC-11 in one change set — the SSE streaming chat surface is now complete.

### US-11-001 — Port + sealed `TurnEvent`
Created (`application/chat/`):
- `TurnEvent.java` — sealed `Started` / `Delta` / `Completed` / `Error` records mirroring the openapi SSE frame shapes (design §7.1)
- `SendMessageUseCase.java` — port returning a cold `Flux<TurnEvent>`; `SendMessageCommand` record
- Tests: `TurnEventTest` (9 cases), `SendMessageCommandTest` (5)

### US-11-002 — Memory window assembler
Created:
- `application/chat/MemoryWindowAssembler.java` — `@Service` delegating to `ConversationRepository.findLastN`. Test: `MemoryWindowAssemblerTest` (6)

### US-11-003 — `ChatRequestBuilder`
Created:
- `application/chat/ChatRequestBuilder.java` — re-fetches agent per turn (REQ-AGT-014 live), translates memory window to `ChatMessage`s, resolves tools from `ToolCatalog`, validates MCP names against `McpServerCatalog`, materializes filesystem MCP per-user root via `FilesystemMcpUserScope.resolveUserRoot`. SYSTEM owner throws `IllegalStateException` (unreachable in v1)
- `application/chat/AgentConfigurationDriftException.java` — `RuntimeException` for tool/MCP catalog drift (mapped to 500 via generic handler)
- Test: `ChatRequestBuilderTest` (9 cases: happy path, model fallback, agent-deleted-mid-turn, live mutation observation, filesystem scope, drift exceptions, SYSTEM rejection)

### US-11-004 — `SendMessageService` orchestration
Created:
- `application/chat/SendMessageService.java` — `@Service` implementing the use case. Algorithm:
  - Synchronous prefix inside `@Transactional`: owner check, cap check, USER persist + count bump + title derivation in one tx
  - Reactive tail (cold Flux): emit `Started` first → lazy `Mono.fromCallable` builds `ChatRequest` → `LlmChatClient.stream(...)` mapped to `Delta`s with text accumulated in a `StringBuilder` → `Mono.fromCallable` persists ASSISTANT in a SECOND tx (via `TransactionTemplate` since reactive callback runs outside `@Transactional`) and emits `Completed`
  - Cancellation: `doOnCancel` logs at DEBUG; no persistence on cancel (REQ-STR-002)
  - `LlmChatClient` injected as `Optional<>` — fails fast with `IllegalStateException` if no provider configured (500 INTERNAL_ERROR — operator misconfig, not provider outage)
- Test: `SendMessageServiceTest` (7 cases incl. happy single-chunk, multi-chunk, cap reached, cross-owner, unknown, LLM error mid-stream, LLM error before first chunk; uses StepVerifier for reactive assertions)

### US-11-005 + US-11-006 — REST adapter + cancellation
Created (`infrastructure/web/conversation/`):
- `SendMessageRequest.java` — `@NotBlank @Size(max=1024) String content`
- `SseFrameWriter.java` — translates `TurnEvent`s to SSE frames; elides empty deltas at the wire (§7.1); package-private; private `ObjectMapper` like `CursorCodec`
- `SseErrorTranslator.java` — maps Reactor errors to `ProblemDetails` (`LlmUnavailableException` → 502, `McpServerException` → 502, `NotFoundException` → 404, else 500). Mirrors `GlobalExceptionHandler` mappings byte-for-byte
- Test: `SseFrameWriterTest` (6 cases — happy frame for each event type + empty-delta elision + error frame shape; uses a `CapturingEmitter` subclass)

Modified:
- `infrastructure/web/conversation/ConversationsController.java` — `@PostMapping("/conversations/{id}/messages", produces=text/event-stream, consumes=application/json)`. Subscribes the Flux to an `SseEmitter` (timeout from `app.streaming.emitter-timeout`); `onCompletion` / `onTimeout` / `onError` callbacks all `subscription.dispose()` — cancellation propagates upstream through Reactor to the OpenAI HTTP client (US-11-006 / REQ-STR-003)
- `infrastructure/web/security/SpringSecurityConfig.java` — added `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` so the `AuthorizationFilter` doesn't re-deny the async dispatch after the REQUEST has been authorized (fixes "response already committed" servlet exception on streaming responses)
- `infrastructure/web/error/GlobalExceptionHandler.java` — added `handleNotAcceptable(HttpMediaTypeNotAcceptableException)` returning 406 `NOT_ACCEPTABLE` (relevant for the streaming endpoint's `produces=text/event-stream` negotiation)
- `infrastructure/config/ApplicationProperties.java` — new `Streaming(@NotNull Duration emitterTimeout)` nested record
- `application.yaml` (main + test) — `app.streaming.emitter-timeout: PT10M` / `PT30S`
- `infrastructure/mcp/FilesystemMcpUserScopeAdapterTest.java` — updated inline `new ApplicationProperties(...)` to the new 7-arg shape

### US-11-007 — End-to-end integration test
Created:
- `src/test/java/.../infrastructure/web/conversation/SseFrameParser.java` — test helper splitting an SSE body on `\n\n` and parsing `(event, data-json)` tuples
- `src/test/java/.../infrastructure/web/conversation/SendMessageEndpointIntegrationTest.java` — 13 cases:
  - Streaming (5): happy path 5-frame sequence + DB row count; second-turn title null + count 4; empty delta elided; LLM 5xx mid-stream → `started+delta+error` + ASSISTANT not persisted; LLM 429 at request time → `LLM_UNAVAILABLE` (NOT mapped to our RATE_LIMITED)
  - Sync prefix failures (8): cap 409, content >1024 400, empty content 400, `Accept: application/json` → 406, cross-owner 404, unknown 404, unauthenticated 401, SYSTEM-vs-USER 404
- Uses a `@TestConfiguration` providing a mocked `ChatModel` + an explicit `OpenAiChatClientAdapter` bean (more reliable than `@ConditionalOnBean(ChatModel)` for test-time wiring)

### Story-spec deviations worth flagging
- **WireMock → mocked `ChatModel`**: the story spec called for WireMock against a fake OpenAI URL. The existing EPIC-09 tests already document that Spring AI 1.1.0 / Spring 7 binary incompat excludes the autoconfig, so WireMock wouldn't reach `OpenAiApi`. Switched to the same mocked-`ChatModel` pattern as `OpenAiChatClientAdapterStreamTest` — equivalent coverage, no WireMock dependency for this test.
- **`ChatRequestBuilder` signature**: the story spec included a `Message newUserMessage` parameter (caller appends it after the memory window). The implementation takes only the memory window — `ConversationRepository.findLastN` returns it including the just-persisted user message, so a separate append is redundant. Equivalent LLM view; one fewer parameter.
- **`@ConditionalOnBean(LlmChatClient.class)` on the service**: the story spec proposed this for test isolation. In practice it's unreliable because `@TestConfiguration` registers AFTER the conditional check. Switched to `Optional<LlmChatClient>` injection — bean always exists; `send()` throws `IllegalStateException` if absent. Cleaner failure mode.
- **`ApplicationProperties` injection**: the story spec hand-waved this. Replaced with `@Value("${app.llm.openai.default-model}") String` to keep the application layer free of infrastructure imports (caught by the existing `LayeringArchTest.application_does_not_depend_on_infrastructure` rule).
- **No `MockMvc slice test`**: the story spec asked for a separate `SendMessageEndpointMockMvcTest` for the sync-prefix failures. The integration test (US-11-007) already covers them inline — splitting would duplicate setup.

### Test suite
Full backend suite: **775 tests, 0 failures, 0 errors** (+55 vs EPIC-10).

---

## 2026-06-01 — EPIC-10 / US-10-006 + 007 + 008 + 009 + 010 — Conversations read / edit-title / delete + messages list

Created (`application/chat/`):
- `ListConversationsUseCase` + `ListConversationsService` — pure forwarder; carries the `Optional<AgentId>` filter; unwraps `PageSize` to int before calling the repository port
- `GetConversationUseCase` + `GetConversationService` — owner-check on the loaded conversation; cross-owner / unknown → `ConversationNotFoundException` (404)
- `EditConversationTitleUseCase` + `EditConversationTitleService` — load, owner-verify, `Conversation.withTitle(...)` to bump `updatedAt`, save
- `DeleteConversationUseCase` + `DeleteConversationService` — load, owner-verify, `deleteById` (cascade to `messages` via V001 FK)
- `ListMessagesUseCase` + `ListMessagesService` — verify parent conversation ownership first, then forward to `ConversationRepository.listMessages` (chronological ASC)

Created (`infrastructure/web/conversation/`):
- `UpdateConversationRequest` — record with `@NotBlank @Size(max=32) String title`
- `MessageResponse` — record matching openapi `Message` (id, role-as-string, content, createdAt)
- `MessageResponseMapper` — static `toResponse(Message)`

Modified:
- `infrastructure/web/conversation/ConversationsController.java` — added 5 new endpoints (GET / GET-by-id / PATCH / DELETE / GET-messages) alongside the existing POST. `CursorCodec` injection added. All endpoints use `@AuthenticationPrincipal Principal` (sealed) → `ConversationOwner.from(principal)`. Pagination methods follow the `AgentsController` shape: `cursorCodec.decode(...)` + `PageSize.fromQueryParam(...)` + `PageDto.of(page, codec, mapper)`

Tests created:
- 5 Mockito unit tests under `src/test/java/.../application/chat/`: `ListConversationsServiceTest` (2 cases — forward + PageSize unwrap), `GetConversationServiceTest` (4 — happy + cross-user + USER-vs-SYSTEM-owned + unknown), `EditConversationTitleServiceTest` (3 — happy with bumped `updatedAt` + cross-owner + unknown), `DeleteConversationServiceTest` (3), `ListMessagesServiceTest` (3)
- 5 endpoint integration tests under `src/test/java/.../infrastructure/web/conversation/`: `ListConversationsEndpointIntegrationTest` (10 — empty, owner isolation, ordering, cursor pagination, agent filter incl. unknown / cross-owner → empty, invalid pageSize / cursor → 400, 401), `GetConversationEndpointIntegrationTest` (5 — happy with body shape, unknown 404, cross-owner 404, SYSTEM-vs-USER 404, 401), `PatchConversationEndpointIntegrationTest` (6 — happy + 2× validation 400 + cross-owner 404 + unknown 404 + 401), `DeleteConversationEndpointIntegrationTest` (4 — 204 + cascade through messages via REST path + unknown 404 + cross-owner 404 leaves row + 401), `ListMessagesEndpointIntegrationTest` (7 — empty + 5-message keyset walk across 3 pages in chronological-ASC + cross-owner 404 + unknown 404 + invalid cursor 400 + invalid pageSize 400 + 401)
- `ConversationsEndpointTestSupport` — package-private shared helpers (`seedUser`, `seedAgent`, `seedConversation`, `seedMessage`, `login`, `extract`) consumed by the 5 integration tests above to keep each per-endpoint file lean

Full backend suite: **720 tests, 0 failures, 0 errors** (+47 vs US-10-005).

EPIC-10 is now **complete** (10/10 Done). The non-streaming chat surface is in place; EPIC-11 will add the SSE `POST /conversations/{id}/messages` streaming endpoint on top of these primitives, and EPIC-12 will layer agent-team delegation on top of EPIC-11.

---

## 2026-06-01 — EPIC-10 / US-10-005 — `POST /conversations` + start use case + URL guard

Created:
- `application/chat/StartConversationUseCase.java` — interface + `StartConversationCommand(ConversationOwner, AgentId)` record
- `application/chat/StartConversationService.java` — `@Service`. Algorithm: load agent (404 on miss); verify ownership via sealed `ConversationOwner` (`UserOwner` → must match `agent.ownerId()`, else 404 — REQ-AUTH-008 existence hiding; `SystemOwner` → always 404 in v1 because no agent is SYSTEM-owned, REQ-AUTH-007); persist a fresh `Conversation` with `title=null`, `MessageCount.EMPTY`, `createdAt=updatedAt=clock.instant().atOffset(UTC)`
- `infrastructure/web/conversation/ConversationsController.java` — `@RestController` (no class-level mapping; `/api/v1` applied centrally). `@PostMapping("/conversations")` with `@AuthenticationPrincipal Principal` (sealed type) → `ConversationOwner.from(principal)` dispatches exhaustively
- `infrastructure/web/conversation/CreateConversationRequest.java` — record with `@NotNull UUID agentId`
- `infrastructure/web/conversation/ConversationResponse.java` — record matching openapi `Conversation` schema (`id, agentId, title, messageCount, createdAt, updatedAt`). Owner intentionally NOT exposed. `@JsonInclude(NON_NULL)` elides `title` while still null
- `infrastructure/web/conversation/ConversationResponseMapper.java` — pure-static `toResponse(Conversation)`
- `src/test/java/.../application/chat/StartConversationServiceTest.java` — 5 Mockito unit tests: USER happy path (captured aggregate shape), cross-owner 404 with no save, unknown agent 404 with no save, SYSTEM 404 with existing agent (deterministic v1), SYSTEM 404 with unknown agent
- `src/test/java/.../infrastructure/web/conversation/CreateConversationEndpointIntegrationTest.java` — 7 `@SpringBootTest` MockMvc tests: 201 happy path with DB row inspection (`owner_user_id` populated, `title` key omitted via `NON_NULL`), cross-owner 404, unknown-agent 404, SYSTEM 404 (asserts NOT 403 + zero conversation rows), missing `agentId` body → 400 `VALIDATION_ERROR` with field `agentId`, unauthenticated → 401 `INVALID_CREDENTIALS`, disabled user → login itself 401 (no token issued, so the post path is never reached)
- `src/test/java/.../infrastructure/web/security/ConversationUrlGuardIntegrationTest.java` — 4 tests: unauthenticated POST → 401 (NOT 302), STANDARD JWT admitted (404 from service, NOT 403), ADMIN JWT admitted, SYSTEM API-key admitted (404, NOT 403 — load-bearing absence-of-403 assertion)

Modified:
- `infrastructure/web/security/SpringSecurityConfig.java` — added one new line: `.requestMatchers(conversationsPattern).hasAnyRole("STANDARD", "ADMIN", "SYSTEM")`, placed immediately after the `agentsPattern` line. `ApiKeyAuthenticationFilter` already grants `ROLE_SYSTEM` from US-04-009; no filter change
- `application/chat/package-info.java` — replaced EPIC-10 placeholder with description naming the new use cases
- `infrastructure/web/conversation/package-info.java` — same

Full backend suite: **673 tests, 0 failures, 0 errors** (+16 vs US-10-004).

Story-spec note: the v1 SYSTEM-can't-own-agents contract documentation was already covered by the US-10-002 `DESIGN-CHOICES.md` entry (last paragraph). No new entry added.

---

## 2026-06-01 — EPIC-10 / US-10-004 — `CONVERSATION_FULL` 409 mapping

Modified:
- `infrastructure/web/error/GlobalExceptionHandler.java` — added `@ExceptionHandler(ConversationFullException.class) handleConversationFull(...)` placed at the top of the 409 block (above the three EPIC-06 agent-conflict handlers and the generic `ConflictException` fallback). Returns 409 with `code=CONVERSATION_FULL`, `title="Conversation full"`, static `detail="Conversation has reached the 64-message cap."`. Logs at INFO (not WARN — hitting the cap is a documented user-facing constraint, not an error condition). The conversation id stays in the exception message (logged) and never appears in the response body.
- `src/test/java/.../infrastructure/web/error/GlobalExceptionHandlerTest.java` — added two MockMvc cases plus one `@GetMapping("/throw/conversation-full")` probe endpoint on the existing `TestErrorController`. First case asserts the new subclass-specific shape (`code = CONVERSATION_FULL`, static detail, type URI). Second case re-asserts that a plain `ConflictException` still routes to the generic `CONFLICT` handler — proves Spring's `@ExceptionHandler` subclass-priority dispatch keeps both handlers reachable.

Full backend suite: **657 tests, 0 failures, 0 errors** (+2 vs US-10-003).

Story-spec deviation worth flagging:
- The story spec called for a separate `GlobalExceptionHandlerConversationFullTest` standalone file. The codebase convention is that every prior handler subclass test (US-06-003 agent-conflict, US-08-007 MCP, US-09-003 LLM) ships its cases inside the shared `GlobalExceptionHandlerTest` next to the same `TestErrorController` probe — a standalone file would duplicate the standalone-MockMvc setup with no extra coverage. Followed the project convention rather than the story spec on this one.

---

## 2026-06-01 — EPIC-10 / US-10-002 + US-10-003 — Owner-column split + persistence adapter

Per user direction, shipped together as one change set: `Hibernate ddl-auto=validate` requires `ConversationJpa` to match the post-V005 schema, so the two stories cannot ship independently without leaving the build red.

### US-10-002 — V005 Flyway migration

Created:
- `src/main/resources/db/migration/V005__conversation_owner_split.sql` — drops `conversations.owner_id` + `idx_conversations_owner_created`; adds nullable `owner_user_id` (FK `users.id` on delete cascade) and `owner_client_id` (FK `api_keys.client_id` on delete cascade); adds `ck_conversations_owner_xor` check constraint; adds three new indexes (`idx_conversations_user_created` and `idx_conversations_client_created` partial; `idx_conversations_agent_created`)
- `src/test/java/.../persistence/ConversationOwnerSplitMigrationTest.java` — 9 assertions: V005 in flyway history; legacy column gone; new columns present + nullable; XOR constraint present; new indexes present + legacy index gone; XOR-violation inserts (both populated / neither populated) fail; USER-owned + SYSTEM-owned inserts succeed

Modified:
- `implementation/DESIGN-CHOICES.md` — added EPIC-10 section: two-column XOR vs `owner_kind` discriminator vs seeded-system-user (chose XOR; rationale: schema-level cascade fidelity, no `users.email` pollution, sealed-type-friendly)

### US-10-003 — `ConversationJpa` rework + adapter + mapper

Modified:
- `infrastructure/persistence/entity/ConversationJpa.java` — replaced single `@ManyToOne UserJpa owner` with two nullable `@ManyToOne` associations: `UserJpa ownerUser` (`@JoinColumn(name="owner_user_id")`) and `ApiKeyJpa ownerApiKey` (`@JoinColumn(name="owner_client_id", referencedColumnName="client_id")`); 8-arg canonical constructor
- `infrastructure/persistence/springdata/ConversationJpaRepository.java` — added four `@Query` keyset finders: `findFirstPageByUserOwner` / `findPageAfterByUserOwner` / `findFirstPageByClientOwner` / `findPageAfterByClientOwner` (each carries the optional `agentId` filter via `(:agentId IS NULL OR …)`)
- `infrastructure/persistence/springdata/MessageJpaRepository.java` — added `findFirstPageByConversation` / `findPageAfterByConversation` (chronological ASC) and `findLastNByConversation` (DESC + `LIMIT n` for memory-window)

Created:
- `infrastructure/persistence/mapper/ConversationMapper.java` — `toDomain(ConversationJpa)` exhaustively asserts XOR (defense-in-depth on top of the DB check) and reconstructs the sealed `ConversationOwner`; `toJpa(Conversation, AgentJpa, UserJpa, ApiKeyJpa)` for new rows; `updateMutableFields(ConversationJpa, Conversation)` for in-place updates; `resolveUserRef` / `resolveApiKeyRef` helpers that dispatch on the sealed owner; full `Message` ↔ `MessageJpa` round-trip
- `infrastructure/persistence/adapter/ConversationRepositoryAdapter.java` — `@Component` implementing `ConversationRepository`. `save` is upsert (read-existing then in-place update, else fresh insert with one `getReferenceById` per owner ref); `listByOwner` dispatches on the sealed owner via `instanceof` pattern matching (Java-17 idiom from US-10-001); `findLastN` queries DESC then reverses to ASC; `pageSize` bounded to `[1, 100]` matching `AgentRepositoryAdapter`
- `src/test/java/.../persistence/ConversationRepositoryAdapterIntegrationTest.java` — 16 tests covering: USER- and SYSTEM-owned round-trip with schema-level column inspection; in-place update path; owner-scoped listing with no leak across owners; optional `agentId` filter (including unknown-agent → empty page); keyset pagination; messages append/list (chronological ASC); `findLastN`; FK cascade through `conversationRepository.deleteById` and through user deletion via `owner_user_id`

Modified (test-side cleanup — call sites that wrote the legacy `conversations.owner_id` column):
- `src/test/java/.../persistence/AgentRepositoryAdapterIntegrationTest.java` — `INSERT INTO conversations … owner_id` → `owner_user_id`
- `src/test/java/.../persistence/InitSchemaMigrationTest.java` — same (`messages_role_check` probe still works post-V005)
- `src/test/java/.../persistence/CascadeIntegrationTest.java` — updated `new ConversationJpa(...)` to the 8-arg shape (extra null for the inverse owner)
- `src/test/java/.../infrastructure/web/admin/DeleteUserEndpointIntegrationTest.java` — same INSERT fix
- `src/test/java/.../infrastructure/web/agent/DeleteAgentEndpointIntegrationTest.java` — same INSERT fix

Full backend suite: **655 tests, 0 failures, 0 errors** (was 165 domain + arch only after US-10-001; now includes every prior EPIC's integration suite running against the post-V005 schema with `ddl-auto=validate` green).

Story-spec deviations worth flagging:
- The `DatabaseAccessException` recommended by the story spec for the XOR-inconsistency defensive read check does not exist on the classpath; used `IllegalStateException` instead, since the case is guaranteed-impossible thanks to the DB check constraint (defense-in-depth only). Not exposed to any user-facing path.
- The story spec wrote `pageSize: PageSize` on the port; using `int` instead (as recorded in US-10-001's SUMMARY note) — the application-side `PageSize` wrapper would create a domain → application dependency forbidden by `LayeringArchTest`.

---

## 2026-06-01 — EPIC-10 / US-10-001 — Conversation/Message domain

Created (`src/main/java/.../domain/conversation/`):
- `ConversationId.java` — UUID wrapper
- `MessageId.java` — UUID wrapper
- `MessageRole.java` — enum `{ USER, ASSISTANT }` (REQ-CHAT-012)
- `MessageContent.java` — non-blank, ≤1024 chars
- `Title.java` — non-blank, ≤32 chars + `fromFirstUserMessage(MessageContent)` + `defaultFor(ConversationId)` helpers
- `MessageCount.java` — `[0, 64]` + `isFull()` + `incrementOrThrow(ConversationId)` (throws `ConversationFullException` at cap)
- `ConversationOwner.java` — sealed sum type `UserOwner(UserId) | SystemOwner(ClientId)` mirroring `Principal`; `from(Principal)` factory (uses `instanceof` patterns since project targets Java 17)
- `Conversation.java` — aggregate record with `withTitle(Title, OffsetDateTime)` + `incrementMessageCount(OffsetDateTime)` helpers; `title` nullable until first user message
- `Message.java` — append-only aggregate record
- `ConversationNotFoundException.java` — extends `NotFoundException` (404 via generic handler)
- `ConversationFullException.java` — extends `ConflictException` (the `CONVERSATION_FULL` 409 specialization ships in US-10-004)
- `ConversationRepository.java` — port: `save` / `findById` / `listByOwner(ConversationOwner, Optional<AgentId>, Cursor, int)` / `deleteById` / `appendMessage` / `listMessages` / `findLastN`

Modified:
- `domain/conversation/package-info.java` — replaced EPIC-10 stub with real package description
- `arch/LayeringArchTest.java` — added rule `no_spring_imports_in_domain_conversation` (positive belt-and-suspenders on top of the broader `domain_does_not_depend_on_framework_packages`)

Tests (`src/test/java/.../domain/conversation/`): `ConversationIdTest`, `MessageIdTest`, `MessageRoleTest`, `MessageContentTest`, `TitleTest`, `MessageCountTest`, `ConversationOwnerTest`, `ConversationTest`, `MessageTest` — 47 new unit tests, all green; full domain+arch suite at 165 tests green.

Story-spec deviation (worth flagging): the port signatures use `int pageSize` rather than `application.shared.PageSize` because `PageSize` lives in the application layer and the hexagonal layering rule forbids the domain from depending on it; matches the existing `AgentRepository` / `ApiKeyRepository` convention. The application service wraps via `PageSize.fromQueryParam(Integer)` and unwraps to `int` before calling the port — to land in US-10-005 onwards.

---

## 2026-05-29 — EPIC-09 / US-09-002, US-09-003, US-09-004, US-09-005

### US-09-002 — LLM configuration + `OPENAI_API_KEY` fail-fast

Created:
- `infrastructure/llm/openai/OpenAiConfig.java` (`@PostConstruct` fail-fast
  check on `spring.ai.openai.api-key`)
- `infrastructure/llm/openai/OpenAiApiKeyMissingFailsFastTest.java`
  (`ApplicationContextRunner` — DB-free)
- `infrastructure/llm/openai/OpenAiApiKeyPresentBootsFineTest.java`
- `infrastructure/llm/openai/OpenAiDefaultModelWiringTest.java`
  (`@SpringBootTest`, verifies `app.llm.openai.default-model` →
  `spring.ai.openai.chat.options.model` relay)

Modified:
- `infrastructure/config/ApplicationProperties.java` — added
  `Llm(@Valid Openai openai)` nested record with `Openai(@NotBlank
  @Size(max=64) String defaultModel)`
- `src/main/resources/application.yaml` — added
  `spring.ai.openai.api-key`, `spring.ai.openai.chat.options.model`,
  `app.llm.openai.default-model`; updated header env-var list
- `src/test/resources/application.yaml` — added
  `spring.ai.openai.api-key=test-openai-key` and
  `app.llm.openai.default-model=gpt-4o-mini`; updated the autoconfig
  exclusion comment to reflect the Spring AI 1.1.0 / Spring 7 binary
  incompat
- `infrastructure/config/ApplicationPropertiesTest.java` — new case
  `llm_openai_default_model_is_bound`
- `infrastructure/mcp/FilesystemMcpUserScopeAdapterTest.java` — updated
  inline `new ApplicationProperties(...)` to match the new 6-arg shape

### US-09-003 — `LlmUnavailableException` + 502 handler

Created:
- `infrastructure/error/LlmUnavailableException.java` (final subclass of
  `ExternalServiceException`)

Modified:
- `infrastructure/web/error/GlobalExceptionHandler.java` — added
  `@ExceptionHandler(LlmUnavailableException.class)` mapping to HTTP 502
  with `code = LLM_UNAVAILABLE`
- `infrastructure/web/error/GlobalExceptionHandlerTest.java` — added
  two MockMvc cases and two stub controller endpoints

### US-09-004 — `OpenAiChatClientAdapter.call(...)` + helpers

Created:
- `infrastructure/llm/openai/OpenAiErrorMapper.java` (cause-chain
  walker; classifies into `http_4xx <code>`, `http_429`,
  `http_5xx <code>`, `timeout`, `connection_refused`, `unknown`)
- `infrastructure/llm/openai/OpenAiChatOptionsTranslator.java`
  (pure-static `ChatRequest` → Spring AI `Prompt` + `ChatOptions`)
- `infrastructure/llm/openai/OpenAiChatClientAdapter.java`
  (`@Component`, `@ConditionalOnBean(ChatModel.class)`, implements
  `LlmChatClient`; ships both `call()` and `stream()` in the same class)
- `infrastructure/llm/openai/OpenAiErrorMapperTest.java` (13 cases)
- `infrastructure/llm/openai/OpenAiChatOptionsTranslatorTest.java` (5 cases)
- `infrastructure/llm/openai/OpenAiChatClientAdapterCallTest.java`
  (Mockito-based; 13 cases — happy path, 4xx/429/5xx/timeout/
  connection-refused/unknown error mapping, message ordering, model
  override, sampling translation)

### US-09-005 — `OpenAiChatClientAdapter.stream(...)`

Created:
- `infrastructure/llm/openai/OpenAiChatClientAdapterStreamTest.java`
  (StepVerifier-based; 11 cases — emission order, empty-delta
  passthrough, 4xx/429/5xx/unknown reactive error mapping, mid-stream
  error preserves preceding chunks, downstream cancel propagates
  upstream)

Modified:
- `pom.xml` — added `reactor-test` (test scope)

---

## 2026-05-29 — EPIC-09 / US-09-001

### US-09-001 — `LlmChatClient` port + `ChatRequest` / `ChatChunk` / `ChatResult` records

Application-side contracts for the provider-agnostic LLM chat-completion port.
Pure-Java records and an interface returning `reactor.core.publisher.Flux` —
no Spring AI imports. Validation throws `ValidationException` with the
offending field name; collection fields are defensively copied via
`List.copyOf` so the records are genuinely immutable.

Created:
- `application/chat/Role.java` (enum `USER | ASSISTANT`)
- `application/chat/ChatMessage.java`
- `application/chat/SamplingParameters.java` (with `none()` factory)
- `application/chat/ChatRequest.java`
- `application/chat/ChatChunk.java`
- `application/chat/ChatResult.java`
- `application/chat/LlmChatClient.java`

Created (tests):
- `application/chat/RoleTest.java`
- `application/chat/ChatMessageTest.java`
- `application/chat/SamplingParametersTest.java`
- `application/chat/ChatRequestTest.java`
- `application/chat/ChatChunkTest.java`
- `application/chat/ChatResultTest.java`
- `application/chat/LlmChatClientContractTest.java`

Modified:
- `application/chat/package-info.java` (drop "EPIC-09" placeholder)
- `arch/LayeringArchTest.java` (new rule
  `no_spring_ai_imports_in_application_chat`)

---

## 2026-05-13 — EPIC-08 / US-08-004, US-08-005, US-08-006, US-08-007

### US-08-004 — `FilesystemMcpUserScope` port + adapter

Created:
- `application/mcp/FilesystemMcpUserScope.java`
- `infrastructure/mcp/FilesystemMcpUserScopeAdapter.java`

### US-08-005 — `McpServersController` + `GET /mcp-servers`

Created:
- `infrastructure/web/mcp/McpServersController.java`
- `infrastructure/web/mcp/McpServerDescriptorResponse.java`
- `infrastructure/web/mcp/McpServerListResponse.java`
- `infrastructure/web/mcp/McpServerResponseMapper.java`
- `infrastructure/web/mcp/package-info.java`

### US-08-006 — `CatalogMcpReferenceValidator` replaces stub

Created:
- `infrastructure/agent/validation/CatalogMcpReferenceValidator.java`

Deleted:
- `infrastructure/agent/validation/NoopMcpReferenceValidator.java`

Modified:
- (test-side helpers for the new validator in
  `infrastructure/web/agent/CreateAgentEndpointIntegrationTest.java` and
  `UpdateAgentEndpointIntegrationTest.java`)

### US-08-007 — `McpServerException` + `MCP_SERVER_ERROR` 502 mapping

Created:
- `infrastructure/error/ExternalServiceException.java`
- `infrastructure/error/McpServerException.java`
- `infrastructure/error/package-info.java`

Modified:
- `infrastructure/web/error/GlobalExceptionHandler.java`

---

## 2026-05-13 — EPIC-08 / US-08-001, US-08-002, US-08-003

### US-08-001 — Domain + application MCP catalog primitives

Created:
- `domain/mcp/McpServerName.java`
- `domain/mcp/UnknownMcpServerException.java`
- `application/mcp/McpServerDescriptor.java`
- `application/mcp/McpServerCatalog.java`
- `application/mcp/ListMcpServersUseCase.java`
- `application/mcp/ListMcpServersService.java`

Modified:
- `domain/mcp/package-info.java`
- `application/mcp/package-info.java`

### US-08-002 — `application.yaml` MCP config + `app.mcp.filesystem.base` binding

Modified:
- `infrastructure/config/ApplicationProperties.java` (new `Mcp.Filesystem` record)
- `src/main/resources/application.yaml`
- `src/test/resources/application.yaml`

### US-08-003 — `McpServerCatalogAdapter` with startup caching

Created:
- `infrastructure/mcp/McpServerCatalogAdapter.java`

Modified:
- `infrastructure/mcp/package-info.java`

### Backlog updates

- `backend/backlog/EPIC-08-US.md` (statuses → Done for US-08-001/002/003)
- `backend/backlog/US-STATUS.md` (statuses + aggregate row → 62 Done / 4 Draft)
