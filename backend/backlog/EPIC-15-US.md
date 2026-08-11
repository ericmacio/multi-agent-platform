# EPIC-15-US.md — User stories for EPIC-15

EPIC-15 — **Observability & health**

This file lists the user stories that close EPIC-15. The EPIC ships
the operational plumbing every deployed instance relies on:
structured JSON logging with a per-request correlation identifier,
per-package configurable log levels, a health/readiness probe via
Spring Boot Actuator, and a regression lock on the sensitive-data
redaction that earlier EPICs already wired in.

> **Already shipped by earlier EPICs.** Three pieces EPIC-15 builds on
> are already in the codebase and MUST NOT be re-implemented:
> - `SensitiveDataMaskingConverter` (Logback converter under
>   `infrastructure/web/error/`, registered in `logback-spring.xml`
>   as conversion word `redactedMsg`) masks BCrypt hashes,
>   `Bearer <…>` headers, and JWT-shaped substrings before they reach
>   any appender (US-03-001 / REQ-SEC-004). The JSON encoder shipped
>   in US-15-003 MUST keep using this conversion word.
> - `SpringSecurityConfig` already permits
>   `GET /actuator/health` anonymously
>   (`requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()`).
>   The matcher is in place; the missing piece is the Actuator
>   dependency itself, added by US-15-001.
> - `RateLimitFilter.shouldNotFilter(...)` already short-circuits any
>   request whose path starts with `/actuator` (US-13-005). The health
>   probe is therefore not subject to the per-minute / per-hour
>   buckets — operators can hammer it from ELB / monitoring at any
>   cadence.
>
> **What is NOT shipped yet** — the four gaps EPIC-15 closes:
> 1. The **Actuator dependency and exposure configuration** — without
>    `spring-boot-starter-actuator` on the classpath, no
>    `/actuator/**` endpoint exists at runtime, regardless of the
>    permit-all matcher.
> 2. The **correlation-ID filter** that generates (or echoes) an
>    `X-Correlation-Id` value, puts it on the SLF4J `MDC`, exposes it
>    on the response, and adds it to
>    `Access-Control-Expose-Headers` so the React frontend can read
>    it via `fetch().headers.get("X-Correlation-Id")` for support
>    ticket attribution. The CORS regression test in US-14-004 was
>    deliberately written with header-containment assertions so this
>    addition would not regress it.
> 3. The **JSON Logback encoder** (composite Logstash-style) that
>    replaces the human-readable pattern in `logback-spring.xml` so
>    log aggregators can index by `level`, `logger`, `correlationId`,
>    and a redacted `message` — while preserving the
>    `%redactedMsg` conversion word so REQ-SEC-004 holds. The
>    per-package log-level smoke test (REQ-OBS-002) lives in the
>    same story since the two pieces are tested through the same
>    Logback harness.
> 4. The **redaction regression integration test** that exercises
>    the full pipeline — request → service log call carrying a
>    `Bearer <jwt>` substring → JSON-encoded console output — and
>    asserts the raw form never reaches the encoder output.
>
> **Out of scope (deferred).** Full Micrometer metrics, distributed
> tracing (OpenTelemetry / Zipkin), log shipping to CloudWatch, and
> the deployed log-aggregation pipeline are NOT part of v1
> observability (per `REQ-OBS-001/002/003` SHOULD scoping). HTTPS
> termination is EPIC-16. Custom `HealthIndicator` beans beyond the
> Spring-Boot-default DataSource check are out of scope — the v1
> contract is "the application can answer 200 OK at
> `/actuator/health` and reports DOWN if the DB is unreachable."

## Conventions

- **ID format**: `US-15-<nnn>` — `15` matches the EPIC number;
  `<nnn>` is a sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories
  start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. The EPIC itself is
  `SHOULD` per `EPICS.md`; story priorities mirror their underlying
  requirement: the three observability stories are `SHOULD`
  (REQ-OBS-001/002/003 are SHOULD), while the redaction regression
  story is `MUST` (REQ-SEC-004 is MUST).
- Each story contains: a narrative ("As a … I want … so that …"), a
  short description, a bullet list of testable acceptance criteria,
  the out-of-scope items, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                              | Priority | Status | Depends on                                                                                       |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|--------------------------------------------------------------------------------------------------|
| US-15-001  | Spring Boot Actuator dependency + `GET /actuator/health` exposed outside `/api/v1`                  | SHOULD   | Done   | EPIC-01 (`SpringSecurityConfig`), EPIC-13 (`RateLimitFilter` exclusion already wired)             |
| US-15-002  | `CorrelationIdFilter` — generate / propagate `X-Correlation-Id`, populate MDC, expose on response   | SHOULD   | Done   | EPIC-01 (`SpringSecurityConfig`, CORS), EPIC-13 (filter ordering)                                 |
| US-15-003  | JSON Logback encoder (`LoggingEventCompositeJsonEncoder`) preserving `%redactedMsg` + per-package log-level smoke test | SHOULD | Draft | US-15-002 (`correlationId` MDC field), US-03-001 (`SensitiveDataMaskingConverter`)               |
| US-15-004  | Sensitive-data redaction regression integration test across every appender                          | MUST     | Done   | US-15-003 (the JSON encoder under test), US-03-001 (the converter under test)                    |

---

## US-15-001 — Spring Boot Actuator dependency + `GET /actuator/health` outside `/api/v1`

- **Status**: Done
- **Priority**: SHOULD

**As a** platform operator
**I want** Spring Boot Actuator on the classpath with a single
exposed endpoint — `GET /actuator/health` — served outside the
`/api/v1` versioned prefix
**So that** ELB / monitoring / smoke probes can ping the application
without holding an authenticated principal, the rate-limiter never
throttles the probe (already wired in US-13-005), and the response
shape stays minimal (`{"status":"UP"}`) without leaking DB host or
schema details.

### Description

Today (verified by grep over `infrastructure/web/security/`):
- `SpringSecurityConfig` already declares
  `requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()` —
  the security side is ready.
- `RateLimitFilter` already short-circuits any request whose path
  starts with `/actuator/` — the rate-limit side is ready.
- `pom.xml` does NOT depend on `spring-boot-starter-actuator` — so
  the endpoint does not exist at runtime. A `GET /actuator/health`
  today returns 404 through the `GlobalExceptionHandler`'s
  `NoHandlerFoundException` branch.

This story adds the dependency and the minimal configuration to
expose health only. The Spring Boot 4.0.6 default
`management.endpoints.web.base-path` is `/actuator`, and the
default `management.server.port` is the same as `server.port`
(`8080`) — both kept as-is so the deploy story (EPIC-16) does not
need to special-case a separate port. The `/actuator` prefix is
**not** routed through `app.api.base-path` (`/api/v1`) because
Spring Boot Actuator installs its own dispatcher servlet mapping
that bypasses the `spring.mvc.servlet.path` chain — meaning the
endpoint lives at `http://host:8080/actuator/health`, NOT at
`http://host:8080/api/v1/actuator/health`. This is the design
choice that motivates the explicit permit-all matcher.

Health-detail policy: `management.endpoint.health.show-details=never`
— the body is exactly `{"status":"UP"}` (or `{"status":"DOWN"}`)
with no `components` object, no DB JDBC URL, no disk-space
threshold. This is REQ-SEC-004-compliant: the probe answer cannot
leak the schema name, host, or credentials.

The Spring-Boot-default `DataSourceHealthIndicator` is kept enabled
(`management.health.db.enabled=true`, default) — it executes
`SELECT 1` (or the H2 / PG equivalent) on each probe; if the DB is
unreachable the endpoint returns 503 with `{"status":"DOWN"}`. This
satisfies REQ-OBS-003's "readiness" wording: the probe reports DOWN
when a dependency the application requires to serve traffic is
unavailable.

### Acceptance criteria

- `pom.xml` declares `spring-boot-starter-actuator` (no version —
  inherits from `spring-boot-starter-parent`). Placement: in the
  alphabetical-by-artifactId run of `spring-boot-starter-*` already
  present in `pom.xml`.
- `application.yaml` adds:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health
    endpoint:
      health:
        show-details: never
  ```
  No other Actuator endpoints (`metrics`, `info`, `loggers`,
  `prometheus`, `env`, `beans`, …) are exposed. The default
  `info` endpoint is implicit-off via `exposure.include: health`.
- `src/test/resources/application.yaml` mirrors the same
  `management.*` block (test profile parity — without it, the
  integration test below would resolve a different endpoint set).
- A new `ActuatorHealthEndpointIntegrationTest` extends
  `PostgresIntegrationTest` and asserts:
  - `GET /actuator/health` (no `Authorization` header) returns 200
    with body `{"status":"UP"}`. Content-Type is
    `application/vnd.spring-boot.actuator.v3+json` (Spring Boot
    default — not asserted exactly; the test asserts the body
    parses as `{"status":"UP"}`).
  - `GET /actuator/health` is served at `/actuator/health`, NOT
    at `/api/v1/actuator/health` (a request to the latter returns
    404).
  - `GET /actuator/metrics`, `GET /actuator/loggers`,
    `GET /actuator/info` each return 404 — only `health` is
    exposed.
  - `GET /actuator/health` does NOT carry a `components` field in
    the response body (`show-details=never` is enforced).
  - The endpoint is NOT throttled by `RateLimitFilter`: even after
    a 429 on `GET /api/v1/_rl_probe`, the next
    `GET /actuator/health` still returns 200.
- A second test asserts the DOWN path:
  `ActuatorHealthDownWhenDataSourceFailsTest` (extends a custom
  base or uses a mocked `DataSourceHealthIndicator` via
  `@MockBean`) — `GET /actuator/health` returns 503 with body
  `{"status":"DOWN"}`. Implementation note: `@MockBean` on
  `DataSourceHealthIndicator` is the simplest seam (no need for a
  broken `DataSource`); the mock returns `Health.down().build()`.
- `LayeringArchTest` is extended with: classes in the
  `infrastructure.web.dev..` package MUST NOT match
  `*HealthIndicator` — the v1 contract is exactly the Spring-Boot
  default DB check; custom indicators are deferred.
- `DESIGN-CHOICES.md` gains one paragraph: "Actuator served at
  `/actuator/health` outside `/api/v1`" — captures the choice (the
  endpoint bypasses `spring.mvc.servlet.path` and is therefore not
  versioned alongside the business API; ops tooling can ping the
  same path across all environments).

### Out of scope

- A separate management port (`management.server.port=8081`). All
  traffic stays on `8080` for v1 simplicity; multi-port hardening
  is an EPIC-16 deployment concern if needed.
- Custom `HealthIndicator` beans (e.g., a Spring AI `ChatModel`
  reachability check, a `BraveSearchHealthIndicator`). Out of v1
  scope — adding indicators is additive and does not change the
  contract.
- The `/actuator/info` endpoint and version banners. The application
  banner is already wired (`spring.main.banner-mode=log`); exposing
  it via Actuator is not a v1 requirement.

### Requirements coverage

`REQ-OBS-003` (health/readiness probe), `REQ-API-006` (versioned
base path applies only to the business API — Actuator lives
outside it), `REQ-RL-003` (rate limiter does not throttle the
probe — pinned by the no-throttle assertion above),
`REQ-SEC-004` (health body does not leak DB host / schema).

### Design references

§6.4 health, §15 configuration, §8.1 filter chain (the
`/actuator/**` skip already in `RateLimitFilter`).

### Dependencies

EPIC-01 (`SpringSecurityConfig`'s permit-all matcher),
EPIC-02 (`DataSource` bean — the default
`DataSourceHealthIndicator` consumes it),
EPIC-13 (`RateLimitFilter.shouldNotFilter(...)`'s `/actuator`
skip — pinned by the no-throttle assertion).

---

## US-15-002 — `CorrelationIdFilter` — generate/propagate `X-Correlation-Id`, populate MDC, expose on response

- **Status**: Done
- **Priority**: SHOULD

**As a** platform operator
**I want** every incoming HTTP request to be tagged with a single
correlation identifier — pulled from a client-supplied
`X-Correlation-Id` header if present and well-formed, otherwise
generated server-side as a fresh `UUID.randomUUID()` — written to
the SLF4J `MDC` for the lifetime of the request, echoed back on
the response as the same `X-Correlation-Id` header, and added to
`Access-Control-Expose-Headers` so the React frontend can read it
**So that** every log line emitted during a single request shares
that identifier (for grep / log-aggregator correlation), and so
that a user reporting a support ticket can attach the
`X-Correlation-Id` they observed in the browser's network panel,
letting operators jump straight to the relevant logs.

### Description

The MDC field is the cornerstone of the JSON encoder in US-15-003;
without this filter, the `correlationId` JSON column would always
be empty. The filter is therefore a precondition for the JSON
layout but is also independently useful: the response header
(`X-Correlation-Id: <uuid>`) gives the frontend an attribute it can
attach to user-facing error toasts.

Header contract (request side):
- Header name: `X-Correlation-Id` (PascalCase, dash-separated —
  matches the existing `X-Client-Id` / `X-Api-Key` convention).
- If the client supplies the header AND the value matches
  `^[a-zA-Z0-9_.:-]{1,128}$` (broad enough to admit external
  trace-IDs like AWS X-Ray's `1-5759e988-bd862e3fe1be46a994272793`
  or OpenTelemetry's lowercase hex `traceparent` middle segment),
  the filter accepts and propagates it verbatim.
- If the client supplies a value that does NOT match, OR does not
  supply the header at all, the filter generates
  `UUID.randomUUID().toString()` (lowercase, hyphenated, 36 chars).
- The filter does NOT reject requests on a malformed inbound
  header — it silently regenerates. Rationale: a misconfigured
  upstream proxy must not break the entire request path.

Header contract (response side):
- Every response (success, 4xx, 5xx, 429, SSE stream) carries
  `X-Correlation-Id: <value>`.
- SSE responses: the header is set on the initial 200 response
  before the first event is written (the filter runs before the
  `SseEmitter` is wired up).
- The 429 emitted by `RateLimitFilter` carries the header too —
  this requires `CorrelationIdFilter` to be ordered BEFORE
  `RateLimitFilter` so the MDC is populated when the 429 envelope
  is built and the header is written on the bucket-exhausted
  response. The filter ordering is asserted by a unit test.

MDC contract:
- Key: `correlationId` (camelCase, matches the JSON encoder field
  name in US-15-003).
- Set in the filter BEFORE `chain.doFilter(...)` is called.
- Cleared via `MDC.remove("correlationId")` in a `finally` block
  AFTER `chain.doFilter(...)` returns — even if it threw. This is
  load-bearing because Tomcat thread-pool threads are reused; a
  leaked MDC entry would attribute the next request's logs to the
  wrong correlation ID.

CORS contract:
- `SpringSecurityConfig.corsConfigurationSource()` is updated so
  `setExposedHeaders(...)` includes `X-Correlation-Id` alongside
  the existing `Retry-After` entry. The list ordering is
  alphabetical for stability: `Retry-After`, `X-Correlation-Id`.
- The CORS regression test in US-14-004 used containment
  assertions on `Access-Control-Expose-Headers` precisely so this
  addition does not regress it; the new entry is purely additive.

### Acceptance criteria

- `infrastructure/web/observability/CorrelationIdFilter.java` is
  added — extends `OncePerRequestFilter`, lives under a new
  `infrastructure/web/observability/` package alongside the JSON
  encoder concerns from US-15-003.
- The filter reads `X-Correlation-Id` from
  `HttpServletRequest.getHeader(...)`; on null OR a value not
  matching `^[a-zA-Z0-9_.:-]{1,128}$`, generates a fresh
  `UUID.randomUUID().toString()`.
- The filter writes the value to:
  - `MDC.put("correlationId", value)` BEFORE
    `filterChain.doFilter(...)`.
  - `HttpServletResponse.setHeader("X-Correlation-Id", value)`
    BEFORE `filterChain.doFilter(...)` (the header must be on the
    response object before any controller writes status / body,
    otherwise SSE responses would commit the response and lose
    the header).
- The filter clears MDC in a `finally` block:
  `MDC.remove("correlationId")`. The `try / finally` wraps the
  `chain.doFilter(...)` call.
- The filter is registered in `SpringSecurityConfig` BEFORE
  `RateLimitFilter` via
  `.addFilterBefore(correlationIdFilter, RateLimitFilter.class)`.
  Filter ordering is asserted by a unit test that introspects the
  resolved `SecurityFilterChain`.
- `SpringSecurityConfig.corsConfigurationSource()` adds
  `X-Correlation-Id` to the existing
  `setExposedHeaders(List.of("Retry-After", ...))` call. The list
  is kept alphabetical so future additions are insertion-ordered
  deterministically.
- A pure-Java unit test
  `CorrelationIdFilterTest` exercises four scenarios:
  - Inbound header present, well-formed → echoed verbatim, MDC
    contains the same value during `chain.doFilter`, response
    header carries it, MDC is cleared after.
  - Inbound header present, malformed (e.g., `value with space`)
    → silently regenerated, MDC carries the UUID, response
    header carries the UUID.
  - Inbound header absent → generated UUID propagated identically.
  - Filter throws after `chain.doFilter(...)` (simulated by
    wrapping the chain to throw) → MDC is STILL cleared in the
    `finally`. Asserted by checking
    `MDC.get("correlationId") == null` after the assertion that
    the exception propagated.
- An integration test
  `CorrelationIdFilterIntegrationTest` extends
  `PostgresIntegrationTest`:
  - A `GET /api/v1/_rl_probe` (or any cheap authenticated GET)
    with NO `X-Correlation-Id` header returns 200 with an
    `X-Correlation-Id` response header matching the UUID v4
    pattern `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`.
  - The same request with `X-Correlation-Id: my-trace-001`
    returns 200 with `X-Correlation-Id: my-trace-001`.
  - The same request with `X-Correlation-Id:`
    `value%20with%20space%20invalid!@#$` (malformed) returns 200
    with an `X-Correlation-Id` response header matching the
    UUID v4 pattern — the malformed value is silently dropped.
  - A 429 response (bucket-exhausted via the
    `RateLimitFilterIntegrationTest` harness) ALSO carries
    `X-Correlation-Id` — pinning the filter-ordering invariant.
  - A CORS preflight (`OPTIONS /api/v1/agents` with
    `Origin: http://localhost:5173`,
    `Access-Control-Request-Headers: X-Correlation-Id`) returns
    `Access-Control-Allow-Headers: X-Correlation-Id, …` (the
    inbound header may also be supplied by frontend code).
  - A CORS preflight or simple GET response from an allowed
    origin carries
    `Access-Control-Expose-Headers: Retry-After, X-Correlation-Id`
    (containment assertion).
- The existing CORS regression test
  (`CorsConfigurationIntegrationTest`) is NOT modified — its
  `Access-Control-Expose-Headers` assertions used containment
  precisely so this story is additive.
- ArchUnit (`LayeringArchTest`) is extended with: classes named
  `CorrelationIdFilter` MUST live under
  `infrastructure.web.observability..` only.

### Out of scope

- Propagating the correlation ID to downstream HTTP calls
  (OpenAI, brave-search MCP). The Spring AI WebClient is
  configured by Spring AI's autoconfiguration and lives behind
  the `OpenAiChatClientAdapter` — wiring a request-scoped
  `WebClient` filter to forward `X-Correlation-Id` is a follow-up
  if v2 needs end-to-end tracing. For v1, the correlation ID
  identifies the inbound request only.
- W3C `traceparent` / `tracestate` headers. The chosen header is
  `X-Correlation-Id` (project-scoped). Bridging to OpenTelemetry
  is a v2 concern.
- Server-Sent Events frame attribution: SSE events do not carry
  per-event correlation IDs. The HTTP response header is set
  once for the entire stream — log lines emitted while building
  individual `delta` frames share the request's MDC value because
  they run on the request thread before the reactive boundary.

### Requirements coverage

`REQ-OBS-001` (structured logging with correlation identifiers),
`REQ-API-003` (CORS allow-list — `X-Correlation-Id` is the new
exposed header), `REQ-NFR-002` (regression test on filter
ordering), `REQ-NFR-004` (frontend compatibility — the React app
will read the header).

### Design references

§6.4 health (filter-chain neighbors), §8.1 filter chain (the
ordering invariant), §15 configuration.

### Dependencies

EPIC-01 (`SpringSecurityConfig` + the CORS configuration),
EPIC-13 (`RateLimitFilter` — the new filter is ordered BEFORE it).

---

## US-15-003 — JSON Logback encoder + per-package log-level smoke test

- **Status**: Done
- **Priority**: SHOULD

**As a** platform operator
**I want** Logback's `CONSOLE` appender to emit one JSON object per
log line — including `timestamp`, `level`, `thread`, `logger`,
`message`, `correlationId`, and (when present) a structured
`stackTrace` — while still routing the `message` field through the
existing `%redactedMsg` conversion word so BCrypt / Bearer / JWT
substrings remain masked, AND I want per-package log-level
configuration (`logging.level.<package>=<level>`) to keep working
unchanged through the new encoder
**So that** log aggregators (CloudWatch Logs Insights, Loki,
ELK, …) can index every field without regex parsing, the
correlation ID from US-15-002 becomes queryable, and operators
can still bump verbosity for a single package via
`SPRING_APPLICATION_JSON` / `application.yaml` without touching
`logback-spring.xml`.

### Description

The current encoder in `logback-spring.xml` is a `PatternLayout`
with `%redactedMsg` for the message slot. Aggregators that ingest
this output have to parse the timestamp / level / logger fields
out of free text with brittle regex — and they have NO way to
recover the `correlationId` MDC value that US-15-002 now
populates.

The chosen JSON encoder is
`net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder`
from the **`logstash-logback-encoder`** library. Rationale:
- Mature, widely deployed (the de-facto Logstash output for
  Logback).
- Supports a nested `<pattern>` provider that lets us keep using
  the existing `redactedMsg` conversion word for the `message`
  field — no rewrite of the redaction logic.
- Renders MDC values as first-class JSON fields via the `<mdc/>`
  provider.
- Renders stack traces as a structured `stack_trace` field via
  `<stackTrace/>` (the
  `ShortenedThrowableConverter` keeps lines below a threshold for
  CloudWatch's 256 KB payload cap).

The encoder configuration:

```xml
<encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
  <providers>
    <timestamp>
      <fieldName>timestamp</fieldName>
      <pattern>yyyy-MM-dd'T'HH:mm:ss.SSSXXX</pattern>
    </timestamp>
    <logLevel><fieldName>level</fieldName></logLevel>
    <threadName><fieldName>thread</fieldName></threadName>
    <loggerName><fieldName>logger</fieldName></loggerName>
    <pattern>
      <pattern>
        {
          "message": "%redactedMsg",
          "correlationId": "%mdc{correlationId:-}"
        }
      </pattern>
    </pattern>
    <stackTrace>
      <fieldName>stackTrace</fieldName>
      <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
        <maxLength>4096</maxLength>
        <rootCauseFirst>true</rootCauseFirst>
      </throwableConverter>
    </stackTrace>
  </providers>
</encoder>
```

Key design notes:
- The `<pattern>` nested provider invokes the existing
  `redactedMsg` conversion word — same converter, same regex
  patterns, no behavior change. REQ-SEC-004 is preserved by
  construction.
- `correlationId` uses the MDC default-value syntax `:-` so a log
  record emitted OUTSIDE a request scope (e.g., during context
  startup, scheduled-task heartbeats) renders an empty string
  instead of breaking JSON validity. An empty string is
  intentional: aggregators can filter on
  `correlationId != ""` to scope to request-attributed traffic.
- The `<stackTrace>` provider renders cause chains as a single
  string field; this is what most aggregators expect. Production
  stack traces remain greppable.
- `rootCauseFirst=true` matches how operators reason about cause
  chains and matches the convention used by
  `GlobalExceptionHandler`'s ERROR-level log statements.

Per-package log levels (REQ-OBS-002): Spring Boot's
`logging.level.<package>=<level>` configuration is handled by
Spring's `LoggingSystem` — it reconfigures Logback's loggers at
context refresh, independent of the encoder. Swapping the encoder
does NOT change this contract; the smoke test below pins it.

### Acceptance criteria

- `pom.xml` declares `net.logstash.logback:logstash-logback-encoder`
  at the latest 7.x release compatible with Logback 1.5 / Spring
  Boot 4.0 (current as of authoring: **7.4**). Placement: in the
  alphabetical order of `dependencies`, between `logback-classic`
  (transitive — not declared explicitly) and the next
  alphabetical entry. NO version downgrade of `logback-classic`
  is allowed.
- `logback-spring.xml` is rewritten so the `CONSOLE` appender
  uses `LoggingEventCompositeJsonEncoder` with the providers
  listed in the description above. The
  `conversionRule` for `redactedMsg` stays at the top of the
  file unchanged.
- The pre-existing pattern (`%d{…} %-5level [%thread] %logger{36}
  - %redactedMsg%n`) is REMOVED — there is no human-readable
  "dev" appender variant. Local development reads JSON on
  stdout; IDE consoles already render line-oriented JSON without
  issue. (If a dev appender becomes necessary later, the
  spring-boot profile mechanism is the right vehicle — out of
  v1 scope.)
- `logback-spring.xml` includes a `<springProperty>` binding for
  the application name so the JSON output carries an
  `application` field — sourced from
  `spring.application.name=multi-agent-platform`. Placement: in
  the encoder's `<providers>` block via a literal field.
- A new test `JsonLoggingEncoderIntegrationTest` (lives under
  `infrastructure/web/observability/`) does the following with a
  programmatically-attached
  `OutputStreamAppender<ILoggingEvent>` (NOT the production
  CONSOLE appender — to avoid System.out capture flakiness):
  - Instantiates the production
    `LoggingEventCompositeJsonEncoder` configuration
    (loaded via Logback's `JoranConfigurator` reading
    `logback-spring.xml`).
  - Emits one log record per scenario:
    1. INFO with no MDC value → JSON has
       `"correlationId":""`.
    2. INFO with
       `MDC.put("correlationId","abc-123")` → JSON has
       `"correlationId":"abc-123"`.
    3. ERROR with a `RuntimeException` cause carrying message
       `"boom"` → JSON has a `stackTrace` field containing
       `"boom"`.
    4. INFO with message
       `"Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature"` →
       JSON has `"message"` field NOT containing the raw
       `Bearer eyJ…` substring (the redaction converter
       still fires).
  - For each scenario, parses the captured JSON line with
    Jackson (`ObjectMapper`) and asserts on a
    `Map<String, Object>` — guaranteeing the output is
    valid JSON.
- A second test
  `PerPackageLogLevelSmokeIntegrationTest` (extends
  `PostgresIntegrationTest`, uses
  `@TestPropertySource(properties =
    "logging.level.com.cognizant.emk.multiagent.application.shared=DEBUG")`):
  - Attaches a `ListAppender<ILoggingEvent>` to a known logger
    under
    `application/shared/` and asserts a `log.debug(...)` call
    is captured at level DEBUG.
  - A separate test method without the property override
    asserts the same DEBUG call is NOT captured (the default
    `INFO` level filters it).
  - This pins REQ-OBS-002 through Spring's standard mechanism;
    no Logback-side hand-rolling is required.
- The existing `SensitiveDataMaskingConverterTest` is NOT
  modified — the converter is unchanged.
- The existing `logback-spring.xml` `conversionRule`
  declaration for `redactedMsg` is unchanged (same class,
  same conversion word).
- `LayeringArchTest` is extended with: classes in
  `infrastructure.web.observability..` MUST NOT depend on
  `org.springframework.boot.actuator..` — the encoder and the
  filter must work without the Actuator dependency for the
  packaged JAR.

### Out of scope

- A second appender variant (e.g., a `dev` profile with
  human-readable colorized output). Acceptable choice but
  unnecessary noise for v1 — JSON-on-stdout is readable enough
  in a dev terminal that this defers cleanly.
- A `RollingFileAppender` writing JSON to
  `/var/log/multi-agent/app.json`. The EC2 deployment in
  EPIC-16 will configure file-rolling via journald / the
  systemd unit, OR via CloudWatch Logs agent — neither needs a
  Logback-side rolling appender.
- Sampling / rate-limiting log records (e.g., a "noisy" logger
  rate-limited to N records/sec). Out of v1 scope.
- Structured fields beyond `correlationId` (e.g.,
  `principalId`, `agentId`, `conversationId`). Each is a useful
  addition for a future iteration — for v1 the foundation
  (`correlationId` + redaction-preserving JSON output) is
  sufficient.

### Requirements coverage

`REQ-OBS-001` (structured JSON logging with correlation IDs —
the JSON output is the contract pinned by the test),
`REQ-OBS-002` (per-package log levels via Spring properties —
the smoke test pins it),
`REQ-SEC-004` (sensitive-data redaction preserved — the redacted
substring assertion pins it).

### Design references

§8.7 logging, §15 configuration. The `redactedMsg` converter
ships in `infrastructure/web/error/SensitiveDataMaskingConverter`
(US-03-001).

### Dependencies

US-15-002 (the MDC `correlationId` field — the encoder reads
it). US-03-001 (the `SensitiveDataMaskingConverter` — the
encoder still routes `message` through the converter via the
nested `<pattern>` provider).

---

## US-15-004 — Sensitive-data redaction regression integration test

- **Status**: Done
- **Priority**: MUST

**As a** security reviewer
**I want** an end-to-end integration test that exercises a real
request flow which deliberately attempts to log raw BCrypt hashes,
`Bearer <jwt>` headers, and bare JWT-shaped substrings, capturing
the JSON-encoded output from the production
`LoggingEventCompositeJsonEncoder` configured by
`logback-spring.xml`, and asserts the raw forms NEVER appear in
the captured output
**So that** REQ-SEC-004 is regression-locked at the appender
boundary — no future contributor can break redaction by adding a
new logger statement, swapping the encoder, replacing a
conversion-word reference, or accidentally bypassing
`%redactedMsg` while reusing the JSON encoder for a new appender.

### Description

US-03-001 shipped `SensitiveDataMaskingConverter` and its
focused unit test (`SensitiveDataMaskingConverterTest`). That
test asserts the converter's regex patterns mask the expected
inputs in isolation. It does NOT assert the production
appender chain routes through the converter — that contract is
enforced today only by the literal `%redactedMsg` in
`logback-spring.xml`. After US-15-003 swaps the encoder to
`LoggingEventCompositeJsonEncoder` with a nested `<pattern>`
provider, the routing changes shape; this story locks the new
shape with a deliberately adversarial test.

The test:
1. Boots a `@SpringBootTest` (PostgresIntegrationTest base).
2. Programmatically attaches an extra appender — a
   `OutputStreamAppender<ILoggingEvent>` writing into a
   `ByteArrayOutputStream` — to the root logger, configured
   with **the exact same encoder configuration as the production
   CONSOLE appender** (re-instantiated via Logback's Joran
   parser reading
   `logback-spring.xml`). The probe is the production encoder;
   the production appender (System.out) is left untouched.
3. Calls a known endpoint (e.g.,
   `POST /auth/login` with wrong credentials) so the
   `GlobalExceptionHandler` logs an ERROR with the
   `Authorization` header value if it were to be logged. Plus,
   issues a controlled log call from a test-only
   `RedactionProbeController` (test classpath, lives under
   `src/test/java/.../observability/`) that logs each of the
   three patterns explicitly:
   - `log.info("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test.signature")`
   - `log.info("BCrypt hash: $2b$12$abcdefghijklmnopqrstuv.ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")`
   - `log.info("Bare JWT: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature")`
4. Drains the captured byte stream, parses each line as JSON,
   and asserts the `message` field's value does NOT contain
   any of the three raw substrings.

### Acceptance criteria

- A test-only controller
  `RedactionProbeController` lives under
  `src/test/java/.../infrastructure/web/observability/` (NOT
  on the main classpath — per the EPIC-CR1-002 convention) and
  exposes `POST /_redaction_probe/log-bearer`,
  `POST /_redaction_probe/log-bcrypt`,
  `POST /_redaction_probe/log-jwt`. Each endpoint emits one
  INFO log statement containing the deliberately sensitive
  substring shown above. The controller is loaded only via the
  integration test's `@SpringBootTest(classes = …)` enumeration
  — it MUST NOT be a component-scanned bean (so it can never
  leak into production).
- A new test
  `infrastructure/web/observability/SensitiveDataRedactionIntegrationTest`
  extends `PostgresIntegrationTest` and:
  - Resolves the root logger via
    `(ch.qos.logback.classic.Logger)
       LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)`.
  - Programmatically attaches a
    `OutputStreamAppender<ILoggingEvent>` whose encoder is the
    same `LoggingEventCompositeJsonEncoder` configuration the
    production CONSOLE appender uses. The appender writes into a
    test-owned `ByteArrayOutputStream`.
  - Detaches the probe appender via
    `@AfterEach` to leave the global logger state pristine.
  - Triggers each of the three probe endpoints AND one real
    failed `POST /auth/login` (so the redaction path is also
    exercised through `GlobalExceptionHandler`).
  - Drains the byte stream, splits by newline, parses each
    non-empty line as JSON via Jackson.
  - For each captured log record, asserts the parsed
    `message` field does NOT contain ANY of:
    - `Bearer eyJ` (any literal BCrypt hash prefix followed by
      a base64-ish segment of the test's known signature)
    - `$2b$12$abcdefghijklmnopqrstuv` (the test BCrypt hash)
    - `eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0` (the test JWT
      header.payload portion)
  - For each captured log record, asserts the JSON line parses
    cleanly into a `Map<String, Object>` — the redaction MUST
    NOT corrupt the JSON shape (e.g., by leaving an unescaped
    quote in the `message` field).
- A "happy path" assertion: the controlled probe message
  `log.info("non-sensitive content")` makes it through the
  encoder unchanged — pinning that the converter only masks the
  matching patterns and does not blank out normal log content.
- The test does NOT depend on the production CONSOLE appender
  being silenced; it attaches an extra appender alongside.
- The probe controller's package
  `infrastructure.web.observability.dev` (test classpath) is
  added to `LayeringArchTest`'s "no dev controllers on main
  classpath" rule (which currently catches
  `infrastructure.web.dev..`). Extending the rule to also catch
  `..web.observability.dev..` keeps the test-only / main
  classpath boundary explicit.
- A small DOC note in `EXCEPTIONS.md` (or a new entry in
  `implementation/DESIGN-CHOICES.md` — the latter is preferred
  because it is a runtime behavior choice, not an exception
  policy) captures: "Sensitive-data redaction is enforced at
  the encoder boundary via the `%redactedMsg` conversion word;
  the regression test in
  `SensitiveDataRedactionIntegrationTest` pins it across the
  production CONSOLE appender shape."
- The test class Javadoc explains *why* an end-to-end
  test exists alongside the focused
  `SensitiveDataMaskingConverterTest`: the unit test pins the
  regex; the integration test pins the encoder routing.

### Out of scope

- New redaction patterns (e.g., AWS access keys, OpenAI API
  key prefixes). The current converter masks BCrypt / Bearer /
  JWT; adding patterns is a converter-level change with its
  own unit test. This story exercises the existing three.
- Asserting redaction in `stackTrace` fields. The
  `ShortenedThrowableConverter` from the Logstash encoder
  serializes exception messages — if an exception message
  ever contained a Bearer header, it would not be redacted
  today. Documenting this in `DESIGN-CHOICES.md` as a known
  limitation is sufficient for v1; adding `%redactedMsg`
  coverage on the stack-trace path is a follow-up if a real
  case appears.
- A property-based test that fuzzes inputs into the
  converter. The unit test already covers the regex; this
  story is about routing, not about regex coverage.

### Requirements coverage

`REQ-SEC-004` (sensitive-data redaction across all log
appenders — pinned end-to-end), `REQ-NFR-002` (regression
test).

### Design references

§8.7 logging, §9.2 GlobalExceptionHandler (the failed-login
log path the test exercises). The converter ships in
`infrastructure/web/error/SensitiveDataMaskingConverter`
(US-03-001).

### Dependencies

US-15-003 (the JSON encoder under test — without it the test
would pin the old pattern shape). US-03-001 (the
`SensitiveDataMaskingConverter` and its conversion-word
registration in `logback-spring.xml`).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-15-001  | Spring Boot Actuator dependency + `GET /actuator/health` exposed outside `/api/v1`                  | SHOULD   | Done   |
| US-15-002  | `CorrelationIdFilter` — generate / propagate `X-Correlation-Id`, populate MDC, expose on response   | SHOULD   | Done   |
| US-15-003  | JSON Logback encoder (`LoggingEventCompositeJsonEncoder`) preserving `%redactedMsg` + per-package log-level smoke test | SHOULD | Draft |
| US-15-004  | Sensitive-data redaction regression integration test across every appender                          | MUST     | Done   |

EPIC-15 is **Done** when all four stories above are `Done`. At that
point the application exposes a minimal Actuator health probe at
`/actuator/health`, every request carries an `X-Correlation-Id`
that flows through both the response header and the JSON log
encoder, log records are JSON with per-package level control via
Spring properties, and the redaction contract from
`REQ-SEC-004` is locked end-to-end. The next step is EPIC-16
(build, packaging & AWS deployment), which consumes the health
probe for ELB wiring and the JSON log output for CloudWatch
ingestion.
