# EPIC-CR1-US.md — User stories for Code Review #1 (HIGH findings)

This file lists the user stories that address the **HIGH-priority** recommendations of
`backend/analysis/CODE-REVIEW-1.md` (review of EPIC-01 / EPIC-02 / EPIC-03). MEDIUM and LOW
findings are intentionally **not** covered here; they will be handled separately as part of
their owning EPICs or via a later follow-up backlog.

These three stories are scoped as remediation work on top of code already shipped by
EPIC-01 / EPIC-02 / EPIC-03. They MUST land before EPIC-04 / EPIC-05 start extending the
`User` aggregate, because retrofitting email canonicalization once API-key SYSTEM principals
and admin user-management endpoints are in place would be risky.

## Conventions

- **ID format**: `US-CR1-<nnn>` — `CR1` marks the source as Code Review #1; `<nnn>` is a
  sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All three stories are `MUST` (they implement HIGH
  findings).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design / review
  references, and its dependencies.

## Story list

| ID          | Title                                                                    | Priority | Status | Depends on                |
|-------------|--------------------------------------------------------------------------|----------|--------|---------------------------|
| US-CR1-001  | Canonicalize email to lowercase (domain + DB) to prevent duplicate accounts | MUST   | Done   | EPIC-02, EPIC-03          |
| US-CR1-002  | Remove `PingController` from production classpath                        | MUST     | Done   | EPIC-01, EPIC-03          |
| US-CR1-003  | Inject the `Clock` bean into `JjwtTokenServiceAdapter`                   | MUST     | Done   | EPIC-03                   |

---

## US-CR1-001 — Canonicalize email to lowercase (domain + DB) to prevent duplicate accounts

- **Status**: Done
- **Priority**: MUST

**As a** platform operator
**I want** the `Email` value object and the `users` table to treat email addresses as
case-insensitive (canonicalized at construction time, with a `lower(email)`-based unique
constraint at the database)
**So that** an admin cannot accidentally create both `Alice@example.test` and
`alice@example.test` as two distinct accounts, and a user who signs in with a casing
different from the one used at account creation is not silently rejected with
`INVALID_CREDENTIALS`.

### Description

Today (per Code Review #1, HIGH finding):
- `domain/user/Email.java` stores the raw input string without normalization.
- `infrastructure/persistence/springdata/UserJpaRepository.findByEmail(String)` issues a
  case-sensitive `where email = ?` against PostgreSQL.
- `db/migration/V001__init_schema.sql` defines only a plain `unique` constraint on
  `users.email` — there is no `lower(email)` index nor a `citext` column.

The remediation chosen here is **canonicalization at the boundary** (lowercase in the
domain) plus a **case-insensitive uniqueness guarantee in the database**, so that no code
path outside the `Email` constructor needs to remember to lowercase. This is the cheapest
option to retrofit and is verifiable end-to-end: a regression in any one of the three
layers (domain, repository, schema) is caught by tests.

Per `REQ-AUTH-009`, the user-facing behavior MUST NOT change — login still returns the
same generic `INVALID_CREDENTIALS` body whether the email is unknown or the password is
wrong. The fix purely closes the **silent mismatch** case where the credentials were in
fact correct but a casing difference made the lookup miss.

### Acceptance criteria

- `domain/user/Email.java` canonicalizes the input at construction time:
  - Calls `value.toLowerCase(Locale.ROOT)` **before** the regex / length validation.
  - The wrapped accessor (`value()`) returns the canonicalized (lowercase) form.
  - The validation messages and field name (`"email"`) are unchanged.
- A new Flyway migration `V004__email_case_insensitive.sql`:
  - Backfills any existing `users.email` row to its lowercase form
    (`update users set email = lower(email) where email <> lower(email);`).
  - Drops the existing plain unique constraint on `users.email`.
  - Adds a **unique functional index** `create unique index ux_users_email_lower on
    users (lower(email));` (the simpler option, no extension required — `citext` would
    require `create extension`, which is not guaranteed available on every target Postgres).
  - The migration is reversible-on-paper (documented in a comment block) but no
    down-migration script is shipped (Flyway forward-only).
- `infrastructure/persistence/springdata/UserJpaRepository.findByEmail(String)` is updated
  so the query matches case-insensitively. Two acceptable implementations (choose one):
  - (a) Lowercase in the adapter before invoking the finder (relying on the fact that the
    `Email` value object now always passes lowercase down — preferred for simplicity).
  - (b) Add a `@Query("select u from UserJpa u where lower(u.email) = lower(:email)")`
    derived finder.
  - Whichever path is chosen, the contract test below MUST pass.
- `infrastructure/persistence/entity/UserJpa.setEmail(String)` is **not** changed to
  silently lowercase — the canonicalization stays at the `Email` boundary so that
  bypassing the value object is loud, not silent. A code comment on the setter documents
  this.
- `EmailTest` is extended:
  - Constructing `new Email("Alice@Example.Com")` exposes `value()` as
    `"alice@example.com"`.
  - Constructing with `"  Alice@Example.Com  "` is rejected (or trimmed — pick one and
    test it; the recommended choice is **reject** because whitespace is not part of a
    valid email and the existing regex already forbids `\s`).
- A new persistence integration test `UserRepositoryEmailCanonicalizationIntegrationTest`
  (extending `PostgresIntegrationTest`) asserts:
  - Saving a `User` whose `Email` was constructed from `"Alice@Example.Com"` results in
    a row whose `email` column equals `alice@example.com`.
  - `findByEmail(new Email("ALICE@EXAMPLE.COM"))` returns the same persisted user.
  - Attempting to persist a second `UserJpa` with `email = "alice@example.com"` after the
    first row already exists fails with a unique-constraint violation (proving the
    functional index is active).
- A new integration test `LoginEmailCaseInsensitiveIntegrationTest` (MockMvc-based)
  asserts:
  - A user is created in the database with `email = "alice@example.com"` and a known
    password hash.
  - `POST /auth/login` succeeds (200) when the request body sends `email` as
    `"Alice@Example.Com"`, `"ALICE@EXAMPLE.COM"`, or `"alice@example.com"`.
  - The byte-identity invariant of `LoginEndpointIntegrationTest
    .unknown_email_returns_401_with_body_byte_identical_to_wrong_password` is preserved
    (`REQ-AUTH-009` is not regressed).
- `InitSchemaMigrationTest` (or a new `EmailCaseInsensitiveMigrationTest`) verifies that
  after all migrations have applied, the `pg_indexes` catalog shows the
  `ux_users_email_lower` functional index on `users (lower(email))` and that the plain
  unique constraint on `email` is no longer present.

### Out of scope

- Migrating to PostgreSQL `citext`. Considered and rejected: it would require an
  extension (`create extension if not exists citext`) that requires DB-owner privileges
  not guaranteed on the target environment, and it does not buy anything the functional
  unique index does not already give us.
- Trimming whitespace inside the local-part or the domain. The regex already forbids
  whitespace anywhere in the value; the canonicalization step only adjusts case.
- Touching the `passwords` / `bcrypt` hashing path — passwords remain case-sensitive.

### Requirements coverage

`REQ-USR-002` (email is unique across the platform), `REQ-AUTH-009` (no leak on
authentication failure), `REQ-PRS-002` (schema changes via Flyway).

### References

- Code Review #1 — HIGH finding "Email lookups are case-sensitive end to end (login
  bypass risk)" in `backend/analysis/CODE-REVIEW-1.md`.
- Design §4.1 User entity, §5 database schema (`users` table), §5.1 migrations.

### Dependencies

- EPIC-02 (schema, Flyway pipeline, `UserJpa` entity).
- EPIC-03 (`Email` value object, `LoginService`, `LoginEndpointIntegrationTest`).

---

## US-CR1-002 — Remove `PingController` from production classpath

- **Status**: Done
- **Priority**: MUST

**As a** platform operator
**I want** `PingController` removed from the production fat JAR (and any future dev-only
controllers segregated by construction, not just by `@Profile`)
**So that** running the deployed JAR with `SPRING_PROFILES_ACTIVE=dev` cannot expose
debugging endpoints to a deployed environment, and so that the codebase does not set the
precedent "any test-shaped controller goes in `src/main/java` and is gated by
`@Profile("dev")`".

### Description

Today (per Code Review #1, HIGH finding):
- `infrastructure/web/dev/PingController` lives on the **production classpath** (under
  `src/main/java`) and is annotated `@Profile("dev")`. It is only used by
  `BasePathConfigTest` / `SpringSecurityConfigTest`.
- `src/test/java/.../infrastructure/web/security/MeProbeController` correctly lives on
  the **test classpath** — the right convention, since it is only needed by test code.
- Every EPIC-03 integration test activates the `dev` profile via `@ActiveProfiles("dev")`.

The risk is twofold:
1. Anyone running the packaged JAR with `SPRING_PROFILES_ACTIVE=dev` (a common operator
   habit, or accidental) ships `GET /api/v1/ping`. Harmless on its own, but unintended.
2. It sets a precedent: the next person adding a debug probe is likely to put it next to
   `PingController` "for parity", and that next probe is exactly the kind of endpoint
   that could echo principal information (`MeProbeController` already does, and only
   accidentally stays in `src/test/java`).

The remediation is to **move `PingController` to `src/test/java`** (its sole consumers
are tests), and to document in `backend/CLAUDE.md` (or in a `package-info.java` Javadoc
on `infrastructure/web/dev/`) that dev-only probe controllers MUST NOT live in
`src/main/java`.

EPIC-15 was scheduled to ship Spring Boot Actuator anyway; if delivering Actuator early
is desired, `GET /actuator/health` already exists in `SpringSecurityConfig`'s permit-all
list and is a perfectly adequate replacement for the smoke-probe role `PingController`
played in dev. **However**, that is a separate, additive concern — this story does not
require shipping Actuator. The minimum acceptance is the move.

### Acceptance criteria

- `backend/src/main/java/com/cognizant/emk/multiagent/infrastructure/web/dev/PingController.java`
  is deleted. The empty `infrastructure/web/dev/` package on the main classpath is
  removed (along with any orphaned `package-info.java`).
- An equivalent test-only controller is added under
  `backend/src/test/java/.../infrastructure/web/dev/PingController.java` (or its
  responsibilities are absorbed into a `@TestConfiguration` used only by
  `BasePathConfigTest` / `BasePathConfigOverrideTest` / `SpringSecurityConfigTest`).
  - The replacement is **not** annotated `@Profile("dev")` — being on the test classpath
    is what scopes it.
  - It exposes the same `GET /ping` route (and any others the tests rely on) with
    byte-identical response shape.
- `BasePathConfigTest`, `BasePathConfigOverrideTest`, and `SpringSecurityConfigTest` are
  updated so they pick up the test-classpath controller (e.g., via
  `@SpringBootTest(classes = { Application.class, PingControllerTestConfig.class })` or
  the equivalent component-scan).
- `mvn package` (or `./mvnw package`) produces a fat JAR where the deployed JAR does
  **not** contain `PingController.class`. A new test
  `JarPackagingDoesNotIncludeDevControllersTest` may either:
  - (preferred) inspect the built JAR after `mvn package` for the class path
    `com/cognizant/emk/multiagent/infrastructure/web/dev/PingController.class` and
    assert it is absent, or
  - (acceptable fallback) use a Maven Enforcer / ArchUnit rule that no class in
    `infrastructure.web.dev..` exists on the main classpath.
- `LayeringArchTest` (or a new sibling) is extended with an ArchUnit rule:
  > Classes annotated `@RestController` and located under packages matching `..dev..`
  > MUST NOT be on the main classpath.
  This is the load-bearing piece — it prevents future regressions.
- A short note is added to `backend/CLAUDE.md` (or `backend/docs/SPECS.md` if the team
  prefers) stating: "Dev-only smoke / probe controllers live in `src/test/java` only.
  They MUST NOT be added to `src/main/java` and gated by `@Profile`."
- All previously green EPIC-01/02/03 tests still pass.

### Out of scope

- Shipping Spring Boot Actuator and replacing the dev ping with `/actuator/health`. That
  is EPIC-15 scope; this story neither blocks it nor depends on it.
- Defining a separate `prod` profile. Today there is only the default profile and `dev`;
  introducing a third profile is unnecessary if the dev controllers are physically
  excluded from the main classpath. A documentation line ("the default profile is the
  production profile; `dev` is local-only") is sufficient.

### Requirements coverage

`REQ-DEP-004` (single runnable fat JAR), `REQ-ARC-004` (simplicity — no debug
controllers in production), `REQ-AUTH-008` (only documented endpoints are reachable in
production).

### References

- Code Review #1 — HIGH finding "`dev` profile leaks `PingController` and
  `MeProbeController` under `/api/v1`" in `backend/analysis/CODE-REVIEW-1.md`.
- Design §3 project structure, §8.1 filter chain (permit-all entries).

### Dependencies

- EPIC-01 (project structure, ArchUnit infrastructure).
- EPIC-03 (the tests that consume `PingController` today).

---

## US-CR1-003 — Inject the `Clock` bean into `JjwtTokenServiceAdapter`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** `JjwtTokenServiceAdapter` to read "now" from the Spring-managed `Clock` bean
(the same bean already consumed by `InMemoryJwtDenylistAdapter` and
`ChangeOwnPasswordService`)
**So that** every time-aware component in the codebase observes the same notion of
"now", so that the `MutableClock` workaround in `LogoutEndpointIntegrationTest` can be
removed, and so that EPIC-13's Bucket4j rate-limit filter (also Clock-aware) is not
forced into the same asymmetry.

### Description

Today (per Code Review #1, HIGH finding):
- `infrastructure/security/JjwtTokenServiceAdapter` has two constructors:
  - A public, Spring-bound one that reads
    `properties.security().jwt().signingSecret()` and `lifetime()`, then **hard-codes**
    `Clock.systemUTC()` when delegating to the package-private constructor.
  - A package-private one accepting an explicit `Clock`, used by unit tests.
- `ClockConfig` exposes `Clock.systemUTC()` as a singleton bean.
  `InMemoryJwtDenylistAdapter` and `ChangeOwnPasswordService` both consume that bean.
- `LogoutEndpointIntegrationTest` works around the asymmetry by defining a custom
  `MutableClock` that overrides the bean for the denylist while accepting that the JWT
  adapter keeps using wall-clock time — a contortion documented in a long Javadoc.

The remediation is mechanical and low-risk: change the public constructor to accept
the `Clock` bean by parameter (Spring will inject the existing `ClockConfig` bean) and
pass it through to the package-private constructor instead of `Clock.systemUTC()`. No
behavior changes in production (the bean is `Clock.systemUTC()` by configuration); the
only effect is that test code can virtualize time uniformly.

### Acceptance criteria

- `JjwtTokenServiceAdapter`'s public constructor takes `Clock clock` as a parameter
  (after `ApplicationProperties`, preserving alphabetical / dependency order) and
  forwards it unchanged to the package-private constructor.
- The body of the public constructor no longer references `Clock.systemUTC()`.
- The package-private constructor still validates the signing secret (defensive
  `@NotBlank` / `@Size(min = 32)` already enforced by `ApplicationProperties` — keep
  the defensive check as-is).
- No other change is required to `issue(...)` or `verify(...)`; they already read
  `clock.instant()`.
- `JjwtTokenServiceAdapterTest`:
  - Removes any ad-hoc `Clock.fixed(...)` constructions that were only needed because
    of the asymmetry, where possible. (The package-private test constructor remains
    available; the change is that production tests may now use the bean.)
  - Adds a new round-trip test "JWT issued at virtualized now `T`, then verify at
    virtualized now `T+lifetime+1s` → `InvalidCredentialsException`" — exercising the
    expired-token branch via the injected `Clock` rather than by forging a token with
    a past `exp` value. This test was missing per Code Review #1.
- `LogoutEndpointIntegrationTest`:
  - The bespoke `MutableClock` is either deleted (if the standard `MutableClock` used
    by the denylist test now covers both) or pulled up into a single shared
    `MutableClock` test utility consumed by both the denylist and the JWT adapter.
  - The long Javadoc on the test class is simplified: the "the JJWT adapter pins to
    `Clock.systemUTC()`" paragraph is removed.
  - The clock-driven eviction scenario still passes: a JWT is issued at virtualized
    `T`, the JWT is logged out (added to denylist), the clock is advanced past
    `lifetime`, the scheduled sweep evicts the denylist entry, and a fresh request
    with the same `jti` now returns `INVALID_CREDENTIALS` because the token is itself
    expired (the JWT adapter's `verify` reads from the same virtualized clock).
- `ApplicationContextSmokeTest` (or equivalent context-load test) is verified to still
  green — Spring resolves the new `Clock` constructor parameter from the
  `ClockConfig` bean.
- A new ArchUnit assertion (optional but recommended): no class under
  `infrastructure/security/**` calls `java.time.Clock.systemUTC()` directly except
  `ClockConfig`. Failing this rule would catch a regression where a future adapter
  re-introduces the asymmetry.
- LOW finding "`JjwtTokenServiceAdapter` constructor uses field `@Autowired`" is
  resolved as part of the same change: the `@Autowired` annotation on the public
  constructor is dropped (Spring picks the sole constructor automatically). This is a
  one-line cleanup that lives naturally in this story.

### Out of scope

- Migrating the JJWT adapter to a different signing algorithm or key-rotation
  scheme. The `JwtParserBuilder.clock(...)` integration with JJWT is the only point
  of contact between JJWT and time; nothing else changes.
- Introducing a virtual-time test framework (e.g., StepVerifier's virtual clock,
  AwaitedClock). The existing `MutableClock` test utility is sufficient.

### Requirements coverage

`REQ-AUTH-003` (JWT `iat` / `exp`), `REQ-AUTH-004` (30-min default lifetime,
configurable), `REQ-AUTH-011` (logout denylist self-expires no later than `exp`),
`REQ-NFR-002` (testability of time-aware components).

### References

- Code Review #1 — HIGH finding "`JjwtTokenServiceAdapter` hard-codes
  `Clock.systemUTC()`, ignoring the `Clock` bean" in
  `backend/analysis/CODE-REVIEW-1.md`. Also folds in the LOW finding
  "`JjwtTokenServiceAdapter` constructor uses field `@Autowired`".
- Design §8.2 JWT, §8.3 denylist.

### Dependencies

- EPIC-03 (`JjwtTokenServiceAdapter`, `InMemoryJwtDenylistAdapter`, `ClockConfig`,
  `LogoutEndpointIntegrationTest`).
