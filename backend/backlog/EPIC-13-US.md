# EPIC-13-US.md — User stories for EPIC-13

EPIC-13 — **Rate limiting (Bucket4j)**

This file lists the user stories that deliver EPIC-13. The EPIC adds the
global Bucket4j rate-limiter at the very top of the security filter chain,
the two stacked per-minute / per-hour buckets, and the admin endpoints that
read and live-update the configuration without restart. Every other piece
of the platform's request pipeline is in place by the end of EPIC-12; this
EPIC inserts the throttling layer that protects the whole stack — including
the SSE chat endpoint — from runaway clients.

> **Scope split with EPIC-02 / EPIC-03 / EPIC-14.**
> - **Persistence row** (`rate_limit_config` table, single-row seed
>   `(id=1, per_minute=10, per_hour=50)`, `RateLimitConfigJpa` entity, raw
>   `RateLimitConfigJpaRepository` Spring Data interface) is **already
>   shipped** by EPIC-02 (US-02-003 schema, US-02-004 seed, US-02-005
>   entity, US-02-006 Spring Data interface). EPIC-13 only adds the domain
>   aggregate, the domain port, and the adapter that maps the JPA entity to
>   the aggregate.
> - **Admin authentication** (JWT-mode + `RequireRole(ADMIN)` plumbing) is
>   already shipped by EPIC-03. EPIC-13 reuses it on the two new
>   `/admin/rate-limit` endpoints — no new auth code.
> - **`ProblemDetails` + `GlobalExceptionHandler`** are already shipped by
>   EPIC-14's incremental rollout (specifically the `@RestControllerAdvice`
>   base, US-03-001). EPIC-13 only adds a new `@ExceptionHandler` branch
>   for the rate-limit exception, plus the `Retry-After` header on the 429
>   response (`REQ-RL-005`).
> - **The filter chain order** is fixed by §8.1: `RateLimitFilter` is the
>   **outermost** filter in the Spring Security chain so unauthenticated
>   traffic counts toward the global bucket too (`REQ-RL-003`). EPIC-13
>   adds the filter and inserts it before `JwtAuthenticationFilter`.
>
> **Out of scope for this EPIC (deferred).** Per-IP / per-user buckets are
> explicitly forbidden by `REQ-RL-003` and are not in scope here.
> Multi-node distributed buckets (Hazelcast / Redis) are out of scope —
> the v1 sizing target is 16 concurrent SSE streams on a single EC2
> instance (`REQ-NFR-005`); a single in-JVM Bucket4j cache is sufficient.
> Endpoint-specific bucket overrides (different limits on `/auth/login` vs
> the chat endpoint) are out of scope — the rate limit is global per
> `REQ-RL-003`. Metrics / observability exports (e.g., Micrometer counters
> for `requests_throttled_total`) are out of scope and tracked under
> EPIC-15.

## Conventions

- **ID format**: `US-13-<nnn>` — `13` matches the EPIC number; `<nnn>` is
  a sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start
  as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. Every story in this EPIC is
  `MUST` — the platform is unprotected without the filter, and the two
  admin endpoints are part of the openapi contract operators rely on.
- Each story contains: a narrative ("As a … I want … so that …"), a
  short description, a bullet list of testable acceptance criteria, the
  out-of-scope items, the requirements coverage, the design references,
  and its dependencies.

## Story list

| ID         | Title                                                                                                                          | Priority | Status | Depends on                                                                          |
|------------|--------------------------------------------------------------------------------------------------------------------------------|----------|--------|-------------------------------------------------------------------------------------|
| US-13-001  | Bucket4j dependency + `RateLimitConfig` aggregate + `RateLimitConfigRepository` port + domain tests                            | MUST     | Done   | EPIC-01 (`pom.xml`, layering ArchUnit), EPIC-02 (`rate_limit_config` schema + seed) |
| US-13-002  | `RateLimitConfigRepository` JPA adapter + domain ↔ JPA mapper + Postgres integration test                                      | MUST     | Done   | US-13-001, EPIC-02 (`RateLimitConfigJpa`, `RateLimitConfigJpaRepository`)           |
| US-13-003  | `GetRateLimitConfigUseCase` + `UpdateRateLimitConfigUseCase` (application layer) + use-case tests                              | MUST     | Done   | US-13-002, EPIC-03 (`UserPrincipal` for `updatedBy`)                                |
| US-13-004  | `RateLimitGate` port + `Bucket4jRateLimitGate` adapter — two stacked buckets, live rebuild on config change, `RateLimitedException` | MUST | Done   | US-13-002                                                                            |
| US-13-005  | `RateLimitFilter` (top-of-chain `OncePerRequestFilter`) + Spring Security wiring + 429 mapping with `Retry-After` header        | MUST     | Done   | US-13-004, EPIC-03 (`SpringSecurityConfig`), US-03-001 (`GlobalExceptionHandler`)   |
| US-13-006  | Admin REST endpoints — `GET /admin/rate-limit`, `PUT /admin/rate-limit` (ADMIN-only)                                            | MUST     | Done   | US-13-003, EPIC-03 (`@PreAuthorize("hasRole('ADMIN')")`)                            |
| US-13-007  | End-to-end integration test — bucket eviction with virtualized clock, 429 body / `Retry-After`, live admin update takes effect | MUST     | Done   | US-13-005, US-13-006                                                                |

---

## US-13-001 — Bucket4j dependency + domain aggregate + repository port

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `RateLimitConfig` domain aggregate (immutable record with
two validated counters and the audit fields), the `RateLimitConfigRepository`
port, and the Bucket4j artifact added to `pom.xml`
**So that** every other story in this EPIC has a typed seam to depend on,
the domain owns the per-minute / per-hour invariants (both `>= 1` per
`REQ-RL-004`), and the Bucket4j classes used by US-13-004 are on the
classpath from the very first commit.

### Description

The persistence layer (table, JPA entity, Spring Data interface, seed
migration) is **already in place** from EPIC-02 — EPIC-13 must not
re-create any of those. The new artefacts in this story live entirely in
the **domain** layer + the build descriptor:

1. **`pom.xml`** — add the Bucket4j core dependency. The project tree
   already pins versions through `spring-boot-starter-parent 4.0.6` and
   the Spring AI BOM; Bucket4j is not in either, so the dependency must
   carry an explicit version pinned in the `<properties>` block (latest
   stable in the 8.x line). Bucket4j Core has zero transitive
   dependencies on JCache / Hazelcast / Redis, so adding it does not
   accidentally drag in distributed-cache machinery.
2. **`domain/ratelimit/RateLimitConfig.java`** — a record carrying
   `perMinute` (int), `perHour` (int), `updatedAt` (`OffsetDateTime`),
   and `updatedBy` (`Optional<UserId>` — the seed row has no admin
   author, every subsequent update does). The compact constructor
   validates both counters with `ValidationException` (the shared
   domain exception), emitting field names `"perMinute"` / `"perHour"`
   so the EPIC-14 problem-details mapper can surface them.
3. **`domain/ratelimit/RateLimitConfigRepository.java`** — port with two
   methods:
   - `RateLimitConfig load()` — read the single configured row.
     Implementations MUST throw `IllegalStateException` (an infra-error,
     not a business error) when no row exists — that would mean the
     seed migration didn't apply.
   - `RateLimitConfig save(RateLimitConfig updated, UserId updatedBy,
     Instant now)` — persist the new counters, refresh `updated_at` and
     `updated_by`. Returns the persisted aggregate so the use case can
     return it to the caller.
4. **Domain exception** — `domain/ratelimit/InvalidRateLimitConfigException`
   is **not** created; bounds violations are reported via the generic
   `ValidationException` so the existing EPIC-14 mapper handles them with
   the per-field `errors[]` envelope.

The constants for the **default** values (10 / 50) live exclusively in the
Flyway seed (V003) and are referenced from the domain Javadoc — the domain
itself must not carry numeric defaults, since the runtime reads the live
row.

### Acceptance criteria

- `pom.xml` declares a new dependency:
  ```xml
  <dependency>
      <groupId>com.bucket4j</groupId>
      <artifactId>bucket4j-core</artifactId>
      <version>${bucket4j.version}</version>
  </dependency>
  ```
  with a corresponding `<bucket4j.version>` property in `<properties>`
  (8.x stable line; the exact patch version is left to the implementer
  but MUST be a non-snapshot release).
- `domain/ratelimit/RateLimitConfig.java` exists as a record:
  ```java
  public record RateLimitConfig(
      int perMinute,
      int perHour,
      OffsetDateTime updatedAt,
      Optional<UserId> updatedBy
  ) {
      public RateLimitConfig {
          if (perMinute < 1) {
              throw new ValidationException("perMinute", "must be at least 1");
          }
          if (perHour < 1) {
              throw new ValidationException("perHour", "must be at least 1");
          }
          Objects.requireNonNull(updatedAt, "updatedAt");
          Objects.requireNonNull(updatedBy, "updatedBy"); // the Optional itself
      }
  }
  ```
- `domain/ratelimit/RateLimitConfigRepository.java` exists with the two
  methods documented above. Javadoc spells out the load-throws-on-missing
  invariant.
- `domain/ratelimit/package-info.java` already exists (EPIC-02 stubbed
  it); its Javadoc is updated to reference `REQ-RL-004`,
  `REQ-RL-005`, and US-13-001.
- Pure-Java tests under `src/test/java/.../domain/ratelimit/`:
  - `RateLimitConfigTest`:
    - `accepts_minimum_valid_values` — `perMinute=1, perHour=1` constructs
      successfully.
    - `accepts_default_seed_values` — `perMinute=10, perHour=50`
      constructs successfully (matches V003 seed).
    - `rejects_zero_perMinute` — `perMinute=0` throws
      `ValidationException` with field `"perMinute"`.
    - `rejects_negative_perMinute` — `perMinute=-1` same surface.
    - `rejects_zero_perHour` / `rejects_negative_perHour` — same.
    - `rejects_null_updatedAt`.
    - `rejects_null_updatedBy_optional` (the wrapper, not the value).
    - `accepts_absent_updatedBy` — `Optional.empty()` is the seed-row
      shape (no admin has updated it yet) and MUST succeed.
- ArchUnit (`LayeringArchTest`, US-01-008) still passes — the new
  classes live in `domain/` with no Spring imports.
- `no_persistence_imports_in_domain` (the ArchUnit guard already in
  place) still passes — the record uses only `java.*` and one
  `ValidationException` from `domain/shared/`.

### Out of scope

- The JPA adapter implementing `RateLimitConfigRepository` (US-13-002).
- The Bucket4j adapter that consumes the aggregate (US-13-004).
- The application use cases (US-13-003).

### Requirements coverage

`REQ-RL-002` (Bucket4j implementation), `REQ-RL-004` (configurable
counters with `>= 1` bounds), `REQ-ARC-002` (hexagonal domain),
`REQ-ARC-006` (records for DTOs), `REQ-NFR-002` (domain unit tests).

### Design references

§4.1 RateLimitConfig entity, §11 rate limiting, §15 configuration
(`bucket4j.version` property).

### Dependencies

EPIC-01 (`pom.xml` structure, `LayeringArchTest`). EPIC-02
(`rate_limit_config` table + seed V003 + `RateLimitConfigJpa` entity —
all already shipped; this story only consumes them indirectly via the
port).

---

## US-13-002 — `RateLimitConfigRepository` JPA adapter + integration test

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `RateLimitConfigRepository` JPA adapter that maps the
single `rate_limit_config` row to the `RateLimitConfig` aggregate and
back, plus a Postgres-backed integration test that proves the round-trip
**So that** the application use cases (US-13-003) and the bucket adapter
(US-13-004) can read and persist the live counters without leaking
`RateLimitConfigJpa` / `UserJpa` types into the application layer.

### Description

`RateLimitConfigRepositoryAdapter` lives in
`infrastructure/persistence/adapter/` next to the other repository
adapters shipped by EPIC-02 / EPIC-03 / EPIC-06 / EPIC-10. It composes
the existing `RateLimitConfigJpaRepository` (US-02-006) with a new
`RateLimitConfigMapper` and a `UserJpaRepository` (existing) to:

1. On `load()`, read the row with id `1` (the schema constrains
   `id smallint primary key default 1 check (id = 1)` — single-row
   table) and convert to `RateLimitConfig`. If absent, throw
   `IllegalStateException("rate_limit_config row missing — Flyway seed
   V003 did not apply")` — operators must see this loudly, never
   silently default to 10/50 in code.
2. On `save(updated, updatedBy, now)`, hydrate the existing row
   (`findById(1).orElseThrow(...)`), copy `perMinute` / `perHour` from
   `updated`, set `updated_at = now`, `updated_by = userJpaRepository
   .getReferenceById(updatedBy.value())` (lazy reference — no extra
   user-row read), and `save(...)`. Returns a re-mapped aggregate
   reflecting the row's new `updated_at` and `updated_by`.

The mapper is a private `static` class in the same file (the type is
trivial, doesn't need its own file) or a package-private utility class —
implementer's choice, both align with the EPIC-02 / EPIC-03 pattern.

### Acceptance criteria

- `infrastructure/persistence/adapter/RateLimitConfigRepositoryAdapter.java`
  exists, implements `RateLimitConfigRepository`, is annotated
  `@Component`, and is wired by constructor injection (no field
  `@Autowired` per the project Java coding standard).
- `load()` returns the V003-seeded values (10 per-minute, 50 per-hour,
  `updated_by = Optional.empty()`).
- `save(RateLimitConfig updated, UserId updatedBy, Instant now)`:
  - Updates `per_minute`, `per_hour`, `updated_at`, `updated_by`.
  - Returns a `RateLimitConfig` whose `updatedAt` matches the value the
    database round-tripped (i.e. the truncated `OffsetDateTime` read
    back from Postgres, not the input `Instant`).
  - Does NOT touch any other row — the table has a primary-key default
    of `1` and a check constraint `id = 1`, so a faulty implementation
    that inserts instead of updates fails loudly.
- `load()` throws `IllegalStateException` when the row is missing (test
  via `@DirtiesContext` + manual `delete` in the integration test
  below).
- Domain ↔ JPA mapping is symmetric: `map(jpa).then(saveBack).map(...)`
  yields the same field values.
- Postgres integration test
  `RateLimitConfigRepositoryAdapterIntegrationTest`:
  - Extends the project's `PostgresIntegrationTest` base (the same
    base every other repository integration test uses).
  - `loads_seeded_row` — without any prior mutation, `load()` returns
    `perMinute=10`, `perHour=50`, `updatedBy=Optional.empty()`.
  - `saves_and_reloads` — `save(new RateLimitConfig(20, 100, now,
    Optional.empty()), bootstrapAdminId, now)` is followed by a
    `load()` that returns `perMinute=20`, `perHour=100`,
    `updatedBy=Optional.of(bootstrapAdminId)`, `updatedAt` equal to
    the round-tripped value.
  - `throws_when_row_missing` — manually `delete` the row, expect
    `IllegalStateException` from `load()`.
- ArchUnit (`LayeringArchTest`): adapter sits under
  `infrastructure/persistence/adapter/` and depends only on
  `domain/ratelimit/`, `domain/user/UserId`, and
  `infrastructure/persistence/{entity,springdata}/` — no application
  imports.

### Out of scope

- The application use cases reading / updating through the port
  (US-13-003).
- The bucket adapter (US-13-004).

### Requirements coverage

`REQ-RL-004` (live-configurable persistence), `REQ-PRS-003`
(transactional integrity — `save()` is `@Transactional` per the
adapter pattern), `REQ-NFR-002` (integration test).

### Design references

§4.1 RateLimitConfig entity, §5 database schema
(`rate_limit_config` single-row), §5.2 cascade rules
(`updated_by → users(id)` is RESTRICT — admin deletion is blocked
while an updated_by reference exists; that's the desired behavior),
§11 rate limiting.

### Dependencies

US-13-001 (domain aggregate + port). EPIC-02 US-02-005
(`RateLimitConfigJpa` entity) and US-02-006
(`RateLimitConfigJpaRepository` Spring Data interface — both already
shipped).

---

## US-13-003 — `GetRateLimitConfigUseCase` + `UpdateRateLimitConfigUseCase`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the two application-layer use cases that the REST controller
(US-13-006) calls into — one read-only `GetRateLimitConfigUseCase` and
one write `UpdateRateLimitConfigUseCase` — plus a small notification
seam (`RateLimitConfigChangeListener`) that US-13-004's bucket adapter
subscribes to so the live cache rebuilds atomically on admin update
**So that** the controller stays a thin REST adapter, the cache rebuild
is decoupled from HTTP machinery, and the unit-test surface for the
update logic (clock injection, audit field stamping, listener
notification) is independent of Spring MVC.

### Description

Both use cases live in `application/ratelimit/`. Each is a `@Component`
with a single public method.

`GetRateLimitConfigUseCase` is a one-liner: delegate to
`repository.load()` and return. It exists for the symmetry with the
existing use-case-per-endpoint pattern (US-04 / 05 / 06 / 10 all follow
it) — having a use case keeps `@PreAuthorize("hasRole('ADMIN')")` off
the repository and makes the call easy to mock in `RateLimitAdminController`
tests.

`UpdateRateLimitConfigUseCase` is the load-bearing one:

1. Receives `UpdateRateLimitConfigCommand(int perMinute, int perHour,
   UserId admin)`. The record's compact constructor validates the
   counters via `ValidationException` (mirroring the domain check —
   defense in depth; the controller-side Bean Validation `@Min(1)` is a
   third layer).
2. Calls `repository.save(new RateLimitConfig(perMinute, perHour,
   clock.instant().atOffset(ZoneOffset.UTC), Optional.of(admin)),
   admin, clock.instant())` — the `Clock` is injected (US-CR1-003
   pattern: every time-aware adapter consumes the singleton `Clock`
   bean, never `Clock.systemUTC()` directly).
3. **Notifies the listener**: calls
   `listener.onRateLimitConfigChanged(updated)` AFTER the
   `@Transactional` save commits. The listener interface lives in
   `application/ratelimit/` so the domain layer stays clean of
   "publish" concepts; the bucket adapter (US-13-004) implements it as
   a Spring `@Component`.

Why a listener and not a direct call into the bucket adapter? The
application layer must not depend on the bucket adapter — that's an
infrastructure concern. A small `RateLimitConfigChangeListener` interface
in `application/ratelimit/` inverts the dependency: the bucket adapter
implements the listener, gets injected into the use case as a port, and
the application stays Spring-free aside from `@Component`.

### Acceptance criteria

- `application/ratelimit/GetRateLimitConfigUseCase.java` exists as a
  `@Component` with a single method
  `RateLimitConfig load()` that delegates to `RateLimitConfigRepository`.
- `application/ratelimit/UpdateRateLimitConfigUseCase.java` exists as a
  `@Component` with a single method
  `RateLimitConfig update(UpdateRateLimitConfigCommand command)`.
- `application/ratelimit/UpdateRateLimitConfigCommand.java` is a record
  with fields `(int perMinute, int perHour, UserId admin)`. Compact
  constructor:
  - `perMinute < 1` → `ValidationException("perMinute", ...)`.
  - `perHour < 1` → `ValidationException("perHour", ...)`.
  - `admin == null` → NPE.
- `application/ratelimit/RateLimitConfigChangeListener.java` is an
  interface with one method:
  ```java
  void onRateLimitConfigChanged(RateLimitConfig updated);
  ```
  Javadoc: "Called after a successful admin update commits. Implementations
  MUST be non-blocking — the bucket rebuild runs on the calling thread."
- `UpdateRateLimitConfigUseCase`:
  - Is annotated `@Transactional` on the public method (the save MUST
    commit before the listener fires).
  - Calls the listener AFTER the `repository.save(...)` call returns.
  - When the listener throws, the use case logs the exception at WARN
    (no stack trace leakage), swallows it, and returns the persisted
    aggregate. Rationale: the row is already committed; reverting the
    save because the cache failed to refresh would be confusing for
    the admin, and the cache will catch up on the next refill boundary
    per `REQ-RL-004` ("changes take effect without redeploying").
- Pure-Java unit tests under `src/test/java/.../application/ratelimit/`:
  - `GetRateLimitConfigUseCaseTest` — verifies delegation to the
    mocked repository.
  - `UpdateRateLimitConfigUseCaseTest`:
    - Happy path saves with the canonicalized `OffsetDateTime` (UTC,
      from the injected `Clock`), invokes the listener, returns the
      persisted aggregate.
    - Save then listener throws → method returns normally, listener
      exception is logged at WARN (verify via a Logback list-appender).
    - `perMinute < 1` rejected by the command constructor before the
      use case is even called.
  - `UpdateRateLimitConfigCommandTest` — `ValidationException` field
    names match `"perMinute"` / `"perHour"`.
- ArchUnit guard `no_spring_ai_imports_in_application_*` (existing) +
  `no_persistence_imports_in_application_*` continue to pass — the
  use cases depend only on the domain port + the `Clock` + the
  listener interface.

### Out of scope

- The bucket adapter implementing the listener (US-13-004).
- The REST controller calling the use cases (US-13-006).

### Requirements coverage

`REQ-RL-004` (live, admin-only update), `REQ-ARC-002` (hexagonal),
`REQ-ARC-006` (records, constructor injection, no Lombok),
`REQ-NFR-002` (application unit tests).

### Design references

§11 rate limiting (live reconfiguration paragraph), §15 configuration,
§16 (no dedicated sequence diagram — this is straight CRUD).

### Dependencies

US-13-002 (the repository adapter). EPIC-03
(`UserId` value object + `Clock` bean — both already shipped).

---

## US-13-004 — `RateLimitGate` port + `Bucket4jRateLimitGate` adapter

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `RateLimitGate` port (`tryAcquire() → TryAcquireResult`)
and its `Bucket4jRateLimitGate` adapter — a single global Bucket4j bucket
that stacks the per-minute and per-hour limits, rebuilds atomically when
an admin saves a new config (listener from US-13-003), and surfaces the
"how long until the next token" via a `RateLimitedException` carrying
`retryAfterSeconds`
**So that** the filter (US-13-005) consumes a small, testable port
instead of touching Bucket4j directly, and the live-reconfiguration
contract from `REQ-RL-004` ("changes take effect on the next request
without restart") is enforced by the adapter and not by the filter.

### Description

The port lives in `application/ratelimit/`:

```java
public interface RateLimitGate {
    /**
     * Attempt to consume one token from the global bucket. Returns
     * {@link TryAcquireResult#ALLOWED} if both buckets had capacity,
     * or a denied result carrying the longer of the two "time until
     * next refill" values so the filter can populate {@code Retry-After}.
     */
    TryAcquireResult tryAcquire();

    sealed interface TryAcquireResult {
        record Allowed() implements TryAcquireResult {}
        record Denied(int retryAfterSeconds) implements TryAcquireResult {}
    }
}
```

The adapter lives in `infrastructure/ratelimit/Bucket4jRateLimitGate.java`
and is a Spring `@Component` that also implements
`RateLimitConfigChangeListener` (US-13-003).

Implementation choices:

1. **Two stacked buckets** in one Bucket4j `Bucket`:
   - Per-minute: capacity = `perMinute`, refill = `perMinute` tokens
     every 1 minute (Bucket4j `Bandwidth.simple(perMinute,
     Duration.ofMinutes(1))`).
   - Per-hour: capacity = `perHour`, refill = `perHour` tokens every
     1 hour.
   - Both bandwidths attached to the same Bucket; Bucket4j's
     `tryConsumeAndReturnRemaining(1)` returns a
     `ConsumptionProbe` that aggregates over all bandwidths — when the
     `consumed == false`, the probe's `getNanosToWaitForRefill()` is the
     wait until the **most-restrictive** bucket refills, which is
     exactly what `Retry-After` should reflect per `REQ-RL-005`.
2. **Volatile reference to the current bucket**. The listener swaps the
   reference under a small lock when an admin update fires; readers
   (the filter) read the volatile reference and call `tryConsume(1)`
   without holding any lock. Rebuild is bounded — a new bucket of size
   N tokens is essentially free.
3. **Cold start** — the adapter is constructed BEFORE the
   `@PostConstruct` of `loadInitialConfig()` runs. The bucket is built
   lazily on the first `tryAcquire()` call OR eagerly via
   `@EventListener(ApplicationReadyEvent.class)`. The latter is
   preferred so the first request never pays the bucket-construction
   cost; if the `ApplicationReadyEvent` listener throws (e.g., the
   `rate_limit_config` row is missing), application startup fails
   loudly — that's the right behavior.
4. **Retry-After rounding** — Bucket4j returns nanoseconds; we ceil to
   the nearest second (`(nanos + 999_999_999) / 1_000_000_000`), with a
   floor of `1` so the client never sees `Retry-After: 0` on a
   denied request.

The infrastructure exception:

```java
public class RateLimitedException extends RuntimeException {
    private final int retryAfterSeconds;
    public RateLimitedException(int retryAfterSeconds) {
        super("Global rate limit exceeded; retry in " + retryAfterSeconds + "s.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public int retryAfterSeconds() { return retryAfterSeconds; }
}
```

It lives in `infrastructure/web/error/` next to the other infrastructure
exceptions (e.g., `LlmUnavailableException`); the filter throws it,
`GlobalExceptionHandler` maps it to 429 with the `Retry-After` header
(US-13-005).

### Acceptance criteria

- `application/ratelimit/RateLimitGate.java` — the port and the sealed
  `TryAcquireResult` hierarchy as specified above.
- `infrastructure/ratelimit/Bucket4jRateLimitGate.java`:
  - Implements `RateLimitGate` and `RateLimitConfigChangeListener`.
  - Stores the current `Bucket` in a `volatile` field.
  - Builds the bucket from `RateLimitConfigRepository.load()` on
    `@EventListener(ApplicationReadyEvent.class)`.
  - `tryAcquire()` calls `bucket.tryConsumeAndReturnRemaining(1)` and
    maps the probe to `Allowed` / `Denied`.
  - `onRateLimitConfigChanged(updated)` rebuilds the bucket atomically
    by swapping the `volatile` reference. The previously-consumed
    tokens are NOT preserved across rebuild — this is a deliberate
    simplification documented in `DESIGN-CHOICES.md` ("after an admin
    update, the new bucket starts full; the old in-flight clients get
    a one-shot grace allotment up to `perMinute` requests").
- `infrastructure/web/error/RateLimitedException.java` — public, runtime
  exception carrying `retryAfterSeconds` (int, ≥ 1).
- Unit tests in `Bucket4jRateLimitGateTest` using Bucket4j's
  `LocalBucketBuilder` + a virtualized `TimeMeter`:
  - **Allowed under the per-minute limit** — 5 acquires against a
    config of `perMinute=10, perHour=50` all return `Allowed`.
  - **Denied at the per-minute boundary** — 11th acquire returns
    `Denied(retryAfterSeconds <= 60)`.
  - **Denied at the per-hour boundary** — across simulated clock
    advances of 1 minute each (so per-minute refills), the 51st
    acquire returns `Denied(retryAfterSeconds <= 3600)`.
  - **Listener rebuild** — call `onRateLimitConfigChanged(new
    RateLimitConfig(1, 1, now, Optional.empty()))`; the next
    `tryAcquire()` returns `Allowed`; the one after returns `Denied`.
  - **Retry-After flooring** — when Bucket4j reports `nanosToWait < 1
    second`, the adapter returns `Denied(retryAfterSeconds == 1)`.
- The `DESIGN-CHOICES.md` file gains a one-paragraph entry: "EPIC-13:
  bucket rebuild on admin config update discards remaining tokens
  rather than scaling them. The trade-off is documented; for the v1
  sizing (`REQ-NFR-005`) it is harmless."
- ArchUnit: the bucket adapter sits under `infrastructure/ratelimit/`
  and imports `io.github.bucket4j.*` — no application or domain
  imports of Bucket4j allowed.

### Out of scope

- The filter that calls the port (US-13-005).
- Multi-node bucket sharing (Hazelcast / Redis) — not in v1.

### Requirements coverage

`REQ-RL-001` (filter applied — this story builds the gate the filter
uses), `REQ-RL-002` (Bucket4j), `REQ-RL-003` (global scope — single
in-JVM bucket, no per-IP / per-user differentiation), `REQ-RL-004`
(live reconfiguration), `REQ-RL-005` (`Retry-After`).

### Design references

§11 rate limiting (the two-stacked-bandwidths model), §15 configuration,
§19 TBD entries (none for rate limiting — this EPIC closes the
section).

### Dependencies

US-13-002 (the repository the adapter reads at startup), US-13-003
(the listener the adapter implements).

---

## US-13-005 — `RateLimitFilter` + Spring Security wiring + 429 mapping

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `RateLimitFilter` (an `OncePerRequestFilter` extending
the standard servlet base) installed at the very top of the Spring
Security chain — before `JwtAuthenticationFilter` — and the
`GlobalExceptionHandler` branch that maps `RateLimitedException` to a
429 response carrying the `Retry-After` header and the standard
`ProblemDetails` body
**So that** unauthenticated traffic counts toward the global bucket
(`REQ-RL-003`), the filter does not have to know about response
formatting (the handler owns it), and the openapi-documented `RateLimited`
response shape is produced by exactly one code path the integration test
in US-13-007 can lock in place.

### Description

The filter lives in `infrastructure/web/ratelimit/RateLimitFilter.java`
(the package-info stub from EPIC-02 is fleshed out here). It is a
`@Component` extending `OncePerRequestFilter` so a single request — even
if forwarded internally by Spring MVC — burns exactly one token.

Implementation:

```java
public final class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitGate gate;
    public RateLimitFilter(RateLimitGate gate) { this.gate = gate; }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        RateLimitGate.TryAcquireResult result = gate.tryAcquire();
        if (result instanceof RateLimitGate.TryAcquireResult.Denied denied) {
            throw new RateLimitedException(denied.retryAfterSeconds());
        }
        chain.doFilter(req, res);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        // Skip the actuator health probe so the rate limiter cannot
        // accidentally page operators (REQ-OBS-003). The /actuator
        // tree lives outside /api/v1 anyway, but the filter chain
        // covers all paths.
        return req.getServletPath().startsWith("/actuator");
    }
}
```

It is wired into the chain in `SpringSecurityConfig` via
`addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)`. The
ordering matches §8.1 exactly: `RateLimitFilter` is outermost, before
authentication, so:

- Login attempts contribute to the global bucket (a credential-stuffing
  attack must not bypass throttling).
- The SSE chat endpoint is throttled at request-open time — once the
  stream is established, the filter is no longer invoked.
- The actuator health probe is skipped via `shouldNotFilter`.

The exception handler entry in `GlobalExceptionHandler`:

```java
@ExceptionHandler(RateLimitedException.class)
public ResponseEntity<ProblemDetails> handleRateLimited(
        RateLimitedException ex, HttpServletRequest req) {
    ProblemDetails body = ProblemDetails.builder()
            .type(URI.create("https://errors.multi-agent-platform/rate-limited"))
            .title("Too many requests")
            .status(429)
            .detail("Global rate limit exceeded; retry later.")
            .code("RATE_LIMITED")
            .instance(req.getRequestURI())
            .build();
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
            .contentType(MediaType.parseMediaType("application/problem+json"))
            .body(body);
}
```

Important: because the filter throws BEFORE the
`DispatcherServlet` is reached, the `@RestControllerAdvice` does NOT
catch it on the standard MVC path. Two options to bridge the gap:

- **Option A (preferred)**: register an `HandlerExceptionResolver`
  bridge — `SpringSecurityConfig` uses
  `.exceptionHandling(eh -> eh.authenticationEntryPoint(...))` already;
  EPIC-13 adds a `HandlerExceptionResolver` bean that delegates to the
  `GlobalExceptionHandler`. This keeps the response shape in one place.
- **Option B**: write the 429 response directly from the filter. Adds
  duplicated formatting code; rejected.

US-13-005 ships **Option A** — the wiring is small: the filter catches
`RateLimitedException`, re-throws after letting the resolver run, or
calls `HandlerExceptionResolverComposite.resolveException(req, res,
null, ex)` directly. The implementer's note in `DESIGN-CHOICES.md`
records the choice.

### Acceptance criteria

- `infrastructure/web/ratelimit/RateLimitFilter.java` exists, extends
  `OncePerRequestFilter`, calls `gate.tryAcquire()`, throws
  `RateLimitedException` on `Denied`, skips `/actuator/**` via
  `shouldNotFilter`.
- `SpringSecurityConfig` registers the filter via
  `.addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)`.
  The integration test in US-13-007 verifies the ordering via a
  `MockMvc` request that includes a malformed `Authorization` header
  — the rate-limit filter must reject before JWT validation runs.
- The `GlobalExceptionHandler` gains a `@ExceptionHandler(RateLimitedException.class)`
  method:
  - 429 status.
  - `Retry-After: <int>` header equal to
    `ex.retryAfterSeconds()`.
  - `Content-Type: application/problem+json`.
  - Body: `code="RATE_LIMITED"`, `title="Too many requests"`,
    `status=429`, `detail` non-empty, `instance=<requested URI>`,
    `type` per openapi `RateLimited` example.
- The filter-thrown exception reaches the handler. A
  `HandlerExceptionResolver` bridge (Option A above) is configured.
- `RateLimitFilterTest` (unit, MockMvc-style with a stubbed
  `RateLimitGate`):
  - Allowed → chain proceeds.
  - Denied → 429 response with `Retry-After` and the
    `application/problem+json` body matching the openapi example.
  - `/actuator/health` → never reaches `gate.tryAcquire()`.
- The DESIGN-CHOICES.md entry for "filter-to-handler bridge" is
  added.

### Out of scope

- The admin endpoints (US-13-006).
- The end-to-end integration test that exercises the bucket via real
  clock advancement (US-13-007).

### Requirements coverage

`REQ-RL-001`, `REQ-RL-003` (unauthenticated traffic counts),
`REQ-RL-005` (429 + Retry-After + ProblemDetails), `REQ-API-004`
(error envelope), `REQ-OBS-003` (`/actuator/health` excluded).

### Design references

§8.1 filter chain (RateLimitFilter is the outermost filter), §9.2
GlobalExceptionHandler, §9.3 error response shape, §11 rate
limiting.

### Dependencies

US-13-004 (`RateLimitGate` port + adapter). EPIC-03 (`SpringSecurityConfig`
+ `JwtAuthenticationFilter`). US-03-001 (`GlobalExceptionHandler`
base + `ProblemDetails` builder — already shipped).

---

## US-13-006 — Admin REST endpoints `GET /admin/rate-limit` + `PUT /admin/rate-limit`

- **Status**: Done
- **Priority**: MUST

**As a** platform admin
**I want** the two REST endpoints `GET /admin/rate-limit` (read live
config) and `PUT /admin/rate-limit` (replace live config) under
`/api/v1`, ADMIN-only via `@PreAuthorize("hasRole('ADMIN')")`, returning
the `RateLimitConfig` body shape documented in `openapi.yaml`
**So that** operators can inspect and change the limits at runtime
(`REQ-RL-004`) without redeploying, and the endpoints honor the same
RBAC and error-envelope conventions as the rest of the admin surface.

### Description

The controller lives in
`infrastructure/web/ratelimit/RateLimitAdminController.java`. It is a
thin REST adapter that delegates to the two use cases shipped in
US-13-003.

Endpoint contract (mirrors `openapi.yaml.operationId getRateLimitConfig`
and `updateRateLimitConfig`):

| Method | Path                | Auth   | Body                                  | 200 body              |
|--------|---------------------|--------|---------------------------------------|-----------------------|
| GET    | `/admin/rate-limit` | ADMIN  | —                                     | `RateLimitConfigDto`  |
| PUT    | `/admin/rate-limit` | ADMIN  | `RateLimitConfigRequestDto`           | `RateLimitConfigDto`  |

Where:

```java
public record RateLimitConfigDto(
        int perMinute,
        int perHour,
        OffsetDateTime updatedAt,
        UUID updatedBy   // nullable: null on the seed row, non-null after first admin update
) {}

public record RateLimitConfigRequestDto(
        @Min(1) @NotNull Integer perMinute,
        @Min(1) @NotNull Integer perHour
) {}
```

The `RequestDto` uses Bean Validation (`@Min(1)`) so a `perMinute=0` or
missing field is caught by Spring before the controller body runs, and
the EPIC-14 problem-details mapper surfaces it as 400
`VALIDATION_ERROR` with `errors[]`. The domain re-validates (defense in
depth — US-13-001 + US-13-003).

The PUT handler reads the calling user from the security context (the
shipped `Principal` sealed type — only `UserPrincipal` reaches here
because the URL is `/admin/**`, the SYSTEM principal is denied by the
authorization rule, and the `@PreAuthorize` enforces `ROLE_ADMIN`),
builds an `UpdateRateLimitConfigCommand`, calls the use case, and
returns the persisted `RateLimitConfig` as a `RateLimitConfigDto`.

### Acceptance criteria

- `infrastructure/web/ratelimit/RateLimitAdminController.java` is a
  `@RestController` mounted at `/admin/rate-limit` (the controller
  does NOT carry the `/api/v1` prefix per `REQ-API-006` — that prefix
  is applied centrally via `app.api.base-path`).
- Method signatures:
  ```java
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public RateLimitConfigDto get();

  @PutMapping
  @PreAuthorize("hasRole('ADMIN')")
  public RateLimitConfigDto update(@RequestBody @Valid RateLimitConfigRequestDto body,
                                   @AuthenticationPrincipal UserPrincipal principal);
  ```
- `RateLimitConfigDto` and `RateLimitConfigRequestDto` are records under
  the same package (or a sibling `dto/` package — follow the pattern of
  the other admin controllers).
- A small mapper turns `RateLimitConfig` (domain) into
  `RateLimitConfigDto` (DTO): `Optional<UserId>` becomes a nullable
  `UUID` via `.map(UserId::value).orElse(null)`.
- MockMvc integration tests `RateLimitAdminControllerIntegrationTest`:
  - **GET as admin** — 200 with the seed values
    (`perMinute=10, perHour=50, updatedBy=null`).
  - **GET as STANDARD user** — 403 with `FORBIDDEN` code.
  - **GET unauthenticated** — 401 `INVALID_CREDENTIALS` (same
    surface as every other authed endpoint).
  - **GET as SYSTEM** (API-key principal) — 403 `FORBIDDEN` (per
    `REQ-AUTH-007` — SYSTEM has no admin capability).
  - **PUT as admin** with `{perMinute: 30, perHour: 200}` — 200
    response with the updated values; a subsequent GET sees the
    same; `updatedBy` is the calling admin's id.
  - **PUT as admin with `perMinute: 0`** — 400 `VALIDATION_ERROR`
    with `errors: [{field: "perMinute", message: ...}]`.
  - **PUT with empty body** — 400 `VALIDATION_ERROR` (Bean
    Validation surfaces `@NotNull`).
  - **PUT as STANDARD** — 403 `FORBIDDEN`.
- The DTOs match the openapi schema **byte-for-byte**: a generated
  client against `openapi.yaml` MUST round-trip the response without
  schema-mismatch warnings. Verified by an `OpenApiContractTest`
  comparing the DTO fields to `RateLimitConfig` / `RateLimitConfigRequest`
  from `openapi.yaml`.

### Out of scope

- The 429 surface (US-13-005).
- An audit-log entry for each update — not in v1 (REQ-OBS-001
  observability is structured logging only).

### Requirements coverage

`REQ-RL-004` (admin-configurable at runtime), `REQ-AUTH-007`
(SYSTEM cannot reach admin endpoints), `REQ-AUTH-008` (RBAC on admin
endpoints), `REQ-API-004` (error envelope), `REQ-API-006`
(`/api/v1` prefix centrally applied).

### Design references

§6.2.4 admin rate-limit endpoints, §8.6 authorization rules, §15
configuration, `openapi.yaml` `getRateLimitConfig` /
`updateRateLimitConfig` / `RateLimitConfig` / `RateLimitConfigRequest`.

### Dependencies

US-13-003 (the use cases). EPIC-03 (admin auth + `@PreAuthorize`).
EPIC-04 (`UserPrincipal` resolution from the security context).

---

## US-13-007 — End-to-end integration test — eviction, 429 envelope, live admin update

- **Status**: Done
- **Priority**: MUST

**As a** backend developer landing the rate-limit feature
**I want** a single integration-test class that mounts the full Spring
Security chain (with the real `RateLimitFilter`) and drives the bucket
through three regression-locked scenarios — boundary eviction with a
virtualized `TimeMeter`, the 429 response shape end-to-end, and the
live admin update reflected on the next request
**So that** every cell of the §11 rate-limiting behavior has a test
that fails red if a future change drops a guarantee, and the chat
team can rely on the documented 429 response while building the
frontend countdown handling (frontend US-07-005 already consumes the
`Retry-After` header).

### Description

The test class lives in `infrastructure/web/ratelimit/RateLimitFilterIntegrationTest.java`
and extends the standard `PostgresIntegrationTest` base. Its harness:

1. Boots the full `@SpringBootTest` context against the test DB.
2. Replaces the `Bucket4jRateLimitGate`'s `TimeMeter` with
   `TimeMeter.SYSTEM_MILLISECONDS` overridden by a custom virtualized
   meter (or constructs a test-only `LocalBucket` via the same Bucket4j
   public API) so per-minute / per-hour boundaries can be exercised
   without `Thread.sleep`. Uses `@TestConfiguration` + `@Primary` to
   swap the gate bean.
3. Uses `MockMvc` to hit a small dev-only `@Profile("test") @RestController`
   exposing `GET /api/v1/_rl_probe` (returning 200 with an empty body).
   The probe is added in the **test classpath only** per the project
   convention from US-CR1-002 ("dev-only smoke / probe controllers live
   in `src/test/java` only").

Scenarios:

- **Boundary eviction (per-minute)**: with `(perMinute=3, perHour=999)`,
  three GETs of `/_rl_probe` return 200; the fourth returns 429 with
  `Retry-After: <= 60`. Advance the virtual clock by 60s; the next GET
  returns 200.
- **Boundary eviction (per-hour)**: with `(perMinute=999, perHour=2)`,
  two GETs return 200; the third returns 429 with `Retry-After:
  <= 3600`.
- **Response envelope**: the 429 body matches the openapi `RateLimited`
  example exactly — `code="RATE_LIMITED"`, `title="Too many requests"`,
  `status=429`, `type` URI present, `instance` equals the requested
  path. `Content-Type` is `application/problem+json`. `Retry-After` is
  present and `>= 1`.
- **Unauthenticated traffic counts**: with the bucket exhausted, a GET
  on `/auth/login` (no Authorization header) also returns 429 — proves
  the filter runs before the JWT filter.
- **Live admin update takes effect on the next request**: with the
  bucket exhausted via `/_rl_probe`, perform a `PUT /admin/rate-limit`
  with `{perMinute: 100, perHour: 1000}` (authenticated as the
  bootstrap admin). The 429 path is replaced by 200 on the very next
  `/_rl_probe` GET — proves the listener (US-13-003) rebuilt the
  bucket atomically.
- **Actuator excluded**: `GET /actuator/health` returns 200 even when
  the bucket is exhausted.

### Acceptance criteria

- `RateLimitFilterIntegrationTest` extends `PostgresIntegrationTest`,
  uses `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `MockMvc`.
- A test-only probe controller `RateLimitProbeController` lives under
  `src/test/java/.../infrastructure/web/ratelimit/` (NOT
  `src/main/java`, per US-CR1-002), exposing
  `GET /api/v1/_rl_probe` returning 204 No Content.
- The five scenarios above all pass:
  1. Per-minute boundary eviction with virtualized clock.
  2. Per-hour boundary eviction with virtualized clock.
  3. 429 response envelope matching the openapi example.
  4. Unauthenticated traffic counts toward the bucket.
  5. Live admin PUT takes effect on the next request.
  6. `/actuator/health` is excluded.
- The harness does NOT use `Thread.sleep` — `TimeMeter` virtualization
  drives the clock.
- The test runs in under 5 seconds locally (no real sleeps).
- The test exercises the **real** filter, the **real**
  `Bucket4jRateLimitGate`, and the **real** `GlobalExceptionHandler`.
  Only the `TimeMeter` is replaced; the rest of the production code
  path is unchanged.

### Out of scope

- Frontend integration — that is frontend US-07-005, which already
  consumes the `Retry-After` header.
- Load testing (e.g., JMeter) — not in v1.
- Metrics-export verification — EPIC-15 scope.

### Requirements coverage

`REQ-RL-001`, `REQ-RL-002`, `REQ-RL-003`, `REQ-RL-004`, `REQ-RL-005`
— all five rate-limit requirements are exercised end-to-end by this
suite. `REQ-NFR-002` (integration-test coverage).

### Design references

§8.1 filter chain (the test asserts the ordering at runtime), §11
rate limiting (the test exercises every paragraph), §9.3 error
response shape (the 429 envelope assertion), `openapi.yaml`
`RateLimited` response.

### Dependencies

US-13-005 (filter wired into the chain), US-13-006 (the admin endpoints
the test calls to drive the live-update scenario).

---

## Summary

| ID         | Title                                                                                                                          | Priority | Status |
|------------|--------------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-13-001  | Bucket4j dependency + `RateLimitConfig` aggregate + repository port + domain tests                                              | MUST     | Done   |
| US-13-002  | `RateLimitConfigRepository` JPA adapter + mapper + Postgres integration test                                                   | MUST     | Done   |
| US-13-003  | `GetRateLimitConfigUseCase` + `UpdateRateLimitConfigUseCase` + change-listener seam + tests                                     | MUST     | Done   |
| US-13-004  | `RateLimitGate` port + `Bucket4jRateLimitGate` adapter (two stacked buckets, live rebuild) + `RateLimitedException`             | MUST     | Done   |
| US-13-005  | `RateLimitFilter` + Spring Security wiring + 429 mapping with `Retry-After`                                                     | MUST     | Done   |
| US-13-006  | Admin REST endpoints — `GET /admin/rate-limit`, `PUT /admin/rate-limit` (ADMIN-only)                                            | MUST     | Done   |
| US-13-007  | End-to-end integration test — eviction, 429 envelope, live admin update, actuator excluded                                      | MUST     | Done   |

EPIC-13 is **Done** when all seven stories above are `Done`. The
platform is then throttled end-to-end and operators have a live
control over the global limits. The next step is EPIC-14
(cross-cutting API concerns) — the rate-limit feature already consumes
EPIC-14's incremental contributions (`GlobalExceptionHandler`,
`ProblemDetails`), so the two EPICs interleave naturally rather than
stacking.
