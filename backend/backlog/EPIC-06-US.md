# EPIC-06-US.md — User stories for EPIC-06

EPIC-06 — **Agents management (owner-scoped CRUD)**

This file lists the user stories that deliver EPIC-06. The EPIC lets an authenticated
end-user (STANDARD or ADMIN) create, list, fetch, replace, and delete their own agents,
with full attribute validation and the team rules from `REQ-AGT-012` /
`REQ-AGT-013`. SYSTEM (API-key) callers are rejected at the URL guard — agents have no
SYSTEM ownership concept.

> **Scope split with EPIC-07 / EPIC-08 / EPIC-10 / EPIC-11 / EPIC-12.**
> - **Tool reference validation** (`REQ-TOOL-004`) is owned by EPIC-07 and **wired into
>   the agent write path then**. EPIC-06 accepts any string in the `tools` array (with
>   length bounds only) and exposes a validation seam on the `CreateAgentService` /
>   `UpdateAgentService` so EPIC-07 plugs in the catalog check without touching
>   EPIC-06 code.
> - **MCP server reference validation** (`REQ-AGT-009`) is owned by EPIC-08 and follows
>   the same seam.
> - **Cascade on agent deletion** (`REQ-AGT-010`) is satisfied by the V001 FK chain
>   shipped by EPIC-02 (`agents → conversations → messages`) and proven by the EPIC-02
>   `CascadeIntegrationTest`. US-06-008's integration test verifies the cascade fires
>   through the REST DELETE path; no new schema work.
> - **Conversation / message creation** is out of scope (EPIC-10 / EPIC-11). The agent
>   record is the source of truth that those EPICs will read at the start of every turn
>   (`REQ-AGT-014`).
> - **Team-delegation execution** is out of scope (EPIC-12). EPIC-06 only enforces the
>   static team-shape rules (single level, same owner, no self-reference); the
>   runtime semantics of `delegate(...)` land later.
> - The EPIC-02 JPA scaffolding (`AgentJpa`, `AgentToolJpa`, `AgentMcpJpa`,
>   `AgentTeamJpa` + their Spring Data interfaces from US-02-005 / US-02-006) is reused
>   verbatim; this EPIC plugs a domain adapter on top of it.
> - The `GlobalExceptionHandler` already maps `ConflictException → 409 CONFLICT`
>   (US-05-003). This EPIC adds subclass handlers for the three specific conflict
>   codes the openapi documents for the agents surface.
> - The `Cursor`, `Page<T>`, `PageDto<T>`, `CursorCodec`, `PageSize` plumbing from
>   EPIC-04 / EPIC-05 is reused; no new pagination work.
> - The `/api/v1/admin/**` URL guard is unchanged. US-06-004 adds a new
>   `/api/v1/agents/**` guard that admits STANDARD and ADMIN and rejects SYSTEM with
>   403 (design §8.6).

## Conventions

- **ID format**: `US-06-<nnn>` — `06` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                              | Priority | Status | Depends on                          |
|------------|------------------------------------------------------------------------------------|----------|--------|-------------------------------------|
| US-06-001  | `Agent` domain: aggregate, value objects, conflict exceptions, repository port    | MUST     | Done   | US-03-002 (`UserId`), EPIC-02       |
| US-06-002  | `AgentRepository` JPA adapter + domain ↔ JPA mapper                                | MUST     | Done   | US-06-001, EPIC-02                  |
| US-06-003  | `GlobalExceptionHandler` extensions for the 3 agent-conflict codes                 | MUST     | Done   | US-05-003, US-06-001                |
| US-06-004  | Create-agent use case & `POST /agents` (+ `/agents/**` URL guard against SYSTEM)   | MUST     | Done   | US-06-001, 002, 003, US-04-009      |
| US-06-005  | List-agents use case & `GET /agents`                                               | MUST     | Done   | US-06-001, 002, US-04-005           |
| US-06-006  | Get-agent use case & `GET /agents/{agentId}`                                       | MUST     | Done   | US-06-001, 002                      |
| US-06-007  | Replace-agent use case & `PUT /agents/{agentId}`                                   | MUST     | Done   | US-06-001, 002, 003                 |
| US-06-008  | Delete-agent use case & `DELETE /agents/{agentId}`                                 | MUST     | Done   | US-06-001, 002, US-02-007           |

---

## US-06-001 — `Agent` domain: aggregate, value objects, conflict exceptions, repository port

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `Agent` aggregate, its value objects (`AgentId`, `AgentName`,
`MemorySize`, `SamplingParams`, `Team`), the three agent-specific conflict exceptions,
and the `AgentRepository` port
**So that** every admin agent use case (US-06-004 .. US-06-008) operates on a
Spring-free, fully-validated domain model and bridges to JPA exclusively through the
adapter delivered in US-06-002.

### Description

Place everything under `domain/agent/`. The aggregate carries all attributes documented
in `REQ-AGT-001`; non-structural invariants that need repository access (unique name
per owner, single-level team, cross-owner team check) live in the application layer
and surface as the three documented `ConflictException` subclasses. Mirror the EPIC-04
/ EPIC-05 patterns for value-object structure and exception placement.

### Acceptance criteria

- `domain/agent/AgentId.java` — record `AgentId(UUID value)` with non-null check.
- `domain/agent/AgentName.java` — record `AgentName(String value)` enforcing:
  - non-null, non-blank;
  - `value.length() <= 32` (`REQ-AGT-001`);
  - violations throw `ValidationException` with field `name`.
  The name is case-sensitive: `"alpha"` and `"Alpha"` are different agents (unlike
  email).
- `domain/agent/MemorySize.java` — record `MemorySize(int value)` enforcing
  `1 <= value <= 36` (`REQ-AGT-004`). Violations throw `ValidationException` with field
  `memorySize`. Static `MemorySize DEFAULT = new MemorySize(12);` exposes the documented
  default for the REST adapter.
- `domain/agent/SamplingParams.java` — record `SamplingParams(String llmModel,
  Double temperature, Integer maxOutputTokens, Double topP)`. Every field is nullable
  (the platform default model applies when `llmModel` is null). The canonical
  constructor enforces:
  - `llmModel == null || llmModel.length() <= 64`;
  - `maxOutputTokens == null || maxOutputTokens >= 1`.
  Sampling-parameter range validation (temperature, topP) is deferred to TBD-4 in the
  design — the canonical constructor accepts any non-null value for now.
- `domain/agent/Team.java` — record `Team(List<AgentId> members)`:
  - `members` is defensively copied (`List.copyOf`) and deduped (preserve insertion
    order; reject duplicate `AgentId`s with `ValidationException` field `team`);
  - rejects null entries;
  - exposes a static `EMPTY` instance for the default case.
  Single-level / same-owner invariants are NOT enforced here — they need repository
  access and live in `CreateAgentService` / `UpdateAgentService`.
- `domain/agent/Agent.java` — record carrying:
  - `id` (`AgentId`), `ownerId` (`UserId`), `name` (`AgentName`), `description`
    (`String`, ≤ 1024, non-blank), `systemPrompt` (`String`, ≤ 1024, non-blank),
    `memorySize` (`MemorySize`), `samplingParams` (`SamplingParams`),
    `tools` (`List<String>`, deduped + each ≤ 64 chars), `enabledMcpServers`
    (`List<String>`, same constraints as tools), `team` (`Team`), `createdAt`,
    `updatedAt`.
  - Canonical constructor:
    - Non-null on every reference field (records can't carry null primitives);
    - Enforces the length / blank rules above with `ValidationException` field
      `description` / `systemPrompt` / `tools` / `enabledMcpServers`;
    - Defensively copies `tools` and `enabledMcpServers` and rejects duplicates.
  - Mutations:
    - `Agent withReplacement(AgentName newName, String newDescription,
       String newSystemPrompt, MemorySize newMemorySize,
       SamplingParams newSamplingParams, List<String> newTools,
       List<String> newEnabledMcpServers, Team newTeam, OffsetDateTime now)`
      — returns a copy keeping `id`, `ownerId`, `createdAt`; bumps `updatedAt = now`.
      Backs the `PUT /agents/{id}` full-replace use case (US-06-007).
- `domain/agent/AgentNotFoundException.java` — `final class` extending
  `domain.shared.NotFoundException`. Message format `"Agent not found: <uuid>"`.
- `domain/agent/DuplicateAgentNameException.java` — `final class` extending
  `domain.shared.ConflictException`. Message format
  `"Agent name already used by this owner: <name>"`. The 409 handler in US-06-003 maps
  this to code `DUPLICATE_AGENT_NAME`.
- `domain/agent/NestedTeamForbiddenException.java` — `final class` extending
  `ConflictException`. Message identifies the offending member id.
- `domain/agent/CrossOwnerTeamMemberException.java` — `final class` extending
  `ConflictException`. Message identifies the offending member id.
- `domain/agent/AgentRepository.java` — port:
  - `Optional<Agent> findById(AgentId id);`
  - `Page<Agent> listByOwner(UserId ownerId, Cursor cursor, int pageSize);` —
    ordered `(createdAt DESC, id DESC)` for the same keyset pattern as
    `ApiKeyRepository.listAll`.
  - `boolean existsByOwnerAndName(UserId ownerId, AgentName name);` — duplicate-name
    pre-flight on create.
  - `boolean existsByOwnerAndNameExcludingId(UserId ownerId, AgentName name,
     AgentId excluded);` — duplicate-name check on replace; "same name as before"
    is OK.
  - `boolean hasNonEmptyTeam(AgentId id);` — backs the single-level rule
    (`REQ-AGT-013`).
  - `Optional<UserId> findOwnerOf(AgentId id);` — backs the cross-owner check
    (`REQ-AGT-012`) without paying for a full aggregate load.
  - `Agent save(Agent agent);` — upsert; the adapter is responsible for replacing
    the child rows in `agent_tools` / `agent_mcp_servers` / `agent_team` atomically.
  - `void delete(AgentId id);` — hard-delete; conversations / messages cascade via
    the V001 FK chain.
- Pure-Java unit tests:
  - `AgentNameTest` — accepts a valid value; rejects null / blank / over-32;
    case-sensitivity (asserts `"Alpha"` and `"alpha"` are distinct `AgentName`s).
  - `MemorySizeTest` — accepts boundaries 1 and 36; rejects 0 and 37 with field
    `memorySize`; `DEFAULT.value() == 12`.
  - `SamplingParamsTest` — accepts all-null (defaults case); enforces `llmModel`
    length cap and `maxOutputTokens >= 1`.
  - `TeamTest` — dedup with insertion order preserved; rejects null entries; `EMPTY`
    is reachable and immutable.
  - `AgentTest` — `withReplacement` preserves `id`/`ownerId`/`createdAt`, bumps
    `updatedAt`, replaces every other field; canonical constructor rejects
    over-length `description` / `systemPrompt`, duplicate tool / MCP names, and null
    references.
  - `AgentNotFoundExceptionTest` / `DuplicateAgentNameExceptionTest` /
    `NestedTeamForbiddenExceptionTest` / `CrossOwnerTeamMemberExceptionTest` —
    each asserts the exception class is reachable as its base type so the generic
    handler routes work.
- ArchUnit (US-01-008) still passes: `domain/agent/**` carries no Spring / JPA /
  Jackson imports.

### Requirements coverage

`REQ-AGT-001`, `REQ-AGT-002`, `REQ-AGT-003`, `REQ-AGT-004`, `REQ-AGT-006`,
`REQ-AGT-007`, `REQ-AGT-012`, `REQ-AGT-013`, `REQ-ARC-002`, `REQ-ARC-003`,
`REQ-ARC-007`.

### Design references

§4.1 Agent entity, §4.2 invariants, §6.2.7 endpoints (conflict codes), §9.1
exception hierarchy.

### Dependencies

US-03-002 (`UserId`), EPIC-02 (DB schema for `agents` + side tables).

---

## US-06-002 — `AgentRepository` JPA adapter + domain ↔ JPA mapper

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the infrastructure adapter that implements `AgentRepository` against the
existing `AgentJpa` / `AgentToolJpa` / `AgentMcpJpa` / `AgentTeamJpa` entities from
EPIC-02
**So that** the agent use cases can read and write the full aggregate without seeing
Spring Data, and so that the four side tables are kept consistent inside a single
transaction on every save.

### Description

The agents aggregate spans four tables (`agents` + three child tables). The mapper
assembles the domain `Agent` from the four entities on read, and on write the adapter
replaces the child rows wholesale — simpler than diffing and adequate at the v1 64-user
scale (`REQ-NFR-005`). Mirror the EPIC-04 `ApiKeyRepositoryAdapter` keyset-paging
strategy for `listByOwner`.

### Acceptance criteria

- `infrastructure/persistence/mapper/AgentMapper.java` — pure Java (no Spring
  stereotypes). Two directions:
  - `toDomain(AgentJpa, List<AgentToolJpa>, List<AgentMcpJpa>, List<AgentTeamJpa>)`
    builds an `Agent`. Insertion order from the side tables is preserved by the
    adapter via deterministic queries.
  - `toJpa(Agent)` produces the new `AgentJpa` and the three side-row lists,
    returned as a small `MappedAgent` record so the adapter can write them in one
    pass.
- `UserJpaRepository` is NOT touched here — this story is agent-scoped only.
- The four Spring Data interfaces from EPIC-02 are extended with the finders this
  story consumes:
  - `AgentJpaRepository`:
    - `boolean existsByOwnerIdAndName(UUID ownerId, String name);`
    - `boolean existsByOwnerIdAndNameAndIdNot(UUID ownerId, String name, UUID id);`
    - `Optional<UUID> findOwnerIdById(UUID id);` — projection via `@Query`
      (`SELECT a.ownerId FROM AgentJpa a WHERE a.id = :id`) so we don't load the
      whole row just to check ownership.
    - `@Query` keyset finders `findFirstPageByOwner(UUID ownerId, Pageable)` and
      `findPageAfterByOwner(UUID ownerId, OffsetDateTime lastCreatedAt, UUID
      lastId, Pageable)`, mirroring `UserJpaRepository`'s shape from US-05-002.
  - `AgentToolJpaRepository` / `AgentMcpJpaRepository` / `AgentTeamJpaRepository`:
    - `List<...> findByAgentId(UUID agentId);` — ordered by primary key to keep
      reads deterministic;
    - `void deleteByAgentId(UUID agentId);` — invoked by `save` before the side-row
      re-insert.
  - `AgentTeamJpaRepository`:
    - `boolean existsByParentAgentId(UUID parentAgentId);` — backs
      `hasNonEmptyTeam`.
- `infrastructure/persistence/adapter/AgentRepositoryAdapter.java` — `@Component`
  implementing `AgentRepository`, constructor-injected with the four Spring Data
  interfaces and `AgentMapper`. Methods:
  - `findById` — load `AgentJpa`, then the three child lists, then `toDomain`.
  - `listByOwner` — keyset paging on the parent table, then bulk-load child rows
    for every parent id in a single query per table (N+1-aware: at most 3 follow-up
    queries per page, not 3×pageSize). Order-preserving join into the domain
    `Page<Agent>` happens in the adapter.
  - `existsByOwnerAndName`, `existsByOwnerAndNameExcludingId`, `findOwnerOf`,
    `hasNonEmptyTeam` — thin forwarders.
  - `save` — `@Transactional`. Algorithm:
    1. Upsert the parent row via `agentJpaRepository.save(parentJpa)`.
    2. `deleteByAgentId` on all three child repositories.
    3. `saveAll` on each child repository with the mapped lists.
    4. Re-read the parent and child rows to build the returned domain `Agent` so
       any DB-generated timestamps land in the result.
  - `delete` — `@Transactional`. Calls `agentJpaRepository.deleteById(id.value())`;
    the FK cascades handle the side tables and the downstream `conversations` /
    `messages` rows. A non-existent id is a silent no-op at this layer.
- Integration test `AgentRepositoryAdapterIntegrationTest` (extends
  `PostgresIntegrationTest`):
  - **Round trip**: save an agent with non-empty `tools`, `enabledMcpServers`, and
    `team`; `findById` returns an equal aggregate (every field, every list, in
    insertion order).
  - **Save is replacing**: save again with a different team set; the agent's row
    count in `agent_team` matches the new set exactly (no leftover rows from the
    first save).
  - **Cursor paging**: pre-populate 3 agents for one owner with strictly increasing
    `createdAt`; `listByOwner(owner, null, 2)` returns the two newest in DESC
    order with a non-null `nextCursor`; the follow-up page returns the third with
    `nextCursor=null`.
  - **Owner scope**: pre-populate 1 agent for owner A and 1 for owner B;
    `listByOwner(A, null, 10)` returns A's only, asserting cross-owner isolation
    at the repository level.
  - **Existence checks**: `existsByOwnerAndName` true / false; the
    `ExcludingId` variant returns false for the same name owned by the same user
    when the supplied id matches the existing row.
  - **`findOwnerOf`**: returns the owner UUID for an existing agent; empty for an
    unknown id.
  - **`hasNonEmptyTeam`**: true when the agent has at least one team member, false
    otherwise.
  - **Delete cascade through agents**: persist an agent + one conversation + one
    message; `delete(agentId)` removes the agent and the JDBC count of related
    `conversations` and `messages` rows drops to zero (mirrors the EPIC-02
    `CascadeIntegrationTest` shape).
- ArchUnit (US-01-008) still passes.

### Requirements coverage

`REQ-AGT-001`, `REQ-AGT-002`, `REQ-AGT-006`, `REQ-AGT-010`, `REQ-AGT-012`,
`REQ-AGT-013`, `REQ-PRS-001`, `REQ-PRS-003`, `REQ-PRS-005`, `REQ-API-005`.

### Design references

§3 project structure (`infrastructure/persistence/{mapper,adapter}/`), §5 database
schema (agents + side tables), §5.2 cascade rules, §6.1 conventions.

### Dependencies

US-06-001 (port + value objects), EPIC-02 (`AgentJpa`, `AgentToolJpa`, `AgentMcpJpa`,
`AgentTeamJpa`, and the four Spring Data repositories).

---

## US-06-003 — `GlobalExceptionHandler` extensions for the 3 agent-conflict codes

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `GlobalExceptionHandler` to map `DuplicateAgentNameException`,
`NestedTeamForbiddenException`, and `CrossOwnerTeamMemberException` to their specific
RFC 7807 codes (`DUPLICATE_AGENT_NAME`, `NESTED_TEAM_FORBIDDEN`,
`CROSS_OWNER_TEAM_MEMBER`)
**So that** the frontend can switch on the documented code values rather than the
generic `CONFLICT` that US-05-003 routes every other `ConflictException` to.

### Description

Precedent: `MustChangePasswordException` (a `ForbiddenException` subclass) already gets
its own `@ExceptionHandler` ahead of the generic `ForbiddenException` handler in EPIC-03.
This story applies the same pattern for the three agent-specific conflict codes
documented in `openapi.yaml` (`ProblemDetails.code` enum + the `Conflict` response
example).

### Acceptance criteria

- `GlobalExceptionHandler` gains three new `@ExceptionHandler` methods, each placed
  **before** the generic `ConflictException` handler so the more-specific subclass
  matches first (Spring MVC walks `@ExceptionHandler`s in declaration order for
  same-priority exceptions):
  - `DuplicateAgentNameException` → 409 `DUPLICATE_AGENT_NAME`, title
    `"Duplicate agent name"`, `detail` = the exception message.
  - `NestedTeamForbiddenException` → 409 `NESTED_TEAM_FORBIDDEN`, title
    `"Nested team forbidden"`.
  - `CrossOwnerTeamMemberException` → 409 `CROSS_OWNER_TEAM_MEMBER`, title
    `"Cross-owner team member"`.
- The existing generic `ConflictException` handler (US-05-003) is unchanged and still
  catches any future business-conflict subclass that does not have a dedicated
  handler.
- The documentation block at the top of the class is extended to list the three new
  codes alongside `CONFLICT`.
- `GlobalExceptionHandlerTest` is extended with one MockMvc-based test per new code,
  asserting status 409, the documented `code` / `title`, the `detail` from the
  exception message, the `instance` path, and `Content-Type:
  application/problem+json`. The probe controller (the existing
  `TestErrorController` inside the test class) gains a `/throw/duplicate-agent-name`,
  `/throw/nested-team-forbidden`, and `/throw/cross-owner-team-member` route.
- The existing 13 / 14 cases (`VALIDATION_ERROR`, `INVALID_CREDENTIALS`,
  `MUST_CHANGE_PASSWORD`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, etc.) still pass —
  no change to their assertions.

### Requirements coverage

`REQ-API-004`, `REQ-ARC-007`.

### Design references

§9.2 `GlobalExceptionHandler`, §9.3 error response shape, §6.2.7 conflict codes for
`/agents`.

### Dependencies

US-05-003 (generic `ConflictException` handler), US-06-001 (the three new exception
classes).

---

## US-06-004 — Create-agent use case & `POST /agents` (+ `/agents/**` URL guard against SYSTEM)

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user (STANDARD or ADMIN)
**I want** to create an agent under my own ownership with a name, description, system
prompt, optional sampling parameters, tools list, MCP-server list, and team
**So that** I can configure a fresh agent for my use, and so that the platform rejects
duplicate names, single-level violations, cross-owner team members, and SYSTEM callers
up front.

### Description

The agent record is the source of truth that EPIC-10 / EPIC-11 will read at the start
of every turn (`REQ-AGT-014`). This story:
- Adds the URL guard `/api/v1/agents/** → hasAnyRole("STANDARD", "ADMIN")` to
  `SpringSecurityConfig`, placed **before** the generic `apiPattern.authenticated()`
  rule so the more-specific match wins. SYSTEM (`ROLE_SYSTEM`) is therefore rejected
  with 403 at the URL layer — design §8.6.
- Introduces the `AgentsController`, the `AgentRequest` / `AgentResponse` DTOs, and
  the `AgentResponseMapper`. All subsequent agent endpoints (US-06-005 .. US-06-008)
  hang off this controller.
- Runs the three repository-backed invariants in the service:
  - Duplicate name → `DuplicateAgentNameException`.
  - Team-member cross-owner → `CrossOwnerTeamMemberException`.
  - Team-member has non-empty team → `NestedTeamForbiddenException`.
  - Self-reference in `team` (team member id equals the new agent's id) is
    impossible on create because the new id is generated by the service — but the
    same check runs on update (US-06-007).
- Exposes a `ToolReferenceValidator` / `McpReferenceValidator` injection seam so
  EPIC-07 / EPIC-08 can plug in the catalog check. **Default in EPIC-06**: both
  validators are no-op `@Component`s shipped here under
  `infrastructure/agent/validation/` that accept any string. EPIC-07 / EPIC-08
  replace them with the catalog-backed implementations (`@Primary` or by removing
  the EPIC-06 stub and providing the real one in their own packages).

### Acceptance criteria

- **URL guard**:
  - `SpringSecurityConfig.securityFilterChain` adds
    `.requestMatchers(apiPrefix + "/agents/**").hasAnyRole("STANDARD", "ADMIN")`
    immediately after the existing admin guard and before
    `requestMatchers(apiPattern).authenticated()`.
  - The `SpringSecurityConfigTest` regression is extended to cover anonymous → 401,
    SYSTEM-via-API-key → 403, STANDARD JWT → 200 (on a small probe ping or on this
    very `POST /agents` endpoint).
- **Application layer**:
  - `application/agent/ToolReferenceValidator.java` — `interface` with
    `void validate(List<String> toolNames)`. Default contract: throw
    `ValidationException` with field `tools` on the first unknown name.
  - `application/agent/McpReferenceValidator.java` — symmetric, field
    `enabledMcpServers`.
  - `application/agent/CreateAgentUseCase.java` — interface:
    ```java
    Agent create(CreateAgentCommand command);
    record CreateAgentCommand(
            UserId ownerId,
            AgentName name,
            String description,
            String systemPrompt,
            MemorySize memorySize,
            SamplingParams samplingParams,
            List<String> tools,
            List<String> enabledMcpServers,
            Team team) {}
    ```
  - `application/agent/CreateAgentService.java` — `@Service`, `@Transactional`,
    constructor-injected with `AgentRepository`, `ToolReferenceValidator`,
    `McpReferenceValidator`, `Clock`. Pipeline:
    1. `existsByOwnerAndName` → `DuplicateAgentNameException` on hit.
    2. `toolReferenceValidator.validate(tools)` (EPIC-06 stub: no-op).
    3. `mcpReferenceValidator.validate(enabledMcpServers)` (EPIC-06 stub: no-op).
    4. For each `memberId` in `team`:
       - `findOwnerOf(memberId)` → must equal `ownerId` else
         `CrossOwnerTeamMemberException`;
       - `hasNonEmptyTeam(memberId)` → must be `false` else
         `NestedTeamForbiddenException`.
    5. Build `new Agent(...)` with fresh `AgentId`, `createdAt = updatedAt = now`,
       and persist via `agentRepository.save`.
    6. Return the persisted aggregate.
- **Infrastructure stubs**:
  - `infrastructure/agent/validation/NoopToolReferenceValidator.java` — `@Component`
    implementing `ToolReferenceValidator` with a no-op `validate`. Javadoc spells
    out that EPIC-07 replaces this.
  - `infrastructure/agent/validation/NoopMcpReferenceValidator.java` — symmetric;
    EPIC-08 replaces it.
- **REST layer**:
  - `infrastructure/web/agent/AgentsController.java` — `@RestController`, no
    class-level `@RequestMapping` (the `/api/v1` prefix is applied centrally).
    Constructor-injected with the five use cases (this story adds Create; the
    others come in US-06-005 .. US-06-008) plus `CursorCodec`.
  - `@PostMapping("/agents")` returns 201.
  - DTOs:
    - `AgentRequest` — record with every field documented in the openapi
      `AgentRequest` schema. `tools`, `enabledMcpServers`, `team` default to
      `List.of()` when `null`. Uses bean validation for length caps on the
      String fields and `@Min`/`@Max` on `memorySize`. The controller constructs
      the domain value objects at the boundary so policy violations land as
      per-field 400.
    - `AgentResponse` — record matching the openapi `Agent` schema (all
      `AgentRequest` fields + `id`, `ownerId`, `createdAt`, `updatedAt`).
    - `AgentResponseMapper` — pure-static mapper from `Agent` to `AgentResponse`.
  - The controller resolves the caller via `@AuthenticationPrincipal UserPrincipal
    principal` and uses `principal.id()` as `ownerId`.
- **Integration test** `CreateAgentEndpointIntegrationTest` (MockMvc + Postgres,
  `@ActiveProfiles("dev")`):
  - Happy path: STANDARD JWT creates an agent with non-empty `tools`,
    `enabledMcpServers`, and empty `team` → 201; response body matches the
    openapi shape; the persisted row has `ownerId = principal.id()`.
  - Duplicate name for the same user → 409 `DUPLICATE_AGENT_NAME`.
  - Same name for a different user is allowed (REQ-AGT-002).
  - Team member owned by another user → 409 `CROSS_OWNER_TEAM_MEMBER`.
  - Team member that itself has a non-empty team → 409 `NESTED_TEAM_FORBIDDEN`.
  - `name` over 32 chars → 400 `VALIDATION_ERROR` field `name`.
  - `memorySize` 0 → 400 field `memorySize`.
  - `memorySize` 37 → 400 field `memorySize`.
  - `description` over 1024 chars → 400 field `description`.
  - `systemPrompt` over 1024 chars → 400 field `systemPrompt`.
  - **SYSTEM caller via API key** → 403 `FORBIDDEN` (the new URL guard).
  - **Anonymous** → 401 `INVALID_CREDENTIALS`.
- **Compatibility test**: a STANDARD JWT can hit `POST /agents` even with
  `mustChangePassword=false` (no admin role required). An ADMIN JWT can also create
  agents under their own ownership.

### Requirements coverage

`REQ-AGT-001`, `REQ-AGT-002`, `REQ-AGT-003`, `REQ-AGT-004`, `REQ-AGT-006`,
`REQ-AGT-007`, `REQ-AGT-012`, `REQ-AGT-013`, `REQ-AUTH-007`, `REQ-AUTH-008`,
`REQ-API-004`, `REQ-API-006`.

### Design references

§6.2.7 admin agents (`POST /agents`), §8.6 authorization rules, §4.2 invariants.

### Dependencies

US-06-001 (`Agent` domain), US-06-002 (JPA adapter), US-06-003 (conflict handlers),
US-04-009 (existing Spring Security wiring + method security).

---

## US-06-005 — List-agents use case & `GET /agents`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user
**I want** to page through every agent I own with full configuration metadata
**So that** I can surface the list in the frontend without seeing — even by mistake —
agents that belong to another user.

### Description

Mirror the EPIC-04 `GET /admin/api-keys` and EPIC-05 `GET /admin/users` patterns: the
controller decodes the opaque cursor through `CursorCodec`, forwards a domain `Cursor`
into the application layer, and emits a `PageDto<AgentResponse>`. The list is owner-scoped
at the repository layer — the service never has the option to read other users' rows.

### Acceptance criteria

- `application/agent/ListAgentsUseCase.java` — interface:
  ```java
  Page<Agent> list(ListAgentsQuery query);
  record ListAgentsQuery(UserId ownerId, Cursor cursor, PageSize pageSize) {}
  ```
- `application/agent/ListAgentsService.java` — `@Service`,
  `@Transactional(readOnly = true)`, pure forwarder to
  `agentRepository.listByOwner(...)`.
- `AgentsController.list`:
  - `@GetMapping("/agents")` with query parameters `cursor` and `pageSize`.
  - Resolves the caller's `UserId` via `@AuthenticationPrincipal UserPrincipal`.
  - Decodes the cursor through the injected `CursorCodec`; builds
    `ListAgentsQuery(principal.id(), cursor, PageSize.fromQueryParam(pageSize))`.
  - Maps the result via
    `PageDto.of(page, cursorCodec, AgentResponseMapper::toResponse)`.
- Integration test `ListAgentsEndpointIntegrationTest`:
  - Pre-populate 3 agents owned by user A with strictly increasing `createdAt` and
    1 agent owned by user B. `GET /agents?pageSize=2` as user A → 200; the two
    newest A-agents in DESC order; `nextCursor` non-null; B's agent absent.
  - Following the cursor returns the third A-agent and `nextCursor=null`.
  - User A's empty case (no agents) → 200, empty items, `nextCursor` absent
    (NON_NULL serialization).
  - `pageSize=0` / `pageSize=101` → 400 `VALIDATION_ERROR`, field `pageSize`.
  - Garbage `cursor` → 400 `VALIDATION_ERROR`, field `cursor`.
  - **Owner isolation regression**: user B's GET never sees A's agents.
  - SYSTEM caller → 403 `FORBIDDEN`; anonymous → 401 `INVALID_CREDENTIALS`.
- The response items contain exactly the openapi-documented `Agent` fields and never
  expose `passwordHash`-like sensitive data (agents have none, but the assertion
  documents the convention).

### Requirements coverage

`REQ-AGT-006`, `REQ-AGT-007`, `REQ-API-004`, `REQ-API-005`.

### Design references

§6.2.7 admin agents (`GET /agents`), §6.1 conventions, §10 pagination.

### Dependencies

US-06-001 (port), US-06-002 (JPA adapter), US-04-005 (`Cursor` / `Page` / `PageDto` /
`PageSize`).

---

## US-06-006 — Get-agent use case & `GET /agents/{agentId}`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user
**I want** to fetch one of my own agents by id
**So that** I can see its full configuration. Cross-owner access surfaces as 404 —
identical to "not found" — to avoid leaking that the id exists for another user.

### Description

The smallest of the agent endpoints. The use case looks up by `AgentId`, then checks
that the loaded agent's `ownerId` equals the caller's. A mismatch raises
`AgentNotFoundException` (not 403) so the existence of someone else's agent stays
invisible.

### Acceptance criteria

- `application/agent/GetAgentUseCase.java` — interface
  `Agent get(GetAgentQuery query);` with
  `record GetAgentQuery(UserId ownerId, AgentId agentId) {}`. Carrying the
  `ownerId` in the query (rather than fetching the agent and checking after) keeps
  the ownership rule visible in the use-case interface.
- `application/agent/GetAgentService.java` — `@Service`,
  `@Transactional(readOnly = true)`. Behavior:
  - `findById(agentId)` → `AgentNotFoundException` if absent.
  - If the loaded agent's `ownerId() != query.ownerId()` → also raise
    `AgentNotFoundException` (no 403; design §8.6).
  - Return the aggregate.
- `AgentsController.get`:
  - `@GetMapping("/agents/{agentId}")`.
  - Path variable validated as UUID (the EPIC-05 `MethodArgumentTypeMismatchException`
    handler from US-05-006 catches malformed values).
  - Returns `AgentResponseMapper.toResponse(...)` with status 200.
- Integration test `GetAgentEndpointIntegrationTest`:
  - Happy path: user A's GET on their own agent → 200 with the documented shape.
  - User A's GET on user B's agent id → 404 `NOT_FOUND` (NOT 403). The response
    body is byte-identical to the truly-not-found case (the two scenarios share
    the same handler path), so the test asserts byte-equality against a fixture.
  - Unknown id → 404.
  - Malformed UUID → 400 `VALIDATION_ERROR` (catch via the US-05-006 handler).
  - SYSTEM caller → 403; anonymous → 401.

### Requirements coverage

`REQ-AGT-006`, `REQ-AGT-007`, `REQ-AUTH-008`, `REQ-API-004`.

### Design references

§6.2.7 admin agents (`GET /agents/{agentId}`), §8.6 authorization rules ("cross-owner
GET is 404, not 403, to avoid leaking existence").

### Dependencies

US-06-001 (port + `AgentNotFoundException`), US-06-002 (JPA adapter).

---

## US-06-007 — Replace-agent use case & `PUT /agents/{agentId}`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user
**I want** to replace one of my own agents' configuration wholesale, including its
team
**So that** every subsequent turn of any ongoing conversation involving the agent
immediately uses the new configuration (`REQ-AGT-014`).

### Description

PUT is a **full replace**: every field except `id`, `ownerId`, `createdAt` is overwritten;
`updatedAt` is bumped to `clock.now()`. Validation mirrors `POST /agents` exactly, with
the duplicate-name pre-flight using the `ExcludingId` variant so renaming an agent
back to its own current name is not a conflict. The team rules — single-level,
same-owner — re-run on every replace. A self-reference (team member id equals the
agent being replaced) is rejected as `NESTED_TEAM_FORBIDDEN` (it would create a
self-loop with non-empty team).

### Acceptance criteria

- `application/agent/UpdateAgentUseCase.java` — interface:
  ```java
  Agent replace(UpdateAgentCommand command);
  record UpdateAgentCommand(
          UserId ownerId,
          AgentId agentId,
          AgentName name,
          String description,
          String systemPrompt,
          MemorySize memorySize,
          SamplingParams samplingParams,
          List<String> tools,
          List<String> enabledMcpServers,
          Team team) {}
  ```
- `application/agent/UpdateAgentService.java` — `@Service`, `@Transactional`,
  constructor-injected with `AgentRepository`, `ToolReferenceValidator`,
  `McpReferenceValidator`, `Clock`. Pipeline:
  1. `findById(agentId)` → `AgentNotFoundException` if absent.
  2. If `existing.ownerId() != command.ownerId()` → also `AgentNotFoundException`
     (same 404 rule as GET).
  3. `existsByOwnerAndNameExcludingId(ownerId, newName, agentId)` →
     `DuplicateAgentNameException` on hit.
  4. Reference validation: `toolReferenceValidator.validate(tools)` and
     `mcpReferenceValidator.validate(enabledMcpServers)`.
  5. Team validation (same as create):
     - For each `memberId` in `team`:
       - If `memberId.equals(agentId)` → `NestedTeamForbiddenException`
         (a self-reference would imply a non-empty team on `agentId` itself);
       - `findOwnerOf(memberId)` must equal `ownerId` else
         `CrossOwnerTeamMemberException`;
       - `hasNonEmptyTeam(memberId)` must be `false` else
         `NestedTeamForbiddenException`.
  6. Apply `existing.withReplacement(...)` with `now = clock.instant().atOffset(UTC)`
     and persist via `save`.
- `AgentsController.replace`:
  - `@PutMapping("/agents/{agentId}")`.
  - Builds the `UpdateAgentCommand` by parsing the `AgentRequest` body at the
    boundary (same value-object constructors as create).
  - Returns `AgentResponse` with status 200.
- Integration test `UpdateAgentEndpointIntegrationTest`:
  - Happy path: PUT a fresh body on an owned agent → 200; persisted row reflects
    every changed field; `updatedAt > createdAt`; `id` and `ownerId` unchanged.
  - **Rename to current name is allowed**: PUT with the same `name` as before → 200.
  - Rename to another agent's name owned by the same user → 409
    `DUPLICATE_AGENT_NAME`.
  - Rename to another agent's name owned by a different user → 200 (REQ-AGT-002:
    name uniqueness is per-owner).
  - Team includes the agent itself → 409 `NESTED_TEAM_FORBIDDEN`.
  - Team member owned by another user → 409 `CROSS_OWNER_TEAM_MEMBER`.
  - Team member with a non-empty team → 409 `NESTED_TEAM_FORBIDDEN`.
  - Cross-owner PUT (user A targets user B's agent id) → 404 `NOT_FOUND`.
  - Unknown id → 404.
  - All validation failures (length caps, memorySize range) → 400 with the
    documented field.
  - SYSTEM → 403; anonymous → 401.

### Requirements coverage

`REQ-AGT-001`, `REQ-AGT-002`, `REQ-AGT-003`, `REQ-AGT-004`, `REQ-AGT-006`,
`REQ-AGT-007`, `REQ-AGT-012`, `REQ-AGT-013`, `REQ-AGT-014`.

### Design references

§6.2.7 admin agents (`PUT /agents/{agentId}`), §4.2 invariants, §8.6 authorization.

### Dependencies

US-06-001 (port), US-06-002 (JPA adapter), US-06-003 (conflict handlers).

---

## US-06-008 — Delete-agent use case & `DELETE /agents/{agentId}`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user
**I want** to hard-delete one of my own agents and have every conversation and
message that references it disappear with it
**So that** `REQ-AGT-010` is satisfied end-to-end through the REST surface — there is
no soft-delete or archive flag.

### Description

The cascade chain (`agents → conversations → messages`, plus the `agent_tools`,
`agent_mcp_servers`, `agent_team` side rows) is provided by the V001 FK chain and is
proven at the DB level by the EPIC-02 `CascadeIntegrationTest` (US-02-007). This story
adds the REST entry point, the use case, and an integration test that verifies the
cascade fires through the endpoint.

### Acceptance criteria

- `application/agent/DeleteAgentUseCase.java` — interface
  `void delete(DeleteAgentCommand command);` with
  `record DeleteAgentCommand(UserId ownerId, AgentId agentId) {}`.
- `application/agent/DeleteAgentService.java` — `@Service`, `@Transactional`.
  Behavior:
  - `findById(agentId)` → `AgentNotFoundException` if absent.
  - If `existing.ownerId() != ownerId` → also `AgentNotFoundException` (same 404
    rule as GET / PUT).
  - `agentRepository.delete(agentId)`.
- `AgentsController.delete`:
  - `@DeleteMapping("/agents/{agentId}")` returning `204 No Content`.
- Integration test `DeleteAgentEndpointIntegrationTest`:
  - Happy path: seed an agent + one conversation + one message via JDBC (the
    conversation / message domain repositories aren't introduced until
    EPIC-10 / EPIC-11). DELETE the agent → 204. JDBC assertions: the agent row,
    the conversation row, the message row, and any rows in
    `agent_tools` / `agent_mcp_servers` / `agent_team` for this agent are all
    gone.
  - Deleting twice → 404 on the second attempt.
  - Cross-owner DELETE → 404 (NOT 403).
  - Unknown id → 404.
  - SYSTEM → 403; anonymous → 401.

### Requirements coverage

`REQ-AGT-006`, `REQ-AGT-007`, `REQ-AGT-010`, `REQ-CHAT-008`, `REQ-PRS-003`,
`REQ-API-004`.

### Design references

§5.2 cascade rules, §6.2.7 admin agents (`DELETE /agents/{agentId}`).

### Dependencies

US-06-001 (port + `AgentNotFoundException`), US-06-002 (JPA adapter), US-02-007
(existing cascade contract test).

---

## EPIC-06 Definition of Done

EPIC-06 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every test from previous EPICs green; the EPIC-06 unit and
  integration tests run green against a local PostgreSQL.
- A STANDARD or ADMIN end-user can:
  1. create an agent with a name, description, system prompt, sampling params, tools,
     MCP servers, and a (possibly empty) team via `POST /agents`;
  2. list their own agents via `GET /agents` with cursor pagination — and never see
     another user's agents in the response;
  3. fetch one of their own agents via `GET /agents/{id}`;
  4. replace an agent's entire configuration via `PUT /agents/{id}`, with the team
     rules re-validated;
  5. hard-delete an agent via `DELETE /agents/{id}` and verify (JDBC) that every
     owned conversation and message disappears.
- A SYSTEM (API-key) caller is rejected with `403 FORBIDDEN` on every
  `/api/v1/agents/**` path; an anonymous caller is rejected with `401`.
- Duplicate-name / nested-team / cross-owner-team-member violations surface as the
  three documented 409 codes — not as the generic `CONFLICT`.
- Tool reference validation and MCP-server reference validation are EPIC-06 no-ops;
  EPIC-07 / EPIC-08 will replace the stub `ToolReferenceValidator` /
  `McpReferenceValidator` adapters with the real catalog-backed ones. No EPIC-06
  code needs to change for that integration.
- The full FK cascade chain (`users → agents → conversations → messages`, plus the
  three `agent_*` side tables) fires correctly through both the EPIC-05
  `DELETE /admin/users/{userId}` path (verified earlier) and the new EPIC-06
  `DELETE /agents/{agentId}` path.
- ArchUnit (US-01-008) still passes: `domain/agent/**` and `application/agent/**` are
  free of Spring MVC / JPA / Jackson imports; the new REST DTOs, controller, and
  validator stubs live exclusively under `infrastructure/**`.
