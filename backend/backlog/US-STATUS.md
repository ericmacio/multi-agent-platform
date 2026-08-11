# US-STATUS.md — Backend user-story status tracker

This document is the **single source of truth** for the implementation status of every backend
user story. It complements `EPICS.md` (EPIC-level breakdown) and the per-EPIC story files
`EPIC-<ref>-US.md` (detailed acceptance criteria).

## Maintenance rules

This file MUST be kept up to date whenever:

1. A new EPIC's user stories are created → append a new section with the EPIC's stories,
   each row in `Draft` status.
2. A user story changes status → update its `Status` column.
3. A user story is added, removed, split, or merged within an existing EPIC → reflect the
   change in this file at the same time as the change to the corresponding `EPIC-<ref>-US.md`.

The four allowed status values are:

| Status        | Meaning                                                                    |
|---------------|----------------------------------------------------------------------------|
| `Draft`       | Story exists in `EPIC-<ref>-US.md`; not yet ready for implementation.      |
| `Ready`       | Acceptance criteria reviewed; story is ready to be picked up.              |
| `In progress` | Implementation has started.                                                |
| `Done`        | All acceptance criteria met; tests green; merged into the working branch.  |

A story moves to `Done` only when the acceptance criteria from its `EPIC-<ref>-US.md` entry
are fully satisfied, including any explicit "verified by …" test artifacts.

## Conventions

- **ID format**: `US-<epic>-<nnn>` (matches `EPIC-<epic>-US.md`).
- **Purpose**: a one-line summary; the authoritative description lives in `EPIC-<epic>-US.md`.
- **Priority**: `MUST`, `SHOULD`, `COULD` — copied from the story file.
- One section per EPIC.
- Stories appear in build order within their EPIC.

---

## EPIC-01 — Project foundation & hexagonal skeleton

| ID         | Purpose                                                              | Priority | Status |
|------------|----------------------------------------------------------------------|----------|--------|
| US-01-001  | Maven project producing a runnable Spring Boot fat-JAR                | MUST     | Done   |
| US-01-002  | Hexagonal package skeleton (`domain` / `application` / `infrastructure`) with bounded-context stubs | MUST | Done |
| US-01-003  | Spring Boot `Application` entry point + context smoke test            | MUST     | Done   |
| US-01-004  | Typed `ApplicationProperties` record bound to the `app.*` config tree | MUST     | Done   |
| US-01-005  | Centralized `/api/v1` base path applied to every `@RestController`    | MUST     | Done   |
| US-01-006  | Spring MVC + Spring Security skeleton with open chain and CORS        | MUST     | Done   |
| US-01-007  | Test infrastructure (JUnit 5 + AssertJ + Mockito) with three exemplar tests | MUST | Done |
| US-01-008  | ArchUnit tests enforcing the hexagonal layering rule                  | MUST     | Done   |

---

## EPIC-02 — Persistence foundation (PostgreSQL + Flyway)

| ID         | Purpose                                                                | Priority | Status |
|------------|------------------------------------------------------------------------|----------|--------|
| US-02-001  | Persistence dependencies & Spring Data JPA / Flyway wiring             | MUST     | Done   |
| US-02-002  | Local PostgreSQL integration-test infrastructure                       | MUST     | Done   |
| US-02-003  | Init schema migration `V001__init_schema.sql`                          | MUST     | Done   |
| US-02-004  | Seed migrations `V002__seed_admin.sql` + `V003__seed_rate_limit_config.sql` | MUST | Done |
| US-02-005  | JPA entity classes for every aggregate (`ddl-auto=validate` contract)  | MUST     | Done   |
| US-02-006  | Spring Data JPA repository interfaces                                  | MUST     | Done   |
| US-02-007  | Cascade-rule integration test (`REQ-USR-006` / `REQ-AGT-010`)          | MUST     | Done   |

---

## EPIC-03 — Authentication: JWT, login, logout, password

| ID         | Purpose                                                                  | Priority | Status |
|------------|--------------------------------------------------------------------------|----------|--------|
| US-03-001  | Minimal `GlobalExceptionHandler`, problem-details mapper & log redaction | MUST     | Done   |
| US-03-002  | `User` domain aggregate, value objects & repository port                 | MUST     | Done   |
| US-03-003  | `UserRepository` JPA adapter + domain ↔ JPA mapper                       | MUST     | Done   |
| US-03-004  | `PasswordHasher` port + BCrypt adapter                                   | MUST     | Done   |
| US-03-005  | `JwtTokenService` port + JJWT (HS256) adapter                            | MUST     | Done   |
| US-03-006  | `JwtDenylist` port + in-memory adapter with scheduled sweep              | MUST     | Done   |
| US-03-007  | `JwtAuthenticationFilter` & Spring Security wiring                       | MUST     | Done   |
| US-03-008  | `ForcedPasswordChangeFilter`                                             | MUST     | Done   |
| US-03-009  | Login use case & `POST /auth/login`                                      | MUST     | Done   |
| US-03-010  | Logout use case & `POST /auth/logout`                                    | MUST     | Done   |
| US-03-011  | Change-own-password use case & `PUT /auth/password`                      | MUST     | Done   |

---

## Code Review #1 — HIGH-finding remediations

> Source: `backend/analysis/CODE-REVIEW-1.md`. Detailed acceptance criteria live in
> `EPIC-CR1-US.md`. These stories remediate HIGH findings on top of
> EPIC-01 / EPIC-02 / EPIC-03 and MUST land before EPIC-04 / EPIC-05 extend the
> `User` aggregate.

| ID          | Purpose                                                                       | Priority | Status |
|-------------|-------------------------------------------------------------------------------|----------|--------|
| US-CR1-001  | Canonicalize email to lowercase (domain + DB) to prevent duplicate accounts   | MUST     | Done   |
| US-CR1-002  | Remove `PingController` from production classpath                             | MUST     | Done   |
| US-CR1-003  | Inject the `Clock` bean into `JjwtTokenServiceAdapter`                        | MUST     | Done   |

---

## EPIC-04 — Authentication: API keys (machine-to-machine)

| ID         | Purpose                                                                  | Priority | Status |
|------------|--------------------------------------------------------------------------|----------|--------|
| US-04-001  | `SystemPrincipal` completes the `Principal` sealed hierarchy             | MUST     | Done   |
| US-04-002  | `ApiKey` domain aggregate & repository port (`ClientId` shipped in US-04-001) | MUST | Done   |
| US-04-003  | `ApiKeyRepository` JPA adapter + domain ↔ JPA mapper                     | MUST     | Done   |
| US-04-004  | `ApiKeyGenerator` + `ApiKeyHasher` ports & adapters                      | MUST     | Done   |
| US-04-005  | Minimal cursor-pagination plumbing (`CursorCodec` + `PageDto<T>`)        | MUST     | Done   |
| US-04-006  | Create-API-key use case & `POST /admin/api-keys`                         | MUST     | Done   |
| US-04-007  | List-API-keys use case & `GET /admin/api-keys`                           | MUST     | Done   |
| US-04-008  | Disable/re-enable-API-key use case & `PATCH /admin/api-keys/{clientId}`  | MUST     | Done   |
| US-04-009  | `ApiKeyAuthenticationFilter` & Spring Security wiring                    | MUST     | Done   |

---

## EPIC-05 — User management (admin)

| ID         | Purpose                                                                  | Priority | Status |
|------------|--------------------------------------------------------------------------|----------|--------|
| US-05-001  | `User` aggregate `withDisabled` + domain exceptions + repository port    | MUST     | Done   |
| US-05-002  | `UserRepository` JPA adapter extensions (existsByEmail, listAll, delete) | MUST     | Done   |
| US-05-003  | `ConflictException` handler in `GlobalExceptionHandler` (409 `CONFLICT`) | MUST     | Done   |
| US-05-004  | Create-user use case & `POST /admin/users`                               | MUST     | Done   |
| US-05-005  | List-users use case & `GET /admin/users`                                 | MUST     | Done   |
| US-05-006  | Get-user use case & `GET /admin/users/{userId}`                          | MUST     | Done   |
| US-05-007  | Enable/disable-user use case & `PATCH /admin/users/{userId}`             | MUST     | Done   |
| US-05-008  | Delete-user use case & `DELETE /admin/users/{userId}`                    | MUST     | Done   |

---

## EPIC-06 — Agents management (owner-scoped CRUD)

| ID         | Purpose                                                                            | Priority | Status |
|------------|------------------------------------------------------------------------------------|----------|--------|
| US-06-001  | `Agent` domain: aggregate, value objects, conflict exceptions, repository port     | MUST     | Done   |
| US-06-002  | `AgentRepository` JPA adapter + domain ↔ JPA mapper                                | MUST     | Done   |
| US-06-003  | `GlobalExceptionHandler` extensions for the 3 agent-conflict codes                 | MUST     | Done   |
| US-06-004  | Create-agent use case & `POST /agents` (+ `/agents/**` URL guard against SYSTEM)   | MUST     | Done   |
| US-06-005  | List-agents use case & `GET /agents`                                               | MUST     | Done   |
| US-06-006  | Get-agent use case & `GET /agents/{agentId}`                                       | MUST     | Done   |
| US-06-007  | Replace-agent use case & `PUT /agents/{agentId}`                                   | MUST     | Done   |
| US-06-008  | Delete-agent use case & `DELETE /agents/{agentId}`                                 | MUST     | Done   |

---

## EPIC-07 — Tools catalog

| ID         | Purpose                                                                            | Priority | Status |
|------------|------------------------------------------------------------------------------------|----------|--------|
| US-07-001  | `ToolDescriptor` + `@ToolGroup` + `ToolCatalog` port + `ListToolsUseCase`          | MUST     | Done   |
| US-07-002  | `ToolCatalogAdapter` — Spring bean scanner with startup caching                    | MUST     | Done   |
| US-07-003  | `AwsS3Tool` Spring `@Component` adapted from `backend/docs/AwsS3Tool.java`         | MUST     | Done   |
| US-07-004  | `ToolsController` & `GET /tools` REST adapter                                      | MUST     | Done   |
| US-07-005  | `CatalogToolReferenceValidator` replaces EPIC-06 `NoopToolReferenceValidator`      | MUST     | Done   |

---

## EPIC-08 — MCP servers integration

| ID         | Purpose                                                                                         | Priority | Status |
|------------|-------------------------------------------------------------------------------------------------|----------|--------|
| US-08-001  | `McpServerName` + `UnknownMcpServerException` + `McpServerCatalog` port + `ListMcpServersUseCase` | MUST   | Done   |
| US-08-002  | `application.yaml` MCP configuration + `app.mcp.filesystem.base` property binding               | MUST     | Done   |
| US-08-003  | `McpServerCatalogAdapter` — Spring AI configuration discovery with startup caching              | MUST     | Done   |
| US-08-004  | `FilesystemMcpUserScope` port + `FilesystemMcpUserScopeAdapter` (per-user root on-demand)       | MUST     | Done   |
| US-08-005  | `McpServersController` & `GET /mcp-servers` REST adapter                                        | MUST     | Done   |
| US-08-006  | `CatalogMcpReferenceValidator` replaces EPIC-06 `NoopMcpReferenceValidator`                     | MUST     | Done   |
| US-08-007  | `McpServerException` + `MCP_SERVER_ERROR` 502 mapping in `GlobalExceptionHandler`               | MUST     | Done   |

---

## EPIC-09 — LLM provider integration (OpenAI)

| ID         | Purpose                                                                                                                       | Priority | Status |
|------------|-------------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-09-001  | `LlmChatClient` port + `ChatRequest` / `ChatChunk` / `ChatResult` records (application layer)                                  | MUST     | Done   |
| US-09-002  | `application.yaml` LLM configuration + `app.llm.openai.*` property binding + fail-fast on `OPENAI_API_KEY`                     | MUST     | Done   |
| US-09-003  | `LlmUnavailableException` + `LLM_UNAVAILABLE` 502 mapping in `GlobalExceptionHandler`                                          | MUST     | Done   |
| US-09-004  | `OpenAiChatClientAdapter` — synchronous `call(ChatRequest)` + Spring AI `ChatOptions` translation + provider error mapping     | MUST     | Done   |
| US-09-005  | `OpenAiChatClientAdapter` — streaming `stream(ChatRequest)` + reactive error mapping + client-cancel handling                  | MUST     | Done   |

---

## EPIC-10 — Conversations & messages (non-streaming surface)

| ID         | Purpose                                                                                                                | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-10-001  | `Conversation` / `Message` domain — aggregates, value objects, `ConversationOwner` sealed type, exceptions, repository port | MUST | Done   |
| US-10-002  | Flyway `V005__conversation_owner_split.sql` — replace `conversations.owner_id` with mutually-exclusive `owner_user_id` / `owner_client_id` | MUST | Done  |
| US-10-003  | `ConversationJpa` rework + `ConversationRepository` JPA adapter + domain ↔ JPA mapper                                  | MUST     | Done   |
| US-10-004  | `ConversationFullException` + `CONVERSATION_FULL` 409 mapping in `GlobalExceptionHandler`                              | MUST     | Done   |
| US-10-005  | `StartConversationUseCase` + `POST /conversations` (+ `/conversations/**` URL guard for STANDARD / ADMIN / SYSTEM)     | MUST     | Done   |
| US-10-006  | `ListConversationsUseCase` + `GET /conversations` (with optional `agentId` filter)                                     | MUST     | Done   |
| US-10-007  | `GetConversationUseCase` + `GET /conversations/{conversationId}`                                                       | MUST     | Done   |
| US-10-008  | `EditConversationTitleUseCase` + `PATCH /conversations/{conversationId}`                                               | MUST     | Done   |
| US-10-009  | `DeleteConversationUseCase` + `DELETE /conversations/{conversationId}`                                                 | MUST     | Done   |
| US-10-010  | `ListMessagesUseCase` + `GET /conversations/{conversationId}/messages`                                                 | MUST     | Done   |

---

## EPIC-11 — SSE streaming chat

| ID         | Purpose                                                                                                                | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-11-001  | `SendMessageUseCase` port + `TurnEvent` sealed type (`Started` / `Delta` / `Completed` / `Error`)                       | MUST     | Done   |
| US-11-002  | `MemoryWindowAssembler` — last-N USER/ASSISTANT message slice from `ConversationRepository.findLastN`                 | MUST     | Done   |
| US-11-003  | `ChatRequestBuilder` — Agent + memory + new user message → `ChatRequest` (tool + MCP wiring, REQ-AGT-014 live)         | MUST     | Done   |
| US-11-004  | `SendMessageService` — orchestration: ownership, cap, user persist, title, LLM stream, assistant persist, error mapping | MUST     | Done   |
| US-11-005  | `POST /conversations/{id}/messages` REST adapter — `SseEmitter` bridge, Accept negotiation, content validation, 409   | MUST     | Done   |
| US-11-006  | Client cancellation (REQ-STR-003) — `SseEmitter.onCompletion/onTimeout` → upstream `Disposable.dispose()`             | MUST     | Done   |
| US-11-007  | End-to-end WireMock LLM integration test — golden path, mid-stream error, cancellation, 64-cap, 406, content-cap      | MUST     | Done   |

---

## EPIC-12 — Agent team delegation

| ID         | Purpose                                                                                                                | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-12-001  | `DelegationService` port + `DelegationCommand` / `DelegationResult` records + runtime team-membership invariant       | MUST     | Done   |
| US-12-002  | `DelegationServiceImpl` — sync `LlmChatClient.call(...)` against a minimal B-only `ChatRequest`; no persistence       | MUST     | Done   |
| US-12-003  | `DelegateTool` Spring AI `@Tool` bean + `ChatRequestBuilder` integration (registered iff `agent.team` is non-empty)   | MUST     | Done   |
| US-12-004  | End-to-end WireMock integration test — golden path, persistence invariants, runtime rejection, sub-agent error isolation | MUST  | Done   |

---

## EPIC-13 — Rate limiting (Bucket4j)

| ID         | Purpose                                                                                                                       | Priority | Status |
|------------|-------------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-13-001  | Bucket4j dependency + `RateLimitConfig` aggregate + `RateLimitConfigRepository` port + domain tests                            | MUST     | Done   |
| US-13-002  | `RateLimitConfigRepository` JPA adapter + domain ↔ JPA mapper + Postgres integration test                                      | MUST     | Done   |
| US-13-003  | `GetRateLimitConfigUseCase` + `UpdateRateLimitConfigUseCase` + `RateLimitConfigChangeListener` seam + tests                    | MUST     | Done   |
| US-13-004  | `RateLimitGate` port + `Bucket4jRateLimitGate` adapter (two stacked buckets, live rebuild) + `RateLimitedException`            | MUST     | Done   |
| US-13-005  | `RateLimitFilter` (top-of-chain `OncePerRequestFilter`) + Spring Security wiring + 429 mapping with `Retry-After` header        | MUST     | Done   |
| US-13-006  | Admin REST endpoints — `GET /admin/rate-limit`, `PUT /admin/rate-limit` (ADMIN-only)                                            | MUST     | Done   |
| US-13-007  | End-to-end integration test — eviction with virtualized clock, 429 envelope, live admin update, actuator excluded             | MUST     | Done   |

---

## EPIC-14 — Cross-cutting API concerns (errors, paging, CORS)

| ID         | Purpose                                                                                                                       | Priority | Status |
|------------|-------------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-14-001  | `UseCaseExecutionException` (application) + `GlobalExceptionHandler` 500 `INTERNAL_ERROR` branch + use-case wrapping convention | MUST     | Done   |
| US-14-002  | `DatabaseAccessException` (infrastructure) + Spring `DataAccessException` translation at the persistence boundary + 500 handler | MUST     | Done   |
| US-14-003  | OpenAPI ↔ `ProblemDetails.code` parity regression test                                                                          | MUST     | Done   |
| US-14-004  | CORS preflight + exposed-headers regression integration test                                                                    | MUST     | Done   |
| US-14-005  | Pagination contract regression test — envelope shape, defaults, opacity, malformed-cursor 400                                   | MUST     | Done   |

---

## EPIC-15 — Observability & health

| ID         | Purpose                                                                                                                       | Priority | Status |
|------------|-------------------------------------------------------------------------------------------------------------------------------|----------|--------|
| US-15-001  | Spring Boot Actuator dependency + `GET /actuator/health` exposed outside `/api/v1`                                              | SHOULD   | Done   |
| US-15-002  | `CorrelationIdFilter` — generate / propagate `X-Correlation-Id`, populate MDC, expose on response                               | SHOULD   | Done   |
| US-15-003  | JSON Logback encoder (`LoggingEventCompositeJsonEncoder`) preserving `%redactedMsg` + per-package log-level smoke test           | SHOULD   | Done   |
| US-15-004  | Sensitive-data redaction regression integration test across every appender                                                      | MUST     | Done   |

---

## Aggregate progress

| EPIC          | Title                                          |  Total | Draft  | Ready | In progress | Done |
|---------------|------------------------------------------------|-------:|-------:|------:|------------:|-----:|
| EPIC-01       | Project foundation & hexagonal skeleton        |      8 |      0 |     0 |           0 |    8 |
| EPIC-02       | Persistence foundation (PostgreSQL + Flyway)   |      7 |      0 |     0 |           0 |    7 |
| EPIC-03       | Authentication: JWT, login, logout, password   |     11 |      0 |     0 |           0 |   11 |
| Code Review #1 | HIGH-finding remediations                     |      3 |      0 |     0 |           0 |    3 |
| EPIC-04       | Authentication: API keys (machine-to-machine)  |      9 |      0 |     0 |           0 |    9 |
| EPIC-05       | User management (admin)                        |      8 |      0 |     0 |           0 |    8 |
| EPIC-06       | Agents management (owner-scoped CRUD)          |      8 |      0 |     0 |           0 |    8 |
| EPIC-07       | Tools catalog                                  |      5 |      0 |     0 |           0 |    5 |
| EPIC-08       | MCP servers integration                        |      7 |      0 |     0 |           0 |    7 |
| EPIC-09       | LLM provider integration (OpenAI)              |      5 |      0 |     0 |           0 |    5 |
| EPIC-10       | Conversations & messages (non-streaming)       |     10 |      0 |     0 |           0 |   10 |
| EPIC-11       | SSE streaming chat                             |      7 |      0 |     0 |           0 |    7 |
| EPIC-12       | Agent team delegation                          |      4 |      0 |     0 |           0 |    4 |
| EPIC-13       | Rate limiting (Bucket4j)                       |      7 |      0 |     0 |           0 |    7 |
| EPIC-14       | Cross-cutting API concerns (errors, paging, CORS) |   5 |      0 |     0 |           0 |    5 |
| EPIC-15       | Observability & health                         |      4 |      0 |     0 |           0 |    4 |
| **All**       |                                                | **108**|  **0** | **0** |       **0** |**108**|

> Stories for EPIC-16 will be appended to this file as its
> `EPIC-16-US.md` file is produced.
