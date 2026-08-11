# SW-DESIGN.md — Backend Software Design

This document is the authoritative software design for the multi-agent platform backend.
It is derived from `backend/docs/SPECS.md`, `backend/requirements/REQS.md`, and the resolved decisions logged in `backend/docs/OPEN-QUESTIONS.md`.
Its purpose is to contain everything needed for the next step: producing the API contract `openapi.yaml`.

> Cross-references throughout the document point to requirement IDs from `REQS.md`. When a value is fixed there, this design only restates it; when a value is a design choice, it is justified inline.

---

## 1. Scope and references

- **In scope**: backend architecture, project structure, domain model, persistence model, REST endpoints (paths, methods, payloads, status codes), authentication and authorization, streaming, error handling, pagination, configuration, integrations (LLM, MCP, tools), rate limiting, deployment topology.
- **Out of scope**: frontend design, ultimate AWS infrastructure topology beyond the EC2 + RDS choice already pinned by SPECS.
- **References**:
  - `backend/docs/SPECS.md` — backend specifications.
  - `backend/requirements/REQS.md` — full requirement set (95 reqs).
  - `backend/docs/JAVA-CODING-STANDARD.md` — coding rules.
  - `backend/docs/EXCEPTIONS.md` — exception layering rules.
  - `backend/docs/AwsS3Tool.java` — reference example for the v1 tool.
  - `docs/SPECS.md` — global project specifications.

---

## 2. High-level architecture

### 2.1 Style — hexagonal (ports & adapters)

Per `REQ-ARC-002` / `REQ-ARC-003`, the backend follows **hexagonal architecture** with three concentric zones:

```
                       ┌─────────────────────────────────────────────────────┐
                       │                  Infrastructure                      │
                       │  REST controllers   Persistence adapters             │
                       │  Security filters   LLM / MCP / Tool adapters        │
                       └─────────────────────────▲───────────────────────────┘
                                                 │ ports (interfaces)
                       ┌─────────────────────────┴───────────────────────────┐
                       │                  Application                         │
                       │      Use cases (orchestration, transactions)         │
                       └─────────────────────────▲───────────────────────────┘
                                                 │
                       ┌─────────────────────────┴───────────────────────────┐
                       │                    Domain                            │
                       │   Entities, value objects, business rules,           │
                       │   business exceptions — pure Java, no Spring         │
                       └─────────────────────────────────────────────────────┘
```

- **Domain** has no dependency on Spring, JPA, Spring AI, or Lombok beyond `@Value`/records as needed.
- **Application** depends only on the domain and on **port interfaces** it declares.
- **Infrastructure** implements the ports and depends on Spring/Spring AI/JPA/PostgreSQL/etc.

### 2.2 Driving vs driven ports

Port placement follows a **DDD-flavored hybrid** that keeps the structure light while preserving the layering rule (`domain ← application ← infrastructure`):

- **Repository interfaces live in the domain**, beside the aggregate they serve. They speak the domain language ("find a user by email") and are owned by the bounded context.
- **Technical / integration ports live in the application** (LLM, MCP, password hashing, JWT, clock). These are orchestration concerns, not domain vocabulary.
- **Use-case interfaces** ("driving ports") sit next to their implementation in the same `application/<context>/` package — no separate `port/in` folder.
- **Adapters live in the infrastructure** and implement either kind of port.

Ports and where they live:

| Port                          | Layer        | Purpose                                            | Requirements     |
|-------------------------------|--------------|----------------------------------------------------|------------------|
| `UserRepository`              | domain       | CRUD on users                                      | REQ-USR-*        |
| `AgentRepository`             | domain       | CRUD on agents (with name uniqueness per owner)    | REQ-AGT-*        |
| `ConversationRepository`      | domain       | Conversations + messages persistence               | REQ-CHAT-*       |
| `ApiKeyRepository`            | domain       | API-key CRUD                                       | REQ-AUTH-007/012 |
| `RateLimitConfigRepository`   | domain       | Live config of rate limiter                        | REQ-RL-004       |
| `JwtDenylist`                 | application  | Logout-triggered jti denylist                      | REQ-AUTH-011     |
| `LlmChatClient`               | application  | Provider-agnostic chat completion (sync + stream)  | REQ-LLM-*        |
| `ToolCatalog`                 | application  | Static tool registry                               | REQ-TOOL-*       |
| `McpServerCatalog`            | application  | Configured MCP servers                             | REQ-MCP-*        |
| `FilesystemMcpUserScope`      | application  | Resolves per-user filesystem root                  | REQ-MCP-005      |
| `PasswordHasher`              | application  | BCrypt wrapper (kept abstract to allow testing)    | REQ-SEC-002      |
| `JwtTokenService`             | application  | Issue/parse/verify JWTs                            | REQ-AUTH-002/010 |
| `Clock`                       | application  | Injected clock (testability)                       | REQ-NFR-002      |

Rationale for the split: a repository interface is part of how the domain expresses persistence ("the `Agent` aggregate is reachable by name within an owner") — it belongs with the aggregate. A `LlmChatClient` is plumbing the use cases need; the domain has no opinion on chat completion APIs.

### 2.3 Threading & web stack

- **Web stack**: Spring **MVC** (servlet, not WebFlux). Streaming is implemented via `SseEmitter` (`org.springframework.web.servlet.mvc.method.annotation.SseEmitter`) fed from the `Flux<ChatResponse>` returned by Spring AI's streaming chat client.
- **Why MVC, not WebFlux**: simplicity (REQ-ARC-004); JPA, Spring Security MVC, Bucket4j servlet filter, and the rest of the stack are easier to combine with MVC. The reactive requirement (`REQ-STR-001`) is satisfied by SSE on top of MVC's async dispatch.
- **Concurrency targets** (`REQ-NFR-005`): 64 registered users, 64 concurrent authenticated, 16 concurrent SSE streams. Tomcat default thread pool (200 threads) is more than enough; SSE streams are largely I/O-bound on the upstream LLM call.

### 2.4 Module / artifact

Single Spring Boot fat JAR (`REQ-DEP-004`). One Maven module — splitting into multi-module would violate `REQ-ARC-004`.

---

## 3. Project structure

Java root package: `com.cognizant.emk.multiagent`. Three top-level packages — `domain`, `application`, `infrastructure` — and nothing else. Inside each, packages are organized by **bounded context** (user, agent, conversation, auth, …) rather than by technical kind. Repository interfaces sit in the domain beside their aggregate; technical ports sit in the application; adapters sit in the infrastructure.

```
backend/
├── pom.xml
├── src/main/java/com/cognizant/emk/multiagent/
│   ├── Application.java                       # Spring Boot main
│   │
│   ├── domain/                                # Pure Java — no Spring, no JPA, no Lombok beyond records
│   │   ├── shared/
│   │   │   ├── BusinessException.java         # abstract base (per EXCEPTIONS.md)
│   │   │   ├── ValidationException.java
│   │   │   ├── NotFoundException.java
│   │   │   ├── ConflictException.java
│   │   │   └── ForbiddenException.java
│   │   ├── user/
│   │   │   ├── User.java                      # aggregate
│   │   │   ├── UserId.java, Email.java, Password.java, Role.java
│   │   │   ├── UserRepository.java            # ← repository interface lives with the aggregate
│   │   │   └── UserNotFoundException.java, EmailAlreadyUsedException.java, ...
│   │   ├── agent/
│   │   │   ├── Agent.java, AgentId.java, AgentConfig.java, MemorySize.java
│   │   │   ├── Team.java                      # value object enforcing flat-team rule
│   │   │   ├── AgentRepository.java           # ←
│   │   │   └── AgentNotFoundException.java, DuplicateAgentNameException.java, NestedTeamForbiddenException.java, ...
│   │   ├── conversation/
│   │   │   ├── Conversation.java, ConversationId.java
│   │   │   ├── Message.java, MessageRole.java, Title.java
│   │   │   ├── ConversationRepository.java    # ←
│   │   │   └── ConversationFullException.java, ConversationNotFoundException.java, ...
│   │   ├── tool/
│   │   │   ├── ToolDescriptor.java
│   │   │   └── UnknownToolException.java
│   │   ├── mcp/
│   │   │   ├── McpServerName.java
│   │   │   └── UnknownMcpServerException.java
│   │   ├── ratelimit/
│   │   │   ├── RateLimitConfig.java
│   │   │   └── RateLimitConfigRepository.java # ←
│   │   └── auth/
│   │       ├── ApiKey.java, ClientId.java
│   │       ├── Principal.java                 # sealed: UserPrincipal | SystemPrincipal
│   │       ├── ApiKeyRepository.java          # ←
│   │       └── InvalidCredentialsException.java
│   │
│   ├── application/                           # Use cases + non-repo (technical) ports — Spring-aware
│   │   ├── auth/
│   │   │   ├── LoginUseCase.java, LoginService.java
│   │   │   ├── LogoutUseCase.java, LogoutService.java
│   │   │   ├── ChangeOwnPasswordUseCase.java, ChangeOwnPasswordService.java
│   │   │   ├── JwtTokenService.java           # technical port (HS256 issuance/verification)
│   │   │   ├── PasswordHasher.java            # technical port (BCrypt)
│   │   │   └── JwtDenylist.java               # technical port (logout denylist)
│   │   ├── user/
│   │   │   └── (CreateUser, ListUsers, GetUser, SetUserDisabled, DeleteUser) — interface + Service
│   │   ├── apikey/
│   │   │   └── (CreateApiKey, ListApiKeys, DisableApiKey) — interface + Service
│   │   ├── agent/
│   │   │   └── (CreateAgent, UpdateAgent, DeleteAgent, ListAgents, GetAgent) — interface + Service
│   │   ├── chat/
│   │   │   ├── (StartConversation, ListConversations, GetConversation, EditConversationTitle, DeleteConversation, ListMessages, SendMessage) — interface + Service
│   │   │   ├── LlmChatClient.java             # technical port (provider-agnostic chat)
│   │   │   ├── ChatRequest.java, ChatChunk.java
│   │   │   └── DelegationService.java         # internal helper used by SendMessageService
│   │   ├── tool/
│   │   │   ├── ListToolsUseCase.java, ListToolsService.java
│   │   │   └── ToolCatalog.java               # technical port
│   │   ├── mcp/
│   │   │   ├── ListMcpServersUseCase.java, ListMcpServersService.java
│   │   │   ├── McpServerCatalog.java          # technical port
│   │   │   └── FilesystemMcpUserScope.java    # technical port (per-user fs root)
│   │   ├── ratelimit/
│   │   │   └── (GetRateLimitConfig, UpdateRateLimitConfig) — interface + Service
│   │   └── shared/
│   │       └── Clock.java                     # technical port
│   │
│   └── infrastructure/
│       ├── web/                               # REST adapter (driving side)
│       │   ├── auth/        AuthController, login/logout/password DTOs
│       │   ├── admin/       UsersAdminController, ApiKeysAdminController, RateLimitAdminController + DTOs
│       │   ├── agent/       AgentController, AgentDtos
│       │   ├── conversation/ ConversationController, ConversationDtos
│       │   ├── catalog/     ToolsController, McpServersController
│       │   ├── error/       GlobalExceptionHandler, ProblemDetailMapper
│       │   ├── pagination/  CursorCodec, PageDto
│       │   ├── security/    JwtAuthenticationFilter, ApiKeyAuthenticationFilter,
│       │   │                ForcedPasswordChangeFilter, SecurityContextPrincipalAdapter, SpringSecurityConfig
│       │   └── ratelimit/   RateLimitFilter (Bucket4j)
│       ├── persistence/                       # JPA adapter (driven side)
│       │   ├── entity/      UserJpa, AgentJpa, AgentToolJpa, AgentMcpJpa, AgentTeamJpa,
│       │   │                ConversationJpa, MessageJpa, ApiKeyJpa, JwtDenylistJpa, RateLimitConfigJpa
│       │   ├── springdata/  Spring Data JPA interfaces (UserJpaRepository, AgentJpaRepository, …)
│       │   ├── mapper/      domain ↔ JPA mappers
│       │   └── adapter/     UserRepositoryAdapter, AgentRepositoryAdapter, ConversationRepositoryAdapter,
│       │                    ApiKeyRepositoryAdapter, RateLimitConfigRepositoryAdapter
│       ├── llm/openai/      OpenAiChatClientAdapter (implements LlmChatClient), OpenAiConfig
│       ├── tool/            ToolCatalogAdapter, AwsS3Tool          # AwsS3Tool moved here from docs/
│       ├── mcp/             McpServerCatalogAdapter, FilesystemMcpUserScopeAdapter
│       ├── security/        BcryptPasswordHasherAdapter, JjwtTokenServiceAdapter,
│       │                    InMemoryJwtDenylistAdapter (single-node v1; see TBD-1)
│       └── config/          ApplicationProperties, WebConfig, SpringAiConfig, SchedulingConfig, ClockConfig
│
└── src/main/resources/
    ├── application.yaml
    ├── logback-spring.xml                     # JSON logging (REQ-OBS-001)
    └── db/migration/
        ├── V001__init_schema.sql
        ├── V002__seed_admin.sql               # bootstrap admin (REQ-USR-007)
        └── V003__seed_rate_limit_config.sql
```

### 3.1 Conventions

- **Use-case interface and `@Service` implementation live side by side** in the same package (e.g. `application/agent/CreateAgentUseCase.java` next to `CreateAgentService.java`). No `port/in` folder.
- **Repository interfaces are domain code** — pure Java, no Spring annotations. The infrastructure `*RepositoryAdapter` classes implement them and depend on Spring Data JPA internally.
- **One controller per bounded context** under `infrastructure/web/<context>/`.
- **DTOs are records**, defined next to the controller that uses them. They never leak into application or domain.
- **No `port/` folder anywhere** — placement (domain vs application) already conveys whether an interface is a domain repository or a technical port.
- Coding standard from `JAVA-CODING-STANDARD.md` applies: records for DTOs, constructor injection, no field `@Autowired`, Lombok only when records can't fit, functional pipelines, JUnit 5 + AssertJ + Mockito for tests.

### 3.2 Layering rule

Compile-time direction:

```
infrastructure  →  application  →  domain
```

The domain has zero outbound dependencies on Spring, JPA, Spring AI, Jackson, etc. The application depends on the domain and may use Spring stereotypes (`@Service`, `@Transactional`). The infrastructure depends on both and pulls in everything else. This is the only enforced rule; package-by-context inside each layer keeps things readable without further ceremony.

---

## 4. Domain model

### 4.1 Entities

#### User (`REQ-USR-001`)
- `id`: UUID
- `email`: unique, RFC 5322 syntactic check
- `passwordHash`: BCrypt (`REQ-SEC-002`)
- `role`: `ADMIN` | `STANDARD`
- `disabled`: boolean (admin can soft-disable; defaults false)
- `mustChangePassword`: boolean — set true for the seeded admin (`REQ-USR-007`); cleared on first successful self-change.
- `createdAt`, `updatedAt`: timestamptz

#### Agent (`REQ-AGT-001`)
- `id`: UUID
- `ownerId`: FK → User
- `name`: ≤ 32 chars (`REQ-AGT-001`/`REQ-AGT-002`)
- `description`: ≤ 1024 chars
- `systemPrompt`: ≤ 1024 chars
- `memorySize`: `[1,36]` default 12
- `llmModel`: nullable string (override platform default)
- `temperature`: nullable double
- `maxOutputTokens`: nullable int
- `topP`: nullable double
- `tools`: set of tool names (FK to a static catalog — validated at write time, not via DB FK)
- `enabledMcpServers`: set of MCP-server names (validated at write time)
- `team`: set of agent IDs owned by the same user — see `REQ-AGT-013`
- `createdAt`, `updatedAt`

#### Conversation (`REQ-CHAT-002` / `REQ-CHAT-009`)
- `id`: UUID
- `agentId`: FK → Agent (cascade delete per `REQ-AGT-010`)
- `ownerId`: FK → User (denormalized; equals agent.ownerId; lets us cascade on user delete and filter quickly)
- `title`: ≤ 32 chars (nullable until first message — see lifecycle below)
- `messageCount`: int (denormalized, capped at 64 per `REQ-CHAT-010`)
- `createdAt`, `updatedAt`

#### Message (`REQ-CHAT-009`)
- `id`: UUID
- `conversationId`: FK → Conversation (cascade)
- `role`: `USER` | `ASSISTANT` (enum) — tool messages are NOT persisted (`REQ-CHAT-012`)
- `content`: ≤ 1024 chars
- `createdAt`: timestamptz

#### ApiKey (`REQ-AUTH-007` / `REQ-AUTH-012`)
- `clientId`: string (UUID-like, public identifier — what callers send in `X-Client-Id`)
- `apiKeyHash`: BCrypt of the cleartext API key (cleartext is shown once at creation)
- `label`: optional human description
- `disabled`: boolean (soft revocation)
- `createdAt`

#### JwtDenylistEntry (`REQ-AUTH-011`)
- `jti`: UUID (primary key)
- `expiresAt`: timestamptz (≤ token's natural `exp`)

#### RateLimitConfig (`REQ-RL-004`)
- Single-row table with: `perMinute` (int), `perHour` (int), `updatedAt`, `updatedBy` (admin user id).

### 4.2 Domain invariants enforced in code (not only DB)

| Invariant                                                                  | Where                                       | Requirement      |
|----------------------------------------------------------------------------|---------------------------------------------|------------------|
| Agent name unique per owner                                                | Application layer + DB unique (owner, name) | REQ-AGT-002      |
| Single-level team (B in A.team ⇒ B.team empty; reciprocally)               | Domain `Team` value object on every write   | REQ-AGT-013      |
| Team members share owner with parent                                       | Application layer                           | REQ-AGT-012      |
| Memory size ∈ [1, 36]                                                      | Domain `MemorySize` constructor             | REQ-AGT-004      |
| Conversation total messages ≤ 64                                           | Application layer (atomic increment)        | REQ-CHAT-010     |
| Messages persisted = USER or ASSISTANT only                                | Domain `MessageRole` enum                   | REQ-CHAT-012     |
| Title derived once, ≤ 32 chars, default `chat-<uuid>` if not derivable     | Application `SendMessageService`            | REQ-CHAT-005     |
| Password meets policy                                                      | Domain `Password` value object              | REQ-SEC-001      |

### 4.3 Conversation lifecycle (state implicit in data)

A conversation has no explicit status enum. State is derived:
- **Empty** — created via `POST /conversations`, `messageCount=0`, `title=null`.
- **Active** — has ≥ 1 message and `messageCount<64`. Continuing it = posting a message.
- **Full** — `messageCount=64`. Posting another message returns 409 (`REQ-CHAT-010`).
- **Restart** = simply post on a not-yet-full conversation. Memory window is rebuilt from persisted messages each turn (`REQ-AGT-005`, `REQ-CHAT-003`).

---

## 5. Database schema (PostgreSQL)

Outline only; full DDL lives in `V001__init_schema.sql`. All tables use UUID v4 primary keys (`pgcrypto.gen_random_uuid()`), `timestamptz` for timestamps. Naming: snake_case.

```sql
-- users
create table users (
  id                uuid primary key default gen_random_uuid(),
  email             varchar(254) not null unique,
  password_hash     varchar(72)  not null,                -- BCrypt
  role              varchar(16)  not null check (role in ('ADMIN','STANDARD')),
  disabled          boolean      not null default false,
  must_change_password boolean   not null default false,
  created_at        timestamptz  not null default now(),
  updated_at        timestamptz  not null default now()
);

-- agents
create table agents (
  id                uuid primary key default gen_random_uuid(),
  owner_id          uuid not null references users(id) on delete cascade,
  name              varchar(32)  not null,
  description       varchar(1024) not null,
  system_prompt     varchar(1024) not null,
  memory_size       int  not null default 12 check (memory_size between 1 and 36),
  llm_model         varchar(64),
  temperature       double precision,
  max_output_tokens int,
  top_p             double precision,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (owner_id, name)
);

-- agent → tools (denormalized; tool catalog is static in code)
create table agent_tools (
  agent_id  uuid not null references agents(id) on delete cascade,
  tool_name varchar(64) not null,
  primary key (agent_id, tool_name)
);

-- agent → MCP servers
create table agent_mcp_servers (
  agent_id        uuid not null references agents(id) on delete cascade,
  mcp_server_name varchar(64) not null,
  primary key (agent_id, mcp_server_name)
);

-- agent → team members (self-relation, single level enforced in app code)
create table agent_team (
  parent_agent_id uuid not null references agents(id) on delete cascade,
  member_agent_id uuid not null references agents(id) on delete cascade,
  primary key (parent_agent_id, member_agent_id),
  check (parent_agent_id <> member_agent_id)
);

-- conversations
create table conversations (
  id            uuid primary key default gen_random_uuid(),
  agent_id      uuid not null references agents(id) on delete cascade,
  owner_id      uuid not null references users(id)  on delete cascade,
  title         varchar(32),
  message_count int  not null default 0 check (message_count between 0 and 64),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index idx_conversations_owner_created on conversations (owner_id, created_at desc, id desc);

-- messages
create table messages (
  id              uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  role            varchar(16) not null check (role in ('USER','ASSISTANT')),
  content         varchar(1024) not null,
  created_at      timestamptz not null default now()
);
create index idx_messages_conv_created on messages (conversation_id, created_at, id);

-- API keys (machine-to-machine)
create table api_keys (
  client_id     varchar(64) primary key,
  api_key_hash  varchar(72) not null,
  label         varchar(128),
  disabled      boolean not null default false,
  created_at    timestamptz not null default now()
);

-- JWT logout denylist
create table jwt_denylist (
  jti        uuid primary key,
  expires_at timestamptz not null
);
create index idx_jwt_denylist_expires on jwt_denylist (expires_at);

-- Rate limit live config (single row)
create table rate_limit_config (
  id          smallint primary key default 1 check (id = 1),
  per_minute  int not null,
  per_hour    int not null,
  updated_at  timestamptz not null default now(),
  updated_by  uuid references users(id)
);
```

### 5.1 Flyway migrations

- `V001__init_schema.sql` — the schema above.
- `V002__seed_admin.sql` — inserts a single admin user with `must_change_password=true`. Email and a temporary BCrypt-hashed password are taken from environment variables (`APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH`); the migration fails fast if either is empty (`REQ-USR-007`).
- `V003__seed_rate_limit_config.sql` — inserts the default `(per_minute=10, per_hour=50)` row (`REQ-RL-004`).

### 5.2 Cascade rules

- `users` → `agents` (cascade): user delete drops their agents (`REQ-USR-006`).
- `agents` → `agent_tools`, `agent_mcp_servers`, `agent_team`, `conversations` (cascade): consistent with `REQ-AGT-010`.
- `conversations` → `messages` (cascade).

The combination of FK cascades + an explicit `users` → `conversations` cascade ensures hard-delete propagation as required (`REQ-USR-006`, `REQ-AGT-010`).

---

## 6. REST API specification

All endpoints share the conventions in this section. The next section enumerates each endpoint with payloads — that table is the direct input for `openapi.yaml`.

### 6.1 Conventions

| Aspect                     | Decision                                                                                                          | Requirement       |
|----------------------------|-------------------------------------------------------------------------------------------------------------------|-------------------|
| Base path                  | `/api/v1` — set as a single `app.api.base-path` Spring property; **not** repeated in `@RestController` mappings   | REQ-API-006       |
| Transport                  | HTTP for v1; HTTPS deferred to production (`REQ-API-001`)                                                         | REQ-API-001       |
| Default media type         | `application/json` (UTF-8); SSE endpoints use `text/event-stream`                                                 | REQ-STR-001       |
| Authentication             | JWT via `Authorization: Bearer <token>` OR API key via `X-Client-Id` + `X-Api-Key`                                | REQ-AUTH-001      |
| Authorization              | Role-based (`STANDARD` vs `ADMIN`) + ownership check on resources                                                 | REQ-AUTH-008      |
| CORS                       | Allow-list of origins via `app.cors.allowed-origins` (comma-separated)                                            | REQ-API-003       |
| Error format               | RFC 7807 Problem Details JSON (see §9)                                                                            | REQ-API-004       |
| Pagination                 | Cursor-based (`cursor` query param + `nextCursor` in response); default page size 20, max 100                     | REQ-API-005       |
| Date/time                  | ISO 8601 UTC (`yyyy-MM-dd'T'HH:mm:ss.SSSXXX`)                                                                     | —                 |
| ID format                  | UUID v4 strings                                                                                                   | —                 |
| Versioning                 | URL path prefix `/api/v1`; future major versions get `/api/v2`                                                    | REQ-API-006       |

### 6.2 Endpoint catalogue

Notation: `[Auth: PUBLIC | USER | ADMIN | SYSTEM]`. `USER` = JWT-authenticated end-user (admin or standard). `SYSTEM` = API-key-authenticated machine principal. `ADMIN` = JWT-authenticated user with `role=ADMIN`.

#### 6.2.1 Authentication — `/api/v1/auth`

| Method | Path                      | Auth   | Purpose                                                       |
|--------|---------------------------|--------|---------------------------------------------------------------|
| POST   | `/auth/login`             | PUBLIC | Email + password → JWT                                        |
| POST   | `/auth/logout`            | USER   | Add the current JWT's `jti` to the denylist                   |
| PUT    | `/auth/password`          | USER   | Change own password (also clears `mustChangePassword`)        |

**POST /auth/login**
- Request: `{ "email": "string", "password": "string" }`
- 200: `{ "token": "string", "tokenType": "Bearer", "expiresAt": "ISO-8601", "mustChangePassword": false }`
- 401: invalid credentials (`REQ-AUTH-009`)
- 403: account disabled
- Notes: response payload includes `mustChangePassword` so the frontend can route to the password-change screen (`REQ-USR-007`).

**POST /auth/logout**
- Request: empty body.
- 204: success.
- 401: missing/invalid token.

**PUT /auth/password**
- Request: `{ "currentPassword": "string", "newPassword": "string" }`
- 204: success — token remains valid until natural expiry; the client may keep using it.
- 400: new password violates policy (`REQ-SEC-001`).
- 401: current password wrong.

#### 6.2.2 Admin — users — `/api/v1/admin/users`

All endpoints `[Auth: ADMIN]` (`REQ-USR-003`, `REQ-USR-005`).

| Method | Path                              | Purpose                                  |
|--------|-----------------------------------|------------------------------------------|
| GET    | `/admin/users`                    | List users (cursor paginated)            |
| POST   | `/admin/users`                    | Create user (`REQ-USR-003`)              |
| GET    | `/admin/users/{userId}`           | Fetch one                                |
| PATCH  | `/admin/users/{userId}`           | Enable/disable (`REQ-USR-005`)           |
| DELETE | `/admin/users/{userId}`           | Hard-delete (`REQ-USR-006`)              |

**POST /admin/users**
- Request: `{ "email": "string", "password": "string", "role": "STANDARD|ADMIN" }`
- 201: `{ "id": "uuid", "email": "string", "role": "...", "disabled": false, "createdAt": "..." }`
- 400: invalid email or password policy violation
- 409: email already used

**PATCH /admin/users/{userId}** — partial update
- Request: `{ "disabled": true|false }`
- 200: full user resource
- 404: user not found

#### 6.2.3 Admin — API keys — `/api/v1/admin/api-keys`

All endpoints `[Auth: ADMIN]` (`REQ-AUTH-007`, `REQ-AUTH-012`).

| Method | Path                                  | Purpose                                       |
|--------|---------------------------------------|-----------------------------------------------|
| GET    | `/admin/api-keys`                     | List metadata (no cleartext)                  |
| POST   | `/admin/api-keys`                     | Create — returns cleartext **once**           |
| PATCH  | `/admin/api-keys/{clientId}`          | Disable (soft revoke)                         |

**POST /admin/api-keys**
- Request: `{ "label": "optional string ≤128" }`
- 201: `{ "clientId": "string", "apiKey": "string-cleartext-shown-once", "label": "...", "disabled": false, "createdAt": "..." }`

**GET /admin/api-keys** — paginated
- 200: `{ "items": [ { "clientId": "...", "label": "...", "disabled": false, "createdAt": "..." } ], "nextCursor": "..." | null }`

**PATCH /admin/api-keys/{clientId}**
- Request: `{ "disabled": true }` (only `true` makes sense in practice; we accept boolean for symmetry)
- 200: metadata; 404: not found.

#### 6.2.4 Admin — rate limit — `/api/v1/admin/rate-limit`

All endpoints `[Auth: ADMIN]` (`REQ-RL-004`).

| Method | Path                | Purpose                              |
|--------|---------------------|--------------------------------------|
| GET    | `/admin/rate-limit` | Read live config                     |
| PUT    | `/admin/rate-limit` | Replace live config                  |

**Body**: `{ "perMinute": int >0, "perHour": int >0 }`
- 200 on read/update; 400 on invalid values.
- Config update takes effect on the next request without restart.

#### 6.2.5 Tools — `/api/v1/tools`

| Method | Path        | Auth | Purpose                                              |
|--------|-------------|------|------------------------------------------------------|
| GET    | `/tools`    | USER | List the static tool catalog (`REQ-TOOL-003`)        |

**GET /tools**
- 200: `{ "items": [ { "name": "string", "description": "string" } ] }` — not paginated (catalog is small and static).

#### 6.2.6 MCP servers — `/api/v1/mcp-servers`

| Method | Path             | Auth | Purpose                                         |
|--------|------------------|------|-------------------------------------------------|
| GET    | `/mcp-servers`   | USER | List configured MCP servers (`REQ-MCP-006`)     |

**GET /mcp-servers**
- 200: `{ "items": [ { "name": "string", "description": "string" } ] }` — not paginated.

#### 6.2.7 Agents — `/api/v1/agents`

All endpoints `[Auth: USER]`, scoped to the caller's owned agents (`REQ-AGT-006`).

| Method | Path                      | Purpose                                               |
|--------|---------------------------|-------------------------------------------------------|
| GET    | `/agents`                 | List own agents (cursor paginated)                    |
| POST   | `/agents`                 | Create agent                                          |
| GET    | `/agents/{agentId}`       | Get one                                               |
| PUT    | `/agents/{agentId}`       | Replace agent configuration                           |
| DELETE | `/agents/{agentId}`       | Delete agent (cascades conversations)                 |

**Agent representation** (used by GET, PUT, POST 201):
```json
{
  "id": "uuid",
  "name": "string ≤32",
  "description": "string ≤1024",
  "systemPrompt": "string ≤1024",
  "memorySize": 12,
  "llmModel": "string|null",
  "temperature": 0.7,
  "maxOutputTokens": 1024,
  "topP": 1.0,
  "tools": ["AwsS3Tool"],
  "enabledMcpServers": ["brave-search"],
  "team": ["agent-uuid", ...],
  "createdAt": "...",
  "updatedAt": "..."
}
```

**POST /agents** request: same shape minus server-managed fields (`id`, `createdAt`, `updatedAt`).
- 201: full agent.
- 400: validation error (length cap, memorySize range, unknown tool, unknown MCP, sampling parameter range).
- 409: duplicate name for this owner (`REQ-AGT-002`); team rule violation (`REQ-AGT-013`); team member belongs to another owner (`REQ-AGT-012`).

**PUT /agents/{id}** — full replace; same body, same status codes; takes effect on the next turn of any ongoing conversation (`REQ-AGT-014`).

**DELETE /agents/{id}**
- 204 on success; 404 if not found / not owned.
- Cascades to conversations and messages.

#### 6.2.8 Conversations — `/api/v1/conversations`

All endpoints `[Auth: USER or SYSTEM]`. Scope: caller's own conversations.

> Note on SYSTEM principal: an API-key call has its own (virtual) ownership scope (`REQ-AUTH-007`). It can manage its own conversations; it cannot see end-user conversations.

| Method | Path                                              | Purpose                                            |
|--------|---------------------------------------------------|----------------------------------------------------|
| GET    | `/conversations`                                  | List own conversations (cursor paginated)          |
| POST   | `/conversations`                                  | Start a new conversation with an agent             |
| GET    | `/conversations/{conversationId}`                 | Get conversation metadata                          |
| PATCH  | `/conversations/{conversationId}`                 | Edit title (`REQ-CHAT-005` user-edit clause)       |
| DELETE | `/conversations/{conversationId}`                 | Delete (`REQ-CHAT-003`)                            |
| GET    | `/conversations/{conversationId}/messages`        | List messages (cursor paginated, oldest-first)     |
| POST   | `/conversations/{conversationId}/messages`        | **Send a message — SSE streamed response**         |

**POST /conversations**
- Request: `{ "agentId": "uuid" }` — no message yet; title remains `null` until the first message is sent.
- 201: `{ "id": "uuid", "agentId": "uuid", "title": null, "messageCount": 0, "createdAt": "...", "updatedAt": "..." }`.
- 404 if agent unknown / not owned.

**Conversation representation** (GET, PATCH 200):
```json
{
  "id": "uuid",
  "agentId": "uuid",
  "title": "string|null",
  "messageCount": 0,
  "createdAt": "...",
  "updatedAt": "..."
}
```

**PATCH /conversations/{id}**
- Request: `{ "title": "string ≤32" }`
- 200: updated representation.
- 400: title too long.

**GET /conversations/{id}/messages**
- 200: `{ "items": [ { "id": "uuid", "role": "USER|ASSISTANT", "content": "...", "createdAt": "..." } ], "nextCursor": "..." | null }`
- Default order: `created_at ASC` (chronological), so the frontend can append.

**POST /conversations/{id}/messages — streaming**
- Content-Type: `application/json`; **Accept: `text/event-stream`** (the controller honors only SSE; `406` if not negotiated).
- Request body: `{ "content": "string ≤1024" }`.
- Response: 200 with `Content-Type: text/event-stream; charset=UTF-8` and SSE frames defined in §7.
- 400 if content too long / empty.
- 409 if conversation is at the 64-message cap (`REQ-CHAT-010`).
- 404 if conversation not owned / not found.

### 6.3 Forced password change

When `mustChangePassword=true`, the `ForcedPasswordChangeFilter` (after the auth filters) blocks every endpoint **except** `PUT /auth/password` and `POST /auth/logout`, returning `403` with code `MUST_CHANGE_PASSWORD` (`REQ-USR-007`). The login response already advertises the flag so the frontend can pre-emptively route the user.

### 6.4 Health

- `GET /actuator/health` — public, returns `{ "status": "UP" }` style payload (`REQ-OBS-003`). Spring Boot Actuator default; documented in `openapi.yaml` only as an informational endpoint outside `/api/v1`.

---

## 7. Streaming (SSE)

`POST /conversations/{id}/messages` is the only SSE endpoint.

### 7.1 Frame format

Each SSE frame is a typed event:

```
event: <type>
data: <UTF-8 JSON>
```

Event types:

| Event       | Payload (JSON)                                           | When                                   |
|-------------|----------------------------------------------------------|----------------------------------------|
| `started`   | `{ "userMessageId": "uuid", "conversationId": "uuid" }`  | After the user message is persisted    |
| `delta`     | `{ "text": "incremental string" }`                       | For each LLM token chunk               |
| `completed` | `{ "assistantMessageId": "uuid", "title": "string|null", "messageCount": int }` | After the assistant message is persisted |
| `error`     | RFC-7807 problem detail (see §9)                         | On any error during streaming          |

The `title` field of `completed` is non-null on the very first turn (auto-derived per `REQ-CHAT-005`), null otherwise.

### 7.2 Server flow (sketch)

```
SendMessageService.handle(req):
  1. load conversation + agent (verify ownership; verify message_count < 64)
  2. persist USER message; bump message_count; if first message → derive title or fallback
     emit `started`
  3. load memory window: last (memorySize) messages of role USER/ASSISTANT
  4. build LLM ChatRequest:
       - system: agent.systemPrompt (+ tool / MCP wiring per agent config)
       - messages: memory window + new user message
       - model = agent.llmModel || platform-default
       - sampling = agent's overrides where present
  5. call llmChatClient.stream(request) → Flux<ChatChunk>
        for each chunk: emit `delta`; accumulate
  6. on Flux completion:
       persist assistant message; bump message_count
       emit `completed`; complete the SseEmitter
  7. on Flux error / client cancel:
       cancel upstream; emit `error` (if still open); complete the SseEmitter
```

### 7.3 Cancellation (`REQ-STR-003`)

`SseEmitter.onTimeout()` and `.onCompletion()` (which Tomcat fires when the client disconnects) call `Disposable.dispose()` on the upstream Flux subscription.

### 7.4 Persistence ordering (`REQ-STR-002`)

- The user message is persisted **before** the LLM call begins.
- The assistant message is persisted **after** streaming completes successfully. If the stream errors mid-way, no partial assistant message is persisted; the conversation length advances by 1 (the user message), not 2.

---

## 8. Authentication, authorization, security

### 8.1 Filter chain (Spring Security)

Order matters. From outermost to innermost:

```
1. RateLimitFilter            (Bucket4j, global bucket, 429 on rejection)         REQ-RL-*
2. JwtAuthenticationFilter    (sets Authentication if Authorization: Bearer ...)  REQ-AUTH-002/006/011
3. ApiKeyAuthenticationFilter (sets Authentication if X-Client-Id+X-Api-Key)      REQ-AUTH-007
4. ForcedPasswordChangeFilter (blocks most endpoints when must_change_password)   REQ-USR-007
5. Spring Security AuthZ      (URL/method based + method security on @PreAuthorize)
6. Controllers
```

If both auth headers are present, JWT wins; the API-key filter short-circuits if a `SecurityContext` is already authenticated.

### 8.2 JWT issuance and validation

- Algorithm: **HS256** (`REQ-AUTH-010`).
- Signing secret: from environment variable `JWT_SIGNING_SECRET`. Application fails fast at startup if missing/empty.
- Lifetime: 30 minutes (`REQ-AUTH-004`), configurable via `app.security.jwt.lifetime`.
- Claims (`REQ-AUTH-003`):
  ```
  sub:   <email>
  role:  ADMIN | STANDARD
  jti:   <UUID v4>           — used by denylist
  iat:   <issued-at>
  exp:   <expiry>
  ```
- Validation per request: signature, `exp`, denylist lookup by `jti`. Failure → 401 with the generic `INVALID_CREDENTIALS` code (`REQ-AUTH-009`).

### 8.3 JWT denylist (`REQ-AUTH-006` exception, `REQ-AUTH-011`)

- Adapter: `InMemoryJwtDenylistAdapter` for v1 single-node deployments.
- Map keyed by `jti` → expiry timestamp; entries are bounded by JWT lifetime.
- A scheduled task (`@Scheduled(fixedDelay=60_000)`) sweeps expired entries; under load, lookups also evict on read. The denylist is therefore O(active-logged-out tokens), which is bounded by `concurrent users × 1` — well under any concern given the 64-user target.
- Persistence across restart is not required (`REQ-AUTH-011`): on restart, denylisted tokens become valid again, but their natural `exp` is at most 30 minutes in the future, which matches the original "TTL-only" baseline of `REQ-AUTH-006`. Documented as a known design trade-off; if multi-node is later added (TBD-1), swap in a Redis-backed adapter.

### 8.4 API-key authentication (`REQ-AUTH-007`)

- Headers: `X-Client-Id`, `X-Api-Key`.
- Validation: lookup by `client_id`, BCrypt-compare submitted key against `api_key_hash`, ensure `disabled=false`.
- On success, an `Authentication` is set with principal = a singleton `SystemPrincipal` carrying the `client_id`.
- Scope: full chat capabilities under the system principal's own ownership; **no admin endpoints**, **no end-user resources** (`REQ-AUTH-007`). Enforced via `@PreAuthorize`-style rules; URL allow-list for `/admin/**` excludes any non-`ADMIN` authentication regardless of source.

### 8.5 Password handling

- Policy enforcement at the domain level (`Password` value object): length ≥ 10, ≥ 1 `[A-Z]`, ≥ 1 special char from `!@#$%^&*()-_=+[]{};:'",.<>/?\|~\``.
- BCrypt cost factor 10 (Spring Security default) — sufficient at our scale.
- Plain-text passwords never stored or logged (`REQ-SEC-002`). The DTO `password` field is annotated to be redacted in any toString.

### 8.6 Authorization rules

| Resource                 | STANDARD                              | ADMIN                                      | SYSTEM                                |
|--------------------------|---------------------------------------|--------------------------------------------|---------------------------------------|
| `/auth/*`                | own login/logout/password             | own login/logout/password                  | n/a (API key callers don't login)     |
| `/admin/**`              | 403                                   | full                                       | 403                                   |
| `/agents/**`             | own agents only                       | own agents only (ADMIN does not see others)| 403 (system has no agent ownership)   |
| `/conversations/**`      | own conversations only                | own conversations only                     | own (system) conversations only       |
| `/tools`, `/mcp-servers` | read                                  | read                                       | read                                  |

Note: ADMIN does **not** automatically gain visibility on other users' agents/conversations — `REQ-AUTH-008` says admins gain "user-management capabilities", not data superuser rights. This is consistent with `REQ-USR-006` cascading hard-deletes (admin can drop a user account but never read their data).

### 8.7 Sensitive-data logging (`REQ-SEC-004`)

- `JwtAuthenticationFilter` and `ApiKeyAuthenticationFilter` log only at DEBUG, and never the raw header value.
- `OpenAiChatClientAdapter` does not log the `OPENAI_API_KEY` or full request body containing user content at INFO; user content is at TRACE only.
- Logback config installs a converter that masks any token-shaped substring at the appender level as a defense in depth.

---

## 9. Error handling

### 9.1 Exception hierarchy (per `EXCEPTIONS.md`)

```
domain
└── BusinessException (abstract)
    ├── ValidationException     → 400
    ├── NotFoundException       → 404
    ├── ConflictException       → 409
    ├── ForbiddenException      → 403
    └── (concrete subclasses live in each bounded context, e.g. DuplicateAgentNameException extends ConflictException)

application
└── UseCaseExecutionException   → 500 (rare; wraps unexpected orchestration failures)

infrastructure
├── DatabaseAccessException     → 503 (or 500)
└── ExternalServiceException    → 502 (LLM provider failure, MCP server failure)
```

### 9.2 GlobalExceptionHandler

A single `@RestControllerAdvice` maps every domain/infra exception to a Problem Details response body and HTTP status. It also handles Spring framework exceptions (`MethodArgumentNotValidException` → 400, `HttpRequestMethodNotSupportedException` → 405, etc.).

### 9.3 Error response shape (RFC 7807 + small extensions)

```json
{
  "type": "https://errors.multi-agent-platform/<machine-code>",
  "title": "Short human title",
  "status": 400,
  "detail": "Longer message safe to surface",
  "instance": "/api/v1/agents",
  "code": "VALIDATION_ERROR",
  "errors": [
    { "field": "name", "message": "must be at most 32 characters" }
  ]
}
```

`code` is a stable machine identifier the frontend can switch on. Defined codes (extensible):

| code                         | Status | Used when                                              |
|------------------------------|--------|--------------------------------------------------------|
| `VALIDATION_ERROR`           | 400    | Bean validation, value-object policy violation         |
| `INVALID_CREDENTIALS`        | 401    | Login fail / bad token / disabled key (generic)        |
| `MUST_CHANGE_PASSWORD`       | 403    | Forced password change blocking access                 |
| `FORBIDDEN`                  | 403    | Authorization check failed                             |
| `NOT_FOUND`                  | 404    | Any not-found                                          |
| `METHOD_NOT_ALLOWED`         | 405    |                                                        |
| `CONFLICT`                   | 409    | Generic conflict                                       |
| `DUPLICATE_AGENT_NAME`       | 409    | Per-owner unique violation (`REQ-AGT-002`)             |
| `NESTED_TEAM_FORBIDDEN`      | 409    | Single-level team rule (`REQ-AGT-013`)                 |
| `CROSS_OWNER_TEAM_MEMBER`    | 409    | Team member of another user (`REQ-AGT-012`)            |
| `CONVERSATION_FULL`          | 409    | 64-message cap (`REQ-CHAT-010`)                        |
| `RATE_LIMITED`               | 429    | Bucket exhausted (`REQ-RL-005`); includes `Retry-After`|
| `LLM_UNAVAILABLE`            | 502    | OpenAI / provider failure                              |
| `MCP_SERVER_ERROR`           | 502    | MCP runtime failure                                    |
| `INTERNAL_ERROR`             | 500    | Unexpected                                             |

Stack traces are never returned (`REQ-API-004`).

---

## 10. Pagination

- Cursor-based, opaque to clients (`REQ-API-005`).
- Cursor = `Base64Url(JSON({ "ts": "2026-05-04T10:00:00Z", "id": "uuid" }))`.
- Backed by **keyset pagination**: `WHERE (created_at, id) < (:ts, :id) ORDER BY created_at DESC, id DESC LIMIT :pageSize+1`. The `+1` lets the server detect whether a next page exists (returned as `nextCursor`) without an extra count query.
- Query parameters: `cursor` (optional), `pageSize` (optional, default 20, max 100).
- Response envelope: `{ "items": [...], "nextCursor": "..." | null, "pageSize": int }`.
- Messages list orders **chronologically (ASC)**, others **DESC**. The `CursorCodec` helper accepts both directions.

---

## 11. Rate limiting

- Implementation: **Bucket4j** in-memory bucket — single global bucket per JVM (`REQ-RL-002`, `REQ-RL-003`).
- Two stacked limits: per-minute and per-hour (`REQ-RL-004`). Both must allow the request; the most restrictive 429s first. `Retry-After` derived from the time until the next refill.
- Live-reconfigurable: `RateLimitFilter` reads `RateLimitConfigRepository` on every refill window boundary (or on admin update via a small cache invalidation) and rebuilds the bucket. The repository is hit at most once every few seconds — negligible.
- The filter sits at the very top of the chain (§8.1) so unauthenticated traffic counts too.

---

## 12. LLM integration

- Spring AI 1.1.0 `ChatClient` is the underlying SDK.
- Adapter `OpenAiChatClientAdapter` implements the application port `LlmChatClient`, exposing:
  ```java
  Flux<ChatChunk> stream(ChatRequest req);
  ChatResult call(ChatRequest req);             // non-streaming variant; not used in v1 chat path
  ```
- `ChatRequest` carries: model, sampling params, system prompt, message history, attached tool descriptors, MCP enablement flags. The adapter translates to Spring AI's `ChatOptions`/messages.
- Provider abstraction: a future `AnthropicChatClientAdapter` would replace the adapter behind the same port without touching application/domain (`REQ-LLM-004`).
- Default model: `gpt-4o-mini` from `app.llm.openai.default-model`; agent override (`Agent.llmModel`) takes precedence.
- Credentials: `OPENAI_API_KEY` env var (`REQ-LLM-003`), bound via Spring property to keep tests injectable.
- Error mapping (`REQ-LLM-005`):
  - 4xx from OpenAI → `ExternalServiceException`, surfaced as 502 `LLM_UNAVAILABLE` (we never reflect provider 4xx as our 4xx — provider quirks are infrastructure problems).
  - Connection / timeout → 502 `LLM_UNAVAILABLE`.
  - Provider rate-limit (429) → still 502 `LLM_UNAVAILABLE`; we don't surface the provider's 429 as our 429 because our rate limiting is independent.

---

## 13. Tools

- `ToolCatalog` port returns a list of `ToolDescriptor` objects (`name`, `description`).
- v1 catalog (`REQ-TOOL-005`): a single tool, `AwsS3Tool`. The reference implementation in `backend/docs/AwsS3Tool.java` is moved into the `infrastructure/tool/` package and adapted to:
  - Be a Spring `@Component` (instead of relying on static state).
  - Read its AWS region from `app.aws.region`, default `eu-west-3` (preserving the example).
  - Use the application's IAM credentials (env / instance role).
- `ToolCatalogAdapter` discovers all Spring beans that have at least one `@Tool`-annotated method (Spring AI's tool-discovery mechanism), gathering their `name` and the tool description string. The catalog is populated once at startup and cached (`REQ-TOOL-001`).
- Tool wiring per agent: when building the `ChatRequest`, the use case filters the global catalog to those names listed in `agent.tools` and attaches them to the chat options.

---

## 14. MCP servers

- Configuration via `application.yaml` per Spring AI MCP conventions (`REQ-MCP-001`):
  ```yaml
  spring:
    ai:
      mcp:
        client:
          stdio:
            connections:
              brave-search:
                command: npx
                args: [-y, "@modelcontextprotocol/server-brave-search"]
                env:
                  BRAVE_API_KEY: ${BRAVE_API_KEY}
              filesystem:
                command: npx
                args: [-y, "@modelcontextprotocol/server-filesystem", "${app.mcp.filesystem.base}/users/${user.id}"]
  ```
- The `filesystem` server's path argument requires per-request user substitution. Spring AI MCP doesn't natively support per-call argument templating, so the backend exposes the filesystem server **through an internal proxy adapter**, `FilesystemMcpUserScopeAdapter`:
  - The adapter intercepts each MCP call destined for `filesystem`, resolves the calling user's root via `FilesystemMcpUserScope` (creates `{base}/users/{userId}` on demand at first use, `REQ-MCP-005`), and forwards the call to a per-user MCP process — or rewrites the path argument server-side.
  - **TBD-2**: which of "per-user MCP process" vs "shared MCP process with path-argument rewriting" we choose depends on Spring AI 1.1.0's MCP API surface. Both satisfy `REQ-MCP-005`; the design phase locks in the cleaner option once we prototype.
- `McpServerCatalogAdapter` reads the configured connection names directly from Spring AI's MCP configuration model and returns them via `GET /mcp-servers`.
- Error handling: MCP failures surface as `ExternalServiceException` → 502 `MCP_SERVER_ERROR`.

---

## 15. Configuration

All app-specific config lives under the `app.*` prefix in `application.yaml`, bound to a single `ApplicationProperties` record at startup. Spring AI / Spring MVC / Spring Security keep their native prefixes.

```yaml
app:
  api:
    base-path: /api/v1                                # REQ-API-006
  cors:
    allowed-origins:                                  # REQ-API-003
      - http://localhost:5173
  security:
    jwt:
      lifetime: PT30M                                 # REQ-AUTH-004
      signing-secret: ${JWT_SIGNING_SECRET}           # REQ-AUTH-010 (required, fail-fast)
  rate-limit:
    default-per-minute: 10                            # REQ-RL-004
    default-per-hour: 50
  llm:
    openai:
      api-key: ${OPENAI_API_KEY}                      # REQ-LLM-003
      default-model: gpt-4o-mini                      # REQ-LLM-002
  aws:
    region: eu-west-3
  mcp:
    filesystem:
      base: ${MCP_FS_BASE:/var/lib/multi-agent/fs}   # REQ-MCP-005

spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate.ddl-auto: validate                      # Flyway owns schema; JPA validates only
  flyway:
    enabled: true
    locations: classpath:db/migration
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat.options.model: ${app.llm.openai.default-model}
    mcp:
      client:
        stdio:
          connections:
            brave-search:
              command: npx
              args: [-y, "@modelcontextprotocol/server-brave-search"]
              env:
                BRAVE_API_KEY: ${BRAVE_API_KEY}
            filesystem:                              # see §14 about per-user proxying
              command: npx
              args: [-y, "@modelcontextprotocol/server-filesystem", "${app.mcp.filesystem.base}"]

logging:
  level:
    root: INFO
    com.cognizant.emk.multiagent: INFO
```

Required environment variables: `JWT_SIGNING_SECRET`, `OPENAI_API_KEY`, `BRAVE_API_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH` (used by Flyway), and optionally `MCP_FS_BASE`.

---

## 16. Sequence diagrams

### 16.1 Login

```
Client → AuthController:    POST /api/v1/auth/login {email, password}
AuthController → LoginUseCase
LoginUseCase → UserRepository.findByEmail(email)
LoginUseCase → PasswordHasher.matches(password, user.passwordHash)
LoginUseCase → JwtTokenService.issue(user) → token (jti, exp)
AuthController → Client:    200 { token, expiresAt, mustChangePassword }
```

### 16.2 Send message (streaming, no delegation)

```
Client → ConversationController:  POST /conv/{id}/messages {content}    Accept: text/event-stream
ConversationController → SendMessageUseCase.handle(...)

SendMessageUseCase:
  load conversation, agent (verify ownership, message_count<64)
  persist USER message; bump message_count; first message? derive title
  emit 'started'
  build memory window + ChatRequest from agent config
  llmChatClient.stream(request) → Flux<ChatChunk>
    on each chunk → emit 'delta'
  on Flux complete:
    persist ASSISTANT message; bump message_count
    emit 'completed'; SseEmitter.complete()
  on Flux error / client disconnect:
    cancel upstream
    emit 'error' if still open; SseEmitter.complete()
```

### 16.3 Send message with delegation

The agent (A) is configured with team={B}. During the LLM call, A decides to delegate.

```
Same as 16.2 up to llmChatClient.stream(request)

Inside the LLM tool/function call mechanism, A invokes a "delegate(taskDescription, agentName=B)" capability:
  → DelegationService.execute(taskDescription, B):
       build minimal ChatRequest for B with only the delegated task as user message
       (no parent conversation history; REQ-AGT-015)
       llmChatClient.call(B-request)  // non-streaming, transient
       returns the final string answer of B
       NOTHING is persisted from B's exchange
  → A's LLM continues with B's answer integrated as a tool result

A's stream proceeds → 'delta' frames flow as usual → final 'completed' frame.
```

The end-user only sees A's stream (`REQ-AGT-015`).

### 16.4 Logout

```
Client → AuthController:  POST /api/v1/auth/logout         Authorization: Bearer <token>
JwtAuthenticationFilter parses token; sets SecurityContext including jti
AuthController → LogoutUseCase.handle(jti, exp)
LogoutUseCase → JwtDenylist.add(jti, expiresAt)
AuthController → Client:  204
```

Subsequent requests with that token: `JwtAuthenticationFilter` checks denylist, sees the `jti`, returns 401.

---

## 17. Build & deployment

- Build: Maven, `./mvnw clean package` produces a fat JAR (`REQ-DEP-004`).
- Local run: `java -jar target/multi-agent-platform-*.jar` against a local PostgreSQL (`REQ-DEP-001`). No Docker required.
- AWS run: copy the JAR to the EC2 instance and start as a systemd service (`REQ-DEP-002`). RDS PostgreSQL recommended for the database, S3 for AwsS3Tool. Outbound calls to OpenAI and the MCP servers (which run as local subprocesses spawned by Spring AI MCP) run on the same EC2 instance.
- Health probe: `/actuator/health` for ELB / monitoring.

---

## 18. Test strategy

- **Domain**: pure JUnit 5 + AssertJ. Cover password policy, agent name uniqueness logic, team rules, memory size bounds, title derivation rules, message-cap accounting.
- **Application**: JUnit 5 + Mockito for use cases, mocking out-port interfaces.
- **Infrastructure**:
  - Persistence: Testcontainers PostgreSQL.
  - REST: `MockMvc` slice tests for controllers, full Spring Boot test for security flows (login, denylist, forced password change).
  - LLM adapter: WireMock against an OpenAI-shaped fake.

The coverage target is "all business rules in domain and application layers" (`REQ-NFR-002`).

---

## 19. Open design items (TBD)

These are implementation-level choices we deliberately defer; none of them block writing `openapi.yaml`.

- **TBD-1** — JWT denylist storage in multi-node deployments. v1 is single-node, in-memory. If we later run more than one node, swap `InMemoryJwtDenylistAdapter` for a Redis-backed one (or accept that nodes have independent denylists, given the 30-min TTL ceiling).
- **TBD-2** — Per-user filesystem MCP wiring. Two viable options under Spring AI 1.1.0; pick during implementation after a quick prototype. Both satisfy `REQ-MCP-005`.
- **TBD-3** — Whether the `delegate(...)` capability exposed to the parent agent is implemented as a Spring AI tool (so the LLM picks it via tool calling) or as a server-side post-processing step. Both fit `REQ-AGT-011/015`; the former is cleaner if Spring AI's tool dispatch composes well with the SSE stream, the latter is fully under our control.
- **TBD-4** — Sampling-parameter validation ranges (temperature, top-p, max output tokens). Use OpenAI's documented ranges as defaults; refine if other providers diverge.

---

## 20. Traceability matrix (selected)

| Requirement                | Carried in design section(s)                                  |
|----------------------------|---------------------------------------------------------------|
| REQ-ARC-002 hexagonal      | §2.1, §3                                                      |
| REQ-USR-007 admin bootstrap| §5.1 Flyway, §6.3, §8.1 filter                                 |
| REQ-AUTH-006 + REQ-AUTH-011| §8.2, §8.3, §16.4                                              |
| REQ-AUTH-007 system scope  | §8.4, §8.6, §6.2.8                                             |
| REQ-AGT-002 unique-per-owner| §4.1, §5 schema unique constraint                             |
| REQ-AGT-013 flat team      | §4.1 (`Team` value object), §6.2.7 conflict codes              |
| REQ-AGT-014 mutation live  | §7.2 step 4, §16.2                                             |
| REQ-AGT-015 delegation     | §16.3                                                          |
| REQ-CHAT-010 64-msg cap    | §4.1, §5 check, §6.2.8 conflict codes                          |
| REQ-CHAT-012 no tool msgs  | §4.1 (`MessageRole`), §5 check, §7                             |
| REQ-MCP-005 per-user fs    | §14, §15                                                       |
| REQ-API-005 cursor pages   | §10                                                            |
| REQ-API-006 base path      | §6.1, §15                                                      |
| REQ-RL-*                   | §11, §8.1                                                      |
| REQ-STR-*                  | §7                                                             |
| REQ-NFR-005 sizing         | §2.3, §8.3                                                     |

---

This design is sufficient to author `openapi.yaml`: every endpoint, payload, status code, error code, and authentication mode is fixed above. Unresolved items (TBD-1..4) are implementation-internal and do not affect the API contract.
