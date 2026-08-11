# EPICS.md — Backend EPICs

This document lists the EPICs of the backend module of the multi-agent platform.
Each EPIC groups a coherent set of capabilities and translates a slice of `requirements/REQS.md` and
`design/SW-DESIGN.md` into deliverable work. The detailed user stories of each EPIC live in a
companion file `EPIC-<ref>-US.md` (to be created in a subsequent step).

## Conventions

- **ID format**: `EPIC-<nn>` — two-digit zero-padded sequence reflecting recommended build order.
- **Status**: one of `Draft`, `Ready`, `In progress`, `Done`. All EPICs start as `Draft`.
- **Priority**: `MUST` (v1 must-have), `SHOULD` (strongly desired for v1), `COULD` (nice-to-have).
- **Requirements coverage**: requirement IDs from `requirements/REQS.md` carried by the EPIC.
- **Design references**: section(s) of `design/SW-DESIGN.md` describing the work.
- **API surface**: paths from `openapi.yaml` delivered by the EPIC (where applicable).
- **Dependencies**: other EPICs that must reach a workable state first.

## Build order rationale

EPICs are numbered by recommended build order, not by importance. Foundations (project skeleton,
persistence, error handling, security primitives) come first because the rest of the system relies
on them. Feature EPICs (agents, conversations, streaming, delegation) come next. Cross-cutting
concerns that depend on the running stack (rate limiting, observability, deployment) come last.

```
EPIC-01 → EPIC-02 ┐
                  ├→ EPIC-03 ┐
                  │          ├→ EPIC-05 → EPIC-06 → EPIC-07 ──→ EPIC-09 ┐
                  │          │                          → EPIC-08 ─────→├→ EPIC-10 → EPIC-11 → EPIC-12
                  │          └→ EPIC-04                                  │
                  └────────────────────────────────────────────────────  ┘
                                                                         │
                                                                         ├→ EPIC-13
                                                                         ├→ EPIC-14 (cross-cutting; runs in parallel)
                                                                         ├→ EPIC-15
                                                                         └→ EPIC-16
```

---

## EPIC list

| ID       | Title                                              | Priority | Status |
|----------|----------------------------------------------------|----------|--------|
| EPIC-01  | Project foundation & hexagonal skeleton            | MUST     | Done   |
| EPIC-02  | Persistence foundation (PostgreSQL + Flyway)       | MUST     | Done   |
| EPIC-03  | Authentication — JWT, login, logout, password      | MUST     | Done   |
| EPIC-04  | Authentication — API keys (machine-to-machine)     | MUST     | Done   |
| EPIC-05  | User management (admin)                            | MUST     | Done   |
| EPIC-06  | Agents management (owner-scoped CRUD)              | MUST     | Done   |
| EPIC-07  | Tools catalog                                      | MUST     | Done   |
| EPIC-08  | MCP servers integration                            | MUST     | Done   |
| EPIC-09  | LLM provider integration (OpenAI)                  | MUST     | Done   |
| EPIC-10  | Conversations & messages (non-streaming surface)   | MUST     | Done   |
| EPIC-11  | SSE streaming chat                                 | MUST     | Done   |
| EPIC-12  | Agent team delegation                              | MUST     | Done   |
| EPIC-13  | Rate limiting (Bucket4j)                           | MUST     | Done   |
| EPIC-14  | Cross-cutting API concerns (errors, paging, CORS)  | MUST     | Done   |
| EPIC-15  | Observability & health                             | SHOULD   | Done   |
| EPIC-16  | Build, packaging & AWS deployment                  | MUST     | Draft  |

---

## EPIC-01 — Project foundation & hexagonal skeleton

- **Goal**: Stand up the Spring Boot 4.0.6 / Java 17 project with the hexagonal package layout
  (`domain` / `application` / `infrastructure`), wire in Spring AI 1.1.0, externalize all
  configuration, and produce a runnable empty fat JAR. This EPIC does not deliver any business
  endpoint — it delivers the scaffolding every other EPIC builds on.
- **Scope**:
  - Maven project (`pom.xml`), single module, fat-JAR build (`REQ-DEP-004`).
  - Package skeleton with the three top-level packages and one stub per bounded context.
  - `Application.java` Spring Boot entry point.
  - `application.yaml` with the `app.*` prefix bound to a single `ApplicationProperties` record.
  - Centralized `/api/v1` base path via Spring property (`REQ-API-006`).
  - Wiring of Spring MVC + Spring Security skeleton (open chain initially).
  - Java coding standard enforcement (records for DTOs, constructor injection, no field
    `@Autowired`, no Lombok unless records cannot fit) per `JAVA-CODING-STANDARD.md`.
  - JUnit 5 + AssertJ + Mockito test infrastructure.
  - Layering rule documented and enforced (e.g., ArchUnit test or equivalent guideline).
- **Out of scope**: any feature endpoint, persistence, security beyond an open filter chain.
- **Requirements coverage**: `REQ-ARC-001`, `REQ-ARC-002`, `REQ-ARC-003`, `REQ-ARC-004`,
  `REQ-ARC-006`, `REQ-API-006`, `REQ-NFR-001`, `REQ-NFR-002`, `REQ-NFR-003`, `REQ-DEP-004`.
- **Design references**: §2 (architecture), §3 (project structure), §15 (configuration).
- **API surface**: none.
- **Dependencies**: none.

## EPIC-02 — Persistence foundation (PostgreSQL + Flyway)

- **Goal**: Provide the database schema, Flyway-managed migrations, JPA entities, and repository
  adapters for every aggregate so feature EPICs can persist data. Includes the seeded admin
  bootstrap (`REQ-USR-007`) and the seeded rate-limit configuration row.
- **Scope**:
  - PostgreSQL connection configuration (Spring properties + env vars).
  - Flyway migrations: `V001__init_schema.sql` (full schema per design §5),
    `V002__seed_admin.sql` (seeded admin from `APP_BOOTSTRAP_ADMIN_EMAIL` /
    `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH`), `V003__seed_rate_limit_config.sql`.
  - JPA entities under `infrastructure/persistence/entity/` and Spring Data JPA interfaces.
  - Repository adapters implementing the domain repository ports (`UserRepository`,
    `AgentRepository`, `ConversationRepository`, `ApiKeyRepository`,
    `RateLimitConfigRepository`).
  - Domain ↔ JPA mappers.
  - Cascade rules consistent with `REQ-USR-006` and `REQ-AGT-010`.
  - Hibernate set to `ddl-auto=validate` (Flyway owns the schema).
  - Persistence integration tests using Testcontainers PostgreSQL.
- **Out of scope**: business validation logic that lives in the domain (covered by feature EPICs).
- **Requirements coverage**: `REQ-PRS-001`, `REQ-PRS-002`, `REQ-PRS-003`, `REQ-PRS-004`,
  `REQ-PRS-005`, `REQ-USR-001`, `REQ-USR-006`, `REQ-USR-007`, `REQ-AGT-010`, `REQ-CHAT-002`,
  `REQ-CHAT-008`, `REQ-CHAT-009`.
- **Design references**: §4 (domain model), §5 (database schema), §5.1 (migrations), §5.2 (cascades).
- **API surface**: none.
- **Dependencies**: EPIC-01.

## EPIC-03 — Authentication: JWT, login, logout, password

- **Goal**: Deliver end-user authentication: sign-in, JWT issuance and validation, logout via
  denylist, self password change, and the forced password-change flow for the seeded admin.
- **Scope**:
  - `JwtTokenService` (HS256 issuance/validation) — secret from `JWT_SIGNING_SECRET` env var
    (fail-fast at startup if missing).
  - JWT claims: `sub` (email), `role`, `jti`, `iat`, `exp`. Default lifetime 30 min, configurable.
  - `JwtAuthenticationFilter` setting Spring Security `Authentication` for `Authorization: Bearer`.
  - `JwtDenylist` port + `InMemoryJwtDenylistAdapter` with scheduled sweep of expired entries.
  - `ForcedPasswordChangeFilter` blocking everything except `PUT /auth/password` and
    `POST /auth/logout` when `mustChangePassword=true`.
  - `Password` value object (policy: ≥10 chars, ≥1 uppercase, ≥1 special).
  - `PasswordHasher` port + `BcryptPasswordHasherAdapter`.
  - Login (`POST /auth/login`), logout (`POST /auth/logout`), self password change
    (`PUT /auth/password`).
  - Generic `INVALID_CREDENTIALS` error format that does not leak email existence or credential
    format.
  - Sensitive-data redaction in logs (no raw JWTs, no passwords).
- **Out of scope**: API-key authentication (EPIC-04), admin user management (EPIC-05).
- **Requirements coverage**: `REQ-AUTH-001`, `REQ-AUTH-002`, `REQ-AUTH-003`, `REQ-AUTH-004`,
  `REQ-AUTH-005`, `REQ-AUTH-006`, `REQ-AUTH-008`, `REQ-AUTH-009`, `REQ-AUTH-010`, `REQ-AUTH-011`,
  `REQ-USR-004`, `REQ-USR-007`, `REQ-SEC-001`, `REQ-SEC-002`, `REQ-SEC-003`, `REQ-SEC-004`.
- **Design references**: §8.1 filter chain, §8.2 JWT, §8.3 denylist, §8.5 password handling,
  §8.7 logging, §16.1 login, §16.4 logout.
- **API surface**: `POST /auth/login`, `POST /auth/logout`, `PUT /auth/password`.
- **Dependencies**: EPIC-01, EPIC-02.

## EPIC-04 — Authentication: API keys (machine-to-machine)

- **Goal**: Provide admin-managed API-key credentials and the request-time authentication path
  for callers using `X-Client-Id` + `X-Api-Key`. API-key callers run under a virtual `SYSTEM`
  principal with full chat capabilities and no admin or end-user visibility.
- **Scope**:
  - Domain `ApiKey` aggregate, `ClientId`, `Principal` sealed type (`UserPrincipal`,
    `SystemPrincipal`).
  - Admin endpoints: list (metadata only), create (cleartext shown once, BCrypt-hashed at rest),
    soft-revoke via `disabled` flag.
  - `ApiKeyAuthenticationFilter` running after the JWT filter; short-circuits when an
    `Authentication` is already present.
  - Authorization rules: SYSTEM = full chat; SYSTEM blocked from `/admin/**` and
    end-user-owned resources.
  - Disabled keys rejected with the same generic `INVALID_CREDENTIALS` error as JWT failures.
- **Out of scope**: end-user JWT flow (EPIC-03).
- **Requirements coverage**: `REQ-AUTH-001`, `REQ-AUTH-007`, `REQ-AUTH-009`, `REQ-AUTH-012`,
  `REQ-SEC-002`, `REQ-SEC-003`, `REQ-SEC-004`.
- **Design references**: §8.1 filter chain, §8.4 API-key auth, §8.6 authorization rules,
  §6.2.3 admin API-key endpoints.
- **API surface**: `GET /admin/api-keys`, `POST /admin/api-keys`, `PATCH /admin/api-keys/{clientId}`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03.

## EPIC-05 — User management (admin)

- **Goal**: Allow admin users to create, list, fetch, enable/disable, and delete user accounts.
  Hard-delete cascades through agents and conversations.
- **Scope**:
  - Admin endpoints: `GET /admin/users`, `POST /admin/users`, `GET /admin/users/{userId}`,
    `PATCH /admin/users/{userId}`, `DELETE /admin/users/{userId}`.
  - Validation: email format, password policy on create, role enum.
  - Conflict on duplicate email (`REQ-USR-002`).
  - Cascade delete of owned agents and conversations (DB FK cascade verified by integration test,
    `REQ-USR-006`).
  - Authorization: only `ADMIN` may call any of these endpoints; even admins cannot read other
    users' agents/conversations (`REQ-AUTH-008`).
- **Out of scope**: API-key admin endpoints (EPIC-04), self password change (EPIC-03).
- **Requirements coverage**: `REQ-USR-001`, `REQ-USR-002`, `REQ-USR-003`, `REQ-USR-005`,
  `REQ-USR-006`, `REQ-AUTH-008`, `REQ-SEC-001`, `REQ-SEC-002`.
- **Design references**: §6.2.2 admin users, §8.6 authorization rules.
- **API surface**: `/admin/users` and `/admin/users/{userId}`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03.

## EPIC-06 — Agents management (owner-scoped CRUD)

- **Goal**: Allow authenticated end-users to create, list, fetch, replace, and delete their own
  agents, with all attribute validation and team rules.
- **Scope**:
  - Domain `Agent` aggregate with all attributes per `REQ-AGT-001`: `name` (≤32, unique per
    owner), `description` (≤1024), `systemPrompt` (≤1024), `memorySize` (`[1,36]`, default 12),
    `llmModel`, `temperature`, `maxOutputTokens`, `topP`, `tools`, `enabledMcpServers`, `team`.
  - `MemorySize` value object enforcing range.
  - `Team` value object enforcing the **single-level** rule (`REQ-AGT-013`):
    no nested team, no self-delegation, no cycles.
  - Cross-owner team-membership check (`REQ-AGT-012`).
  - Reference integrity for `tools` (against the static catalog) and `enabledMcpServers`
    (against the configured MCP server set) — validated at write time, not via DB FK.
  - Endpoints: `GET /agents`, `POST /agents`, `GET /agents/{id}`, `PUT /agents/{id}`,
    `DELETE /agents/{id}` — cursor paginated; 409 codes `DUPLICATE_AGENT_NAME`,
    `NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER`.
  - Cascade on agent deletion (conversations and messages via DB cascade).
  - Mutation propagates live: agent config is read at the start of each turn (`REQ-AGT-014`) —
    enforced through the `SendMessageService` design (delivered in EPIC-11) but the agent record
    here is the source of truth.
- **Out of scope**: actual chat/turn execution (EPIC-10/11), delegation execution (EPIC-12).
- **Requirements coverage**: `REQ-AGT-001`, `REQ-AGT-002`, `REQ-AGT-003`, `REQ-AGT-004`,
  `REQ-AGT-005`, `REQ-AGT-006`, `REQ-AGT-007`, `REQ-AGT-008`, `REQ-AGT-009`, `REQ-AGT-010`,
  `REQ-AGT-012`, `REQ-AGT-013`, `REQ-AGT-014`, `REQ-CHAT-008`.
- **Design references**: §4.1 Agent, §4.2 invariants, §6.2.7 endpoints.
- **API surface**: `/agents` and `/agents/{agentId}`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03, EPIC-07 (tool catalog needed for reference
  validation), EPIC-08 (MCP catalog needed for reference validation).

## EPIC-07 — Tools catalog

- **Goal**: Stand up the static tool catalog, expose it via `GET /tools`, and ship the v1
  reference tool `AwsS3Tool`.
- **Scope**:
  - Domain `ToolDescriptor`.
  - Application `ToolCatalog` port + `ListToolsUseCase`.
  - Infrastructure `ToolCatalogAdapter` that discovers Spring beans with `@Tool`-annotated
    methods at startup; cached (catalog is static — `REQ-TOOL-001`).
  - `AwsS3Tool` Spring `@Component` adapted from `backend/docs/AwsS3Tool.java` (region from
    `app.aws.region`, default `eu-west-3`; uses application IAM credentials).
  - `GET /tools` endpoint (no pagination).
  - Validation hook for agent writes: unknown tool name → `VALIDATION_ERROR` (consumed by
    EPIC-06).
- **Out of scope**: agent-side wiring of tools to chat requests (EPIC-11).
- **Requirements coverage**: `REQ-TOOL-001`, `REQ-TOOL-002`, `REQ-TOOL-003`, `REQ-TOOL-004`,
  `REQ-TOOL-005`.
- **Design references**: §13 tools, §6.2.5.
- **API surface**: `GET /tools`.
- **Dependencies**: EPIC-01.

## EPIC-08 — MCP servers integration

- **Goal**: Configure the `brave-search` and `filesystem` MCP servers, expose the configured
  list via `GET /mcp-servers`, and enforce per-user filesystem scoping.
- **Scope**:
  - `application.yaml` MCP configuration via Spring AI per design §14 — `BRAVE_API_KEY` env
    var consumed by `brave-search`.
  - `McpServerCatalog` port + `ListMcpServersUseCase` + `McpServerCatalogAdapter` reading from
    Spring AI's MCP configuration model.
  - `FilesystemMcpUserScope` port + `FilesystemMcpUserScopeAdapter` resolving the per-user root
    `{base}/users/{userId}` (created on demand at first use, `REQ-MCP-005`). Approach choice
    between "per-user MCP process" and "shared process with path rewriting" deferred to TBD-2 in
    the design.
  - `GET /mcp-servers` endpoint (no pagination).
  - Validation hook for agent writes: unknown MCP server name → `VALIDATION_ERROR` (consumed by
    EPIC-06).
  - MCP runtime errors mapped to `MCP_SERVER_ERROR` 502.
- **Out of scope**: per-agent MCP wiring during chat turns (EPIC-11).
- **Requirements coverage**: `REQ-MCP-001`, `REQ-MCP-002`, `REQ-MCP-003`, `REQ-MCP-004`,
  `REQ-MCP-005`, `REQ-MCP-006`, `REQ-AGT-009`.
- **Design references**: §14 MCP servers, §6.2.6.
- **API surface**: `GET /mcp-servers`.
- **Dependencies**: EPIC-01.

## EPIC-09 — LLM provider integration (OpenAI)

- **Goal**: Provide the provider-agnostic chat-completion port and the OpenAI adapter (default
  model `gpt-4o-mini`), with synchronous and streaming entry points and proper error mapping.
- **Scope**:
  - Application `LlmChatClient` port (sync `call` + reactive `stream`).
  - `ChatRequest` / `ChatChunk` / `ChatResult` records carrying model, sampling parameters,
    system prompt, message history, attached tool descriptors, and MCP enablement flags.
  - Infrastructure `OpenAiChatClientAdapter` translating `ChatRequest` into Spring AI's
    `ChatOptions` and messages.
  - Credentials from `OPENAI_API_KEY` env var (never logged).
  - Default model from `app.llm.openai.default-model`; per-agent overrides take precedence.
  - Error mapping (`REQ-LLM-005`): provider 4xx/5xx/timeouts → `ExternalServiceException` →
    502 `LLM_UNAVAILABLE`. Provider 429 also surfaces as 502, not as 429.
  - Adapter unit-testable behind `LlmChatClient` (WireMock against an OpenAI-shaped fake).
- **Out of scope**: chat orchestration consuming this port (EPIC-11), delegation (EPIC-12).
- **Requirements coverage**: `REQ-LLM-001`, `REQ-LLM-002`, `REQ-LLM-003`, `REQ-LLM-004`,
  `REQ-LLM-005`, `REQ-ARC-005`, `REQ-SEC-003`, `REQ-SEC-004`.
- **Design references**: §12 LLM integration.
- **API surface**: none directly.
- **Dependencies**: EPIC-01.

## EPIC-10 — Conversations & messages (non-streaming surface)

- **Goal**: Deliver the non-streaming half of the chat surface: create / list / get / edit-title
  / delete a conversation, list messages, and the persistence machinery for messages — without
  yet implementing the SSE send path.
- **Scope**:
  - Domain `Conversation`, `Message`, `MessageRole` (`USER` | `ASSISTANT` only — `REQ-CHAT-012`),
    `Title` value object (≤32 chars, default `chat-<uuid>` rule).
  - Endpoints: `GET /conversations`, `POST /conversations`, `GET /conversations/{id}`,
    `PATCH /conversations/{id}` (title edit), `DELETE /conversations/{id}`,
    `GET /conversations/{id}/messages`.
  - Owner-scoped access for both `USER` and `SYSTEM` principals (`REQ-CHAT-007`,
    `REQ-AUTH-007`).
  - Cursor-paginated listing (DESC for conversations, chronological ASC for messages).
  - 64-message cap enforced as a domain invariant (`REQ-CHAT-010`) and as a DB check.
  - Cascade: deleting a conversation deletes messages.
- **Out of scope**: send-message endpoint (EPIC-11).
- **Requirements coverage**: `REQ-CHAT-001`, `REQ-CHAT-002`, `REQ-CHAT-003`, `REQ-CHAT-004`,
  `REQ-CHAT-005`, `REQ-CHAT-007`, `REQ-CHAT-008`, `REQ-CHAT-009`, `REQ-CHAT-010`, `REQ-CHAT-011`,
  `REQ-CHAT-012`, `REQ-API-005`.
- **Design references**: §4.1 Conversation/Message, §4.3 lifecycle, §6.2.8 endpoints.
- **API surface**: `/conversations` and `/conversations/{conversationId}` (excluding the SSE
  send path).
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03, EPIC-06.

## EPIC-11 — SSE streaming chat

- **Goal**: Deliver the streaming send-message endpoint that ties together agent configuration,
  memory window, LLM call, persistence, and SSE frame emission.
- **Scope**:
  - `POST /conversations/{id}/messages` returning `text/event-stream` (406 if not negotiated).
  - `SendMessageService` orchestration:
    1. Verify ownership and `messageCount<64`.
    2. Persist USER message, bump count, derive title on first message.
    3. Emit `started` SSE frame.
    4. Build memory window (last `memorySize` USER/ASSISTANT messages — `REQ-AGT-005`).
    5. Build `ChatRequest` from **current** agent config (live mutation per `REQ-AGT-014`),
       attach selected tools (EPIC-07) and enabled MCP servers (EPIC-08).
    6. Call `llmChatClient.stream(request)`; emit `delta` frames per token chunk.
    7. On completion, persist ASSISTANT message, bump count, emit `completed` frame.
    8. On client cancel/error, dispose the upstream Flux, emit `error`, close the emitter.
  - Bridging Spring AI's `Flux<ChatResponse>` to Spring MVC `SseEmitter`.
  - Tool-call requests/results NOT persisted as messages (`REQ-CHAT-012`).
  - Content-length cap (≤1024) enforced; 409 `CONVERSATION_FULL` at cap.
- **Out of scope**: agent-team delegation (EPIC-12).
- **Requirements coverage**: `REQ-AGT-005`, `REQ-AGT-014`, `REQ-CHAT-006`, `REQ-CHAT-009`,
  `REQ-CHAT-010`, `REQ-CHAT-012`, `REQ-STR-001`, `REQ-STR-002`, `REQ-STR-003`, `REQ-STR-004`,
  `REQ-NFR-004`, `REQ-NFR-005`.
- **Design references**: §7 streaming, §16.2 send-message sequence.
- **API surface**: `POST /conversations/{conversationId}/messages` (SSE).
- **Dependencies**: EPIC-09, EPIC-10, EPIC-07, EPIC-08.

## EPIC-12 — Agent team delegation

- **Goal**: Wire the `delegate(...)` capability so an agent A can dispatch a sub-task to a team
  member B during its turn, per the constrained execution model.
- **Scope**:
  - `DelegationService` invoked from within `SendMessageService`.
  - Implementation choice between a Spring AI tool and a server-side post-step (TBD-3 in design).
  - Constraints enforced (`REQ-AGT-015`):
    - Only the delegated task is passed to B (no parent history).
    - B's exchanges with the LLM are NOT persisted anywhere.
    - End-user only sees A's aggregated answer (no intermediate frames).
    - B's call does NOT count against the parent conversation's 64-message cap.
  - Agent-team config already validated by EPIC-06 (single-level, same owner).
- **Out of scope**: changes to the SSE frame protocol; nested delegation (forbidden).
- **Requirements coverage**: `REQ-AGT-011`, `REQ-AGT-015`, `REQ-CHAT-010` (consistency).
- **Design references**: §16.3 delegation sequence, §19 TBD-3.
- **API surface**: none directly (effects flow through the existing SSE endpoint).
- **Dependencies**: EPIC-11.

## EPIC-13 — Rate limiting (Bucket4j)

- **Goal**: Protect the API with a global Bucket4j rate limiter, expose admin endpoints to view
  and update limits at runtime, and emit `429` with `Retry-After` when buckets are exhausted.
- **Scope**:
  - `RateLimitFilter` at the very top of the security chain (counts unauthenticated traffic too).
  - Two stacked global buckets: per-minute (default 10) and per-hour (default 50). Most
    restrictive 429s first.
  - Live reconfiguration: `RateLimitConfigRepository` re-read on update or refill boundary.
  - Admin endpoints: `GET /admin/rate-limit`, `PUT /admin/rate-limit`.
  - 429 response uses the standard `ProblemDetails` shape with `code=RATE_LIMITED` and a
    `Retry-After` header.
- **Out of scope**: per-IP or per-user limits (explicitly excluded by `REQ-RL-003`).
- **Requirements coverage**: `REQ-RL-001`, `REQ-RL-002`, `REQ-RL-003`, `REQ-RL-004`, `REQ-RL-005`.
- **Design references**: §11 rate limiting, §6.2.4 admin endpoints, §8.1 filter chain.
- **API surface**: `GET /admin/rate-limit`, `PUT /admin/rate-limit`.
- **Dependencies**: EPIC-02 (config row), EPIC-03 (admin auth).

## EPIC-14 — Cross-cutting API concerns (errors, paging, CORS)

- **Goal**: Ship the shared API plumbing every controller relies on: the `GlobalExceptionHandler`,
  the RFC 7807 problem-details mapping, the cursor-paging helper, and CORS configuration.
- **Scope**:
  - `BusinessException` hierarchy in the domain (`ValidationException`, `NotFoundException`,
    `ConflictException`, `ForbiddenException`) with concrete subclasses per bounded context.
  - `UseCaseExecutionException` (application), `ExternalServiceException` /
    `DatabaseAccessException` (infrastructure).
  - `@RestControllerAdvice` `GlobalExceptionHandler` mapping every exception to the
    `ProblemDetails` schema and the documented `code` values; no stack-trace leaks; handles
    Spring exceptions (`MethodArgumentNotValidException`, `HttpRequestMethodNotSupportedException`,
    etc.).
  - `CursorCodec` + `PageDto<T>` helper used uniformly across list endpoints (default 20,
    max 100, opaque base64-encoded payload over keyset pagination).
  - CORS configuration via `app.cors.allowed-origins` (`REQ-API-003`).
  - Sensitive-data log redaction (Logback converter masking token-shaped substrings).
- **Out of scope**: any business endpoint (covered by their respective EPICs).
- **Requirements coverage**: `REQ-API-003`, `REQ-API-004`, `REQ-API-005`, `REQ-ARC-007`,
  `REQ-SEC-004`.
- **Design references**: §6.1 conventions, §9 error handling, §10 pagination, §15 configuration,
  §8.7 logging.
- **API surface**: applies to every endpoint.
- **Dependencies**: EPIC-01. Most other EPICs depend on this for error semantics; deliver early.

## EPIC-15 — Observability & health

- **Goal**: Provide structured logging with correlation IDs, configurable log levels, and a
  health/readiness probe.
- **Scope**:
  - Logback JSON layout with correlation/request-id MDC field.
  - Log-level configuration per package via Spring properties.
  - Spring Boot Actuator health endpoint at `GET /actuator/health` (outside `/api/v1`).
  - Sensitive-data redaction confirmed across all log appenders.
- **Out of scope**: full metrics / tracing pipeline (not a v1 requirement).
- **Requirements coverage**: `REQ-OBS-001`, `REQ-OBS-002`, `REQ-OBS-003`, `REQ-SEC-004`.
- **Design references**: §6.4 health, §8.7 logging.
- **API surface**: `GET /actuator/health`.
- **Dependencies**: EPIC-01.

## EPIC-16 — Build, packaging & AWS deployment

- **Goal**: Produce a single runnable Spring Boot fat JAR, document local-run and AWS-EC2
  deployment, and verify infra/business separation.
- **Scope**:
  - Maven build producing the fat JAR (`./mvnw clean package`).
  - `application.yaml` profiles (default + AWS-friendly overrides).
  - Local run instructions against a local PostgreSQL (no Docker).
  - AWS-EC2 deployment: JAR copy, systemd service unit, RDS PostgreSQL connection, S3
    permissions for `AwsS3Tool` via instance role.
  - Verification that the same JAR runs locally and in AWS (no AWS SDK calls in business logic).
  - Health probe wired for ELB/monitoring.
- **Out of scope**: EKS/ECS/Fargate, Docker, HTTPS termination beyond a deployment note for prod.
- **Requirements coverage**: `REQ-DEP-001`, `REQ-DEP-002`, `REQ-DEP-003`, `REQ-DEP-004`,
  `REQ-API-001`, `REQ-NFR-003`.
- **Design references**: §17 build & deployment.
- **API surface**: none.
- **Dependencies**: every other EPIC reaches a runnable state first.

---

## Notes

- **Open design items** (TBD-1 multi-node JWT denylist, TBD-2 filesystem MCP wiring,
  TBD-3 delegation execution mechanism, TBD-4 sampling-parameter validation ranges) are
  **internal to their owning EPICs** (EPIC-03, EPIC-08, EPIC-12, EPIC-06 respectively) and do
  not surface in the API contract.
- **Cross-cutting EPIC-14 should ideally be implemented incrementally alongside the first
  feature EPICs** rather than treated as a strict prerequisite — a minimal `GlobalExceptionHandler`
  must exist before the first endpoint is shipped, but the full set of error codes grows as
  feature EPICs land.
- **Testing** is part of every EPIC, not a separate one. Each EPIC includes domain unit tests
  (`JUnit 5 + AssertJ`), application tests (`Mockito`), and infrastructure tests (`Testcontainers`,
  `MockMvc`, `WireMock`) as relevant per `REQ-NFR-002`.
