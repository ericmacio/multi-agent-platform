# EPIC-05-US.md — User stories for EPIC-05

EPIC-05 — **User management (admin)**

This file lists the user stories that deliver EPIC-05. The EPIC adds the admin
user-management surface on top of the `User` aggregate shipped in EPIC-03: create, list,
fetch, enable/disable, and hard-delete user accounts. Hard-delete cascades through
owned agents and conversations (`REQ-USR-006`) and is verified by an end-to-end
integration test on top of the EPIC-02 cascade contract.

> **Scope split with EPIC-03 / EPIC-04 / EPIC-14.**
> - The `User` aggregate, `Email`, `Password`, `Role`, `UserId`, the `UserRepository`
>   port, and the JPA adapter were shipped by EPIC-03 (US-03-002 / US-03-003). This EPIC
>   extends them with the read/write paths admin operations need.
> - The `/admin/**` URL guard (`hasRole("ADMIN")`), `@EnableMethodSecurity`, the cursor
>   pagination plumbing (`Cursor`, `Page<T>`, `CursorCodec`, `PageDto<T>`, `PageSize`),
>   and the admin REST package convention were shipped by EPIC-04 (US-04-005 / -009).
>   This EPIC reuses them verbatim — no further filter or security-config changes.
> - The `GlobalExceptionHandler` covered 400 / 401 / 403 / 404 / 405 / 500 after
>   EPIC-03 / EPIC-04; this EPIC adds the 409 `CONFLICT` mapping. EPIC-14 consolidates
>   the full taxonomy.
> - The `domain/shared/ConflictException` base class already exists from earlier work;
>   this EPIC introduces only the user-specific subclasses
>   (`DuplicateEmailException`, `UserNotFoundException`).
> - The EPIC-02 cascade contract (US-02-007 `CascadeIntegrationTest`) already proves the
>   DB-level FK cascade from `users → agents → conversations`. This EPIC verifies the
>   same cascade fires through the REST DELETE path; it does not introduce new schema.

## Conventions

- **ID format**: `US-05-<nnn>` — `05` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                    | Priority | Status | Depends on                          |
|------------|--------------------------------------------------------------------------|----------|--------|-------------------------------------|
| US-05-001  | `User` aggregate `withDisabled` + domain exceptions + repository port    | MUST     | Done   | US-03-002                           |
| US-05-002  | `UserRepository` JPA adapter extensions (existsByEmail, listAll, delete) | MUST     | Done   | US-05-001, US-03-003                |
| US-05-003  | `ConflictException` handler in `GlobalExceptionHandler` (409 `CONFLICT`) | MUST     | Done   | US-03-001                           |
| US-05-004  | Create-user use case & `POST /admin/users`                               | MUST     | Done   | US-05-001, 002, 003, US-03-004      |
| US-05-005  | List-users use case & `GET /admin/users`                                 | MUST     | Done   | US-05-001, 002, US-04-005           |
| US-05-006  | Get-user use case & `GET /admin/users/{userId}`                          | MUST     | Done   | US-05-001, US-03-003                |
| US-05-007  | Enable/disable-user use case & `PATCH /admin/users/{userId}`             | MUST     | Done   | US-05-001, 002                      |
| US-05-008  | Delete-user use case & `DELETE /admin/users/{userId}`                    | MUST     | Done   | US-05-001, 002, US-02-007           |

---

## US-05-001 — `User` aggregate `withDisabled` + domain exceptions + repository port

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `User` aggregate to gain a `withDisabled(...)` mutation, the user
bounded context to gain the two domain exceptions admin operations need, and the
`UserRepository` port to gain the read/write surface that EPIC-05 will consume
**So that** every admin use case stays in the application layer and never reaches into
Spring Data or Hibernate directly.

### Description

Mirror the EPIC-04 pattern: the aggregate exposes copy-with-disabled semantics, the
domain exceptions sit alongside `MustChangePasswordException` under `domain/user/`, and
the port grows the finder / mutation surface needed by US-05-004 .. US-05-008.

### Acceptance criteria

- `User.withDisabled(boolean newDisabled, OffsetDateTime now)` — returns a copy with the
  new `disabled` flag and `updatedAt = now`. All other fields are preserved. Null
  `now` throws `NullPointerException` with the descriptive message `"now"`.
- `domain/user/UserNotFoundException.java` — extends the existing
  `domain.shared.NotFoundException`. Message format `"User not found: <userId>"` where
  the value is the UUID string of the missing user. The class is `final` (no further
  subclassing — single concrete leaf, like `ApiKeyNotFoundException`).
- `domain/user/DuplicateEmailException.java` — extends the existing
  `domain.shared.ConflictException`. Message format
  `"User with this email already exists: <email>"`. The class is `final`.
- `UserRepository` (from US-03-002) is extended with:
  - `boolean existsByEmail(Email email);` — used by `CreateUserService` for the
    pre-flight duplicate-email check (US-05-004).
  - `Page<User> listAll(Cursor cursor, int pageSize);` — keyset-paginated, ordered
    `(createdAt DESC, id DESC)`. Mirrors `ApiKeyRepository.listAll` exactly so the
    admin controller layer can reuse the same `PageDto.of(...)` shape from US-04-005.
  - `void delete(UserId id);` — hard-delete by id. Cascade through `agents` and
    `conversations` is provided by the V001 FK cascade and is verified at the DB level
    by the EPIC-02 `CascadeIntegrationTest`; the port contract here is just "the row
    is gone after `delete` returns".
- Pure-Java unit tests:
  - `UserWithDisabledTest` — `withDisabled(true, ...)` flips the flag and bumps
    `updatedAt`; the rest of the aggregate is unchanged; round-trip `false → true → false`
    is symmetric.
  - `UserNotFoundExceptionTest` — message contains the UUID; the class is reachable as
    a `NotFoundException` for the `instanceof` check that `GlobalExceptionHandler` does.
  - `DuplicateEmailExceptionTest` — message contains the canonicalized (lowercase)
    email; the class is reachable as a `ConflictException`.
- ArchUnit (US-01-008) still passes: `domain/user/**` and `domain/shared/**` carry no
  Spring / JPA / Jackson imports.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-002`, `REQ-USR-005`, `REQ-USR-006`, `REQ-ARC-002`, `REQ-ARC-003`,
`REQ-ARC-007`.

### Design references

§4.1 User entity, §4.2 invariants, §6.2.2 admin users, §9.1 exception hierarchy.

### Dependencies

US-03-002 (`User` aggregate, `Email`, `Role`, `UserId`, `UserRepository` port).

---

## US-05-002 — `UserRepository` JPA adapter extensions (existsByEmail, listAll, delete)

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the JPA adapter to implement the new repository methods introduced by
US-05-001 — `existsByEmail`, the cursor-paged `listAll`, and `delete`
**So that** the admin use cases can read and write users at the application layer
without seeing Spring Data, and so that the existing `findByEmail` / `findById` /
`save` paths remain untouched.

### Description

Mirror the EPIC-04 `ApiKeyRepositoryAdapter` keyset-pagination approach: fetch
`pageSize + 1` rows ordered `(createdAt DESC, id DESC)`, trim, and emit a non-null
`nextCursor` when more rows exist. Reuse the existing `UserJpaRepository` /
`UserRepositoryAdapter` files from US-03-003; this story extends them rather than
introducing new classes.

### Acceptance criteria

- `UserJpaRepository` gains:
  - `boolean existsByEmail(String email);` — Spring Data derived query. Callers always
    pass the canonicalized (lowercase) value from `Email#value()` per US-CR1-001, so
    the functional unique index on `lower(email)` (V004) keeps lookups O(1).
  - `@Query("SELECT u FROM UserJpa u ORDER BY u.createdAt DESC, u.id DESC")
     List<UserJpa> findFirstPage(Pageable pageable);`
  - `@Query("SELECT u FROM UserJpa u WHERE u.createdAt < :lastCreatedAt
     OR (u.createdAt = :lastCreatedAt AND u.id < :lastId)
     ORDER BY u.createdAt DESC, u.id DESC")
     List<UserJpa> findPageAfter(@Param("lastCreatedAt") OffsetDateTime lastCreatedAt,
     @Param("lastId") UUID lastId, Pageable pageable);`
  - The `delete` path uses the inherited `JpaRepository.deleteById(UUID)`; no new
    method is added.
- `UserRepositoryAdapter` gains the matching three methods:
  - `existsByEmail(Email)` → `userJpaRepository.existsByEmail(email.value())`.
  - `listAll(Cursor, int)` → identical pageSize-bounded keyset walk as
    `ApiKeyRepositoryAdapter.listAll`. Bounds: `1 <= pageSize <= 100`; out-of-range
    throws `IllegalArgumentException` (the controller layer guards the public surface
    via `PageSize`).
  - `delete(UserId)` → `userJpaRepository.deleteById(id.value())`. The method is
    annotated `@Transactional` so the implicit Hibernate flush + the FK cascade run
    inside a single transaction; without it Spring Data wraps each call in its own,
    which still works but is less clear about the intent.
- Integration test `UserRepositoryAdapterAdminExtensionsIntegrationTest` (extends
  `PostgresIntegrationTest`):
  - `existsByEmail` returns `true` for the seeded admin and `false` for a never-seen
    address; case-insensitive (mixed-case input round-trips through `Email`).
  - Persisted 3 users with distinct `createdAt`, `listAll(null, 2)` returns the two
    newest in DESC order with a non-null `nextCursor`; following the cursor returns
    the third user with a null `nextCursor`.
  - `listAll` resolves the tie-breaker on `id DESC` when `createdAt` is identical
    across rows (same property as the EPIC-04 list test).
  - `delete` removes the row; `findById` returns empty afterwards.
  - `delete` of a non-existent UUID is a silent no-op (Spring Data semantics).
- ArchUnit (US-01-008) still passes.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-002`, `REQ-USR-005`, `REQ-USR-006`, `REQ-PRS-001`, `REQ-PRS-003`,
`REQ-PRS-005`, `REQ-API-005`.

### Design references

§5 database schema, §5.2 cascade rules, §6.1 conventions (cursor pagination), §10
pagination.

### Dependencies

US-05-001 (`UserRepository` port surface), US-03-003 (existing adapter and mapper).

---

## US-05-003 — `ConflictException` handler in `GlobalExceptionHandler` (409 `CONFLICT`)

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `GlobalExceptionHandler` to map every `ConflictException` (and its
subclasses) to a 409 `CONFLICT` RFC 7807 response
**So that** US-05-004's duplicate-email path and every future business-conflict path
(`DUPLICATE_AGENT_NAME`, `NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER`,
`CONVERSATION_FULL`) gets the documented body shape automatically.

### Description

The `domain.shared.ConflictException` base class is already in the codebase (added
during earlier work) but no `@ExceptionHandler` consumes it. This story adds a single
handler that returns the generic `CONFLICT` code with `detail` derived from the
exception message. Future feature EPICs that introduce more specific conflict codes
(EPIC-06 / EPIC-11) MAY add their own `@ExceptionHandler` for finer-grained `code`
values; the generic handler shipped here covers the default case.

### Acceptance criteria

- `GlobalExceptionHandler` gains:
  ```java
  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ProblemDetails> handleConflict(
          ConflictException ex, HttpServletRequest req) {
      return body(HttpStatus.CONFLICT, "CONFLICT", "Conflict", ex.getMessage(), req);
  }
  ```
- The handler block is positioned between the existing 403 group and the existing 404
  group so the handler ordering reads top-down by HTTP status (consistent with the
  current file).
- The generic `CONFLICT` code is added to the documentation block at the top of the
  class so the file stays self-explanatory about which codes it surfaces.
- `GlobalExceptionHandlerTest` (US-03-001 MockMvc-based) is extended:
  - A new branch fires a synthetic `ConflictException("duplicated")` from a probe
    controller and asserts 409, `code=CONFLICT`, `title=Conflict`,
    `detail=duplicated`, and `Content-Type: application/problem+json`.
  - The existing 400 / 401 / 403 / 404 / 405 / 500 branches still pass — no change to
    their assertions.
- `DuplicateEmailException` (from US-05-001) is NOT a separate handler in this story;
  it surfaces as `CONFLICT` via the `ConflictException` superclass handler. EPIC-06
  may introduce `DUPLICATE_AGENT_NAME` as a separate handler with a more specific
  `code`; the precedent for that is `MustChangePasswordException` extending
  `ForbiddenException` and getting its own specific handler in EPIC-03.

### Requirements coverage

`REQ-API-004`, `REQ-ARC-007`.

### Design references

§9.2 `GlobalExceptionHandler`, §9.3 error response shape, §9.1 exception hierarchy
(`ConflictException → 409`).

### Dependencies

US-03-001 (existing handler and probe controller test infrastructure).

---

## US-05-004 — Create-user use case & `POST /admin/users`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to create a new user account with an email, a password, and a role
**So that** the platform onboards new end-users — public self-signup is not supported
(REQ-USR-003) — and so that new accounts inherit the same forced-password-change flow
as the seeded admin.

### Description

Mirror the EPIC-04 `POST /admin/api-keys` pattern: dedicated DTOs at the boundary, a
use-case interface with a command record, a `@Service @Transactional` implementation,
and a controller method on `UsersAdminController`. The use case validates inputs via
the existing domain value objects (`Email`, `Password`), guards against duplicate
emails with `existsByEmail`, BCrypt-hashes the password via the existing
`PasswordHasher`, and persists the new user with `mustChangePassword=true` so the
admin-set temporary password forces a change at first login (matches the seeded admin
pattern from `REQ-USR-007`).

### Acceptance criteria

- `application/user/CreateUserUseCase.java` — interface:
  ```java
  User create(CreateUserCommand command);
  record CreateUserCommand(Email email, Password password, Role role) {}
  ```
- `application/user/CreateUserService.java` — `@Service`, `@Transactional`,
  constructor-injected with `UserRepository`, `PasswordHasher`, `Clock`. Behavior:
  - `userRepository.existsByEmail(email)` → `DuplicateEmailException` if true.
  - `passwordHasher.hash(password)` → BCrypt hash.
  - Build `new User(UserId(UUID.randomUUID()), email, hash, role, /*disabled*/ false,
    /*mustChangePassword*/ true, now, now)` where `now = clock.instant()
    .atOffset(ZoneOffset.UTC)` and persist via `userRepository.save`.
  - Return the persisted aggregate; the cleartext password never leaves the service.
- `infrastructure/web/admin/UsersAdminController.java` — `@RestController`,
  `@PreAuthorize("hasRole('ADMIN')")` at the class level (defense in depth above the
  URL guard from US-04-009). No class-level `@RequestMapping` — the `/api/v1` prefix is
  applied centrally by `WebConfig` (REQ-API-006). The class hosts all five admin user
  endpoints (US-05-004 through US-05-008).
  - `@PostMapping("/admin/users")` returns `201 Created`.
- DTOs:
  - `CreateUserRequest(@NotBlank @Email String email, @NotBlank String password,
     @NotNull Role role)` — bean validation runs at the controller boundary; the
     domain value objects re-enforce on construction.
  - `UserResponse(UUID id, String email, Role role, boolean disabled,
     boolean mustChangePassword, OffsetDateTime createdAt, OffsetDateTime updatedAt)`
     — matches the `User` schema in `openapi.yaml`. Never carries `passwordHash`.
  - `UserResponseMapper.toResponse(User)` — pure static mapper; the test asserts the
     mapping expression never reads `passwordHash`.
- The controller maps `CreateUserRequest` to `CreateUserCommand` by constructing the
  `Email` and `Password` value objects at the boundary so policy violations land as
  per-field 400 `VALIDATION_ERROR` (the `Password` constructor uses field
  `"password"`, matching the openapi 400 example).
- Integration test `CreateUserEndpointIntegrationTest` (MockMvc + Postgres,
  `@ActiveProfiles("dev")`, same admin-bootstrap fixture as the EPIC-04 admin tests):
  - Happy path: admin POSTs `{"email":"alice@example.test","password":"Standard!1A",
    "role":"STANDARD"}` → 201; body carries the documented `UserResponse` shape with
    `mustChangePassword=true`, `disabled=false`. The persisted row has a BCrypt hash
    that matches the cleartext, and the response body does NOT contain
    `passwordHash`.
  - Duplicate email: same email twice → second call → 409 `CONFLICT`.
  - Email format invalid → 400 `VALIDATION_ERROR`, field `email`.
  - Password violates policy (e.g., `"short"`) → 400 `VALIDATION_ERROR`, field
    `password`.
  - Missing `role` → 400 `VALIDATION_ERROR`, field `role`.
  - Unknown role (e.g., `"SUPERADMIN"`) → 400 (HttpMessageNotReadableException —
    Spring's default enum binding rejects unknown values).
  - Non-admin JWT (STANDARD) → 403 `FORBIDDEN`.
  - Unauthenticated → 401 `INVALID_CREDENTIALS`.
  - The application log on the test appender contains no cleartext password.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-002`, `REQ-USR-003`, `REQ-USR-007`, `REQ-AUTH-008`,
`REQ-AUTH-009`, `REQ-SEC-001`, `REQ-SEC-002`, `REQ-SEC-004`, `REQ-API-004`.

### Design references

§6.2.2 admin users (`POST /admin/users`), §8.5 password handling, §8.6 authorization
rules.

### Dependencies

US-05-001 (`User` aggregate + `DuplicateEmailException`), US-05-002 (JPA adapter
`existsByEmail`), US-05-003 (409 handler), US-03-004 (`PasswordHasher`).

---

## US-05-005 — List-users use case & `GET /admin/users`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to page through every user account with metadata only (no password hash)
**So that** I can audit who has access to the platform and pick a target before
disabling or deleting.

### Description

Mirror the EPIC-04 `GET /admin/api-keys` pipeline: the controller decodes the opaque
wire cursor via `CursorCodec`, the use case forwards to `userRepository.listAll(...)`,
and the result is mapped to a `PageDto<UserResponse>` via `UserResponseMapper`. The
response carries no `passwordHash` field; this is asserted explicitly in the test by
parsing the body and walking every item.

### Acceptance criteria

- `application/user/ListUsersUseCase.java` — interface:
  ```java
  Page<User> list(ListUsersQuery query);
  record ListUsersQuery(Cursor cursor, PageSize pageSize) {}
  ```
  `cursor` is `null` on the first page; the REST adapter decodes the encoded wire
  value before constructing the query (same pattern as `ListApiKeysQuery` from
  US-04-007 — keeps the application layer free of any `CursorCodec` dependency to
  satisfy the layering rule).
- `application/user/ListUsersService.java` — `@Service`,
  `@Transactional(readOnly = true)`, pure forwarder to `userRepository.listAll`.
- `UsersAdminController.list`:
  - `@GetMapping("/admin/users")` with query parameters `cursor` and `pageSize`.
  - Decodes the cursor via the injected `CursorCodec`; an invalid value surfaces as
    400 `VALIDATION_ERROR` with field `cursor` via the codec's existing behavior.
  - Builds `ListUsersQuery(decoded, PageSize.fromQueryParam(pageSize))` and calls the
    use case. Out-of-range `pageSize` surfaces as 400 `VALIDATION_ERROR` with field
    `pageSize` via the existing `PageSize` validator.
  - Maps the result with
    `PageDto.of(page, cursorCodec, UserResponseMapper::toResponse)`.
- Integration test `ListUsersEndpointIntegrationTest` (MockMvc + Postgres):
  - Pre-populate 3 users (in addition to the seeded admin) with descending `createdAt`.
    `GET /admin/users?pageSize=2` → 200; the seeded admin appears in the right
    position (newest of the 4 or the oldest, depending on test setup — assert by
    email, not by index); `nextCursor` is non-null.
  - Following the cursor yields the remaining users; the final page has
    `nextCursor=null`.
  - `pageSize=0` / `pageSize=101` → 400, field `pageSize`.
  - Garbage `cursor` → 400, field `cursor`.
  - Response items contain exactly `{id, email, role, disabled, mustChangePassword,
    createdAt, updatedAt}`; an explicit assertion verifies `passwordHash` is **not**
    in the JSON.
  - Non-admin → 403 `FORBIDDEN`; unauthenticated → 401 `INVALID_CREDENTIALS`.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-005`, `REQ-AUTH-008`, `REQ-API-004`, `REQ-API-005`,
`REQ-SEC-004`.

### Design references

§6.2.2 admin users (`GET /admin/users`), §6.1 conventions, §10 pagination.

### Dependencies

US-05-001 (`UserRepository.listAll`), US-05-002 (JPA adapter keyset paging), US-04-005
(`Cursor`, `Page`, `CursorCodec`, `PageDto`, `PageSize`).

---

## US-05-006 — Get-user use case & `GET /admin/users/{userId}`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to fetch the metadata of one specific user by id
**So that** I can drill into account details before deciding whether to disable or
delete the account.

### Description

The smallest of the admin endpoints. The use case looks up by `UserId`; the controller
returns the same `UserResponse` shape as `POST` / `PATCH`. A 404 is raised through the
new `UserNotFoundException` from US-05-001, mapped by the existing
`NotFoundException` handler.

### Acceptance criteria

- `application/user/GetUserUseCase.java` — interface
  `User get(UserId userId);` (the input is a single value object, so a separate command
  record adds no value; precedent: `LoginUseCase` carries a command record only
  because it has two fields).
- `application/user/GetUserService.java` — `@Service`,
  `@Transactional(readOnly = true)`. Behavior:
  - `userRepository.findById(userId)` → `UserNotFoundException` if absent.
  - Return the aggregate.
- `UsersAdminController.get`:
  - `@GetMapping("/admin/users/{userId}")`.
  - Path variable `userId` is parsed via Spring's built-in UUID converter; a malformed
    UUID surfaces as 400 (Spring's `MethodArgumentTypeMismatchException`, mapped by
    the existing handler chain).
  - Returns `UserResponseMapper.toResponse(...)` with status 200.
- Integration test `GetUserEndpointIntegrationTest`:
  - Happy path: pre-create a user, GET by id → 200 with the expected payload (no
    `passwordHash` in the body).
  - Unknown id → 404 `NOT_FOUND`.
  - Malformed UUID in the path → 400 (the exact `code` may be `VALIDATION_ERROR` or
    a default; assert only the status).
  - Non-admin → 403; unauthenticated → 401.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-005`, `REQ-AUTH-008`, `REQ-API-004`, `REQ-SEC-004`.

### Design references

§6.2.2 admin users, §9.2 `GlobalExceptionHandler` (404 mapping).

### Dependencies

US-05-001 (`UserNotFoundException`), US-03-003 (existing `findById` path).

---

## US-05-007 — Enable/disable-user use case & `PATCH /admin/users/{userId}`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to toggle a user's `disabled` flag
**So that** I can revoke a compromised account immediately and re-enable a temporarily
suspended one without losing its history.

### Description

Mirror the EPIC-04 `PATCH /admin/api-keys/{clientId}` pattern. The use case loads the
user, applies `user.withDisabled(...)`, and persists. Unlike the API-key case the
domain has a natural `updatedAt` field, so the mutation uses the injected `Clock` to
bump it (matches what `withNewPasswordHash` already does in `User`).

### Acceptance criteria

- `application/user/UpdateUserUseCase.java` — interface:
  ```java
  User updateDisabled(UpdateUserCommand command);
  record UpdateUserCommand(UserId userId, boolean disabled) {}
  ```
- `application/user/UpdateUserService.java` — `@Service`, `@Transactional`. Behavior:
  - Load via `userRepository.findById` → `UserNotFoundException` if absent.
  - Apply `user.withDisabled(command.disabled(), clock.instant().atOffset(ZoneOffset.UTC))`
    and persist via `userRepository.save`.
  - Return the updated aggregate.
- `UsersAdminController.update`:
  - `@PatchMapping("/admin/users/{userId}")`.
  - Request DTO `UpdateUserRequest(@NotNull Boolean disabled)`.
  - Returns `UserResponse` with status 200.
- Integration test `UpdateUserEndpointIntegrationTest`:
  - Pre-create a user with `disabled=false`. PATCH `{"disabled":true}` → 200, response
    shows `disabled=true`, DB row reflects it, `updatedAt > createdAt`.
  - Round-trip back to `false` is symmetric.
  - Unknown id → 404 `NOT_FOUND`.
  - Missing `disabled` field → 400 `VALIDATION_ERROR`, field `disabled`.
  - Non-admin → 403; unauthenticated → 401.
  - **Self-disable smoke**: an admin patches their own account to `disabled=true`. The
    request succeeds (the endpoint does not prohibit self-disable); a subsequent
    login with the same credentials returns 401 `INVALID_CREDENTIALS` (`LoginService`
    already rejects disabled accounts). This documents the current behavior — design
    is silent on whether self-disable should be forbidden, and we follow the
    least-surprise rule of not adding hidden invariants.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-005`, `REQ-AUTH-008`, `REQ-AUTH-009`, `REQ-API-004`,
`REQ-SEC-004`.

### Design references

§6.2.2 admin users (`PATCH /admin/users/{userId}`), §4.2 invariants.

### Dependencies

US-05-001 (`User.withDisabled`, `UserNotFoundException`), US-05-002 (no new repository
method needed — uses existing `findById` + `save`).

---

## US-05-008 — Delete-user use case & `DELETE /admin/users/{userId}`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** to hard-delete a user account and have every owned agent, conversation, and
message disappear with it
**So that** REQ-USR-006 is satisfied end-to-end through the REST surface — soft-delete
and anonymization are explicitly out of scope.

### Description

The use case calls `userRepository.delete(userId)`. The actual cascade through
`agents → agent_tools / agent_mcp_servers / agent_team / conversations → messages` is
provided by the V001 schema's FK cascades and is already proven at the DB level by the
EPIC-02 `CascadeIntegrationTest` (US-02-007). This story adds a thin HTTP entry point
and verifies, via a JDBC-level check inside the integration test, that the same
cascade fires when the deletion is triggered through the REST API.

### Acceptance criteria

- `application/user/DeleteUserUseCase.java` — interface
  `void delete(UserId userId);`.
- `application/user/DeleteUserService.java` — `@Service`, `@Transactional`. Behavior:
  - Load via `userRepository.findById` → `UserNotFoundException` if absent. The
    pre-flight lookup converts a missing user into 404; without it the call would
    silently no-op (Spring Data `deleteById` does not raise on a missing id).
  - Call `userRepository.delete(userId)`.
- `UsersAdminController.delete`:
  - `@DeleteMapping("/admin/users/{userId}")`.
  - Returns `204 No Content`.
- Integration test `DeleteUserEndpointIntegrationTest`:
  - Seed a user, then seed one agent for that user and one conversation referencing
    the agent (direct repository writes — the agent and conversation persistence
    paths are out of scope for this EPIC, so the test uses `JdbcTemplate` to insert
    the rows directly).
  - DELETE the user → 204.
  - JDBC assertions: the row in `users`, the row in `agents`, the row in
    `conversations`, and any rows in `messages` referencing the conversation are all
    gone (cascade verification through the REST path).
  - Deleting the same id again → 404 `NOT_FOUND`.
  - Unknown id → 404 `NOT_FOUND`.
  - Non-admin → 403; unauthenticated → 401.
  - **Self-delete smoke**: the seeded admin deletes their own account. The request
    succeeds; a subsequent request with the previously issued JWT now fails because
    the user row no longer exists — the `JwtAuthenticationFilter` does not consult
    the DB on each request (the token is self-validating), so the 401 comes from
    whichever later check (`ForcedPasswordChangeFilter` → `findById`) trips first.
    The test documents this behavior; design is silent on whether self-delete should
    be forbidden, and we mirror the self-disable rule from US-05-007.

### Requirements coverage

`REQ-USR-001`, `REQ-USR-005`, `REQ-USR-006`, `REQ-AGT-010`, `REQ-CHAT-008`,
`REQ-AUTH-008`, `REQ-PRS-003`, `REQ-API-004`, `REQ-SEC-004`.

### Design references

§5.2 cascade rules, §6.2.2 admin users (`DELETE /admin/users/{userId}`),
§9.2 `GlobalExceptionHandler` (404 mapping).

### Dependencies

US-05-001 (`UserNotFoundException`, `UserRepository.delete`), US-05-002 (JPA adapter
`delete`), US-02-007 (existing cascade contract test).

---

## EPIC-05 Definition of Done

EPIC-05 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every existing EPIC-01 / EPIC-02 / EPIC-03 / Code Review #1 /
  EPIC-04 test green; the EPIC-05 unit and integration tests run green against a local
  PostgreSQL.
- An authenticated admin can:
  1. create a new user via `POST /admin/users`; the new user has
     `mustChangePassword=true` and the persisted password hash matches the cleartext
     submitted in the request;
  2. list users via `GET /admin/users` with cursor pagination; the response carries no
     `passwordHash` in any item;
  3. fetch a single user via `GET /admin/users/{userId}`;
  4. toggle the `disabled` flag via `PATCH /admin/users/{userId}`;
  5. hard-delete a user via `DELETE /admin/users/{userId}` and verify (via JDBC in
     the test) that every owned agent, conversation, and message is gone too.
- An attempt to create a user with an email that already exists returns
  `409 CONFLICT`.
- A STANDARD JWT or SYSTEM API-key caller is rejected with `403 FORBIDDEN` on every
  `/admin/users/**` path (the `/admin/**` URL guard from US-04-009 already enforces
  this; this EPIC adds no new security wiring).
- Unauthenticated requests on `/admin/users/**` return `401 INVALID_CREDENTIALS`.
- No log line, on any appender, contains a cleartext password (REQ-SEC-002 /
  REQ-SEC-004 — verified by extending the existing log-redaction smoke check from
  EPIC-03 to cover the new `POST /admin/users` path).
- ArchUnit (US-01-008) still passes: `domain/user/**` and `application/user/**` are
  free of Spring MVC / JPA / Jackson imports; the new admin REST DTOs and controller
  live exclusively under `infrastructure/web/admin/**`.
