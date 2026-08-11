# REQS.md — Backend Requirements

This document lists the requirements for the backend of the multi-agent platform.
It is derived from `backend/docs/SPECS.md` and the global `docs/SPECS.md`.

> Scope note: per the backend workflow, this document MUST NOT define any REST endpoints.
> Endpoints are specified in `design/SW-DESIGN.md`.

## Conventions

- **ID format**: `REQ-<area>-<nnn>` (e.g., `REQ-AGT-001`).
- **Priority**: `MUST` (required for v1), `SHOULD` (strongly desired), `COULD` (nice-to-have).
- **Status**: `Draft` for all entries in this initial version.
- Each requirement has a single, testable statement and one or more acceptance criteria where useful.

## Areas

| Code   | Area                                |
|--------|-------------------------------------|
| GEN    | General / cross-cutting             |
| ARC    | Architecture                        |
| USR    | User management                     |
| AUTH   | Authentication & authorization      |
| SEC    | Security (passwords, secrets)       |
| AGT    | Agents                              |
| TOOL   | Tools                               |
| MCP    | MCP servers                         |
| LLM    | LLM provider                        |
| CHAT   | Chat / conversations                |
| STR    | Streaming                           |
| PRS    | Persistence / database              |
| RL     | Rate limiting                       |
| API    | API exposure / CORS                 |
| OBS    | Observability / logging             |
| NFR    | Non-functional                      |
| DEP    | Deployment                          |

---

## 1. General (GEN)

### REQ-GEN-001 — Backend purpose
- **Priority**: MUST
- **Statement**: The backend SHALL expose a Web Service API that allows creating, configuring, and managing AI agents, and conducting chat conversations with them.
- **Source**: SPECS §Purpose.

### REQ-GEN-002 — Multi-user platform
- **Priority**: MUST
- **Statement**: The backend SHALL support multiple distinct users; each user’s data SHALL be isolated from other users.
- **Acceptance**: A user MUST NOT see, modify, or use any agent or conversation that belongs to another user.

### REQ-GEN-003 — Frontend & external clients
- **Priority**: MUST
- **Statement**: The API SHALL be consumable both by the project’s ReactJS frontend and by external programmatic clients.

---

## 2. Architecture (ARC)

### REQ-ARC-001 — Technology stack
- **Priority**: MUST
- **Statement**: The backend SHALL be implemented with **Spring Boot 4.0.6**, **Java 17**, and **Spring AI 1.1.0**.

### REQ-ARC-002 — Hexagonal architecture (preferred)
- **Priority**: SHOULD
- **Statement**: The solution SHOULD adopt a hexagonal architecture (domain / application / infrastructure) with the domain free of framework dependencies. An alternative MAY be proposed if better justified.

### REQ-ARC-003 — Separation of concerns
- **Priority**: MUST
- **Statement**: Business rules SHALL be isolated from technical framework concerns (Spring, persistence, HTTP, LLM client).

### REQ-ARC-004 — Simplicity
- **Priority**: MUST
- **Statement**: Project structure and architecture SHALL remain simple and readable; gratuitous abstractions SHALL be avoided.

### REQ-ARC-005 — Provider-agnostic LLM design
- **Priority**: MUST
- **Statement**: The LLM access layer SHALL be designed so that additional LLM providers can be added later without changes to domain or use-case code.

### REQ-ARC-006 — Coding standards
- **Priority**: MUST
- **Statement**: Implementation SHALL follow `backend/docs/JAVA-CODING-STANDARD.md` (functional style, immutability, records for DTOs, constructor injection, Lombok where records do not fit, JUnit 5 + AssertJ + Mockito).

### REQ-ARC-007 — Exception handling standards
- **Priority**: MUST
- **Statement**: Exception handling SHALL follow `backend/docs/EXCEPTIONS.md`: layered typology (business / application / technical), no Spring exceptions in the domain, centralized HTTP mapping in a `@RestControllerAdvice` in the REST adapter, no leaking of technical exceptions to clients.

---

## 3. User management (USR)

### REQ-USR-001 — User entity
- **Priority**: MUST
- **Statement**: The system SHALL persist users with at least: unique identifier, email (used as username), hashed password, role flag (admin / standard), created/updated timestamps.

### REQ-USR-002 — Email as username
- **Priority**: MUST
- **Statement**: Email SHALL be the user identifier exposed in JWT claims and used at sign-in. Email SHALL be unique across the platform.

### REQ-USR-003 — Admin-only signup
- **Priority**: MUST
- **Statement**: Only users with the **admin** role flag SHALL be able to create new user accounts. Public self-signup is NOT supported.

### REQ-USR-004 — Self password change
- **Priority**: MUST
- **Statement**: An authenticated user SHALL be able to change their own password. The new password SHALL satisfy `REQ-SEC-001`.

### REQ-USR-005 — Admin user management
- **Priority**: SHOULD
- **Statement**: Admin users SHOULD be able to list, disable/enable, and delete user accounts.

### REQ-USR-006 — Cascade on user removal (hard-delete)
- **Priority**: MUST
- **Statement**: When a user is removed, all of that user’s owned agents and all related conversations SHALL be **hard-deleted**. Soft-delete or anonymization SHALL NOT be used.
- **Acceptance**: After deletion, no row remains in any table referencing the deleted user, agent, or conversation.

### REQ-USR-007 — First-admin bootstrap & forced password change
- **Priority**: MUST
- **Statement**: A first admin user SHALL be created by a Flyway migration at initial schema provisioning. On first successful sign-in, that admin SHALL be forced to change their password before any other operation is permitted. There SHALL be no automatic admin-password rotation policy.
- **Acceptance**: Until the initial password is changed, every authenticated request from the seeded admin (other than the password-change action itself) is rejected with a clear business error.

---

## 4. Authentication & authorization (AUTH)

### REQ-AUTH-001 — Two authentication modes
- **Priority**: MUST
- **Statement**: The backend SHALL support two authentication modes:
  1. **JWT mode** — `Authorization: Bearer <token>`.
  2. **API-key mode** — headers `X-Api-Key` and `X-Client-Id`.

### REQ-AUTH-002 — JWT issuance
- **Priority**: MUST
- **Statement**: JWTs SHALL be issued by the backend after a successful sign-in with email + password.

### REQ-AUTH-003 — JWT claims
- **Priority**: MUST
- **Statement**: JWTs SHALL include at least:
  - the user’s **email** (subject / username);
  - the user’s **role**;
  - a **`jti`** (JWT ID) — a unique, non-guessable identifier per token, used by the logout denylist defined in `REQ-AUTH-011`;
  - standard `iat` (issued-at) and `exp` (expiry) claims.
- Signature SHALL be verified on every request.

### REQ-AUTH-004 — JWT lifetime
- **Priority**: MUST
- **Statement**: Default JWT lifetime SHALL be **30 minutes**. The lifetime SHALL be configurable via Spring properties.

### REQ-AUTH-005 — No refresh token
- **Priority**: MUST
- **Statement**: The system SHALL NOT issue refresh tokens. Re-authentication is required after expiry.

### REQ-AUTH-006 — Single active token per user (TTL-based enforcement)
- **Priority**: MUST
- **Statement**: The “single active token per user” rule SHALL be enforced **primarily by the JWT’s short TTL** (`REQ-AUTH-004`). The backend SHALL NOT maintain a registry of all issued tokens, nor a per-user token-version counter. Issuing a new JWT does NOT actively revoke any previously issued, still-valid JWT.
- **Narrow exception**: A bounded **logout denylist** as defined in `REQ-AUTH-011` is permitted. It is not a general token registry: entries are written only on explicit logout, are keyed on `jti`, and self-evict no later than the token’s natural `exp`.
- **Acceptance**: Outside of logout events, a JWT remains valid until natural expiry without any per-token server-side state.

### REQ-AUTH-007 — API-key credentials (machine-to-machine)
- **Priority**: MUST
- **Statement**: `(X-Client-Id, X-Api-Key)` pairs SHALL be **machine-to-machine credentials**, not bound to any end-user account. They SHALL be created exclusively by an admin user through a dedicated administrative capability. The API-key value SHALL be persisted in hashed form and SHALL be displayed in clear only once at creation time.
- **Authorization scope**: Calls authenticated by API-key SHALL run under a virtual **"system" principal** with **full chat capabilities** (start a conversation, exchange messages with an agent, list/restart/delete the system principal’s own conversations). The system principal SHALL NOT have admin capabilities (e.g., user management, API-key management) and SHALL NOT see end-user-owned resources.
- **Acceptance**: A standard (non-admin) end-user SHALL NOT be able to create, list, or revoke API-key pairs.

### REQ-AUTH-008 — Authorization model
- **Priority**: MUST
- **Statement**: All non-public endpoints SHALL require authentication. Standard users SHALL only access resources they own; admins SHALL additionally access user-management capabilities.

### REQ-AUTH-009 — Authentication failure handling
- **Priority**: MUST
- **Statement**: Failed authentication or authorization SHALL return a generic error without leaking whether the email exists or the credential format was wrong.

### REQ-AUTH-010 — JWT signing algorithm and key
- **Priority**: MUST
- **Statement**: JWTs SHALL be signed with **HS256** (HMAC-SHA256). The signing secret SHALL be supplied through a fixed environment variable and SHALL NOT be hard-coded, generated at startup, or logged. The variable name SHALL be documented in configuration and consumed via Spring properties.
- **Acceptance**: Application startup SHALL fail fast with a clear error if the signing-secret environment variable is missing or empty.

### REQ-AUTH-011 — Logout endpoint and denylist
- **Priority**: MUST
- **Statement**: The backend SHALL expose a logout capability for authenticated end-users. On a successful logout call:
  - The server SHALL extract the presented JWT’s `jti` (`REQ-AUTH-003`) and add it to a **logout denylist**.
  - The denylist entry SHALL **self-expire no later than the token’s `exp`**, so the denylist remains bounded by the configured JWT lifetime (`REQ-AUTH-004`).
  - Every authenticated request SHALL reject any JWT whose `jti` is on the denylist with the same generic error as `REQ-AUTH-009`.
  - The client SHALL also discard the JWT locally.
- **Storage**: The denylist MAY be in-process (single-node deployments) or backed by an external store; the choice is left to the design (`SW-DESIGN.md`). It SHALL NOT be persisted past restart unless the design explicitly requires it (entries shorter than the JWT TTL would offer marginal benefit).
- **Acceptance**: Calling a protected endpoint with a logged-out JWT — before its natural `exp` — is rejected. Outside of logout events, no per-token server-side state exists, consistent with `REQ-AUTH-006`.

### REQ-AUTH-012 — API-key listing and revocation (admin)
- **Priority**: MUST
- **Statement**: Admin users SHALL be able to:
  - **List** existing API-key pairs with **metadata only** — `client-id`, creation timestamp, label/description if any, and enabled/disabled status. The cleartext API-key SHALL NEVER be returned after creation.
  - **Revoke** an API-key by setting a **`disabled` flag**. Revocation is a soft operation (the row is preserved for traceability). A disabled key SHALL be rejected at authentication with the same generic error as `REQ-AUTH-009`.

---

## 5. Security — passwords & secrets (SEC)

### REQ-SEC-001 — Password policy
- **Priority**: MUST
- **Statement**: Passwords SHALL meet all of:
  - minimum length **10 characters**;
  - at least **one uppercase letter** (`A`–`Z`);
  - at least **one special character**.
- **Acceptance**: Password creation/change SHALL be rejected with a specific business error when the policy is violated.

### REQ-SEC-002 — Password hashing
- **Priority**: MUST
- **Statement**: Passwords SHALL be hashed with **BCrypt** before persistence. Plain-text passwords SHALL NOT be stored or logged.

### REQ-SEC-003 — Secret handling
- **Priority**: MUST
- **Statement**: All credentials and API keys (`OPENAI_API_KEY`, `BRAVE_API_KEY`, JWT signing key, DB password) SHALL be supplied via environment variables or external configuration; they SHALL NOT be hard-coded or logged.

### REQ-SEC-004 — Sensitive log redaction
- **Priority**: MUST
- **Statement**: Logs SHALL NOT contain passwords, raw JWTs, raw API keys, or LLM credentials.

---

## 6. Agents (AGT)

### REQ-AGT-001 — Agent attributes
- **Priority**: MUST
- **Statement**: An agent SHALL be specified by:
  - `name` (string, mandatory, unique per owner — see `REQ-AGT-002`; max **32** characters);
  - `description` (string, mandatory; max **1024** characters) — describes the service the agent provides; used by other agents during delegation;
  - `systemPrompt` (string, mandatory; max **1024** characters) — agent context and behavior;
  - `tools` (list of tool references, default empty);
  - `enabledMcpServers` (list of MCP-server names selected from those configured; default empty — see `REQ-AGT-009`);
  - `memorySize` (integer, default `12`, max `36`);
  - `llmModel` (string, optional override of the platform default model — see `REQ-LLM-002`);
  - `temperature` (number, optional sampling parameter);
  - `maxOutputTokens` (integer, optional sampling parameter);
  - `topP` (number, optional sampling parameter);
  - `owner` (user reference);
  - `team` (list of agent references for delegation, default empty — see `REQ-AGT-013`).
- **Acceptance**: Inputs exceeding any documented length SHALL be rejected with a business validation error.

### REQ-AGT-002 — Per-owner unique agent name
- **Priority**: MUST
- **Statement**: Agent names SHALL be unique **per owner**. Two different users MAY create agents that share the same name; the same owner MAY NOT.

### REQ-AGT-003 — Mandatory fields validated
- **Priority**: MUST
- **Statement**: Creation/update SHALL reject inputs missing `name`, `description`, or `systemPrompt`, or violating field constraints, with a business validation error.

### REQ-AGT-004 — Memory size bounds
- **Priority**: MUST
- **Statement**: `memorySize` SHALL be in the inclusive range `[1, 36]`. Default value when not provided is `12`.

### REQ-AGT-005 — Memory semantics
- **Priority**: MUST
- **Statement**: `memorySize` defines the maximum number of past **persisted messages** (user and assistant only, per `REQ-CHAT-009` and `REQ-CHAT-012`) retained as context for the next LLM call. When the limit is exceeded, the oldest messages SHALL be dropped first. Tool-call requests/results that occur transiently within a turn (and are not persisted) do NOT count toward `memorySize`.

### REQ-AGT-006 — Private ownership
- **Priority**: MUST
- **Statement**: Every agent has exactly one owner. Only the owner SHALL be able to read, list, modify, delete, or chat with their agents.

### REQ-AGT-007 — CRUD on agents
- **Priority**: MUST
- **Statement**: An owner SHALL be able to create, read, update, and delete their agents.

### REQ-AGT-008 — Tool assignment
- **Priority**: MUST
- **Statement**: When creating or updating an agent, the owner SHALL be able to attach any subset of the registered tools (see §7).

### REQ-AGT-009 — Per-agent, per-MCP-server enablement
- **Priority**: MUST
- **Statement**: MCP enablement SHALL be granular **per agent and per MCP server**. Each agent carries an `enabledMcpServers` list referencing MCP servers declared in configuration; only the listed servers are exposed to that agent. An empty list means no MCP capability for the agent.
- **Acceptance**: Adding a name that is not in the configured MCP-server set SHALL be rejected with a validation error.

### REQ-AGT-010 — Cascade on agent deletion
- **Priority**: MUST
- **Statement**: When an agent is deleted, all conversations referring to that agent SHALL be deleted as well.

### REQ-AGT-011 — Team delegation
- **Priority**: MUST
- **Statement**: An agent SHALL be able to delegate a task to another agent listed in its team. Delegation SHALL use the target agent’s `description` to choose the right delegate. Execution semantics are defined in `REQ-AGT-015`.

### REQ-AGT-012 — Team membership scope
- **Priority**: MUST
- **Statement**: Agents listed in a team SHALL belong to the same owner as the delegating agent. Cross-owner team membership is NOT allowed.

### REQ-AGT-013 — Single-level team (no nested delegation)
- **Priority**: MUST
- **Statement**: Team membership SHALL be flat — exactly one delegation level. Concretely:
  - An agent B SHALL NOT be added to agent A’s team if B’s own team is non-empty.
  - An agent B that is already a member of some agent A’s team SHALL NOT be assigned a non-empty team.
- **Acceptance**: Any create/update operation that would violate either rule SHALL be rejected with a business validation error. By construction, this prevents self-delegation and cycles.

### REQ-AGT-014 — Mutation propagates to existing conversations
- **Priority**: MUST
- **Statement**: When an agent’s configuration is updated (`systemPrompt`, `tools`, `enabledMcpServers`, `memorySize`, `llmModel`, sampling parameters, `team`), all **subsequent turns** in any conversation involving that agent — including ongoing conversations — SHALL immediately use the new configuration. Past persisted messages are not rewritten.
- **Acceptance**: Configuration is read at the start of each turn; no per-conversation snapshot of the agent configuration is taken at conversation creation time.

### REQ-AGT-015 — Delegation execution model
- **Priority**: MUST
- **Statement**: When agent A delegates a task to agent B (`REQ-AGT-011`):
  - Only the **delegated task** SHALL be passed to agent B. B SHALL NOT receive A’s ongoing conversation history.
  - B’s exchanges with the LLM SHALL NOT be persisted — neither into A’s parent conversation, nor into a separate B-owned conversation, nor into long-lived memory. They are transient to the delegation call.
  - The end-user SHALL see only A’s **aggregated answer**; intermediate sub-agent traces SHALL NOT be streamed or stored.
  - B’s call SHALL NOT count against the parent conversation’s 64-message cap (`REQ-CHAT-010`).

---

## 7. Tools (TOOL)

### REQ-TOOL-001 — Static catalog
- **Priority**: MUST
- **Statement**: Tools SHALL be statically declared and discovered at application startup; the catalog SHALL NOT change at runtime.

### REQ-TOOL-002 — Implementation contract
- **Priority**: MUST
- **Statement**: A tool is a specific Java class+method conformant to **Spring AI** tool specifications.

### REQ-TOOL-003 — Tool listing
- **Priority**: MUST
- **Statement**: The backend SHALL be able to enumerate the available tools so that users can pick which ones to attach to an agent.

### REQ-TOOL-004 — Tool reference integrity
- **Priority**: MUST
- **Statement**: Attaching a tool reference that does not exist in the static catalog SHALL be rejected with a validation error.

### REQ-TOOL-005 — Initial v1 catalog
- **Priority**: MUST
- **Statement**: The v1 static tool catalog SHALL contain a single tool: **`AwsS3Tool`** (S3 bucket interactions). A reference implementation example is provided at `backend/docs/AwsS3Tool.java` and MAY be adapted during implementation. The catalog SHALL be exposed through a dedicated tool service responsible for enumeration and lookup (`REQ-TOOL-001`, `REQ-TOOL-003`).

---

## 8. MCP servers (MCP)

### REQ-MCP-001 — Configured by properties
- **Priority**: MUST
- **Statement**: MCP servers SHALL be declared via `application.properties` (Spring properties), not via the API.

### REQ-MCP-002 — Pre-configured servers
- **Priority**: MUST
- **Statement**: The default configuration SHALL include `brave-search` (web search) and `filesystem` (local file access).

### REQ-MCP-003 — Brave Search credentials
- **Priority**: MUST
- **Statement**: The Brave Search MCP server SHALL receive its API key from the `BRAVE_API_KEY` environment variable.

### REQ-MCP-004 — Per-agent, per-server enablement
- **Priority**: MUST
- **Statement**: MCP capabilities SHALL be enabled / disabled per agent **and** per MCP server (see `REQ-AGT-009`). There is no single global on/off MCP flag at the agent level.

### REQ-MCP-005 — Filesystem MCP scoped per user
- **Priority**: MUST
- **Statement**: The `filesystem` MCP server SHALL expose a root directory **scoped per user**. A user SHALL NEVER reach files belonging to another user through this MCP server.
- **Path convention**: The per-user root SHALL be `{base}/users/{userId}` where `{base}` is a configurable base directory. Per-user folders SHALL be created **on demand at first use**, not eagerly at user creation time.
- **Acceptance**: The root path used by the `filesystem` MCP server is derived from the calling user’s identity and is enforced server-side, not via prompt instructions.

### REQ-MCP-006 — Configured MCP servers enumeration
- **Priority**: MUST
- **Statement**: The backend SHALL be able to enumerate the MCP servers declared in configuration, so that an agent owner can pick which ones to enable on their agents (`enabledMcpServers` per `REQ-AGT-001` / `REQ-AGT-009`). This mirrors the tool listing capability in `REQ-TOOL-003`.

---

## 9. LLM provider (LLM)

### REQ-LLM-001 — Default provider
- **Priority**: MUST
- **Statement**: The default and initial LLM provider SHALL be **OpenAI**.

### REQ-LLM-002 — Default model
- **Priority**: MUST
- **Statement**: The default model SHALL be **`gpt-4o-mini`**. Model name SHALL be configurable.

### REQ-LLM-003 — Credentials
- **Priority**: MUST
- **Statement**: OpenAI credentials SHALL be read from the `OPENAI_API_KEY` environment variable.

### REQ-LLM-004 — Provider abstraction
- **Priority**: MUST
- **Statement**: The LLM client SHALL sit behind an abstraction in the application/domain layer so other providers can be added without touching business logic (see also `REQ-ARC-005`).

### REQ-LLM-005 — Failure mapping
- **Priority**: MUST
- **Statement**: LLM provider errors SHALL be mapped to technical exceptions in the infrastructure layer and translated to safe, user-facing errors at the REST boundary; raw provider error payloads SHALL NOT be returned to clients.

---

## 10. Chat / conversations (CHAT)

### REQ-CHAT-001 — Launch a chat
- **Priority**: MUST
- **Statement**: An authenticated user SHALL be able to start a chat with any agent they own. Chatting with another user’s agent SHALL be forbidden.

### REQ-CHAT-002 — Persistent storage
- **Priority**: MUST
- **Statement**: All conversations and their messages SHALL be persisted for later use.

### REQ-CHAT-003 — Conversation lifecycle
- **Priority**: MUST
- **Statement**: A user SHALL be able to **view**, **restart**, and **delete** any of their past conversations.
- **Restart semantics**: Restarting a conversation SHALL reload the previously persisted messages into the agent’s chat memory and into the LLM context (truncated to the agent’s `memorySize` per `REQ-AGT-005`), so that the conversation continues seamlessly as if it had never been interrupted. No new conversation is created; messages are appended to the same conversation record.

### REQ-CHAT-004 — Multiple concurrent conversations
- **Priority**: MUST
- **Statement**: A user SHALL be able to start a new conversation with an agent even while another conversation with the same agent is still ongoing.

### REQ-CHAT-005 — Auto-derived title
- **Priority**: MUST
- **Statement**: Each conversation SHALL carry a `title` field auto-derived from the first non-empty user message, subject to the rules below.
- **Rules**:
  - Maximum length is **32 characters**; longer messages are truncated.
  - Empty or whitespace-only messages are ignored for title derivation.
  - When no usable title can be derived, a default title `chat-<uuid>` SHALL be assigned.
  - The title SHALL be **editable by the user** at any time after auto-derivation.
- **Acceptance**: The title is set at the time the first non-empty user message is recorded and is not re-derived afterward, even if earlier messages are edited or removed.

### REQ-CHAT-006 — Memory window applied
- **Priority**: MUST
- **Statement**: When sending a message to the LLM, the backend SHALL include at most `memorySize` past messages from the conversation as context (see `REQ-AGT-005`).

### REQ-CHAT-007 — Owner-scoped access
- **Priority**: MUST
- **Statement**: A user SHALL only see, read, restart, or delete conversations that belong to them.

### REQ-CHAT-008 — Cascade on agent deletion
- **Priority**: MUST
- **Statement**: Deleting an agent SHALL delete all of its conversations (mirrors `REQ-AGT-010`).

### REQ-CHAT-009 — Message metadata
- **Priority**: MUST
- **Statement**: Each persisted message SHALL include role (**`user`** or **`assistant`** only — see `REQ-CHAT-012`), content, and timestamp. Message content SHALL be at most **1024 characters**; longer inputs from the user SHALL be rejected with a business validation error.

### REQ-CHAT-010 — Maximum messages per conversation
- **Priority**: MUST
- **Statement**: A conversation SHALL contain at most **64 messages**. When the limit is reached, further user messages SHALL be rejected with a clear business error (the user can start a new conversation per `REQ-CHAT-004`).
- **Acceptance**: The 64-message cap applies to all roles combined (user, assistant, tool, system) as persisted in `REQ-CHAT-009`.

### REQ-CHAT-011 — No per-user quotas
- **Priority**: MUST
- **Statement**: There SHALL be no per-user quota on the number of agents, the number of conversations, the number of attached tools, or any similar resource. The only chat-level cap is `REQ-CHAT-010`.

### REQ-CHAT-012 — Tool-call messages are not persisted
- **Priority**: MUST
- **Statement**: Tool-call requests issued by the LLM and tool-call results returned to the LLM SHALL NOT be persisted as messages of the conversation. They are transient artifacts of a single turn and are not visible to the end-user, not counted in `memorySize` (`REQ-AGT-005`), and not counted in the 64-message cap (`REQ-CHAT-010`).

---

## 11. Streaming (STR)

### REQ-STR-001 — SSE for chat responses
- **Priority**: MUST
- **Statement**: The chat response path SHALL be reactive and use **Server-Sent Events** (`text/event-stream`).

### REQ-STR-002 — Persistence after streaming
- **Priority**: MUST
- **Statement**: Once a streamed response completes, the assistant message SHALL be persisted to the conversation.

### REQ-STR-003 — Client cancellation
- **Priority**: SHOULD
- **Statement**: The streaming endpoint SHOULD detect client disconnections and cancel the in-flight LLM call to release resources.

### REQ-STR-004 — Frontend compatibility
- **Priority**: MUST
- **Statement**: The streaming format SHALL be consumable by the ReactJS frontend through standard SSE clients.

---

## 12. Persistence / database (PRS)

### REQ-PRS-001 — Database engine
- **Priority**: MUST
- **Statement**: PostgreSQL SHALL be the persistence engine for users, agents, conversations, messages, and API-key records.

### REQ-PRS-002 — Schema migrations
- **Priority**: MUST
- **Statement**: Database schema SHALL be managed and versioned with **Flyway**. Schema changes SHALL be applied automatically at application startup.

### REQ-PRS-003 — Transactional integrity
- **Priority**: MUST
- **Statement**: Operations spanning multiple writes (e.g., agent deletion → conversations → messages) SHALL be transactional.

### REQ-PRS-004 — Connection configuration
- **Priority**: MUST
- **Statement**: DB host, port, credentials, and pool settings SHALL be externally configurable (Spring properties + environment variables).

### REQ-PRS-005 — Conversation storage
- **Priority**: MUST
- **Statement**: Conversations SHALL be persisted in dedicated storage tables that allow efficient retrieval of recent N messages for the memory window.

---

## 13. Rate limiting (RL)

### REQ-RL-001 — Rate limiter filter
- **Priority**: MUST
- **Statement**: A rate-limiting filter SHALL be applied to incoming requests.

### REQ-RL-002 — Implementation
- **Priority**: MUST
- **Statement**: The implementation SHALL use **Bucket4j**.

### REQ-RL-003 — Global scope
- **Priority**: MUST
- **Statement**: The rate limit SHALL be global (not per-IP, not per-user).

### REQ-RL-004 — Configurable limits, default values
- **Priority**: MUST
- **Statement**: Capacity and refill rate SHALL be configurable. Defaults SHALL be:
  - **50 requests per hour**, and
  - **10 requests per minute**.
- Refill window for the hourly bucket SHALL be **1 hour**.
- Limits SHALL be runtime-configurable by **admin users** (changes take effect without redeploying the application).

### REQ-RL-005 — Throttle response
- **Priority**: MUST
- **Statement**: Rate-limit rejections SHALL be returned with HTTP status **429 Too Many Requests**, in the standard error format defined by `REQ-API-004`. A `Retry-After` header SHOULD be included indicating when capacity is expected to be available again.

---

## 14. API exposure & CORS (API)

### REQ-API-001 — REST over HTTP
- **Priority**: MUST
- **Statement**: The backend SHALL expose its capabilities as a REST API over HTTP for local development. HTTPS SHALL be considered only as a secondary step in production.

### REQ-API-002 — API-first contract
- **Priority**: MUST
- **Statement**: The API contract SHALL be described in the root `openapi.yaml` and kept in sync with the implementation.

### REQ-API-003 — CORS
- **Priority**: MUST
- **Statement**: CORS SHALL be enabled. The list of allowed origins SHALL be configurable via Spring properties.

### REQ-API-004 — Error format
- **Priority**: MUST
- **Statement**: Error responses SHALL share a single, documented JSON shape (e.g., problem detail) with: error code, human-readable message, and optional details. Stack traces SHALL NOT be exposed.

### REQ-API-005 — Pagination model (scrolling)
- **Priority**: MUST
- **Statement**: List endpoints (agents, conversations, messages) SHALL use a **scrolling / cursor-based** pagination model (continuation token from a previous page), not page-number/offset pagination. Page size SHALL be configurable with a documented maximum.

### REQ-API-006 — Versioned base path
- **Priority**: MUST
- **Statement**: All endpoints SHALL be served under a versioned prefix **`/api/v1`**. The prefix SHALL be defined **centrally** in configuration and SHALL NOT be repeated inside individual `@RestController` mappings.
- **Acceptance**: A single configuration property determines the prefix, and changing it relocates all endpoints accordingly.

---

## 15. Observability / logging (OBS)

### REQ-OBS-001 — Structured logging
- **Priority**: SHOULD
- **Statement**: Logs SHOULD be structured (JSON) and include correlation identifiers for incoming requests.

### REQ-OBS-002 — Log levels
- **Priority**: SHOULD
- **Statement**: Log level SHOULD be configurable per package via Spring properties.

### REQ-OBS-003 — Health endpoint
- **Priority**: SHOULD
- **Statement**: A health/readiness probe SHOULD be exposed for ops checks (Spring Boot Actuator is acceptable).

---

## 16. Non-functional (NFR)

### REQ-NFR-001 — Maintainability
- **Priority**: MUST
- **Statement**: Code SHALL follow the project Java coding standard (records for DTOs, constructor injection, functional style where appropriate).

### REQ-NFR-002 — Test coverage
- **Priority**: SHOULD
- **Statement**: Unit tests SHOULD use JUnit 5 + AssertJ + Mockito and cover business rules in the domain and application layers.

### REQ-NFR-003 — Configurability
- **Priority**: MUST
- **Statement**: All environment-specific values (DB URL, secrets, model name, CORS origins, rate-limiter capacity, JWT lifetime) SHALL be externalized.

### REQ-NFR-004 — Compatibility with the React frontend
- **Priority**: MUST
- **Statement**: The API SHALL work seamlessly with a ReactJS frontend, including SSE streaming.

### REQ-NFR-005 — Concurrency and sizing targets (v1)
- **Priority**: MUST
- **Statement**: The v1 sizing targets SHALL be:
  - total registered users: **64**;
  - concurrent authenticated users: **64**;
  - concurrent in-flight SSE chat streams: **16**.
- These targets inform thread-pool / reactive-runtime sizing, DB connection-pool sizing, and the rate-limiter defaults in `REQ-RL-004`.

---

## 17. Deployment (DEP)

### REQ-DEP-001 — Local development
- **Priority**: MUST
- **Statement**: The backend SHALL run on a local developer machine with a locally available PostgreSQL server. Docker SHALL NOT be required locally.

### REQ-DEP-002 — Cloud target
- **Priority**: MUST
- **Statement**: The backend SHALL be deployable to AWS, primarily on an EC2 instance via plain JAR copy. EKS/ECS/Fargate are out of scope for v1.

### REQ-DEP-003 — Infra/business separation
- **Priority**: MUST
- **Statement**: Cloud-specific concerns (e.g., S3, RDS, DynamoDB if used) SHALL be isolated from business logic so that the same JAR runs locally and in AWS.

### REQ-DEP-004 — Single artifact
- **Priority**: MUST
- **Statement**: The build SHALL produce a runnable Spring Boot fat JAR.

---

## Resolution log

> All initial open questions have been resolved per `backend/docs/OPEN-QUESTIONS.md`.
> This log preserves the question, the resolution, and the requirement(s) that now carry the answer.

| Ref  | Question (summary)                                  | Resolution                                                                                                  | Carried by                                  |
|------|-----------------------------------------------------|-------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| Q-1  | User deletion cascade                               | Hard-delete owned agents and conversations.                                                                 | `REQ-USR-006`                               |
| Q-2  | MCP toggle granularity                              | Per-agent, per-MCP-server.                                                                                  | `REQ-AGT-001`, `REQ-AGT-009`, `REQ-MCP-004` |
| Q-3  | Delegation depth / cycle handling                   | Single-level team only: a team member must have an empty team, and an agent with members cannot be a member.| `REQ-AGT-013`                               |
| Q-4  | Brave key env var name                              | `BRAVE_API_KEY` (SPECS updated).                                                                            | `REQ-MCP-003`                               |
| Q-5  | Meaning of "restart" a conversation                 | Reload existing chat memory and LLM context; resume the same conversation seamlessly.                       | `REQ-CHAT-003`                              |
| Q-6  | Title rules                                         | Max 32 chars; ignore empty messages; default `chat-<uuid>` if not derivable; user-editable afterward.       | `REQ-CHAT-005`                              |
| Q-7  | Rate-limit response & defaults                      | HTTP 429; defaults 50/h and 10/min; admin-configurable at runtime.                                          | `REQ-RL-004`, `REQ-RL-005`                  |
| Q-8  | First-admin bootstrap                               | Created by Flyway; forced password change on first login; no rotation policy.                               | `REQ-USR-007`                               |
| Q-9  | API-key lifecycle                                   | Machine-to-machine; created by admin only via dedicated capability; not bound to a user.                    | `REQ-AUTH-007`                              |
| Q-10 | Single-active-JWT enforcement                       | Primarily TTL-based; no general registry, no version counter. Narrow logout denylist (Q-19) is allowed.     | `REQ-AUTH-006`                              |
| Q-11 | Agent name uniqueness scope                         | Per owner, not global (SPECS updated).                                                                      | `REQ-AGT-002`                               |
| Q-12 | Pagination                                          | Scrolling / cursor-based pagination for list endpoints.                                                     | `REQ-API-005`                               |
| Q-13 | Quotas                                              | No per-user quotas; max **64 messages** per conversation.                                                   | `REQ-CHAT-010`, `REQ-CHAT-011`              |
| Q-14 | Editable conversation titles                        | Yes, editable by the user after auto-derivation.                                                            | `REQ-CHAT-005`                              |
| Q-15 | Filesystem MCP root scope                           | Scoped per user (no cross-user file access).                                                                | `REQ-MCP-005`                               |
| Q-16 | Audit trail                                         | Not required for v1.                                                                                        | — (no requirement)                          |
| Q-17 | JWT signing algorithm and key source                | HS256; signing key from a fixed environment variable; fail fast if missing.                                 | `REQ-AUTH-010`                              |
| Q-18 | API-key authorization scope                         | Full chat capabilities under a virtual "system" principal; no admin or end-user-resource access.            | `REQ-AUTH-007`                              |
| Q-19 | Logout endpoint                                     | Provided; server adds JWT `jti` to a self-expiring denylist (≤ token `exp`); client also discards the JWT.  | `REQ-AUTH-003`, `REQ-AUTH-006`, `REQ-AUTH-011` |
| Q-20 | Field length caps                                   | Agent `name` 32, `description` 1024, `systemPrompt` 1024; chat message `content` 1024.                      | `REQ-AGT-001`, `REQ-CHAT-009`               |
| Q-21 | Agent mutation propagation                          | All subsequent turns use the new configuration immediately; no per-conversation snapshot.                   | `REQ-AGT-014`                               |
| Q-22 | Initial v1 tool catalog                             | Single tool: `AwsS3Tool` (example at `backend/docs/AwsS3Tool.java`). Exposed via a dedicated tool service.  | `REQ-TOOL-005`                              |
| Q-23 | Per-agent LLM overrides                             | Model name and sampling parameters (temperature, maxOutputTokens, topP) are agent properties; live updates. | `REQ-AGT-001`, `REQ-AGT-014`                |
| Q-24 | Tool-call message persistence                       | Not persisted; transient to a turn; not visible, not counted in memory window or 64-message cap.            | `REQ-CHAT-012`, `REQ-AGT-005`               |
| Q-25 | Delegation execution model                          | Only delegated task passed; sub-agent calls not persisted; user sees aggregated answer; not counted in cap. | `REQ-AGT-015`                               |
| Q-26 | API base path / versioning                          | `/api/v1` prefix configured centrally; not repeated in controller mappings.                                 | `REQ-API-006`                               |
| Q-27 | Filesystem MCP base directory                       | Layout `{base}/users/{userId}`; per-user folders created on demand on first use.                            | `REQ-MCP-005`                               |
| Q-28 | Concurrency / sizing targets                        | 64 registered users, 64 concurrent authenticated users, 16 concurrent SSE streams.                          | `REQ-NFR-005`                               |
| Q-29 | API-key lifecycle operations                        | Admin can list (metadata only) and revoke via a `disabled` flag (soft revocation).                          | `REQ-AUTH-012`                              |
