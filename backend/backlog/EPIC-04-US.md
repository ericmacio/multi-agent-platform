# EPIC-04-US.md — User stories for EPIC-04

EPIC-04 — **Authentication: API keys (machine-to-machine)**

This file lists the user stories that deliver EPIC-04. The EPIC delivers admin-managed,
machine-to-machine credentials and the request-time authentication path for callers using
`X-Client-Id` + `X-Api-Key`. API-key callers run under a virtual `SystemPrincipal` with
full chat capabilities and no visibility on admin endpoints or end-user-owned resources.

> **Scope split with EPIC-03 / EPIC-05 / EPIC-10 / EPIC-14.**
> - End-user JWT authentication, the `Principal` sealed type (only `UserPrincipal` shipped),
>   the `GlobalExceptionHandler` for `VALIDATION_ERROR` / `INVALID_CREDENTIALS` /
>   `MUST_CHANGE_PASSWORD` / `FORBIDDEN` / `NOT_FOUND` / `METHOD_NOT_ALLOWED` /
>   `INTERNAL_ERROR`, and the `ForcedPasswordChangeFilter` are delivered by EPIC-03.
>   This EPIC reuses them as-is (EPIC-03's filter already handles non-`UserPrincipal`
>   principals by passing the request through unchanged).
> - The `ApiKeyJpa` entity and the empty `ApiKeyJpaRepository` (extending
>   `JpaRepository<ApiKeyJpa, String>`) are delivered by EPIC-02 (US-02-005 / US-02-006).
>   This EPIC plugs a domain repository adapter into that existing infrastructure and
>   accrues the few finders the use cases need.
> - Admin user-management endpoints (`/admin/users/*`) are delivered by EPIC-05. The
>   `hasRole("ADMIN")` URL guard on `/admin/**` that this EPIC introduces will be reused
>   by EPIC-05 and EPIC-13 without modification.
> - The minimal cursor-pagination helper shipped here (`CursorCodec` + `PageDto<T>`) is
>   the first slice of EPIC-14; subsequent feature EPICs reuse it. The full taxonomy is
>   consolidated by EPIC-14.
> - The `SystemPrincipal` shipped here is consumed by EPIC-10/EPIC-11 for owner-scoped
>   conversation access; this EPIC introduces it but does not connect it to any chat use
>   case yet.

## Conventions

- **ID format**: `US-04-<nnn>` — `04` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                  | Priority | Status | Depends on                          |
|------------|------------------------------------------------------------------------|----------|--------|-------------------------------------|
| US-04-001  | `SystemPrincipal` completes the `Principal` sealed hierarchy           | MUST     | Done   | US-03-002                           |
| US-04-002  | `ApiKey` domain aggregate & repository port (`ClientId` shipped in US-04-001) | MUST | Done   | US-04-001, EPIC-02                |
| US-04-003  | `ApiKeyRepository` JPA adapter + domain ↔ JPA mapper                   | MUST     | Done   | US-04-002, EPIC-02                  |
| US-04-004  | `ApiKeyGenerator` + `ApiKeyHasher` ports & adapters                    | MUST     | Done   | EPIC-01                             |
| US-04-005  | Minimal cursor-pagination plumbing (`CursorCodec` + `PageDto<T>`)      | MUST     | Done   | EPIC-01                             |
| US-04-006  | Create-API-key use case & `POST /admin/api-keys`                       | MUST     | Done   | US-04-002, 003, 004                 |
| US-04-007  | List-API-keys use case & `GET /admin/api-keys`                         | MUST     | Done   | US-04-002, 003, 005                 |
| US-04-008  | Disable/re-enable-API-key use case & `PATCH /admin/api-keys/{clientId}`| MUST     | Done   | US-04-002, 003                      |
| US-04-009  | `ApiKeyAuthenticationFilter` & Spring Security wiring                  | MUST     | Done   | US-04-001, 002, 003, 004, US-03-007 |

---

## US-04-001 — `SystemPrincipal` completes the `Principal` sealed hierarchy

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `Principal` sealed interface (shipped by US-03-002 with only
`UserPrincipal`) to gain its second permitted variant `SystemPrincipal`
**So that** the API-key authentication filter (US-04-009) can populate the
`SecurityContext` with a typed, Spring-free principal, and downstream code can pattern
match on `Principal` to enforce the SYSTEM authorization rules of design §8.6.

### Description

EPIC-03 declared `Principal` as `sealed permits UserPrincipal` and noted that
`SystemPrincipal` would be added to the `permits` clause by EPIC-04. The implementation
is a tiny immutable record carrying the `ClientId` of the calling API key. The
`ForcedPasswordChangeFilter` (US-03-008) already documents that it lets
non-`UserPrincipal` requests through unchanged, so no change is required there.

> **Note on scope spillover.** `SystemPrincipal` requires `ClientId` at the type level,
> so the `ClientId` value object was shipped in this story as a strict prerequisite even
> though `EPICS.md` and the US-04-002 acceptance list originally placed it under
> US-04-002. US-04-002 now adds only the `ApiKey` aggregate, repository port, and
> `ApiKeyNotFoundException`.

### Acceptance criteria

- `domain/auth/SystemPrincipal.java` — record `SystemPrincipal(ClientId clientId)`
  implementing `Principal`. Non-null `clientId`; throws `NullPointerException` with a
  descriptive message on a null argument.
- `Principal.java` (from US-03-002) compiles cleanly with both permitted variants; the
  `sealed` modifier remains, and the file lists `permits UserPrincipal, SystemPrincipal`
  explicitly. No new public method is added to the `Principal` interface.
- `domain/auth/ClientId.java` — record `ClientId(String value)` shipped early as a
  prerequisite (full justification in the scope-spillover note above). Non-null,
  non-blank, `value.length() <= 64`, character set restricted to `[A-Za-z0-9_-]+`.
  Violations throw `ValidationException` with field `clientId`. A `ClientIdTest`
  covers happy path, null, blank, over-length, internal whitespace, and reserved
  separators (`/`, `:`).
- A pure-Java unit test `SystemPrincipalTest`:
  - Constructs a `SystemPrincipal` from a valid `ClientId` and asserts `clientId()` round-trips.
  - Asserts the null-argument case throws `NullPointerException` with message `"clientId"`.
  - Asserts membership in the `Principal` sealed type.
  - Walks an `instanceof` pattern-matching chain over both permitted variants. Pattern
    matching for `switch` is preview-only in Java 17, so static exhaustiveness via a
    switch expression is not available; the chain ends with an `UnsupportedOperationException`
    fallback that fails the test if a third variant is ever added to `permits`.
- ArchUnit (US-01-008) still passes: `domain/auth/**` carries no Spring / JPA / Jackson
  imports.

### Requirements coverage

`REQ-AUTH-001`, `REQ-AUTH-007`, `REQ-ARC-002`, `REQ-ARC-003`.

### Design references

§3 project structure (`domain/auth/`), §8.4 API-key authentication, §8.6 authorization
rules.

### Dependencies

US-03-002 (`Principal` sealed interface, `UserPrincipal`).

---

## US-04-002 — `ApiKey` domain aggregate & repository port

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `ApiKey` aggregate, the `ApiKeyRepository` port, and the API-key domain
exceptions
**So that** the admin API-key use cases (US-04-006 / US-04-007 / US-04-008) and the
authentication filter (US-04-009) operate on a Spring-free, fully-validated domain model.

### Description

Place the `ApiKey` aggregate, the `ApiKeyRepository` interface, and the lookup helper
exception under `domain/auth/` (the bounded context that already owns `Principal`,
`ClientId`, and `InvalidCredentialsException` per EPIC-03 / US-04-001). The port stays
Spring-free; the adapter is delivered by US-04-003.

> **Note 1.** `ClientId` was already shipped in US-04-001 as a prerequisite for
> `SystemPrincipal`. This story consumes it; do not re-create it.
>
> **Note 2.** The `Cursor` and `Page<T>` records were shipped here as
> `domain/shared/{Cursor,Page}.java` rather than under `application/shared` as the
> US-04-005 acceptance list originally placed them. Reason: the domain repository port
> `ApiKeyRepository.listAll(Cursor, int)` returns a `Page<ApiKey>`, and the hexagonal
> layering rule (US-01-008 `LayeringArchTest`) forbids `domain/**` from depending on
> `application/**`. The HTTP-side plumbing (`CursorCodec`, `PageDto`, `PageSize`) remains
> in US-04-005's scope.

### Acceptance criteria

- `domain/auth/ApiKey.java` — aggregate carrying:
  - `clientId` (`ClientId`),
  - `apiKeyHash` (raw String — BCrypt hash, never the cleartext),
  - `label` (nullable `String`, max **128** chars; trims `null` / blank to `null`),
  - `disabled` (`boolean`),
  - `createdAt` (`OffsetDateTime`).
  - Domain methods:
    - `boolean isActive()` → `!disabled`.
    - `ApiKey withDisabled(boolean disabled)` → returns a copy with the new flag.
- `domain/auth/ApiKeyRepository.java` — interface with:
  - `Optional<ApiKey> findByClientId(ClientId clientId);`
  - `ApiKey save(ApiKey apiKey);`
  - `Page<ApiKey> listAll(Cursor cursor, int pageSize);` — uses the `Page` /
    `Cursor` types defined in US-04-005; ordering is `(created_at DESC, client_id DESC)`
    so newest API keys come first.
  - `void updateDisabled(ClientId clientId, boolean disabled);` — partial update used by
    US-04-008 to avoid round-tripping the hash through the domain.
- `domain/auth/ApiKeyNotFoundException.java` extends `NotFoundException` (the base class
  shipped in EPIC-01 / EPIC-03); its message contains only the `client_id` value
  (which is safe to surface — it is the public identifier, not the secret).
- The aggregate is **never serialized** to JSON directly; the controller layer always
  maps to a DTO in US-04-006 / US-04-007 / US-04-008. The `apiKeyHash` field is
  therefore never at risk of leaking.
- Pure-Java unit tests:
  - `ApiKeyTest` — `withDisabled(true)` returns a copy with `disabled=true` and preserves
    `clientId`, `apiKeyHash`, `label`, `createdAt`; round-trip with `false` is symmetric.
  - `ApiKeyTest` — `isActive()` returns the inverse of `disabled`.
  - (`ClientIdTest` already exists from US-04-001; no need to re-create.)
- ArchUnit (US-01-008) still passes: `domain/auth/**` has no Spring / JPA / Jackson
  imports.

### Requirements coverage

`REQ-AUTH-007`, `REQ-AUTH-012`, `REQ-SEC-002`, `REQ-SEC-003`, `REQ-ARC-002`,
`REQ-ARC-003`.

### Design references

§3 project structure (`domain/auth/`), §4.1 ApiKey entity, §5 database schema
(`api_keys` table), §6.2.3 admin API-key endpoints.

### Dependencies

US-04-001 (`ClientId`), EPIC-01 (domain exception base classes, `NotFoundException`),
EPIC-02 (DB schema for `api_keys` already in place).

---

## US-04-003 — `ApiKeyRepository` JPA adapter + domain ↔ JPA mapper

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the infrastructure adapter that implements `ApiKeyRepository` against the
`ApiKeyJpa` entity and `ApiKeyJpaRepository` shipped by EPIC-02
**So that** the admin API-key endpoints and the authentication filter can read and write
API-key records without knowing about Spring Data JPA.

### Description

Wire the domain repository port to the existing Spring Data JPA infrastructure. Per the
EPIC-02 scope-split note (US-02-006), the per-aggregate finder methods accrue here. This
story adds the finders that EPIC-04 actually consumes and ships the cursor-paged keyset
query used by `GET /admin/api-keys`.

### Acceptance criteria

- `infrastructure/persistence/mapper/ApiKeyMapper.java` — pure Java class (or final class
  with static methods) translating between `domain.auth.ApiKey` and
  `infrastructure.persistence.entity.ApiKeyJpa`. No Spring annotations. The mapper
  preserves all `ApiKey` fields round-trip: `clientId`, `apiKeyHash`, `label`,
  `disabled`, `createdAt`.
- `infrastructure/persistence/adapter/ApiKeyRepositoryAdapter.java` — `@Component`
  implementing `domain.auth.ApiKeyRepository`, constructor-injected with
  `ApiKeyJpaRepository` (EPIC-02) and `ApiKeyMapper`. Methods:
  - `findByClientId(ClientId)` → uses `ApiKeyJpaRepository.findById(clientId.value())`.
  - `save(ApiKey)` → maps to `ApiKeyJpa`, persists, returns the mapped result.
  - `listAll(Cursor, int)` → uses a `@Query` keyset query on `(created_at DESC,
    client_id DESC)`; the cursor decodes to the last seen `(createdAt, clientId)` pair
    (see US-04-005). Page size is bounded `[1, 100]` per design §6.1.
  - `updateDisabled(ClientId, boolean)` → uses a `@Modifying` `@Query`
    (`update ApiKeyJpa a set a.disabled = :disabled where a.clientId = :clientId`),
    returns void, and is wrapped in `@Transactional` at the adapter method level.
- `ApiKeyJpaRepository` is extended with:
  - `@Query` finder for the keyset page (the query may be defined on the adapter side
    via `JpaRepository` query methods or `@Query`; either is acceptable).
  - The `@Modifying` update method above.
  No speculative finders are added.
- Integration test `ApiKeyRepositoryAdapterIntegrationTest` (extends
  `PostgresIntegrationTest` from US-02-002):
  - Persists three `ApiKey` records with distinct `createdAt` and asserts
    `listAll(null, 2)` returns the two newest in DESC order with a non-null `nextCursor`.
  - Calling `listAll(nextCursor, 2)` returns the remaining one and a null `nextCursor`.
  - `updateDisabled` toggles the flag in DB; a subsequent `findByClientId` reflects it.
  - `save` round-trips a fresh `ApiKey`; retrieving it asserts equality on every field.
  - `findByClientId` for an unknown id returns `Optional.empty()`.
- ArchUnit (US-01-008) still passes: the adapter sits in `infrastructure/**`,
  `ApiKeyRepository` stays in `domain/**`.

### Requirements coverage

`REQ-AUTH-007`, `REQ-AUTH-012`, `REQ-PRS-001`, `REQ-PRS-003`, `REQ-PRS-005`,
`REQ-API-005`.

### Design references

§3 project structure (`infrastructure/persistence/{mapper,adapter}/`), §5 database
schema (`api_keys` table), §6.1 conventions (cursor pagination).

### Dependencies

US-04-002 (`ApiKey`, `ClientId`, port), EPIC-02 (`ApiKeyJpa`, `ApiKeyJpaRepository`).

---

## US-04-004 — `ApiKeyGenerator` + `ApiKeyHasher` ports & adapters

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an application-layer port that produces a fresh API-key pair
(`clientId` + cleartext secret) at creation time, and a sibling port that BCrypt-hashes
and BCrypt-verifies the cleartext secret
**So that** the create-API-key use case and the authentication filter remain free of
direct dependencies on `java.security.SecureRandom` and on Spring Security's
`BCryptPasswordEncoder`.

### Description

The platform password hasher delivered in US-03-004 operates on the `Password` value
object and is gated by the platform password policy (≥10 chars, ≥1 uppercase,
≥1 special). An API key is a random opaque secret, not a user-chosen password, so it
must NOT go through that policy. This story introduces a distinct port pair targeting
raw strings, with a thin adapter that reuses Spring Security's BCrypt encoder under the
hood. The generator port produces both the public `ClientId` and the cleartext secret in
a single call so the controller can return them once and never see them again.

### Acceptance criteria

- `application/auth/ApiKeyGenerator.java` — interface:
  ```java
  GeneratedApiKey generate();
  record GeneratedApiKey(ClientId clientId, String cleartextApiKey) {}
  ```
- `infrastructure/security/SecureRandomApiKeyGeneratorAdapter.java` — `@Component`
  implementing the port. Behavior:
  - `clientId`: a fresh `UUID.randomUUID()` rendered without dashes
    (`UUID.toString().replace("-", "")` → 32 hex chars).
  - `cleartextApiKey`: 32 random bytes drawn from a single, lazily initialized
    `SecureRandom` instance, base64url-encoded **without padding** (≈ 43 chars). The
    cleartext fits comfortably in the `varchar(72)` BCrypt hash column once hashed.
  - The `SecureRandom` instance is a private field initialized in the no-arg constructor;
    a constructor that accepts a `SecureRandom` is provided for tests.
- `application/auth/ApiKeyHasher.java` — interface:
  ```java
  String hash(String cleartextApiKey);
  boolean matches(String cleartextApiKey, String storedHash);
  ```
- `infrastructure/security/BcryptApiKeyHasherAdapter.java` — `@Component` implementing
  the port using a private `BCryptPasswordEncoder` field with cost factor 10 (constructor
  `new BCryptPasswordEncoder()`). `matches` returns `false` (never throws) when the
  stored hash is a malformed string.
- The adapters never log the cleartext API key, the hash, or the `SecureRandom` seed.
- Unit test `SecureRandomApiKeyGeneratorAdapterTest`:
  - Two successive `generate()` calls produce distinct `clientId` and distinct cleartext
    values.
  - The `clientId` matches `^[a-f0-9]{32}$`.
  - The cleartext matches `^[A-Za-z0-9_-]+$` and is at least 43 characters.
  - Injecting a fixed-seed `SecureRandom` makes the cleartext deterministic, as a sanity
    check on the test seam. Implementation note: the test uses
    `SecureRandom.getInstance("SHA1PRNG")` and seeds it before the first `nextBytes`
    call — the default platform `SecureRandom` documents `setSeed(long)` as
    *supplementing* rather than *replacing* the seed, so two default instances seeded
    with the same value still emit different bytes.
- Unit test `BcryptApiKeyHasherAdapterTest`:
  - `hash` produces a string matching `^\$2[aby]\$10\$.{53}$`.
  - `matches` returns `true` for the original cleartext and `false` for any altered
    variant.
  - `matches` returns `false` (never throws) when the stored hash is a malformed string.
- ArchUnit (US-01-008) still passes: both ports sit in `application/**`, both adapters
  in `infrastructure/**`.

### Requirements coverage

`REQ-AUTH-007`, `REQ-SEC-002`, `REQ-SEC-003`, `REQ-SEC-004`, `REQ-ARC-005`.

### Design references

§3 project structure (`application/auth/`, `infrastructure/security/`), §8.4 API-key
authentication (BCrypt comparison), §8.5 password handling (cost factor 10).

### Dependencies

EPIC-01 (Spring Boot context), US-04-002 (`ClientId`).

---

## US-04-005 — Minimal cursor-pagination plumbing (`CursorCodec` + `PageDto<T>`)

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a tiny, reusable cursor codec and a generic `PageDto<T>` envelope record
**So that** `GET /admin/api-keys` (and every subsequent list endpoint) can implement
opaque cursor pagination consistently, matching the `PageEnvelope` schema in
`openapi.yaml`.

### Description

This is the first feature EPIC to ship a list endpoint, so it must also ship the minimum
cross-cutting slice of EPIC-14 that list endpoints require. The codec is intentionally
narrow: it encodes a small `Cursor` value object as base64url JSON. The full taxonomy of
EPIC-14 (every error code, the `BusinessException` hierarchy completion, CORS) is
delivered later.

> **Note.** `Cursor` and `Page<T>` already shipped in US-04-002 under
> `domain/shared/{Cursor,Page}.java` (not `application/shared/` as listed below) — the
> domain repository port `ApiKeyRepository.listAll` returns a `Page<ApiKey>`, so they
> must live in `domain/**` to satisfy the hexagonal layering rule. This story now adds
> only the HTTP-layer plumbing on top.

### Acceptance criteria

- (`Cursor` and `Page` already exist under `domain/shared/`; do not re-create them.)
- `infrastructure/web/pagination/CursorCodec.java` — `@Component`:
  - `String encode(Cursor cursor)` — serializes the cursor as compact JSON
    (`{"t":"...","i":"..."}` with ISO-8601 UTC for `t`) then base64url-encodes the bytes
    without padding.
  - `Cursor decode(String encoded)` — strict decoding: any IO / parse / format failure
    throws `ValidationException` with field `cursor` and message
    `"invalid cursor"`. Empty / null encoded strings return `null` (caller asks for the
    first page).
  - The codec uses the Spring-managed Jackson `ObjectMapper`, configured with
    `JavaTimeModule` (already on the classpath via Spring Boot starters).
- `infrastructure/web/pagination/PageDto.java` — record `PageDto<T>(List<T> items,
  String nextCursor, int pageSize)` matching the `PageEnvelope` schema in `openapi.yaml`
  (items, nextCursor, pageSize). A static helper `PageDto.of(Page<T> page, CursorCodec
  codec, Function<T, ?> itemMapper)` maps a domain `Page` to its DTO form by applying
  `itemMapper` to each item and encoding the `nextCursor`.
- `application/shared/PageSize.java` — small helper record `PageSize(int value)` with the
  constraints `1 ≤ value ≤ 100` per design §6.1; out-of-range values throw
  `ValidationException` with field `pageSize`. Static factory
  `PageSize.fromQueryParam(Integer requested)` returns the default (`20`) when
  `requested` is null, otherwise constructs and validates.
- Unit tests:
  - `CursorCodecTest` — round-trips a cursor through `encode`/`decode`; rejects garbage
    input with `ValidationException`; treats null/blank as the first page.
  - `PageSizeTest` — default for null, accepts boundary values 1 and 100, rejects 0 and
    101.
  - `PageDtoTest` — `PageDto.of(...)` produces a DTO whose `items` are mapped, whose
    `pageSize` matches the source `Page`, and whose `nextCursor` is the codec-encoded
    cursor (or `null` on the last page).
- ArchUnit (US-01-008) still passes: `Cursor`, `Page`, `PageSize` live in
  `application/shared/**`; the codec and `PageDto` live in `infrastructure/web/**`.

### Requirements coverage

`REQ-API-004`, `REQ-API-005`, `REQ-ARC-007`.

### Design references

§6.1 conventions (cursor pagination, page size 20 / max 100), §10 pagination strategy.

### Dependencies

EPIC-01 (Spring Boot Jackson auto-configuration).

---

## US-04-006 — Create-API-key use case & `POST /admin/api-keys`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to create a new machine-to-machine API key, optionally labeled, and receive
its cleartext value **exactly once** in the response
**So that** an external client can authenticate to the chat surface using
`X-Client-Id` + `X-Api-Key`, and so that the secret value never lingers anywhere I can
later retrieve it on the server.

### Description

The endpoint is ADMIN-only (the `/admin/**` URL guard ships in US-04-009). The use case
delegates secret generation to `ApiKeyGenerator` (US-04-004), BCrypt-hashes the
cleartext through `ApiKeyHasher` (US-04-004), persists the metadata + hash via
`ApiKeyRepository` (US-04-003), and returns the cleartext in the response body. Once
the response is sent, the cleartext is unrecoverable by design.

### Acceptance criteria

- `application/auth/CreateApiKeyUseCase.java` — interface
  `CreateApiKeyResult create(CreateApiKeyCommand command);`
  - `CreateApiKeyCommand(String label)` — `label` is nullable; blank values are
    normalized to `null` by the controller before constructing the command.
  - `CreateApiKeyResult(ClientId clientId, String cleartextApiKey, String label,
    boolean disabled, OffsetDateTime createdAt)`.
- `application/auth/CreateApiKeyService.java` — `@Service`, `@Transactional` on the
  public method, constructor-injected with `ApiKeyGenerator`, `ApiKeyHasher`,
  `ApiKeyRepository`, `Clock`. Behavior:
  - Reject `label` longer than 128 chars with `ValidationException` (field `label`).
  - Call `apiKeyGenerator.generate()` → `(clientId, cleartextApiKey)`.
  - Compute `apiKeyHash = apiKeyHasher.hash(cleartextApiKey)`.
  - Build `new ApiKey(clientId, apiKeyHash, label, false, clock.instant()
    .atOffset(ZoneOffset.UTC))` and persist via `repository.save`.
  - Return the `CreateApiKeyResult` carrying the cleartext.
  - Never log the cleartext, the hash, or the command.
- `infrastructure/web/admin/ApiKeysAdminController.java` — `@RestController` (no
  class-level `@RequestMapping` per design §3.1), constructor-injected with the use
  case. `@PreAuthorize("hasRole('ADMIN')")` on the class (defense in depth above the URL
  rule from US-04-009).
  - `@PostMapping("/admin/api-keys")`.
  - Request DTO: `CreateApiKeyRequest(@Size(max = 128) String label)`.
  - Response DTO: `ApiKeyCreatedResponse(String clientId, String apiKey, String label,
    boolean disabled, OffsetDateTime createdAt)` matching `ApiKeyCreated` in
    `openapi.yaml`. `apiKey` carries the cleartext.
  - Returns `201 Created`.
- The Jackson `ObjectMapper` is configured to omit the cleartext from any subsequent
  serialization path; the response DTO is the only place it ever surfaces, and that DTO
  is never persisted nor logged.
- Integration test `CreateApiKeyEndpointIntegrationTest` (MockMvc + Spring context +
  Postgres):
  - Authenticated as the seeded admin (after clearing `mustChangePassword` via a DB
    update in the fixture), `POST /admin/api-keys` with `{"label":"ci"}` → 201, the
    body contains a 32-hex `clientId`, a base64url-shaped `apiKey`, the label `"ci"`,
    `disabled=false`, and a recent `createdAt`.
  - Persisted row: `api_key_hash` matches `^\$2[aby]\$10\$.{53}$`; the row's
    `api_key_hash` is **not** equal to the cleartext.
  - The cleartext returned in the body, when later run through
    `BcryptApiKeyHasherAdapter.matches`, returns `true` against the persisted hash.
  - `label` longer than 128 chars → 400 `VALIDATION_ERROR` with `errors[].field ==
    "label"`.
  - Missing `label` (omitted or null) → 201 with `label=null` in the response and DB.
  - Non-admin (STANDARD JWT) → 403 `FORBIDDEN` (the URL guard from US-04-009 is asserted
    here as the integration cross-check).
  - Unauthenticated → 401 `INVALID_CREDENTIALS`.
  - The application log file (captured in the test) contains neither the cleartext
    `apiKey` nor the BCrypt hash (verified by a substring assertion against the captured
    log output).

### Requirements coverage

`REQ-AUTH-007`, `REQ-AUTH-009`, `REQ-AUTH-012`, `REQ-SEC-002`, `REQ-SEC-003`,
`REQ-SEC-004`, `REQ-USR-003`.

### Design references

§6.2.3 admin API-key endpoints (`POST /admin/api-keys`), §8.5 password handling,
§8.6 authorization rules.

### Dependencies

US-04-002 (`ApiKey`, repository port), US-04-003 (JPA adapter), US-04-004 (generator
and hasher ports).

---

## US-04-007 — List-API-keys use case & `GET /admin/api-keys`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to page through the list of registered API keys with metadata only
(no cleartext, no hash)
**So that** I can audit which clients exist, see which are disabled, and identify the
right one to revoke when needed.

### Description

The endpoint is ADMIN-only (URL guard ships in US-04-009). The use case delegates to
`ApiKeyRepository.listAll(cursor, pageSize)` (US-04-003) and the controller maps the
domain `Page<ApiKey>` to the `ApiKeyPage` response shape in `openapi.yaml`. The
cleartext API key is never returned outside creation — the response DTO does not even
have a slot for it.

### Acceptance criteria

- `application/auth/ListApiKeysUseCase.java` — interface
  `Page<ApiKey> list(ListApiKeysQuery query);`
  - `ListApiKeysQuery(String encodedCursor, PageSize pageSize)`.
- `application/auth/ListApiKeysService.java` — `@Service`,
  `@Transactional(readOnly = true)`, constructor-injected with `ApiKeyRepository`,
  `CursorCodec`. Decodes the `encodedCursor` via the codec (a `ValidationException` from
  the codec bubbles up to the GlobalExceptionHandler → 400), forwards to the repository,
  returns the `Page<ApiKey>`.
- `ApiKeysAdminController.list`:
  - `@GetMapping("/admin/api-keys")`.
  - Query parameters: `cursor` (optional string), `pageSize` (optional int).
  - Builds `ListApiKeysQuery(cursor, PageSize.fromQueryParam(pageSize))` and calls the
    use case.
  - Maps `Page<ApiKey>` to `PageDto<ApiKeyResponse>` via `PageDto.of(...)`. The
    `ApiKeyResponse(String clientId, String label, boolean disabled, OffsetDateTime
    createdAt)` DTO is the **only** API-key response shape that omits the cleartext and
    the hash, exactly matching the `ApiKey` schema in `openapi.yaml`.
  - Returns `200 OK`.
- The DTO mapper for `ApiKey → ApiKeyResponse` lives next to the controller (e.g.
  `infrastructure/web/admin/ApiKeyResponseMapper.java`); a unit test asserts the mapper
  never reads the `apiKeyHash` field — it does not even appear in the mapping
  expression.
- Integration test `ListApiKeysEndpointIntegrationTest` (MockMvc + Postgres):
  - Pre-populate 3 API keys with descending `createdAt`. `GET /admin/api-keys?pageSize=2`
    → 200, `items.size()=2`, items are in DESC `createdAt` order, `nextCursor` is
    non-null, `pageSize=2`.
  - Follow `nextCursor` → 200, `items.size()=1`, `nextCursor=null`.
  - `pageSize=0` → 400 `VALIDATION_ERROR` (field `pageSize`).
  - `pageSize=101` → 400 `VALIDATION_ERROR` (field `pageSize`).
  - `cursor=not-a-valid-base64` → 400 `VALIDATION_ERROR` (field `cursor`).
  - Response body, when parsed back to JSON, contains exactly the keys
    `{items, nextCursor, pageSize}` at the top level and each item contains exactly
    `{clientId, label, disabled, createdAt}` — **no** `apiKey` field, **no**
    `apiKeyHash` field.
  - Non-admin → 403 `FORBIDDEN`. Unauthenticated → 401 `INVALID_CREDENTIALS`.

### Requirements coverage

`REQ-AUTH-007`, `REQ-AUTH-012`, `REQ-API-004`, `REQ-API-005`, `REQ-SEC-004`.

### Design references

§6.2.3 admin API-key endpoints (`GET /admin/api-keys`), §6.1 conventions, §10
pagination.

### Dependencies

US-04-002 (`ApiKey`, repository port), US-04-003 (JPA adapter with keyset query),
US-04-005 (`CursorCodec`, `PageDto`).

---

## US-04-008 — Disable/re-enable-API-key use case & `PATCH /admin/api-keys/{clientId}`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to soft-revoke (or re-enable) an existing API key by toggling its `disabled`
flag
**So that** a compromised client can be locked out without losing the audit trail of its
existence, and so a temporarily disabled client can be reactivated without a fresh
secret rotation.

### Description

The endpoint is ADMIN-only (URL guard ships in US-04-009). The use case validates the
`clientId`, ensures the row exists (404 otherwise), and writes the new `disabled` flag
via the partial-update path on the repository (US-04-003). A subsequent
authentication attempt with a disabled key surfaces the generic
`INVALID_CREDENTIALS` 401 (US-04-009).

### Acceptance criteria

- `application/auth/UpdateApiKeyUseCase.java` — interface
  `ApiKey updateDisabled(UpdateApiKeyCommand command);`
  - `UpdateApiKeyCommand(ClientId clientId, boolean disabled)`.
- `application/auth/UpdateApiKeyService.java` — `@Service`, `@Transactional`,
  constructor-injected with `ApiKeyRepository`. Behavior:
  - Look up `findByClientId(clientId)` → `ApiKeyNotFoundException` if absent.
  - Call `repository.updateDisabled(clientId, disabled)`.
  - Re-fetch and return the updated `ApiKey` (so the controller can serialize the full
    metadata) — alternatively, returning `existing.withDisabled(disabled)` is acceptable
    and avoids the second round-trip.
- `ApiKeysAdminController.updateDisabled`:
  - `@PatchMapping("/admin/api-keys/{clientId}")`.
  - Path variable: `clientId` (validated through the `ClientId` constructor; bad values
    surface as 400 `VALIDATION_ERROR`).
  - Request DTO: `UpdateApiKeyRequest(@NotNull Boolean disabled)`.
  - Response DTO: `ApiKeyResponse` (same as US-04-007 — no cleartext, no hash).
  - Returns `200 OK`.
- Integration test `UpdateApiKeyEndpointIntegrationTest` (MockMvc + Postgres):
  - Pre-populate one API key with `disabled=false`. `PATCH /admin/api-keys/{clientId}`
    with `{"disabled":true}` → 200; the row in DB now has `disabled=true`.
  - A subsequent call to `GET /admin/api-keys` shows the same key with `disabled=true`.
  - Patching back to `{"disabled":false}` → 200; DB reflects the toggle.
  - Idempotent: re-issuing the same PATCH with the same flag value → 200, no DB write
    visible in the modification timestamp (asserted via the persisted `created_at`
    being unchanged — there is no `updated_at` on `api_keys` per design §5).
  - Unknown `clientId` → 404 `NOT_FOUND` with body code `NOT_FOUND`.
  - Missing `disabled` field in request body → 400 `VALIDATION_ERROR`.
  - Non-admin → 403 `FORBIDDEN`. Unauthenticated → 401 `INVALID_CREDENTIALS`.

### Requirements coverage

`REQ-AUTH-007`, `REQ-AUTH-009`, `REQ-AUTH-012`, `REQ-SEC-004`.

### Design references

§6.2.3 admin API-key endpoints (`PATCH /admin/api-keys/{clientId}`), §8.6 authorization
rules.

### Dependencies

US-04-002 (`ApiKey`, `ApiKeyNotFoundException`, repository port), US-04-003 (JPA adapter
partial-update path).

---

## US-04-009 — `ApiKeyAuthenticationFilter` & Spring Security wiring

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an `ApiKeyAuthenticationFilter` that authenticates `X-Client-Id` +
`X-Api-Key` requests and a Spring Security configuration that wires it after the JWT
filter, plus an updated URL ruleset that locks `/admin/**` to `ROLE_ADMIN`
**So that** machine-to-machine callers can reach the chat surface under a typed
`SystemPrincipal`, and so that neither STANDARD users nor SYSTEM principals can ever
reach `/admin/**`.

### Description

The filter runs once per request, **after** `JwtAuthenticationFilter`, and short-circuits
when the `SecurityContext` already carries an `Authentication` (JWT wins per design
§8.1). When both API-key headers are present, the filter looks up the `ApiKey` by
`clientId`, verifies it is enabled, BCrypt-compares the submitted cleartext, and on
success sets the `Authentication` to a `UsernamePasswordAuthenticationToken` carrying a
`SystemPrincipal` and the authority `ROLE_SYSTEM`. Any failure path raises
`InvalidCredentialsException` — the existing `GlobalExceptionHandler` from US-03-001
maps it to 401 `INVALID_CREDENTIALS`. The URL ruleset update locks `/admin/**` to
`ROLE_ADMIN` so STANDARD JWTs and SYSTEM API-keys both get 403 there, matching design
§8.6.

### Acceptance criteria

- `infrastructure/web/security/ApiKeyAuthenticationFilter.java` — extends
  `OncePerRequestFilter`, constructor-injected with `ApiKeyRepository` and
  `ApiKeyHasher`. Behavior:
  - If the `SecurityContext` already has a non-anonymous `Authentication`, continue the
    chain unchanged.
  - Read `X-Client-Id` and `X-Api-Key` headers. If either is missing, continue the chain
    unauthenticated (the URL rules decide whether 401 is appropriate).
  - If both are present:
    1. Construct `ClientId` from the header value — a `ValidationException` on bad shape
       is translated to `InvalidCredentialsException` (no leak about whether the format
       was the problem).
    2. `findByClientId(clientId)` → `InvalidCredentialsException` if absent.
    3. `apiKey.isActive()` → `InvalidCredentialsException` if `disabled=true`
       (`REQ-AUTH-012`).
    4. `apiKeyHasher.matches(submittedSecret, apiKey.apiKeyHash())` →
       `InvalidCredentialsException` if false.
    5. On success, build `SystemPrincipal(apiKey.clientId())` and set
       `UsernamePasswordAuthenticationToken(systemPrincipal, null,
        List.of(new SimpleGrantedAuthority("ROLE_SYSTEM")))` on the `SecurityContext`.
  - The filter never writes the response body; failures bubble up as exceptions so the
    central error handler shapes the response.
  - The filter logs only at DEBUG, and never the raw header value or the BCrypt hash
    (`REQ-SEC-004`).
- `SpringSecurityConfig.java` (from US-03-007) is updated:
  - `addFilterAfter(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class)` so the
    filter runs in position 3 per design §8.1.
  - `authorizeHttpRequests` gains the rule
    `requestMatchers("/api/v1/admin/**").hasRole("ADMIN")` placed **before** the
    blanket `anyRequest().authenticated()`.
  - The existing `POST /auth/login` and `GET /actuator/health` permit-all rules are
    preserved.
- Integration test `ApiKeyAuthenticationFilterIntegrationTest` (MockMvc + Postgres):
  - Pre-populate one enabled API key with a known cleartext.
  - Request to the EPIC-03 probe endpoint (`/__test/me`) with valid `X-Client-Id` +
    `X-Api-Key` → 200; the response shows the principal type is `SystemPrincipal`
    and the `clientId` matches.
  - Same probe with a disabled API key (toggled via the repository) → 401
    `INVALID_CREDENTIALS`.
  - Same probe with wrong `X-Api-Key` → 401 `INVALID_CREDENTIALS`.
  - Same probe with unknown `X-Client-Id` → 401 `INVALID_CREDENTIALS`.
  - **JWT wins**: a request that carries **both** a valid `Authorization: Bearer ...`
    and an unrelated API-key pair is authenticated as the JWT user (not as the SYSTEM
    principal); asserted via the probe response.
  - Both headers absent → 401 (unauthenticated) on a protected endpoint.
- Integration test `AdminUrlGuardIntegrationTest` (MockMvc):
  - Authenticated as STANDARD JWT, `GET /admin/api-keys` → 403 `FORBIDDEN`.
  - Authenticated as SYSTEM via API key, `GET /admin/api-keys` → 403 `FORBIDDEN`.
  - Authenticated as ADMIN JWT (after clearing `mustChangePassword`), `GET
    /admin/api-keys` → 200.
- The error body for every 401 path above is byte-for-byte identical to the existing
  `INVALID_CREDENTIALS` body produced by EPIC-03 (asserted by JSON equality against
  a fixture), so attackers cannot distinguish JWT failures from API-key failures.
- ArchUnit (US-01-008) still passes: the filter lives in `infrastructure/web/security/**`,
  the port stays in `application/auth/**`, the domain types stay in `domain/auth/**`.

### Requirements coverage

`REQ-AUTH-001`, `REQ-AUTH-007`, `REQ-AUTH-008`, `REQ-AUTH-009`, `REQ-AUTH-012`,
`REQ-SEC-002`, `REQ-SEC-004`.

### Design references

§8.1 filter chain (position 3), §8.4 API-key authentication, §8.6 authorization rules,
§8.7 sensitive-data logging.

### Dependencies

US-04-001 (`SystemPrincipal`), US-04-002 (`ApiKey`, `ClientId`, repository port),
US-04-003 (JPA adapter), US-04-004 (`ApiKeyHasher`), US-03-007 (existing
`SpringSecurityConfig` and `JwtAuthenticationFilter`).

---

## EPIC-04 Definition of Done

EPIC-04 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every existing EPIC-01 / EPIC-02 / EPIC-03 / Code Review #1 test
  green; the EPIC-04 unit and integration tests run green against a local PostgreSQL.
- An admin can:
  1. create an API key via `POST /admin/api-keys` and receive the cleartext **once**;
  2. list API keys via `GET /admin/api-keys` and see only metadata (no cleartext, no
     hash) with cursor pagination working across page boundaries;
  3. disable that key via `PATCH /admin/api-keys/{clientId}` and confirm the change via
     `GET`.
- An external caller can:
  1. authenticate with `X-Client-Id` + `X-Api-Key` and reach a protected endpoint under a
     `SystemPrincipal`;
  2. **NOT** reach any `/admin/**` endpoint (403 `FORBIDDEN`);
  3. lose access immediately after the key is disabled, with a generic 401
     `INVALID_CREDENTIALS` response.
- Failed API-key authentication paths (unknown client_id, wrong api_key, disabled key,
  malformed headers) all return a body byte-for-byte identical to the EPIC-03 JWT
  `INVALID_CREDENTIALS` body.
- When both `Authorization: Bearer ...` and the API-key header pair are present on the
  same request, the JWT principal wins; the API-key filter short-circuits without
  consulting the database.
- `/admin/**` is restricted to `ROLE_ADMIN` for every existing and future admin
  endpoint; STANDARD JWTs and SYSTEM API-keys are both 403 there.
- No log line, on any appender, contains a cleartext API key, an `api_key_hash`, a JWT,
  a BCrypt password hash, or a cleartext password — verified by the existing
  `SensitiveDataMaskingConverter` test plus a smoke check on the integration-test log
  file (the EPIC-03 substring assertions are extended to cover the API-key cleartext
  and hash).
- The cleartext API key value is **only** present in the body of the `POST
  /admin/api-keys` 201 response and is unrecoverable from the server after that
  response is sent.
- ArchUnit (US-01-008) still passes: `domain/**` has no Spring / JPA / Jackson imports;
  `application/**` has no JPA / Spring Security imports; the API-key filter,
  Spring Security wiring, and admin REST DTOs live exclusively under
  `infrastructure/web/**`.
