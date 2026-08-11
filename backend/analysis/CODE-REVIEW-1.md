# Backend Code Review #1 — EPIC-01 / EPIC-02 / EPIC-03

**Date**: 2026-05-06
**Reviewer**: Claude (automated review)
**Scope**: `backend/src/` — 108 main Java files, 35 test files. Covers EPIC-01 foundation, EPIC-02 persistence, EPIC-03 JWT auth.

---

## Executive summary

The codebase is in good shape and the three delivered EPICs reach their stated acceptance criteria. The hexagonal layering is real (not just folder-deep): the domain has zero Spring/JPA/Jackson imports, repository ports live with their aggregate, technical ports live in `application/`, and adapters live in `infrastructure/`. ArchUnit codifies the rule. EPIC-03 is the most security-sensitive layer and gets the security fundamentals right: HS256 with a 32-byte minimum secret, fail-fast on missing secret, generic `INVALID_CREDENTIALS` everywhere a leak could occur, JWT denylist with both read-time and scheduled eviction, BCrypt cost 10, sensitive-data Logback redaction, and a clean filter chain that funnels every 401/403 through the same `GlobalExceptionHandler`. The tests exercise expired/tampered/denylisted/non-Bearer/anonymous paths plus the byte-identical wrong-password vs unknown-email body comparison.

What needs attention is mostly second-order. **Email lookups are case-sensitive end to end** — `Alice@Example.com` and `alice@example.com` will register as two distinct accounts and a user who signs up with one casing then signs in with another will get a generic 401. **`PingController` and `MeProbeController` are exposed under `@Profile("dev")`** but the non-test integration tests activate `dev` via `@ActiveProfiles("dev")` — which means if anyone ever runs the JAR with `SPRING_PROFILES_ACTIVE=dev` (a common local-debug habit) they ship a probe endpoint that echoes the principal. **`InMemoryJwtDenylistAdapter` uses `Clock.systemUTC()` indirectly via the bean while `JjwtTokenServiceAdapter` hard-codes `Clock.systemUTC()` in its public constructor**, which is asymmetrical and made the logout test work around it with a custom `MutableClock`. There are also a handful of test-quality gaps (no test that an empty `Authorization` header without `Bearer` prefix is treated as anonymous-rather-than-401, no test that the `/api/v1` prefix change cascades to the security rules in `SpringSecurityConfig`, ArchUnit doesn't enforce `application/**` is free of Spring AI / JPA — though it covers Spring MVC and JPA explicitly).

**At-a-glance verdict**: Solid foundation, ready for EPIC-04 with **0 CRITICAL must-fix items**, **3 HIGH items worth fixing before EPIC-04 starts using the User aggregate from another bounded context**, and a handful of MEDIUM/LOW polish items.

---

## Findings

### CRITICAL

_None._

### HIGH

#### [HIGH] Email lookups are case-sensitive end to end (login bypass risk)

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/domain/user/Email.java`, `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/persistence/springdata/UserJpaRepository.java`, `backend/src/main/resources/db/migration/V001__init_schema.sql` line 16.
- **Observation**: `Email` is constructed with the raw `value` and never lowercased. `UserJpaRepository.findByEmail(String)` translates to `where email = ?` (case-sensitive in PostgreSQL). The `users.email` column has only a `unique` constraint, not a case-insensitive uniqueness via `lower(email)` or `citext`. Result: if an admin seeds `Alice@example.test` and the user later signs in as `alice@example.test`, login fails with `INVALID_CREDENTIALS` even though the credentials are correct. Worse, EPIC-05 user creation (admin-only) could let an admin accidentally create both `Alice@example.test` and `alice@example.test` as two distinct rows.
- **Why it matters**: `REQ-USR-002` says email is unique across the platform — emails are inherently case-insensitive (RFC 5321 §2.4 only the local-part is technically case-sensitive but virtually all mail systems treat addresses case-insensitively). The current behavior is a footgun that will produce confusing support tickets and could collide with `REQ-AUTH-009` (no leak about email existence) — a user who doesn't realize their casing differs gets a 401 indistinguishable from "wrong password".
- **Recommendation**: Normalize at construction time inside `Email` (`.toLowerCase(Locale.ROOT)`) and add a Flyway migration for either `unique (lower(email))` or migrate the column to `citext`. Alternatively keep `Email` case-preserving but lowercase in `UserJpaRepository` finder + `lower(email)` unique index. Update `EmailTest` to assert canonicalization. This is best fixed before EPIC-04/05 ship — once API-key-tied SYSTEM principals and admin user-management endpoints land, retrofitting normalization gets risky.

#### [HIGH] `dev` profile leaks `PingController` and `MeProbeController` under `/api/v1`

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/web/dev/PingController.java`, `backend/src/test/java/com/cognizant/emk/multiagent/infrastructure/web/security/MeProbeController.java`.
- **Observation**: `MeProbeController` is on the **test classpath** (good) but `PingController` is on the **production classpath** under `@Profile("dev")`. Anyone running the packaged JAR with `SPRING_PROFILES_ACTIVE=dev` (a common operator habit, or accidental) ships `GET /api/v1/ping` returning `{"ok":true}`. That's harmless on its own, but it sets a precedent that "dev" is the convention-for-debug-endpoints, while every EPIC-03 integration test uses `@ActiveProfiles("dev")` — there is no production profile distinct from dev.
- **Why it matters**: `MeProbeController` lives in `src/test/java` (so it's not in the JAR — confirmed by Maven's standard packaging), but the convention is fragile: the next person adding a test probe might put it in `src/main/java` "for parity" with `PingController`, and that probe would echo `principal.id/email/role` which is exactly the kind of footprint an attacker uses to confirm token validity. There is no production profile (e.g. `prod`) defined to suppress dev controllers in deployment.
- **Recommendation**: (a) Move `PingController` to `src/test/java` like `MeProbeController` — it is already only used by `BasePathConfigTest` / `SpringSecurityConfigTest`. (b) Or, if a runtime smoke endpoint is wanted, replace it with `GET /actuator/health` (already permit-all in `SpringSecurityConfig`) and delete `PingController` entirely. (c) Document in `application.yaml` that the default profile is the production profile and `dev` is for local dev only. EPIC-15 was scheduled to ship Actuator anyway — pulling that one slice forward removes the need for `PingController`.

#### [HIGH] `JjwtTokenServiceAdapter` hard-codes `Clock.systemUTC()`, ignoring the `Clock` bean

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/security/JjwtTokenServiceAdapter.java` lines 47–53.
- **Observation**: The Spring-injected constructor reads `properties.security().jwt().signingSecret()` and `properties.security().jwt().lifetime()` but then unconditionally passes `Clock.systemUTC()` to the package-private constructor. The `ClockConfig` `@Bean` is consumed by `InMemoryJwtDenylistAdapter` and `ChangeOwnPasswordService`, but **not** by the JWT adapter. `LogoutEndpointIntegrationTest` documents this with a long Javadoc explaining "the JJWT adapter pins to `Clock.systemUTC()` (not the Spring-managed bean), so token issuance and signature-time verification keep using real wall-clock time. Only the denylist's view of 'now' is virtualized."
- **Why it matters**: This is a subtle correctness/testability split. Half the codebase honors a single Clock bean; the JWT adapter does not. It directly forces `LogoutEndpointIntegrationTest` to write a custom `MutableClock` and reset it between tests. More concerning: when EPIC-13 lands a Bucket4j rate-limit filter that should also use the same Clock, the asymmetry will compound. There is no test that exercises "JWT issued at virtualized now `T`, then verify at virtualized now `T+lifetime+1s`" — the expired-token test forges a token directly with JJWT.
- **Recommendation**: Inject the `Clock` bean and use it for `now` in both `issue` and `verify`. The package-private test constructor already accepts a `Clock`. Doing this also lets `JjwtTokenServiceAdapterTest` exercise time-based branches without `Instant.now()` and removes the workaround in `LogoutEndpointIntegrationTest`.

### MEDIUM

#### [MEDIUM] Disabled-account check leaks via timing oracle (subtle)

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/application/auth/LoginService.java` lines 36–45.
- **Observation**: `LoginService.login` always runs `userRepository.findByEmail` first; if absent, throws immediately. If the email exists, it then runs the BCrypt check (~50–100 ms at cost 10). That's a **classic timing oracle**: an attacker can distinguish "email exists" from "email does not exist" by the response time, even though the response body is byte-identical. `REQ-AUTH-009` says "without leaking whether the email exists or the credential format was wrong" — strictly speaking, the body never leaks, but timing does. The integration test `LoginEndpointIntegrationTest.unknown_email_returns_401_with_body_byte_identical_to_wrong_password` asserts only the body, not timing.
- **Why it matters**: At v1's 64-user scale this is not exploitable in any practical sense, but it's an honest deviation from the "no leak" spirit of `REQ-AUTH-009`. Real implementations typically run BCrypt against a sentinel hash even on email-not-found to equalize timing. Worth a comment or a follow-up.
- **Recommendation**: Either (a) accept the limitation explicitly in a code comment + a known-issue note (lowest cost, fine for v1), or (b) on `findByEmail.isEmpty()`, run `passwordHasher.matches(command.password(), DUMMY_BCRYPT_HASH)` to consume the same time budget. Document the choice.

#### [MEDIUM] `JwtTokenService.verify` swallows `Email`/`Role` `ValidationException`s into `InvalidCredentialsException`

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/security/JjwtTokenServiceAdapter.java` lines 92–110.
- **Observation**: The `try/catch (RuntimeException)` block catches `ValidationException` thrown by `new Email(claims.getSubject())`, `IllegalArgumentException` thrown by `Role.valueOf` for unknown roles, and `IllegalArgumentException` thrown by `UUID.fromString` for malformed UIDs — and all of them become `InvalidCredentialsException`. That's the desired outer behavior, but inside the catch all `RuntimeException`s are caught indiscriminately. If the call path ever surfaces a transient `RuntimeException` from JJWT (e.g. an `OutOfMemoryError`-wrapping or a transient parser issue), it gets reported to the client as 401 instead of 500.
- **Why it matters**: Catching `RuntimeException` instead of the specific JJWT/`IllegalArgumentException`/`ValidationException` types is an anti-pattern that obscures real bugs. It also hides JVM errors like `IllegalStateException` from `Keys.hmacShaKeyFor` if the key is somehow nulled at runtime.
- **Recommendation**: Catch `JwtException` (the JJWT base type), `IllegalArgumentException`, and `ValidationException` explicitly. Let other `RuntimeException`s propagate and become 500.

#### [MEDIUM] `ForcedPasswordChangeFilter` issues a DB read on every request

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/web/security/ForcedPasswordChangeFilter.java` line 68.
- **Observation**: Every authenticated request runs `userRepository.findById(principal.id())` to read the `mustChangePassword` flag. The `UserPrincipal` already came from a verified JWT — the flag could be put into the JWT claims at issuance time and read from `principal` instead of going to the DB. Today, with 64 users, the DB hit is negligible; with 64 concurrent users + Spring Boot's default Hikari pool it's still fine; but every protected request now touches the DB even when no business logic needs the user row.
- **Why it matters**: This is intentionally cautious — a DB read picks up a flag flipped by another user's password change without waiting for token expiry — but the design (`REQ-AUTH-006`) explicitly says "Issuing a new JWT does NOT actively revoke any previously issued, still-valid JWT." Reading the flag every request is more stringent than the design demands. It also creates a per-request pool dependency: a slow DB hangs every authenticated call.
- **Recommendation**: Either (a) keep as-is and document the tradeoff (acceptable for v1), or (b) put `mustChangePassword` into the JWT and re-read from the DB only on the password-change endpoint itself. Option (b) makes the filter trivially fast and removes the DB dependency for read-only endpoints. The Javadoc on the filter even mentions "A simple per-request cache (request attribute) is acceptable but optional" — that is a tell that the AC author saw the cost.

#### [MEDIUM] `ArchUnit` does not enforce that `application/**` is free of Spring AI

- **Location**: `backend/src/test/java/com/cognizant/emk/multiagent/arch/LayeringArchTest.java` lines 62–71.
- **Observation**: The rule `application_does_not_use_spring_mvc_or_jpa` lists `org.springframework.web..`, `jakarta.persistence..`, `org.hibernate..` — but **not** `org.springframework.ai..`. EPIC-03 Definition of Done says "`application/**` carries no JPA/Spring AI imports". Today EPIC-03 has no temptation to import Spring AI from application, but EPIC-09's `LlmChatClient` port and `ChatRequest` records are about to land in `application/chat/`, and the rule would not catch a slip.
- **Why it matters**: Layering rules are exactly the kind of test that should fail loudly the moment someone violates it; a missing entry means a regression goes unnoticed.
- **Recommendation**: Add `"org.springframework.ai.."` to the `application_does_not_use_spring_mvc_or_jpa` rule (and rename it to `application_does_not_use_framework_io_libraries`). Also consider adding `"io.jsonwebtoken.."` since JJWT must stay in infrastructure too.

#### [MEDIUM] Persistence integration tests rely on a developer-installed Postgres, not Testcontainers

- **Location**: `backend/src/test/java/com/cognizant/emk/multiagent/persistence/PostgresIntegrationTest.java` lines 15–30.
- **Observation**: EPIC-02 scope says "Persistence integration tests using **Testcontainers** PostgreSQL." The actual test base class connects to a locally-installed Postgres at `multi_agent_test`, with the developer expected to `psql -U postgres -c "CREATE DATABASE..."` once. The CLAUDE.md notes "Local environment will not allow to use any docker container" so Testcontainers can't run locally — but Testcontainers also can't run on the CI box if CI has no Docker. There is no CI yet (no `.github/workflows`, no Jenkinsfile checked in), so the practical impact today is zero, but the divergence from the EPIC-02 scope is real and means that integration tests need ad-hoc setup on every developer machine and on every CI runner.
- **Why it matters**: The current setup blocks running `mvn test` without manual Postgres provisioning. CI integration will require a Postgres service container or a switch to Testcontainers. The README/docs do not flag this prerequisite outside of the test class Javadoc.
- **Recommendation**: Document the manual Postgres setup in `backend/CLAUDE.md` (it's only mentioned inside the test class). When CI lands, decide between (a) Testcontainers with Docker (fastest dev loop on Linux/macOS, no-go for the local Windows machine without Docker), (b) embedded Postgres (e.g. zonky/embedded-database-spring-test) — works without Docker, or (c) a CI-provisioned Postgres service container. This is documentation/policy work, not code.

#### [MEDIUM] `ProblemDetails` `errors[]` collapses empty list to `null` — surprising for clients

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/web/error/ProblemDetails.java` lines 24–28.
- **Observation**: The compact constructor sets `errors` to `null` when an empty list is passed in. Combined with `@JsonInclude(JsonInclude.Include.NON_NULL)`, that means an absent vs empty `errors` array is indistinguishable on the wire. The OpenAPI spec lists `errors` as optional, so this is technically conformant — but downstream clients that do `body.errors().isEmpty()` will NPE; the typed Java client gets a `null` instead of a safer empty list.
- **Why it matters**: Minor consistency issue. The current handler never produces an empty list at construction, so the constructor branch is dead code today, but it sets a confusing precedent.
- **Recommendation**: Either remove the empty→null collapse and rely on `@JsonInclude(JsonInclude.Include.NON_EMPTY)` to suppress an empty array on serialization, or keep the collapse and document it. Pick one.

### LOW

#### [LOW] `JjwtTokenServiceAdapter` constructor uses field `@Autowired`

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/security/JjwtTokenServiceAdapter.java` line 47.
- **Observation**: The Spring-bound constructor is annotated `@Autowired`. It is the only public constructor; Spring does not need the annotation.
- **Why it matters**: The `JAVA-CODING-STANDARD.md` says "Prefer constructor injection over `@Autowired` field injection" — which this respects, but the `@Autowired` annotation here is redundant and slightly inconsistent with every other adapter in the project (e.g. `BcryptPasswordHasherAdapter`, `InMemoryJwtDenylistAdapter`, `UserRepositoryAdapter`, `LoginService`) where there is no `@Autowired`.
- **Recommendation**: Drop the `@Autowired`.

#### [LOW] `ForcedPasswordChangeFilter` exact-match path comparison rejects trailing-slash variants

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/web/security/ForcedPasswordChangeFilter.java` lines 80–87.
- **Observation**: The filter uses `passwordChangePath.equals(path)` (exact match). If a client sends `PUT /api/v1/auth/password/` (trailing slash) the filter does NOT allow it through — it would be blocked with `MUST_CHANGE_PASSWORD`. Spring Boot 3 disabled trailing-slash matching by default, so the controller would then 404, but the filter's behavior is to misclassify the request as "not on the allow-list" rather than "let the chain decide".
- **Why it matters**: An accidental trailing slash from a frontend would surface as 403 `MUST_CHANGE_PASSWORD` instead of the expected 404 — confusing during the only flow where the user actually has to use this exact endpoint.
- **Recommendation**: Use `AntPathMatcher` or strip trailing slashes before comparison. Or — simpler — use `request.getServletPath()` and a matcher pair that allows exactly the documented method+path.

#### [LOW] `LoginEndpointIntegrationTest` and friends hand-roll a JSON parser

- **Location**: `backend/src/test/java/com/cognizant/emk/multiagent/infrastructure/web/auth/LoginEndpointIntegrationTest.java` lines 192–206 (and three peers in the same package).
- **Observation**: Each integration test rolls its own `extract(json, field)` substring helper. The Spring Boot test starter already brings Jackson; the tests deliberately avoid it ("Avoids pulling in Jackson at test time"). That avoidance is unjustified — Jackson is on the classpath, the `MockMvc` setup uses it for `jsonPath`, and the helper is a bug magnet (escaped quotes inside string values would break it).
- **Why it matters**: Code smell, not a correctness bug today. Future tests that need to extract numeric fields, arrays, or escaped strings will get bitten.
- **Recommendation**: Use Spring's `JsonPath` or Jackson `ObjectMapper`. Already used in many other places (`jsonPath("$.token")` lives in the same files).

#### [LOW] `UserJpa` defines explicit setters but never enforces invariant on `email`

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/persistence/entity/UserJpa.java` lines 65–70.
- **Observation**: The setter `setEmail(String)` accepts any string, bypassing the `Email` value object's policy. The mapper goes through `new Email(...)` on the way out, but in-place JPA entity mutation could persist invalid values.
- **Why it matters**: Today no code path mutates `email` on an existing JPA entity (the adapter goes through `UserMapper.toJpa(user)` which constructs a fresh `UserJpa`). But the setter exists, and any future code that uses it bypasses the policy.
- **Recommendation**: Either remove `setEmail` (and the other unused setters: `setPasswordHash`, `setRole`, `setDisabled`, `setMustChangePassword`, `setUpdatedAt`) since the adapter creates new instances, or make them package-private. Alternatively, keep them and add a comment that domain mutations go through `UserMapper`.

#### [LOW] `BcryptPasswordHasherAdapter.matches` does not null-guard `rawPassword`

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/security/BcryptPasswordHasherAdapter.java` lines 26–32.
- **Observation**: A null `rawPassword` would NPE on `rawPassword.cleartext()`. The port contract requires `Password` to be non-null (and `Password` itself rejects null at construction), but the defensive check on `storedHash` suggests the author wanted to be tolerant.
- **Why it matters**: Symmetry. Today the adapter only sees `Password` instances created in controllers, where the binding layer prevents null — but the adapter's port comment says "Implementations MUST return `false` (and not throw) when `storedHash` is malformed" which is the same defensive contract that should logically apply to the password.
- **Recommendation**: Optional — add `if (rawPassword == null) return false;` for symmetry with the storedHash branch.

#### [LOW] `Email` regex is permissive

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/domain/user/Email.java` line 17.
- **Observation**: `^[^\s@]+@[^\s@]+\.[^\s@]+$` accepts strings like `a@a.a`, `..@.a..`, etc. The Javadoc says "RFC 5322-style check (good-enough for v1)" and this is intentional. The acceptance criterion in US-03-002 explicitly cites this regex as acceptable.
- **Why it matters**: It's a deliberate choice; no action needed unless v1 hits real-world emails like `a+b@c.co.uk` or international domains. The current regex does accept those (only `\s` and `@` are forbidden in the local/domain parts). Bare `a@b` would fail (good).
- **Recommendation**: No change. Documenting here for completeness.

#### [LOW] `application.yaml` has no `validation` configuration to fail fast on missing `DB_*`

- **Location**: `backend/src/main/resources/application.yaml` lines 19–22.
- **Observation**: `${DB_URL}` resolves to literal `null` (the string) if the env var is missing, not a startup failure. Spring's datasource autoconfig will then fail with a confusing "Driver claims to not accept jdbcUrl, null" rather than the clear "DB_URL is required" message. `JWT_SIGNING_SECRET` has the right shape (bound to `ApplicationProperties.Security.Jwt#signingSecret` with `@NotBlank @Size(min=32)` so an empty value fails fast) but the DB config does not.
- **Why it matters**: Day-1 ops ergonomics. Anyone forgetting to set `DB_URL` gets an opaque message instead of the documented "fail fast at startup" the comment in `application.yaml` claims.
- **Recommendation**: Add `${DB_URL:?DB_URL must be set}` syntax in `application.yaml` (Spring/Boot's resource value style supports the colon-question default for required-with-error). Or bind the datasource URL through `ApplicationProperties` with `@NotBlank`.

#### [LOW] `AgentJpa` and `ConversationJpa` `@ManyToOne(fetch = LAZY)` are correct, but no `@OneToMany` on the parent side — flagged for future EPICs

- **Location**: `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/persistence/entity/AgentJpa.java` line 22; `ConversationJpa.java` line 22.
- **Observation**: All `@ManyToOne` associations correctly use `FetchType.LAZY`. There are no `@OneToMany` collections on the parent side, which avoids the classic N+1 / accidental EAGER trap. DB cascade is the source of truth, which the design explicitly mandates.
- **Why it matters**: Positive — but worth flagging that EPIC-06 (agents) and EPIC-10 (conversations) will be tempted to add `@OneToMany` collections; if they do, they should default to LAZY and never `cascade` from JPA.
- **Recommendation**: No change today. Add a guideline note to the design (or to a future code review) that JPA-side cascades stay disabled and DB FK cascades are the single source of truth.

#### [LOW] `LayeringArchTest.domain_classes_live_in_a_known_bounded_context` allows `..domain..` (any subpackage)

- **Location**: `backend/src/test/java/com/cognizant/emk/multiagent/arch/LayeringArchTest.java` lines 73–96.
- **Observation**: The rule lists `domain.shared`, `domain.user`, `domain.agent`, `domain.conversation`, `domain.tool`, `domain.mcp`, `domain.ratelimit`, `domain.auth`, **and** `domain.<name>..` for each — i.e. it allows any nested subpackage under each context. That's fine, but the test passes today because every domain class lives directly in one of those packages; if someone created `domain.unknown`, the rule would catch it. Good.
- **Why it matters**: Just documenting the rule scope is correct.
- **Recommendation**: No change.

### POSITIVE

- **Hexagonal layering is real**. The domain truly has zero Spring/JPA/Jackson imports (verified by ArchUnit and by spot-checks of every domain file). `Email`, `Password`, `User`, `UserId`, `Role`, `UserRepository`, `Principal`, `UserPrincipal`, `BusinessException`, `ValidationException`, `NotFoundException`, `ForbiddenException`, `ConflictException`, `InvalidCredentialsException`, `MustChangePasswordException`, `UserNotFoundException` — all pure Java. The repository port is in `domain/`, the BCrypt and JJWT ports are in `application/`, the adapters are in `infrastructure/`. This matches design §2.2 / §3 exactly.

- **Generic `INVALID_CREDENTIALS` everywhere a leak could occur**. Login (`LoginService.java` lines 36–45) deliberately surfaces the same exception for unknown email, wrong password, and disabled account — and `LoginEndpointIntegrationTest.unknown_email_returns_401_with_body_byte_identical_to_wrong_password` byte-compares the response. Same exception is raised by `JwtAuthenticationFilter` on missing/invalid/expired/denylisted token, and both the `AuthenticationEntryPoint` and `AccessDeniedHandler` route through the `GlobalExceptionHandler`. `InvalidCredentialsException` carries a static `"Authentication failed."` literal so callers cannot accidentally embed user-supplied data.

- **Sensitive-data masking has good test coverage** (`SensitiveDataMaskingConverterTest`): JWT, BCrypt hash, `Bearer ...` (with both alphabetic and real-JWT values), multiple tokens in one message, and the `null`/empty edge cases. The Logback config wires the converter via `%redactedMsg` instead of `%m` — meaning every appender uses the masked output by default. Future EPICs that swap in a JSON layout will inherit the masking.

- **`/api/v1` prefix is genuinely centralized**. `WebConfig.configurePathMatch` adds it once for every `@RestController`. `SpringSecurityConfig` reads `properties.api().basePath()` and uses it for both the login and the `apiPattern`. `ForcedPasswordChangeFilter` does the same for its allow-list. `BasePathConfigOverrideTest` proves changing `app.api.base-path` to `/api/v2` relocates the `PingController` accordingly. No controller hard-codes `/api/v1`.

- **Filter ordering in `SpringSecurityConfig`** is correct: JWT auth runs before `UsernamePasswordAuthenticationFilter` (so the security context is populated for downstream filters); `ForcedPasswordChangeFilter` runs immediately after JWT auth (so it sees the populated principal). The chain is stateless, CSRF disabled, form login disabled, http basic disabled — all appropriate for a JWT-only API.

- **Fail-fast on missing/short signing secret is verified** by both bean-validation in `ApplicationProperties` (`@NotBlank @Size(min = 32)`) and a defensive check in `JjwtTokenServiceAdapter`'s package-private constructor. `JwtSigningSecretFailFastTest` uses an `ApplicationContextRunner` to verify both an empty and a short secret block startup.

- **Idempotent logout via `ConcurrentHashMap.put`** is a clean choice — no extra branch in `LogoutService`, the denylist's idempotency is documented in its Javadoc, and `LogoutServiceTest.calling_twice_with_the_same_jti_is_idempotent_at_the_use_case_boundary` asserts the contract.

- **Read-time + scheduled eviction on the denylist** keeps memory bounded under both steady load (read-time eviction wins) and pathological "tokens were denylisted but never read again" cases (scheduled sweep wins). The 60-second `@Scheduled(fixedDelay = 60_000)` is a sensible cadence given the 30-minute JWT TTL.

- **Cascade rules are tested with real Postgres** (`CascadeIntegrationTest`): both user→agent→conversation→message and agent→conversation→message paths. The schema's `on delete cascade` from `users → conversations` (denormalized FK) covers the case where a user is deleted but somehow their agents are not — defensive in depth.

- **Hibernate `ddl-auto=validate`** in `application.yaml` (line 28) plus `HibernateValidateContractTest` actively verifies that the JPA entities match the migrated schema. A column rename in either side would fail startup.

- **DTOs are records** (`LoginRequest`, `LoginResponse`, `ChangePasswordRequest`, `ProblemDetails`, `ProblemDetails.FieldError`), constructor injection is used everywhere, no Lombok in the codebase, no field `@Autowired` (with one stylistic exception flagged above). The codebase actually follows the standards it claims to.

- **`UserPrincipal` carries `jti` and `expiresAt`** instead of introducing a request-scoped holder. This is the simpler of the two options US-03-010 explicitly offered and the choice is documented in the Javadoc — a nice piece of "we considered the alternative and chose intentionally" engineering.

- **`PasswordHasher.matches` returns `false` for malformed `storedHash`** (rather than throwing) and `BcryptPasswordHasherAdapterTest` explicitly tests that case. Defensive, contract-conformant, and avoids surprising 500s if the database ever ends up with a bad hash.

- **The integration tests reach for the right invariants**: byte-identical bodies for unknown-email vs wrong-password, JWT denylist size assertions, clock-driven denylist eviction, post-password-change re-login round-trip, MUST_CHANGE_PASSWORD before/after flag flip with the same JWT, MUST_CHANGE_PASSWORD allow-list verification for both `/auth/password` and `/auth/logout`, denylisted/expired/tampered/non-Bearer JWT all returning the same generic body. These are real behavioral tests, not "did the bean wire up" smoke tests.

---

## Section-by-section assessment

### Architecture & layering

Hexagonal layering is real. The package layout under `com.cognizant.emk.multiagent` matches the design's `domain` / `application` / `infrastructure` triad, with package-by-context inside each layer. Repository ports (e.g. `UserRepository`) live in the domain alongside their aggregate; technical ports (`PasswordHasher`, `JwtTokenService`, `JwtDenylist`) live in `application/auth/`; adapters (`UserRepositoryAdapter`, `BcryptPasswordHasherAdapter`, `JjwtTokenServiceAdapter`, `InMemoryJwtDenylistAdapter`) live in `infrastructure/`. The domain compiles without Spring/JPA/Jackson on the classpath. The application uses `@Service` / `@Transactional` (acceptable per the design) but no Spring MVC / JPA / Spring AI imports.

ArchUnit codifies the rule with five focused tests, each one assertion. The rules are tight, with one gap noted under MEDIUM (`org.springframework.ai..` is not in the application-forbidden list, and JJWT `io.jsonwebtoken..` is also not enforced). The bounded-context rule lists every documented context — a class created under `domain.unknown` would fail.

The `Application.java` Spring Boot main is minimal and the `@EntityScan` narrowing to `infrastructure.persistence.entity` correctly prevents Hibernate from interpreting unrelated packages.

### Domain layer

The value objects are well-shaped:
- `Email` validates length and a regex, throws `ValidationException` with field `"email"`. Documented limitations on the regex are intentional.
- `Password` enforces the policy at construction (≥10, ≥1 uppercase, ≥1 special character) and overrides `toString()` to never leak the cleartext. Tests cover null, too-short, no-uppercase, no-special, and the toString contract.
- `Role` is a clean enum.
- `UserId` is a typed UUID wrapper with non-null check and a static `of(UUID)` factory.
- `User` is a record with non-null checks, `isActive()`, and `withNewPasswordHash(String, OffsetDateTime)` returning a copy with `mustChangePassword=false` and the `updatedAt` bumped. The hash is held as raw `String` (the `Password` value object models cleartext only) — a clean separation.

The exception hierarchy mirrors design §9.1 exactly: `BusinessException` (abstract) → `ValidationException`, `NotFoundException`, `ConflictException`, `ForbiddenException`. Concrete subclasses live in their bounded context: `InvalidCredentialsException` (domain/auth, with the static literal message), `MustChangePasswordException` extends `ForbiddenException` (domain/user), `UserNotFoundException` extends `NotFoundException` (domain/user).

The `Principal` sealed interface declares only `UserPrincipal` for now (`SystemPrincipal` is reserved for EPIC-04, with a Javadoc comment to that effect).

### Application layer

Use cases are interface + `@Service` implementation in the same package, exactly per the design convention. Inputs/outputs are records carrying non-null checks at construction.

- `LoginService`: clean three-step flow, all paths surface `InvalidCredentialsException`, the `mustChangePassword` flag flows up unchanged. No transaction needed (read-only). One subtle timing-oracle deviation flagged under MEDIUM.
- `LogoutService`: thin delegate to `JwtDenylist.add`, idempotent by virtue of the underlying map.
- `ChangeOwnPasswordService`: `@Transactional`, takes `Clock` for testability, throws `UserNotFoundException` for the (realistically unreachable) race-with-delete case, throws `InvalidCredentialsException` for wrong current password.

All use-case Javadocs explain the why, not just the what — and they reference REQ IDs.

### Infrastructure — web (controllers, security, error handling)

- `AuthController`: no class-level `@RequestMapping`; relies on the centralized `/api/v1` prefix. Three methods, each ~5–10 lines, do exactly the binding work. The `parsePassword` helper rethrows `Password` value-object validation errors with the correct field name (`currentPassword` vs `newPassword`) so the response error mentions the field the user supplied.
- `JwtAuthenticationFilter`: `OncePerRequestFilter`, parses Bearer header, calls `verify`, checks denylist, sets `UsernamePasswordAuthenticationToken` with `ROLE_<role>`. On any failure clears the security context and dispatches via `HandlerExceptionResolver` so the `GlobalExceptionHandler` writes the response — no inline JSON-writing.
- `ForcedPasswordChangeFilter`: looks up the user, short-circuits with `MustChangePasswordException` outside the allow-list. Clean; one DB read per request flagged under MEDIUM.
- `SpringSecurityConfig`: stateless, CSRF disabled, CORS configured from `app.cors.allowed-origins`, login + actuator/health permit-all, everything else under `/api/v1/**` authenticated. Custom `AuthenticationEntryPoint` and `AccessDeniedHandler` route through `GlobalExceptionHandler`. Filter ordering is correct.
- `GlobalExceptionHandler`: maps every documented exception to RFC 7807 `application/problem+json`. Covers `ValidationException`, `MethodArgumentNotValidException`, `ConstraintViolationException`, `InvalidCredentialsException`, `MustChangePasswordException`, `ForbiddenException`/`AccessDeniedException`, `NotFoundException`/`NoHandlerFoundException`/`NoResourceFoundException`, `HttpRequestMethodNotSupportedException`, and a `Throwable` catch-all that logs at ERROR and returns 500. Stack traces are never returned. The `instance` field is populated from `req.getRequestURI()` on every response.
- `ProblemDetails`: record matching the `openapi.yaml` schema and design §9.3. Two static factories (with and without per-field errors), `@JsonInclude(NON_NULL)`. One minor consistency issue flagged under MEDIUM.
- `SensitiveDataMaskingConverter`: extends Logback's `ClassicConverter`, registers via `<conversionRule>` in `logback-spring.xml`, masks JWT/BCrypt/Bearer in that order. Static `mask(String)` is unit-testable without a Logback event.

### Infrastructure — persistence (JPA entities, repositories, Flyway)

- `V001__init_schema.sql` matches design §5 column-for-column: UUID PKs with `gen_random_uuid()` defaults, `varchar(254)` email, `varchar(72)` BCrypt hash, `varchar(32)` agent name, `varchar(1024)` description / system_prompt / message content, `varchar(64)` client_id / tool_name / mcp_server_name, FK cascade rules per `REQ-USR-006` / `REQ-AGT-010`, unique `(owner_id, name)` for agents, check constraints (memory_size 1–36, message_count 0–64, role enum). Indexes on `(owner_id, created_at desc, id desc)` for conversations and `(conversation_id, created_at, id)` for messages match the keyset-pagination plan in design §10. The check `parent_agent_id <> member_agent_id` on `agent_team` blocks self-membership at the DB layer (the single-level rule still requires application enforcement).
- `V002__seed_admin.sql` uses Flyway placeholders `${app_bootstrap_admin_email}` and `${app_bootstrap_admin_password_hash}`, sourced from env vars via `application.yaml`. `must_change_password=true` is hard-coded — correct.
- `V003__seed_rate_limit_config.sql` inserts the documented defaults (10/min, 50/hour) into the single-row table.
- JPA entities are classic mutable-with-getters/setters (no Lombok per the standard), default protected no-arg constructor, all `@ManyToOne` use `FetchType.LAZY`, no JPA-side `cascade` or `@OneToMany` (DB cascade is the source of truth). `equals`/`hashCode` based on the business key (`id`, or `clientId`/`jti` for ID-typed entities) — safe with proxies.
- Composite-key entities (`AgentToolJpa`, `AgentMcpJpa`, `AgentTeamJpa`) use `@EmbeddedId` with a record-typed `Id` — concise and idiomatic.
- Spring Data interfaces are minimal — just `JpaRepository<T, ID>` plus the one `findByEmail` finder needed today. No premature finders.

### Configuration & environment

- `application.yaml` externalizes every secret to env vars: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH`, `JWT_SIGNING_SECRET`. `OPENAI_API_KEY`, `BRAVE_API_KEY`, `MCP_FS_BASE` are documented as required by future EPICs.
- `ApplicationProperties` is a `@ConfigurationProperties("app") @Validated` record with nested `Api`, `Cors`, `Security.Jwt` records. Bean-validation propagates via `@Valid`, and `@NotBlank @Size(min = 32)` on `signingSecret` is the load-bearing failover for `REQ-AUTH-010`.
- `WebConfig` adds `app.api.base-path` as a path prefix to every `@RestController` — single-source-of-truth for the base path.
- `ClockConfig` exposes `Clock.systemUTC()`. `SchedulingConfig` enables `@Scheduled`. Both minimal and right-sized.
- One MEDIUM/LOW concern flagged: DB env vars don't fail-fast with a clear message when missing.

### Tests

Strong overall. Coverage breadth and depth are real:

- **Domain unit tests**: `EmailTest` (6 cases incl. 254-char boundary), `PasswordTest` (5 policy branches + toString), `UserTest` (6 cases incl. null guards and `withNewPasswordHash` invariants).
- **Application unit tests**: `LoginServiceTest` (5 cases: happy, mustChangePassword flow, unknown email, wrong password, disabled — verifies `verify(..., never())` on downstream collaborators), `ChangeOwnPasswordServiceTest` (3 cases incl. argument capture for the saved User), `LogoutServiceTest` (2 cases incl. idempotency).
- **Infrastructure unit tests**: `BcryptPasswordHasherAdapterTest` (cost-factor regex, salt randomness, malformed-hash tolerance), `JjwtTokenServiceAdapterTest` (round-trip + 4 negative branches), `InMemoryJwtDenylistAdapterTest` (5 cases incl. concurrency sanity + clock-driven eviction), `SensitiveDataMaskingConverterTest` (7 cases), `GlobalExceptionHandlerTest` (12 cases — every branch).
- **Integration tests** (real Spring context + real Postgres): `LoginEndpointIntegrationTest` (8 cases incl. byte-identical body), `LogoutEndpointIntegrationTest` (2 cases incl. clock-driven denylist sweep), `ChangeOwnPasswordEndpointIntegrationTest` (6 cases), `JwtAuthenticationFilterIntegrationTest` (6 cases incl. tampered/expired/denylisted/non-Bearer), `ForcedPasswordChangeFilterIntegrationTest` (5 cases), `SpringSecurityConfigTest` (4 cases incl. CORS preflight), `BasePathConfigTest` / `BasePathConfigOverrideTest` (4 cases), `JwtSigningSecretFailFastTest` (3 cases), `CascadeIntegrationTest` (2 cases through real DB), `InitSchemaMigrationTest` (3 cases incl. check-constraint probe), `SeedMigrationsTest` (2 cases), `HibernateValidateContractTest`, `RepositoriesContextTest`, `UserRepositoryAdapterIntegrationTest` (3 cases), `PersistenceContextLoadTest`, `ApplicationContextSmokeTest`.

Mocking discipline is good — Mockito is used only for use-case unit tests, where the collaborators are ports. No partial mocks, no spying, no `@MockBean` in adapter tests.

The persistence integration tests use a developer-installed Postgres rather than Testcontainers (flagged under MEDIUM); not a correctness issue but a divergence from EPIC-02 scope.

A few coverage gaps worth noting (none blocking):
- No test that an `Authorization: Bearer` header with empty value is treated as no-credentials vs invalid-credentials.
- No test that overriding `app.api.base-path` propagates to `SpringSecurityConfig` (only to `WebConfig`).
- No test that the JWT filter correctly handles a request to a permit-all endpoint (`/auth/login`, `/actuator/health`) with an invalid `Authorization` header — does it short-circuit or pass through?

### Coding standard compliance

- DTOs: records (`LoginRequest`, `LoginResponse`, `ChangePasswordRequest`, `ProblemDetails`, `LoginCommand`, `LoginResult`, `LogoutCommand`, `ChangePasswordCommand`, `IssuedToken`, `TokenClaims`).
- Constructor injection: yes, throughout. Field `@Autowired` appears once on a constructor (LOW finding) and zero times on fields.
- Lombok: not in the dependency tree, not used.
- Immutability: domain types are records; JPA entities are mutable as JPA requires.
- Functional style: streams are used where appropriate (`MethodArgumentNotValidException` handling, `ConstraintViolationException` handling). No imperative-style code where streams would be cleaner.
- Member ordering: constants → fields → constructors → public methods → private — followed in every file inspected.
- Method size: every method I read fits comfortably on one screen. Helper extraction is used where complexity rises (`GlobalExceptionHandler.body`, `GlobalExceptionHandler.toFieldError`, `AuthController.parsePassword`).

### Exception handling discipline

Follows `EXCEPTIONS.md`:
- Domain exceptions live in domain/ and depend on no Spring class. `BusinessException` is `abstract` and protected-constructor — only its subclasses can be thrown.
- Application layer raises domain exceptions; no separate application exception is needed yet (future EPICs may add `UseCaseExecutionException`).
- Infrastructure layer raises `InvalidCredentialsException` from the JJWT adapter (a domain exception — appropriate because the technical translation is "this token is bad" which is a domain concept).
- `@RestControllerAdvice` is centralized in `GlobalExceptionHandler` — no controller catches anything itself.
- Spring framework exceptions (`MethodArgumentNotValidException`, `ConstraintViolationException`, `HttpRequestMethodNotSupportedException`, `NoHandlerFoundException`, `NoResourceFoundException`, `AccessDeniedException`) are mapped explicitly.
- The catch-all `Throwable` handler logs at ERROR with the full stack trace but returns a sanitized `INTERNAL_ERROR` body.
- `application.yaml` enables `spring.mvc.throw-exception-if-no-handler-found` and disables `spring.web.resources.add-mappings` so unmapped URLs raise `NoHandlerFoundException` instead of bypassing the advice — a subtle but important configuration.

### Spec / OpenAPI contract drift

- `LoginResponse` shape (`token`, `tokenType="Bearer"`, `expiresAt`, `mustChangePassword`) matches `openapi.yaml` `LoginResponse`.
- `ChangePasswordRequest` shape (`currentPassword`, `newPassword`) matches `openapi.yaml` `ChangePasswordRequest`.
- `POST /auth/login` returns `200` with the body above; matches.
- `POST /auth/logout` returns `204` with empty body; matches.
- `PUT /auth/password` returns `204`; matches.
- `ProblemDetails` shape matches the OpenAPI schema (`type`, `title`, `status`, `detail`, `instance`, `code`, `errors`). Codes used today (`VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `MUST_CHANGE_PASSWORD`, `FORBIDDEN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `INTERNAL_ERROR`) are a strict subset of the documented enum.
- `Content-Type: application/problem+json` is set on every error response.
- Controllers do not include `/api/v1` in their `@PostMapping`/`@PutMapping` paths — the prefix is centralized.

No drift identified.

---

## Requirements traceability spot-check

| REQ-ID | Status | Evidence |
|---|---|---|
| REQ-USR-001 (User entity persisted with id, email, hash, role, flags, timestamps) | Implemented | `domain/user/User.java`, `infrastructure/persistence/entity/UserJpa.java`, `V001__init_schema.sql` lines 14–23 |
| REQ-USR-007 (Seeded admin + forced password change) | Implemented | `V002__seed_admin.sql`, `ForcedPasswordChangeFilter.java`, `MustChangePasswordException`, `ChangeOwnPasswordService.java` clears the flag |
| REQ-AUTH-003 (JWT claims `sub`, `role`, `jti`, `iat`, `exp`) | Implemented | `JjwtTokenServiceAdapter.issue` lines 67–80 emits all five plus a custom `uid` |
| REQ-AUTH-004 (30-min default lifetime, configurable) | Implemented | `application.yaml` `app.security.jwt.lifetime: PT30M`; `ApplicationProperties.Security.Jwt.lifetime` |
| REQ-AUTH-009 (Generic auth failure, no leak) | Implemented | `LoginService` collapses unknown-email/wrong-password/disabled to `InvalidCredentialsException`; byte-identical body verified by `LoginEndpointIntegrationTest`. Timing-oracle subtlety flagged under MEDIUM. |
| REQ-AUTH-010 (HS256 + signing secret env, fail-fast) | Implemented | `JjwtTokenServiceAdapter` HS256; `ApplicationProperties` `@NotBlank @Size(min=32)`; `JwtSigningSecretFailFastTest` |
| REQ-AUTH-011 (Logout denylist self-evicting ≤ token exp) | Implemented | `InMemoryJwtDenylistAdapter` read-time + scheduled sweep; `LogoutService`; `LogoutEndpointIntegrationTest` clock-driven eviction |
| REQ-SEC-001 (Password policy ≥10, ≥1 upper, ≥1 special) | Implemented | `domain/user/Password.java` enforces at construction; tests cover all three branches |
| REQ-SEC-002 (BCrypt hashing, no plaintext stored/logged) | Implemented | `BcryptPasswordHasherAdapter` (cost 10); `Password.toString` redacts; verified by `BcryptPasswordHasherAdapterTest` |
| REQ-SEC-004 (No tokens/passwords in logs) | Implemented | `SensitiveDataMaskingConverter` + Logback `<conversionRule>`; `JjwtTokenServiceAdapter` logs only at DEBUG without secret/token |
| REQ-API-006 (`/api/v1` central prefix) | Implemented | `WebConfig.configurePathMatch`; `SpringSecurityConfig` reads from properties; `BasePathConfigOverrideTest` proves it cascades |
| REQ-USR-006 (Cascade hard-delete) | Implemented | `V001__init_schema.sql` `on delete cascade`; `CascadeIntegrationTest` verifies user→agent→conversation→message |
| REQ-PRS-002 (Flyway migrations) | Implemented | `pom.xml` includes Flyway; `V001`/`V002`/`V003` apply automatically; verified by `InitSchemaMigrationTest`, `SeedMigrationsTest` |
| REQ-API-004 (RFC 7807 errors, no stack traces) | Implemented | `ProblemDetails` shape; `GlobalExceptionHandler` uses `application/problem+json`; `Throwable` handler logs but does not return the stack trace |
| REQ-ARC-002 (Hexagonal) | Implemented | Layering enforced by `LayeringArchTest`; domain has zero Spring/JPA imports verified by spot-check |

---

## Recommended next actions

**Must-fix before EPIC-04 / EPIC-05 (which extend the User aggregate):**

1. **Normalize email casing** in `Email` (and add a `lower(email)` unique index migration) so admin-created users and signed-in users hit the same row regardless of input casing. (HIGH)
2. **Move `PingController` out of `src/main/java`** or replace with the Actuator `/health` endpoint. Suppress dev-only controllers from production builds. (HIGH)
3. **Inject the `Clock` bean into `JjwtTokenServiceAdapter`** for symmetry with the rest of the time-aware code, and remove the `MutableClock` workaround in `LogoutEndpointIntegrationTest`. (HIGH)

**Should-fix soon:**

4. **Add `org.springframework.ai..` and `io.jsonwebtoken..` to the `application/**` ArchUnit ban list** before EPIC-09 lands the `LlmChatClient` port. (MEDIUM)
5. **Decide on JWT-claim vs DB-read for `mustChangePassword`** in `ForcedPasswordChangeFilter` (current per-request DB read is fine for v1 but adds DB dependency to every authenticated endpoint). (MEDIUM)
6. **Catch specific exception types in `JjwtTokenServiceAdapter.verify`** instead of bare `RuntimeException`. (MEDIUM)
7. **Document the manual Postgres setup** for integration tests in `backend/CLAUDE.md` (currently only in the test class Javadoc). (MEDIUM)
8. **Add a constant-time path** for unknown-email login (run BCrypt against a sentinel hash) or accept the timing oracle and document the choice. (MEDIUM)

**Nice-to-have:**

9. Drop the redundant `@Autowired` on `JjwtTokenServiceAdapter`'s constructor. (LOW)
10. Use `AntPathMatcher` or trailing-slash-tolerant matching in `ForcedPasswordChangeFilter`. (LOW)
11. Replace the hand-rolled JSON extractors in integration tests with Jackson or `JsonPath`. (LOW)
12. Remove unused JPA entity setters (or make them package-private). (LOW)
13. Make the DB env vars fail-fast with clear messages via `${VAR:?...}` syntax in `application.yaml`. (LOW)
14. Decide and document the `errors` empty-vs-null behavior in `ProblemDetails`. (MEDIUM-LOW)

---

## Files / areas not reviewed

- `backend/src/test/java/com/cognizant/emk/multiagent/ApplicationContextSmokeTest.java` — quick smoke test, not deeply read.
- `backend/src/test/java/com/cognizant/emk/multiagent/domain/DomainExemplarTest.java`, `application/ApplicationExemplarTest.java`, `infrastructure/InfrastructureExemplarTest.java` — exemplars from EPIC-01 / US-01-007, not deeply read; assumed to remain trivial assertions.
- `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/persistence/springdata/{Agent,AgentTool,AgentMcp,AgentTeam,Conversation,Message,ApiKey}JpaRepository.java` — empty `JpaRepository<T, ID>` interfaces, spot-checked but not individually reviewed.
- `package-info.java` files — read incidentally; no Javadoc-only file flagged anything.
- `backend/target/` — build output, not reviewed.
- The `backend/docs/AwsS3Tool.java` reference example for EPIC-07 — out of scope.

This review covers all production source files in EPIC-01/02/03 scope plus the listed test files (the heaviest 20+ tests by line count, and a sampling of the rest). No `mvn` commands were run; review is static analysis only.
