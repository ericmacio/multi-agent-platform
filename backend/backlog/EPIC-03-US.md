# EPIC-03-US.md — User stories for EPIC-03

EPIC-03 — **Authentication: JWT, login, logout, password**

This file lists the user stories that deliver EPIC-03. The EPIC delivers end-user
authentication: the `User` domain aggregate (this EPIC owns it per the EPIC-02 scope split),
JWT issuance and validation, the logout denylist, the forced-password-change flow, the
sign-in / sign-out / self password-change endpoints, and the minimum slice of cross-cutting
plumbing (problem-details error mapper, sensitive-log redaction) those endpoints require.

> **Scope split with EPIC-04 / EPIC-05 / EPIC-14.**
> - API-key authentication, the `ApiKey` aggregate, and the `ApiKeyAuthenticationFilter` are
>   delivered by EPIC-04.
> - Admin user-management endpoints (`/admin/users/*`) are delivered by EPIC-05; this EPIC
>   only ships the read-side of the `User` aggregate needed to authenticate an existing user
>   (the seeded admin from EPIC-02 `V002__seed_admin.sql`) and to update its password hash
>   on self password change.
> - The `GlobalExceptionHandler` shipped here covers only the error codes EPIC-03 emits
>   (`VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `MUST_CHANGE_PASSWORD`, `FORBIDDEN`,
>   `NOT_FOUND`, `INTERNAL_ERROR`). Subsequent feature EPICs add their own codes, and
>   EPIC-14 consolidates the full taxonomy.

## Conventions

- **ID format**: `US-03-<nnn>` — `03` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                  | Priority | Status | Depends on              |
|------------|------------------------------------------------------------------------|----------|--------|-------------------------|
| US-03-001  | Minimal `GlobalExceptionHandler`, problem-details mapper & log redaction | MUST   | Draft  | EPIC-01                 |
| US-03-002  | `User` domain aggregate, value objects & repository port               | MUST     | Draft  | EPIC-01, EPIC-02        |
| US-03-003  | `UserRepository` JPA adapter + domain ↔ JPA mapper                     | MUST     | Draft  | US-03-002, EPIC-02      |
| US-03-004  | `PasswordHasher` port + BCrypt adapter                                 | MUST     | Draft  | US-03-002               |
| US-03-005  | `JwtTokenService` port + JJWT (HS256) adapter                          | MUST     | Draft  | US-03-002               |
| US-03-006  | `JwtDenylist` port + in-memory adapter with scheduled sweep            | MUST     | Draft  | EPIC-01                 |
| US-03-007  | `JwtAuthenticationFilter` & Spring Security wiring                     | MUST     | Draft  | US-03-001, 005, 006     |
| US-03-008  | `ForcedPasswordChangeFilter`                                           | MUST     | Done   | US-03-001, 003, 007     |
| US-03-009  | Login use case & `POST /auth/login`                                    | MUST     | Done   | US-03-003, 004, 005, 007|
| US-03-010  | Logout use case & `POST /auth/logout`                                  | MUST     | Done   | US-03-006, 007, 009     |
| US-03-011  | Change-own-password use case & `PUT /auth/password`                    | MUST     | Done   | US-03-003, 004, 007, 008|

---

## US-03-001 — Minimal `GlobalExceptionHandler`, problem-details mapper & log redaction

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a minimal `@RestControllerAdvice` that maps every domain exception this EPIC emits
to the documented RFC 7807 `ProblemDetails` shape, plus a Logback converter that masks
token-shaped substrings in any log message
**So that** the auth endpoints can return safe, contract-compliant JSON errors and no raw
JWT or BCrypt hash ever leaks to a log appender.

### Description

This is the first feature EPIC to ship endpoints, so it must also ship the cross-cutting
slice the endpoints depend on. The full error taxonomy of design §9 is delivered
incrementally by subsequent EPICs and consolidated by EPIC-14; this story ships only the
codes EPIC-03 actually emits. The Logback redaction converter is installed once here and
re-used by every downstream EPIC.

### Acceptance criteria

- A `ProblemDetails` Java record lives under `infrastructure/web/error/` matching the
  shape documented in design §9.3 (`type`, `title`, `status`, `detail`, `instance`,
  `code`, optional `errors[]`). Serialized with `application/problem+json`.
- A `GlobalExceptionHandler` `@RestControllerAdvice` maps:
  - `ValidationException` (domain) and `MethodArgumentNotValidException` /
    `ConstraintViolationException` (Spring) → 400 `VALIDATION_ERROR`, with per-field
    `errors[]` populated from the binding result when available.
  - `InvalidCredentialsException` (domain) → 401 `INVALID_CREDENTIALS`.
  - `ForbiddenException` (domain) and Spring `AccessDeniedException` → 403 `FORBIDDEN`.
  - A new `MustChangePasswordException` (domain, extends `ForbiddenException`) → 403
    `MUST_CHANGE_PASSWORD`.
  - `NotFoundException` (domain) and Spring `NoHandlerFoundException` → 404 `NOT_FOUND`.
  - `HttpRequestMethodNotSupportedException` → 405 `METHOD_NOT_ALLOWED`.
  - Any other `Throwable` → 500 `INTERNAL_ERROR`, with the message scrubbed of stack
    trace details (the trace itself is logged at ERROR, **not** returned).
- The `instance` field is populated from the request URI on every response.
- No exception path ever returns a Spring default error JSON (`/error`); every code path
  goes through this handler.
- A `SensitiveDataMaskingConverter` Logback converter (registered in
  `logback-spring.xml`) replaces any token-shaped substring with `***` before the
  message reaches the appender. Token-shaped is defined as one of:
  - JWT: three Base64Url segments separated by `.` of total length ≥ 40.
  - BCrypt hash: matches `\$2[aby]\$\d{2}\$.{53}`.
  - Bearer-prefixed value: `Bearer\s+[A-Za-z0-9_\-\.]+`.
- A unit test (`GlobalExceptionHandlerTest`, MockMvc-based) exercises one branch per code
  above and asserts the body shape, the status, and the `Content-Type:
  application/problem+json` header.
- A unit test (`SensitiveDataMaskingConverterTest`) feeds a JWT-shaped string, a
  BCrypt-shaped string, and a `Bearer xxx` substring through the converter and asserts
  each is masked while surrounding text is preserved.

### Requirements coverage

`REQ-API-004`, `REQ-ARC-007`, `REQ-SEC-004`.

### Design references

§9.1 exception hierarchy, §9.2 `GlobalExceptionHandler`, §9.3 error response shape,
§8.7 sensitive-data logging.

### Dependencies

EPIC-01 must be `Done`.

---

## US-03-002 — `User` domain aggregate, value objects & repository port

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the `User` aggregate, its value objects (`Email`, `Password`, `Role`), the
`UserRepository` port, and the auth-domain exceptions
**So that** every authentication use case operates on a Spring-free, fully-validated
domain model.

### Description

Place the `User` aggregate and `UserRepository` interface under `domain/user/`, the
`Principal` sealed type and `InvalidCredentialsException` under `domain/auth/`. The
`Password` value object enforces the platform policy at construction time so policy
violations cannot reach persistence. The repository port stays Spring-free; the adapter
is delivered by US-03-003.

### Acceptance criteria

- `domain/user/Email.java` — record `Email(String value)` with RFC 5322 syntactic check
  (`Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")` is acceptable for v1) and
  `value.length() <= 254`. Throws `ValidationException` with field `email` on violation.
- `domain/user/Password.java` — record-or-class value object enforcing `REQ-SEC-001`:
  length ≥ 10, ≥ 1 `[A-Z]`, ≥ 1 char from `!@#$%^&*()-_=+[]{};:'",.<>/?\|~\``. Throws
  `ValidationException` with field `password` on violation. The wrapped string is
  exposed only via an explicit `cleartext()` accessor; `toString()` returns
  `"Password{***}"`.
- `domain/user/Role.java` — `enum Role { ADMIN, STANDARD }`.
- `domain/user/User.java` — aggregate carrying `UserId`, `Email`, `passwordHash` (raw
  String — the hash, not a `Password`), `Role`, `disabled` (boolean),
  `mustChangePassword` (boolean), `createdAt`, `updatedAt`. Provides domain methods:
  - `boolean isActive()` → `!disabled`.
  - `User withNewPasswordHash(String newHash, OffsetDateTime now)` → returns a copy with
    the new hash, `mustChangePassword=false`, and `updatedAt=now`.
- `domain/user/UserId.java` — record `UserId(UUID value)` with non-null check.
- `domain/user/UserRepository.java` — interface with at least:
  - `Optional<User> findByEmail(Email email);`
  - `Optional<User> findById(UserId id);`
  - `User save(User user);`
- `domain/auth/Principal.java` — sealed interface `Principal permits UserPrincipal,
  SystemPrincipal`; this story ships only `UserPrincipal(UserId id, Email email, Role
  role)`. `SystemPrincipal` is delivered by EPIC-04.
- `domain/auth/InvalidCredentialsException.java` extends `BusinessException` (created in
  EPIC-01); its message is the static literal `"Authentication failed."` so that no
  caller can accidentally embed user-supplied data in it.
- `domain/user/MustChangePasswordException.java` extends `ForbiddenException`.
- Pure-Java unit tests:
  - `EmailTest` — accepts a valid email, rejects empty / no-`@` / over-254-chars.
  - `PasswordTest` — accepts a policy-compliant value, rejects each individual policy
    failure (too short, no uppercase, no special) with the documented field name.
  - `UserTest` — `withNewPasswordHash` clears `mustChangePassword` and updates
    `updatedAt` while preserving `id`, `email`, `role`, `disabled`.
- ArchUnit (US-01-008) still passes: `domain/**` has no Spring / JPA / Jackson imports.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-002`, `REQ-USR-004`, `REQ-USR-007`, `REQ-SEC-001`,
`REQ-AUTH-009`, `REQ-ARC-002`, `REQ-ARC-003`.

### Design references

§3 project structure (`domain/user/`, `domain/auth/`), §4.1 User entity,
§4.2 invariants (`Password` policy), §8.5 password handling.

### Dependencies

EPIC-01 (domain exception base classes), EPIC-02 (DB schema for `users` already in place).

---

## US-03-003 — `UserRepository` JPA adapter + domain ↔ JPA mapper

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the infrastructure adapter that implements `UserRepository` against the JPA
`UserJpa` entity shipped in EPIC-02
**So that** the login and password-change use cases can read and write users without
knowing about Spring Data JPA.

### Description

Wire the domain repository port to the existing Spring Data JPA infrastructure. Per the
EPIC-02 scope-split note (US-02-006), the per-aggregate finder methods accrue here. This
story adds the single finder needed by EPIC-03 (`findByEmail`) and the `UserMapper`.

### Acceptance criteria

- `infrastructure/persistence/mapper/UserMapper.java` — pure Java class (or final class
  with static methods) translating between `domain.user.User` and
  `infrastructure.persistence.entity.UserJpa`. No Spring annotations.
- `infrastructure/persistence/adapter/UserRepositoryAdapter.java` — `@Component`
  implementing `domain.user.UserRepository`, constructor-injected with
  `UserJpaRepository` (EPIC-02) and `UserMapper`. Methods:
  - `findByEmail(Email)` → uses a new `Optional<UserJpa> findByEmail(String email)` on
    `UserJpaRepository`.
  - `findById(UserId)` → delegates to `findById(UUID)`.
  - `save(User)` → maps to `UserJpa`, persists, returns the mapped result.
- `UserJpaRepository` is extended with the single `findByEmail(String email)` Spring
  Data derived query. No other finder is added in this story.
- The mapper preserves all `User` fields round-trip: `id`, `email`, `passwordHash`,
  `role`, `disabled`, `mustChangePassword`, `createdAt`, `updatedAt`.
- Integration test `UserRepositoryAdapterIntegrationTest` (extends
  `PostgresIntegrationTest` from US-02-002):
  - Asserts `findByEmail` returns the seeded admin user after Flyway has run
    (`APP_BOOTSTRAP_ADMIN_EMAIL`), with `role=ADMIN`, `disabled=false`,
    `mustChangePassword=true`.
  - Persists a new `User`, retrieves it by id and by email, asserts equality.
  - Calls `save` on a `User` produced by `withNewPasswordHash(...)` and asserts the row
    in the DB now has the new hash and `must_change_password=false`.
- ArchUnit (US-01-008) still passes: the adapter sits in `infrastructure/**`,
  `UserRepository` stays in `domain/**`.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-002`, `REQ-USR-007`, `REQ-PRS-001`, `REQ-PRS-005`.

### Design references

§3 project structure (`infrastructure/persistence/{mapper,adapter}/`),
§5 database schema (`users` table), §3.1 conventions (repository ports in domain,
adapters in infrastructure).

### Dependencies

US-03-002 (domain `User` and port), EPIC-02 (`UserJpa`, `UserJpaRepository`, seeded admin).

---

## US-03-004 — `PasswordHasher` port + BCrypt adapter

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a `PasswordHasher` application port and a BCrypt-backed adapter
**So that** the login and password-change use cases can compare and hash passwords
without depending on a Spring Security class directly.

### Description

The port lives in the application layer (it is a technical port, per design §3 and
§3.1). The adapter sits in `infrastructure/security/`. BCrypt is the only hashing
algorithm supported, with the Spring Security default cost factor.

### Acceptance criteria

- `application/auth/PasswordHasher.java` — interface with:
  - `String hash(Password password);`
  - `boolean matches(Password rawPassword, String storedHash);`
- `infrastructure/security/BcryptPasswordHasherAdapter.java` — `@Component` implementing
  the port using `org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder` with
  cost factor 10 (constructor `new BCryptPasswordEncoder()`). Internally calls
  `password.cleartext()` only when invoking the encoder; never logs the cleartext.
- A unit test `BcryptPasswordHasherAdapterTest`:
  - `hash` produces a string matching the BCrypt regex
    `^\$2[aby]\$10\$.{53}$` (cost factor 10).
  - `matches` returns `true` for the original password and `false` for any altered
    variant.
  - `matches` returns `false` (never throws) when the stored hash is a malformed string.
- ArchUnit (US-01-008) still passes: the port sits in `application/**`, the adapter in
  `infrastructure/**`.

### Requirements coverage

`REQ-SEC-002`, `REQ-ARC-005` (port abstraction).

### Design references

§3 project structure (`application/auth/`, `infrastructure/security/`),
§8.5 password handling (BCrypt cost factor 10).

### Dependencies

US-03-002 (`Password` value object).

---

## US-03-005 — `JwtTokenService` port + JJWT (HS256) adapter

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a `JwtTokenService` application port and a JJWT-backed adapter that signs and
verifies HS256 tokens with a secret loaded from `JWT_SIGNING_SECRET`
**So that** login can issue tokens and the auth filter can validate them, without
either depending on the JJWT library.

### Description

This is the canonical issuance/validation surface for JWTs. The signing key is read once
at startup and cached; the application fails fast if the env var is missing. Default
lifetime is 30 minutes, configurable via `app.security.jwt.lifetime` (Duration).

### Acceptance criteria

- The Maven `pom.xml` adds the `io.jsonwebtoken:jjwt-api`, `jjwt-impl` (runtime), and
  `jjwt-jackson` (runtime) dependencies at version 0.12.x. No transitive Spring import.
- `application/auth/JwtTokenService.java` — interface:
  ```java
  IssuedToken issue(User user);                          // returns token + jti + expiresAt
  TokenClaims verify(String rawToken);                    // throws InvalidCredentialsException on any failure
  record IssuedToken(String token, UUID jti, OffsetDateTime expiresAt) {}
  record TokenClaims(UserId userId, Email email, Role role, UUID jti, OffsetDateTime expiresAt) {}
  ```
- `infrastructure/security/JjwtTokenServiceAdapter.java` — `@Component` implementing the
  port. Behavior:
  - Reads `JWT_SIGNING_SECRET` (mapped through `app.security.jwt.signing-secret` in
    `ApplicationProperties`); the application MUST fail fast at startup with a clear
    error if the value is missing or empty (length ≥ 32 bytes after UTF-8 encoding;
    HS256 needs at least 256 bits of key material).
  - Reads `app.security.jwt.lifetime` (`java.time.Duration`, default `PT30M`).
  - Signs with HS256 (`Jwts.builder().signWith(key, Jwts.SIG.HS256)`).
  - Claims emitted on `issue`: `sub`=email, `role`=`ADMIN|STANDARD`, `jti`=fresh
    `UUID.randomUUID()`, `iat`=now, `exp`=now + lifetime. The User's `id` is also stored
    in a custom `uid` claim (UUID string) to avoid a DB round-trip on every request.
  - `verify` parses the token, validates signature and `exp`, and returns `TokenClaims`.
    Any failure (bad signature, expired, malformed, missing claim, unknown role) is
    translated to `InvalidCredentialsException`. The original cause is preserved as
    suppressed for the logger only — never surfaced.
- `ApplicationProperties` (from US-01-004) gains a nested `Security.Jwt(Duration
  lifetime, String signingSecret)` group, with `@NotEmpty` validation on
  `signingSecret`.
- Unit tests `JjwtTokenServiceAdapterTest` (no Spring context, key built inline):
  - Round-trip: `issue` then `verify` returns the same `userId`, `email`, `role`, `jti`,
    and an `expiresAt` exactly `lifetime` after `iat` (assert with a 1-second
    tolerance).
  - `verify` rejects a token signed with a different key → `InvalidCredentialsException`.
  - `verify` rejects an expired token → `InvalidCredentialsException`.
  - `verify` rejects a token with a `role` claim outside the enum →
    `InvalidCredentialsException`.
- A Spring-context test asserts the application **fails to start** when
  `JWT_SIGNING_SECRET` is empty (assert through `@SpringBootTest` with property
  override).
- The adapter never logs the token, the secret, or any claim other than `jti` (and only
  at DEBUG).

### Requirements coverage

`REQ-AUTH-002`, `REQ-AUTH-003`, `REQ-AUTH-004`, `REQ-AUTH-005`, `REQ-AUTH-006`,
`REQ-AUTH-009`, `REQ-AUTH-010`, `REQ-SEC-003`, `REQ-SEC-004`.

### Design references

§8.2 JWT issuance and validation, §15 configuration (`app.security.jwt.*` and required
env vars).

### Dependencies

US-03-002 (`User`, `Email`, `Role`, `UserId`, `InvalidCredentialsException`).

---

## US-03-006 — `JwtDenylist` port + in-memory adapter with scheduled sweep

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a `JwtDenylist` application port and an in-memory adapter that self-evicts
expired entries
**So that** logout can invalidate a token before its natural expiry without persisting
state across restarts.

### Description

V1 is single-node, so an in-memory map is sufficient (design §8.3, TBD-1 covers the
multi-node case). Entries self-evict on a 60-second scheduler and on read.

### Acceptance criteria

- `application/auth/JwtDenylist.java` — interface:
  ```java
  void add(UUID jti, OffsetDateTime expiresAt);
  boolean contains(UUID jti);            // returns false for expired entries; opportunistic eviction
  int size();                            // diagnostic
  ```
- `infrastructure/security/InMemoryJwtDenylistAdapter.java` — `@Component` implementing
  the port, backed by a `ConcurrentHashMap<UUID, OffsetDateTime>`. `contains` evicts on
  read when the entry's expiry is in the past (returns `false`).
- A `@Scheduled(fixedDelay = 60_000)` method removes every entry whose `expiresAt` is
  before `now`. Scheduling is enabled by an `@EnableScheduling` configuration delivered
  in this story (`infrastructure/config/SchedulingConfig.java`).
- A `Clock` bean (`java.time.Clock.systemUTC()`) lives in
  `infrastructure/config/ClockConfig.java` and is used by the adapter so tests can
  inject a controllable `Clock`. The matching `application/shared/Clock.java` port is
  also delivered here (or, if simpler, the adapter consumes `java.time.Clock` directly
  — pick one and document; the design lists `Clock` as a port but a Spring-managed
  `java.time.Clock` is acceptable for v1).
- Unit tests `InMemoryJwtDenylistAdapterTest`:
  - `add` then `contains` returns `true` while not expired.
  - `contains` returns `false` when the entry has expired (read-time eviction).
  - `add` for an already-expired `expiresAt` is a no-op (entry is not retained).
  - The sweep method (invoked directly) drains every expired entry.
  - `add` is concurrency-safe: 1 000 parallel `add` calls on distinct `jti` end with
    `size() == 1000` (sanity check, not a stress test).
- The adapter never logs the `jti` at higher than DEBUG level.

### Requirements coverage

`REQ-AUTH-006`, `REQ-AUTH-011`, `REQ-NFR-005` (bounded by 64-user target).

### Design references

§8.3 JWT denylist, §19 TBD-1 (multi-node).

### Dependencies

EPIC-01 (Spring Boot context).

---

## US-03-007 — `JwtAuthenticationFilter` & Spring Security wiring

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a `JwtAuthenticationFilter` that authenticates `Authorization: Bearer`
requests and a Spring Security configuration that places it correctly in the chain
**So that** every protected endpoint can rely on a populated `SecurityContext`.

### Description

The filter runs once per request, parses the `Authorization` header, calls
`JwtTokenService.verify`, looks up the `jti` in the `JwtDenylist`, then sets the
`Authentication` on the `SecurityContext` using a `UserPrincipal`. Authorities are
derived from the `Role` claim. The filter is wired ahead of Spring Security's
`UsernamePasswordAuthenticationFilter` and **after** the (future) `RateLimitFilter`
slot reserved by EPIC-13. Filter ordering follows design §8.1.

### Acceptance criteria

- `infrastructure/web/security/JwtAuthenticationFilter.java` — extends
  `OncePerRequestFilter`, constructor-injected with `JwtTokenService` and `JwtDenylist`.
  Behavior:
  - If `Authorization` header is absent or does not start with `Bearer ` → continue the
    chain unauthenticated (the URL-level rules later decide whether 401 is appropriate).
  - Else: extract the token, call `verify`, then `denylist.contains(jti)`. If the token
    is denylisted, raise `InvalidCredentialsException` (handled by the
    `GlobalExceptionHandler` from US-03-001 → 401 `INVALID_CREDENTIALS`).
  - On success, build `UserPrincipal(userId, email, role)` and set
    `UsernamePasswordAuthenticationToken(principal, null,
     List.of(new SimpleGrantedAuthority("ROLE_" + role.name())))` on the
    `SecurityContext`.
  - The filter never writes the response body itself; failures bubble up as exceptions
    so the central error handler shapes the response.
- `infrastructure/web/security/SpringSecurityConfig.java` (replacing the EPIC-01 stub):
  - `SecurityFilterChain` declares:
    - `csrf().disable()`,
    - `sessionCreationPolicy(STATELESS)`,
    - `cors(withDefaults())` reusing the EPIC-01 CORS configuration source,
    - `authorizeHttpRequests`:
      - `permitAll` on `POST /api/v1/auth/login`,
      - `permitAll` on `GET /actuator/health` (for EPIC-15 readiness),
      - all other paths under `/api/v1/**` → `authenticated`,
    - `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`,
    - exception handling: `authenticationEntryPoint` and `accessDeniedHandler` rethrow
      as `InvalidCredentialsException` / `AccessDeniedException` so they are handled by
      the `GlobalExceptionHandler` (consistent error envelope).
- Integration test `JwtAuthenticationFilterIntegrationTest` (MockMvc + Spring context):
  - Anonymous request to a probe protected endpoint (a `/__test/me` controller wired in
    test scope only, returning the `Authentication.principal`) → 401
    `INVALID_CREDENTIALS`.
  - Request with a valid token issued by `JwtTokenService.issue(...)` → 200 and the
    response body shows the principal's email and role.
  - Request with a denylisted token (added via the `JwtDenylist` adapter) → 401
    `INVALID_CREDENTIALS`.
  - Request with an expired or tampered token → 401 `INVALID_CREDENTIALS`.
- The `EPIC-01 / US-01-006` "open chain" expectation is updated: the test that asserted
  every endpoint was permit-all is **replaced** by an assertion that
  `POST /auth/login` is public and the probe endpoint requires auth.
- ArchUnit (US-01-008) still passes.

### Requirements coverage

`REQ-AUTH-001`, `REQ-AUTH-002`, `REQ-AUTH-003`, `REQ-AUTH-006`, `REQ-AUTH-008`,
`REQ-AUTH-009`, `REQ-AUTH-011`, `REQ-SEC-004`.

### Design references

§8.1 filter chain, §8.2 JWT validation, §8.6 authorization rules.

### Dependencies

US-03-001 (problem-details handler), US-03-005 (`JwtTokenService`), US-03-006
(`JwtDenylist`).

---

## US-03-008 — `ForcedPasswordChangeFilter`

- **Status**: Done
- **Priority**: MUST

**As a** platform operator
**I want** a filter that blocks every endpoint except `PUT /auth/password` and
`POST /auth/logout` when the current user has `mustChangePassword=true`
**So that** the seeded admin (and any future user with the flag set) cannot use the
platform until their password is changed.

### Description

The filter sits **after** the JWT filter in the chain (and after the future
`ApiKeyAuthenticationFilter` slot reserved by EPIC-04, which has nothing to do with the
flag). It looks up the current `UserPrincipal`, fetches the user via `UserRepository`,
and short-circuits with `MustChangePasswordException` (→ 403 `MUST_CHANGE_PASSWORD`)
when the flag is set on a path that is not on the allow-list.

### Acceptance criteria

- `infrastructure/web/security/ForcedPasswordChangeFilter.java` — extends
  `OncePerRequestFilter`, constructor-injected with `UserRepository`. Wired in the
  `SecurityFilterChain` immediately **after** the `JwtAuthenticationFilter`.
- Behavior:
  - If the `SecurityContext` has no `Authentication`, or the principal is not a
    `UserPrincipal` (covers the future `SystemPrincipal` from EPIC-04), continue the
    chain unchanged.
  - Else, look up the user by id. If `mustChangePassword == false`, continue the chain.
  - Else, allow the request only if it matches one of:
    - `PUT /api/v1/auth/password`
    - `POST /api/v1/auth/logout`
    Any other path raises `MustChangePasswordException` → 403 `MUST_CHANGE_PASSWORD`.
- The lookup is done once per request and never persisted in the session (we are
  stateless). A simple per-request cache (request attribute) is acceptable but optional.
- Integration test `ForcedPasswordChangeFilterIntegrationTest`:
  - Issue a JWT for the seeded admin (`mustChangePassword=true`).
  - `GET /api/v1/__test/me` → 403 `MUST_CHANGE_PASSWORD` with the documented body
    shape.
  - `POST /api/v1/auth/logout` → 204 (allow-listed; logout endpoint shipped in
    US-03-010 — until then the test asserts the filter does **not** raise on this path
    and lets the chain return 404 from a stub handler).
  - `PUT /api/v1/auth/password` → allowed by the filter (the endpoint itself is
    delivered by US-03-011).
  - After flipping the flag (via a DB-level update in the test), the same JWT can hit
    the protected endpoint and gets 200.

### Requirements coverage

`REQ-USR-007`, `REQ-AUTH-008`.

### Design references

§8.1 filter chain (position), §6.3 forced password change.

### Dependencies

US-03-001 (handler), US-03-003 (UserRepository adapter), US-03-007 (filter chain).

---

## US-03-009 — Login use case & `POST /auth/login`

- **Status**: Done
- **Priority**: MUST

**As an** end-user
**I want** to sign in with my email and password and receive a signed JWT
**So that** I can call protected endpoints on subsequent requests.

### Description

The login endpoint is public and consumes `application/json`. The use case verifies the
user exists, is not disabled, and that the BCrypt-hashed password matches; then it
issues a JWT through `JwtTokenService`. Failure at any of the verification steps returns
the same generic `INVALID_CREDENTIALS` error so attackers can't probe email existence.

### Acceptance criteria

- `application/auth/LoginUseCase.java` — interface
  `LoginResult login(LoginCommand command)`. `LoginCommand(Email email, Password
  password)` and `LoginResult(String token, OffsetDateTime expiresAt, boolean
  mustChangePassword)` records.
- `application/auth/LoginService.java` — `@Service` implementing the use case,
  constructor-injected with `UserRepository`, `PasswordHasher`, `JwtTokenService`.
  Behavior:
  - `findByEmail` → `InvalidCredentialsException` if absent.
  - `passwordHasher.matches(password, user.passwordHash)` → `InvalidCredentialsException`
    if false.
  - `user.isActive()` → `InvalidCredentialsException` if `disabled` (we expose 401, not
    403, on disabled accounts to avoid leaking that the account exists).
  - On success, call `jwtTokenService.issue(user)` and return the `LoginResult`. The
    token issuance does **not** clear `mustChangePassword`; the flag flows to the
    response body so the frontend can route the user to the password screen.
- `infrastructure/web/auth/AuthController.java` — `@RestController` (no class-level
  `@RequestMapping` per design §3.1; the `/api/v1` prefix is centralized) with method
  `@PostMapping("/auth/login")` and request DTO `LoginRequest(@NotBlank @Email String
  email, @NotBlank String password)`. Response DTO `LoginResponse(String token, String
  tokenType, OffsetDateTime expiresAt, boolean mustChangePassword)` with
  `tokenType="Bearer"`.
- The controller maps `LoginRequest` → `LoginCommand` via `Email`/`Password`
  constructors (which throw `ValidationException` → 400 on policy violation; bean
  validation also catches `@NotBlank` / `@Email` failures).
- Integration test `LoginEndpointIntegrationTest`:
  - With the seeded admin's email and the matching cleartext password (test config
    sets `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH` from a known cleartext) → 200 with a
    populated `token`, `expiresAt` ~ 30 minutes ahead, `mustChangePassword=true`.
  - Wrong password → 401 `INVALID_CREDENTIALS`.
  - Unknown email → 401 `INVALID_CREDENTIALS` (same body — assert byte-for-byte
    parity with the wrong-password case to verify no leak).
  - Disabled user (toggled at DB level) → 401 `INVALID_CREDENTIALS`.
  - Empty email or empty password → 400 `VALIDATION_ERROR`.
  - Password too short → 400 `VALIDATION_ERROR` (the policy is enforced at the
    `Password` constructor; the controller maps that to a 400 via the
    `GlobalExceptionHandler`).
  - The issued token can then authenticate the probe endpoint from US-03-007 (end-to-end
    smoke).

### Requirements coverage

`REQ-AUTH-001`, `REQ-AUTH-002`, `REQ-AUTH-003`, `REQ-AUTH-004`, `REQ-AUTH-009`,
`REQ-AUTH-010`, `REQ-USR-002`, `REQ-USR-007`, `REQ-SEC-001`, `REQ-SEC-002`.

### Design references

§6.2.1 endpoints, §16.1 login sequence.

### Dependencies

US-03-003, US-03-004, US-03-005, US-03-007.

---

## US-03-010 — Logout use case & `POST /auth/logout`

- **Status**: Done
- **Priority**: MUST

**As an** end-user
**I want** to invalidate my current JWT before its natural expiry
**So that** a stolen token cannot be used after I sign out.

### Description

The logout endpoint requires a valid JWT. The handler extracts the `jti` and `exp` from
the current `Authentication` (populated by `JwtAuthenticationFilter`), then asks the
denylist to remember it until expiry.

### Acceptance criteria

- `application/auth/LogoutUseCase.java` — interface `void logout(LogoutCommand
  command)`. `LogoutCommand(UUID jti, OffsetDateTime expiresAt)`.
- `application/auth/LogoutService.java` — `@Service`, constructor-injected with
  `JwtDenylist`. Calls `denylist.add(jti, expiresAt)`. Idempotent: calling twice with
  the same `jti` is a no-op.
- The `JwtAuthenticationFilter` from US-03-007 already exposes `jti` and `expiresAt` on
  the `SecurityContext`. To make them retrievable in the controller, this story
  introduces a small `AuthenticatedToken` request-scoped holder (or, simpler, stuffs
  the values into the `UserPrincipal` itself). Pick the simpler option and document
  the choice in the class Javadoc.
- `AuthController.logout`:
  - `@PostMapping("/auth/logout")`, `@PreAuthorize` not needed (URL rule already
    authenticated).
  - Returns `204 No Content`. Empty body.
- Integration test `LogoutEndpointIntegrationTest`:
  - Sign in, capture the token, call `POST /auth/logout` → 204.
  - Re-use the same token to call the probe endpoint → 401 `INVALID_CREDENTIALS`.
  - Calling logout again with the now-invalidated token → 401 (the filter rejects
    before the handler runs).
  - The denylist `size()` is exactly 1 immediately after the first logout (read via the
    bean directly in the test), and 0 after advancing the test `Clock` past the JWT
    `exp` and triggering the sweep manually.

### Requirements coverage

`REQ-AUTH-001`, `REQ-AUTH-003`, `REQ-AUTH-006`, `REQ-AUTH-009`, `REQ-AUTH-011`.

### Design references

§6.2.1 endpoints, §8.3 denylist, §16.4 logout sequence.

### Dependencies

US-03-006, US-03-007, US-03-009 (login flow needed to issue the token under test).

---

## US-03-011 — Change-own-password use case & `PUT /auth/password`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated user
**I want** to change my own password
**So that** I can rotate it on demand and clear the forced-change flag set on my
seeded-admin account.

### Description

This endpoint is on the allow-list of `ForcedPasswordChangeFilter`, so it works for the
seeded admin even before their first password change. The use case verifies the
`currentPassword` against the stored hash, hashes the `newPassword`, and persists the
update via `User.withNewPasswordHash(...)`. The current JWT remains valid until natural
expiry (consistent with `REQ-AUTH-006`); we do **not** force a re-login.

### Acceptance criteria

- `application/auth/ChangeOwnPasswordUseCase.java` — interface `void
  changePassword(ChangePasswordCommand command)`.
  `ChangePasswordCommand(UserId userId, Password currentPassword, Password
  newPassword)`.
- `application/auth/ChangeOwnPasswordService.java` — `@Service`,
  `@Transactional` on the public method, constructor-injected with `UserRepository`,
  `PasswordHasher`, `Clock`. Behavior:
  - Load user by id; `UserNotFoundException` if absent (treated as 404 `NOT_FOUND` —
    realistically unreachable since the principal came from a verified JWT).
  - `passwordHasher.matches(currentPassword, user.passwordHash)` →
    `InvalidCredentialsException` if false (mapped to 401, **not** 400, to keep parity
    with the login error semantics).
  - Hash `newPassword` and call `user.withNewPasswordHash(newHash, clock.instant()
    .atOffset(ZoneOffset.UTC))`, then `userRepository.save(...)`.
- `AuthController.changePassword`:
  - `@PutMapping("/auth/password")`.
  - Request DTO `ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank
    String newPassword)` (the policy is enforced when constructing `Password`).
  - Maps the request to `ChangePasswordCommand` using the principal's `UserId` from
    `SecurityContext`. Returns `204 No Content`.
- Integration test `ChangeOwnPasswordEndpointIntegrationTest`:
  - Seeded admin signs in (still `mustChangePassword=true`).
  - Hits `PUT /auth/password` with a policy-compliant new password → 204; the user row
    in DB now shows a different `password_hash` and `must_change_password=false`.
  - Re-using the same JWT, the admin can now hit a non-allow-list endpoint without 403
    (the filter no longer fires).
  - A new login with the **old** password → 401; with the **new** password → 200.
  - Wrong `currentPassword` → 401 `INVALID_CREDENTIALS`.
  - `newPassword` violating the policy (e.g., `"short"`) → 400 `VALIDATION_ERROR` with
    `errors[].field == "newPassword"`.

### Requirements coverage

`REQ-USR-004`, `REQ-USR-007`, `REQ-AUTH-008`, `REQ-AUTH-009`, `REQ-SEC-001`,
`REQ-SEC-002`, `REQ-SEC-004`.

### Design references

§6.2.1 endpoints (`PUT /auth/password`), §6.3 forced password change, §8.5 password
handling.

### Dependencies

US-03-003, US-03-004, US-03-007, US-03-008.

---

## EPIC-03 Definition of Done

EPIC-03 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every existing EPIC-01 and EPIC-02 test green; the EPIC-03 unit and
  integration tests run green against a local PostgreSQL.
- The seeded admin (`V002__seed_admin.sql`) can:
  1. sign in via `POST /auth/login` and receive a JWT carrying `mustChangePassword=true`;
  2. be blocked by `ForcedPasswordChangeFilter` on every other endpoint;
  3. clear the flag through `PUT /auth/password`;
  4. then hit any `authenticated` endpoint up to natural JWT expiry;
  5. invalidate the current token via `POST /auth/logout` and lose access immediately.
- Failed authentication paths (unknown email, wrong password, expired token, denylisted
  token, disabled account) all return the same generic 401 `INVALID_CREDENTIALS` body.
- The application fails fast at startup if `JWT_SIGNING_SECRET` is missing or shorter
  than 32 bytes.
- No log line, on any appender, contains a JWT, a BCrypt hash, or a cleartext password
  — verified by the `SensitiveDataMaskingConverter` test plus a smoke check on the
  integration-test log file.
- ArchUnit (US-01-008) still passes: `domain/**` carries no Spring/JPA imports;
  `application/**` carries no JPA/Spring AI imports; the JWT filter and SecurityConfig
  live exclusively in `infrastructure/web/security/**`.
