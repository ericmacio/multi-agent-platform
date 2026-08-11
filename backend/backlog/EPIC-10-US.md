# EPIC-10-US.md — User stories for EPIC-10

EPIC-10 — **Conversations & messages (non-streaming surface)**

This file lists the user stories that deliver EPIC-10. The EPIC ships the
**non-streaming** half of the chat surface: create / list / get / edit-title /
delete a conversation, list messages, and the persistence machinery for
messages and conversations. The streaming send-message endpoint
(`POST /conversations/{id}/messages`) is **out of scope** and owned by
EPIC-11; the domain invariants it relies on (64-message cap, `MessageRole`
enum, title rules) ship here because EPIC-11 needs them to build on.

> **Scope split with EPIC-06 / EPIC-11 / EPIC-12.**
> - **The SSE send-message endpoint** (`POST /conversations/{id}/messages`)
>   is EPIC-11. EPIC-10 stops at `GET /conversations/{id}/messages` for the
>   read path, ships the `ConversationFullException` + `CONVERSATION_FULL`
>   409 handler that EPIC-11 will throw, and leaves the `SendMessageService`
>   orchestration entirely to EPIC-11.
> - **Memory window assembly** (`REQ-AGT-005`) is EPIC-11 — the repository
>   port shipped here only needs to support "list the last N messages of a
>   conversation in chronological order", which `findMessagesByConversation`
>   covers as a side effect of the messages-list endpoint.
> - **Live agent mutation** (`REQ-AGT-014`) is EPIC-11 — at conversation
>   creation time the domain stores `agentId` only, and EPIC-11's chat-turn
>   code re-reads the agent at the start of every turn.
> - **Agent-team delegation** (`REQ-AGT-015`) is EPIC-12 — sub-agent calls
>   are not persisted as messages of the parent conversation, so this EPIC's
>   `Message` aggregate has nothing to say about them.
> - **The EPIC-02 JPA scaffolding** (`ConversationJpa`, `MessageJpa` +
>   `ConversationJpaRepository`, `MessageJpaRepository` from
>   US-02-005 / US-02-006) is reused. EPIC-10 reworks `ConversationJpa` to
>   carry mutually-exclusive `owner_user_id` / `owner_client_id` columns
>   (see US-10-002 for the migration and §8.6 / REQ-AUTH-007 for the
>   motivation).
> - **Cascade on agent deletion** is satisfied by the V001 FK chain
>   (`agents → conversations → messages`) and proven by the EPIC-02
>   `CascadeIntegrationTest`. EPIC-10 adds a new cascade test for the
>   delete-conversation REST path (US-10-009) but does not change schema
>   cascade rules.
> - **The `Cursor`, `Page<T>`, `PageDto<T>`, `CursorCodec`, `PageSize`**
>   plumbing from EPIC-04 / EPIC-05 is reused as-is; no new pagination
>   work.
> - **The `GlobalExceptionHandler`** already maps `ConflictException →
>   409 CONFLICT` (US-05-003) and `NotFoundException → 404 NOT_FOUND`
>   (US-03-001). EPIC-10 adds a single subclass-specific handler for
>   `ConversationFullException → 409 CONVERSATION_FULL` (US-10-004) so
>   the openapi-documented code is honored end-to-end; the generic
>   `NotFoundException` handler already covers `ConversationNotFoundException`
>   without a new entry.
> - **The `/api/v1/admin/**` and `/api/v1/agents/**` URL guards are
>   unchanged.** US-10-005 adds a new `/api/v1/conversations/**` guard that
>   admits STANDARD, ADMIN, **and** SYSTEM — the only of the three feature
>   surfaces (admin, agents, conversations) that SYSTEM may reach
>   (design §8.6).

## Conventions

- **ID format**: `US-10-<nnn>` — `10` matches the EPIC number; `<nnn>` is a
  sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as
  `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short
  description, a bullet list of testable acceptance criteria, the
  requirements coverage, the design references, and its dependencies.

## Story list

| ID         | Title                                                                                                            | Priority | Status | Depends on                                  |
|------------|------------------------------------------------------------------------------------------------------------------|----------|--------|---------------------------------------------|
| US-10-001  | `Conversation` / `Message` domain — aggregates, value objects, `ConversationOwner` sealed type, exceptions, repository port | MUST | Done  | US-06-001 (`AgentId`), US-04-001 (`ClientId`), US-03-002 (`UserId`) |
| US-10-002  | Flyway `V005__conversation_owner_split.sql` — replace `conversations.owner_id` with mutually-exclusive `owner_user_id` / `owner_client_id` | MUST | Done  | EPIC-02 (`V001` baseline), US-04-003 (`api_keys.client_id` FK target) |
| US-10-003  | `ConversationJpa` rework + `ConversationRepository` JPA adapter + domain ↔ JPA mapper                            | MUST     | Done   | US-10-001, US-10-002                        |
| US-10-004  | `ConversationFullException` + `CONVERSATION_FULL` 409 mapping in `GlobalExceptionHandler`                        | MUST     | Done   | US-10-001, US-05-003                        |
| US-10-005  | `StartConversationUseCase` + `POST /conversations` (+ `/conversations/**` URL guard for STANDARD / ADMIN / SYSTEM) | MUST   | Done   | US-10-001..003, US-04-009, US-06-006        |
| US-10-006  | `ListConversationsUseCase` + `GET /conversations` (with optional `agentId` filter)                               | MUST     | Done   | US-10-001..003, US-04-005                   |
| US-10-007  | `GetConversationUseCase` + `GET /conversations/{conversationId}`                                                 | MUST     | Done   | US-10-001..003                              |
| US-10-008  | `EditConversationTitleUseCase` + `PATCH /conversations/{conversationId}`                                         | MUST     | Done   | US-10-001..003                              |
| US-10-009  | `DeleteConversationUseCase` + `DELETE /conversations/{conversationId}`                                           | MUST     | Done   | US-10-001..003                              |
| US-10-010  | `ListMessagesUseCase` + `GET /conversations/{conversationId}/messages`                                           | MUST     | Done   | US-10-001..003, US-04-005                   |

---

## US-10-001 — `Conversation` / `Message` domain — aggregates, value objects, `ConversationOwner` sealed type, exceptions, repository port

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `Conversation` and `Message` aggregates, their value objects
(`ConversationId`, `MessageId`, `Title`, `MessageRole`, `MessageContent`,
`MessageCount`), the `ConversationOwner` sealed sum type (parallel to
`Principal`), the conversation-specific domain exceptions, and the
`ConversationRepository` port
**So that** every EPIC-10 use case (US-10-005 .. US-10-010) and EPIC-11's
`SendMessageService` operate on a Spring-free, fully-validated domain model
and bridge to JPA exclusively through the adapter delivered in US-10-003.

### Description

Everything lives under `domain/conversation/`. The package-info stub from
EPIC-01 is replaced with the real classes by this story. The
`ConversationOwner` sealed type is the load-bearing decision: it mirrors the
existing `Principal` sealed type (`UserPrincipal | SystemPrincipal`,
US-04-001) so that a conversation can be owned by either a `UserOwner`
carrying a `UserId` or a `SystemOwner` carrying a `ClientId` — without the
domain ever depending on Spring Security types. This is the cleanest way to
honour REQ-AUTH-007's "SYSTEM SHALL NOT see end-user resources" plus
REQ-CHAT-007's "owner-scoped access" through the same query path, and it is
what makes the schema change in US-10-002 type-safe upstream.

The aggregate is intentionally **small**: the `Conversation` value object
carries identity, ownership, target agent, title, message count, and
timestamps — but NOT the list of messages. Messages live in their own
aggregate (`Message`) addressed by `(conversationId, messageId)`. This
mirrors the EPIC-06 `Agent` / `AgentTeam` split, keeps the 64-message cap
enforceable atomically (US-10-005 / EPIC-11 bump `messageCount` in the
same row), and keeps memory-window queries (`findLastN`) decoupled from
the conversation read path.

### Acceptance criteria

- `domain/conversation/ConversationId.java` — record `ConversationId(UUID
  value)` with non-null check. Mirrors `AgentId` / `UserId` shape.
- `domain/conversation/MessageId.java` — record `MessageId(UUID value)`
  with non-null check.
- `domain/conversation/MessageRole.java` — enum `{ USER, ASSISTANT }`:
  - Locked to **exactly** these two values per REQ-CHAT-012. Tool-call
    requests / results are NEVER persisted; the enum has no `TOOL` or
    `SYSTEM` variant.
  - Order is `USER, ASSISTANT` to match the persisted DB `check (role in
    ('USER', 'ASSISTANT'))` from V001 and the openapi `MessageRole` enum
    declaration order.
- `domain/conversation/MessageContent.java` — record `MessageContent(String
  value)` enforcing:
  - non-null, non-blank;
  - `value.length() <= 1024` (REQ-CHAT-009 + openapi `content` cap);
  - violations throw `ValidationException` with field `content`.
- `domain/conversation/Title.java` — record `Title(String value)` enforcing:
  - non-null, non-blank;
  - `value.length() <= 32` (REQ-CHAT-005 + openapi cap);
  - violations throw `ValidationException` with field `title`.
  - Static helpers:
    - `Title.fromFirstUserMessage(MessageContent firstMessage)` —
      truncates the message's content to the first 32 characters, trimming
      surrounding whitespace; returns `Optional<Title>` empty if the
      content is blank after trim (REQ-CHAT-005 ignore-empty rule).
    - `Title.defaultFor(ConversationId id)` — returns `new Title("chat-" +
      id.value().toString())` truncated to 32 characters (matches the
      `chat-<uuid>` fallback in REQ-CHAT-005; the truncation keeps the
      36-char default within the 32 cap).
  - Both helpers are pure functions; the *when* of derivation (only on
    the first non-empty user message) is owned by EPIC-11's
    `SendMessageService`. This story only ships the building blocks.
- `domain/conversation/MessageCount.java` — record `MessageCount(int value)`
  enforcing:
  - `0 <= value <= 64` (REQ-CHAT-010 cap + DB `check (message_count between
    0 and 64)`);
  - violations throw `ValidationException` with field `messageCount`.
  - Static `MessageCount EMPTY = new MessageCount(0);` exposes the empty
    state for the REST adapter and use cases.
  - Convenience methods: `boolean isFull()` returning `value == 64`,
    `MessageCount increment()` returning a new instance or throwing
    `ConversationFullException` (see below) when already at 64.
- `domain/conversation/ConversationOwner.java` — sealed interface:
  ```java
  public sealed interface ConversationOwner
          permits ConversationOwner.UserOwner, ConversationOwner.SystemOwner {

      record UserOwner(UserId userId) implements ConversationOwner {
          public UserOwner { Objects.requireNonNull(userId, "userId"); }
      }

      record SystemOwner(ClientId clientId) implements ConversationOwner {
          public SystemOwner { Objects.requireNonNull(clientId, "clientId"); }
      }
  }
  ```
  - Static factory `ConversationOwner.from(Principal principal)` returns
    a `UserOwner` for `UserPrincipal` and a `SystemOwner` for
    `SystemPrincipal`. The exhaustive switch is checked at compile time
    thanks to the sealed `Principal` hierarchy.
- `domain/conversation/Conversation.java` — record `Conversation`:
  ```java
  public record Conversation(
      ConversationId id,
      AgentId agentId,
      ConversationOwner owner,
      Title title,                     // nullable until first non-empty user message
      MessageCount messageCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt
  ) {}
  ```
  - Canonical-constructor validation:
    - non-null `id`, `agentId`, `owner`, `messageCount`, `createdAt`,
      `updatedAt`;
    - `title` MAY be null (REQ-CHAT-005 — title is auto-derived on the
      first user message; until then `null`);
  - Domain helpers (pure, do NOT touch the repository):
    - `Conversation withTitle(Title newTitle, OffsetDateTime now)` —
      returns a copy with the new title and bumped `updatedAt`. Throws
      `ValidationException` if `newTitle` is null (the edit-title API
      requires a non-null body; clearing the title back to `null` is not
      a supported operation per the openapi `UpdateConversationRequest`).
    - `Conversation incrementMessageCount(OffsetDateTime now)` — returns
      a copy with `messageCount.increment()` and bumped `updatedAt`;
      throws `ConversationFullException` (via `MessageCount.increment`)
      if the count is already 64.
- `domain/conversation/Message.java` — record `Message`:
  ```java
  public record Message(
      MessageId id,
      ConversationId conversationId,
      MessageRole role,
      MessageContent content,
      OffsetDateTime createdAt
  ) {}
  ```
  - Canonical-constructor validation: every field non-null.
- `domain/conversation/ConversationNotFoundException.java` — final class
  extending `NotFoundException` (from `domain/shared/`, US-03-001). Maps to
  HTTP 404 `NOT_FOUND` via the existing generic handler — no new
  `GlobalExceptionHandler` entry. Constructor takes a `ConversationId`;
  message is `"Conversation not found: " + id.value()`.
- `domain/conversation/ConversationFullException.java` — final class
  extending `ConflictException` (from `domain/shared/`, US-05-003).
  Constructor takes a `ConversationId`; message is `"Conversation " + id
  .value() + " has reached the 64-message cap"`. The handler entry that
  maps this subclass to the openapi code `CONVERSATION_FULL` (not the
  generic `CONFLICT`) ships in US-10-004.
- `domain/conversation/ConversationRepository.java` — interface:
  ```java
  public interface ConversationRepository {

      Conversation save(Conversation conversation);

      Optional<Conversation> findById(ConversationId id);

      Page<Conversation> listByOwner(
              ConversationOwner owner,
              Optional<AgentId> agentFilter,
              Cursor cursor,
              PageSize pageSize);

      void deleteById(ConversationId id);

      // --- messages (shipped together because Message has no aggregate
      //     of its own beyond the parent conversation) ---

      Message appendMessage(Message message);

      Page<Message> listMessages(
              ConversationId conversationId,
              Cursor cursor,
              PageSize pageSize);

      /** Returns the last N messages in chronological ascending order; used
       *  by EPIC-11's memory-window assembly. EPIC-10 ships only the
       *  signature; EPIC-11 adds the JPA implementation if not provided
       *  for free by US-10-003. */
      List<Message> findLastN(ConversationId conversationId, int n);
  }
  ```
  - Returns `Optional<Conversation>` on `findById` (consistent with
    `UserRepository.findByEmail` from EPIC-03 and
    `AgentRepository.findById` from EPIC-06 — `Optional`, not `null`).
  - `listByOwner` takes a `ConversationOwner` (not separately-typed
    UserId/ClientId parameters) so the adapter can dispatch on the sealed
    type and emit the right `where` clause against the new XOR columns
    introduced by US-10-002.
  - The `agentFilter` is an `Optional<AgentId>` because the openapi
    documents an **optional** `agentId` query parameter on
    `GET /conversations` (US-10-006).
  - `appendMessage` is named explicitly (vs `save`) to convey that messages
    are append-only — they are never updated, never deleted other than
    cascade-on-conversation-delete. This is also a hook for EPIC-11's
    persistence ordering (REQ-STR-002 — user message before LLM call,
    assistant message after stream completion).
  - `findLastN` is the only memory-window primitive; the cap (`n`) comes
    from `Agent.memorySize().value()`, resolved by EPIC-11.
- `domain/conversation/package-info.java` is updated:
  - Drop the "Populated by EPIC-10" placeholder for the parts shipped by
    this story.
  - Add a sentence stating that the SSE send-message use case
    (`SendMessageService`) and the memory-window assembly land in
    EPIC-11 and consume this package's port and aggregates without
    extending them.
- Pure-Java unit tests under
  `src/test/java/com/cognizant/emk/multiagent/domain/conversation/`:
  - `ConversationIdTest`, `MessageIdTest` — non-null check.
  - `MessageRoleTest` — exactly two values in the documented order;
    no `valueOf("TOOL")` etc.
  - `MessageContentTest` — accepts a valid 1024-char content; rejects
    null, blank, and 1025-char content with field `content`.
  - `TitleTest`:
    - Direct constructor: accepts a valid 32-char value; rejects null,
      blank, and 33-char value with field `title`.
    - `Title.fromFirstUserMessage(new MessageContent("Hello world"))`
      returns `Optional.of(new Title("Hello world"))`.
    - `Title.fromFirstUserMessage(new MessageContent(longString40Chars))`
      returns `Optional.of` whose value equals the first 32 chars after
      trim.
    - `Title.fromFirstUserMessage(new MessageContent("   "))` — actually,
      `MessageContent` already rejects blank content, so this case asserts
      that `MessageContent` rejection is what stops the empty-title path;
      a separate assertion on `Title.fromFirstUserMessage` with a content
      that is non-blank but trims to empty (e.g., `"   x   "` → trims to
      `"x"`, returns `Optional.of(new Title("x"))`) verifies the trim
      rule.
    - `Title.defaultFor(new ConversationId(UUID.fromString("a9b9bb11-1234-4abc-9def-1234567890ab")))`
      returns a title whose value starts with `"chat-"` and whose total
      length is `<= 32`.
  - `MessageCountTest` — accepts `[0, 64]`; rejects `-1` and `65` with
    field `messageCount`; `EMPTY.value() == 0`; `new MessageCount(5)
    .increment().value() == 6`; `new MessageCount(64).isFull() == true`;
    `new MessageCount(64).increment()` throws
    `ConversationFullException` (the construction site supplies the
    `ConversationId` via the calling `Conversation.incrementMessageCount`
    helper — `MessageCount.increment()` re-throws the exception thrown by
    its caller via a small dedicated method `MessageCount
    .incrementOrThrow(ConversationId id)`; the API is documented in the
    record's Javadoc so the call-site stays clean).
  - `ConversationOwnerTest`:
    - `UserOwner(null)` throws NPE with `"userId"`.
    - `SystemOwner(null)` throws NPE with `"clientId"`.
    - `ConversationOwner.from(new UserPrincipal(...))` returns a
      `UserOwner` whose `userId` matches the principal.
    - `ConversationOwner.from(new SystemPrincipal(new ClientId("svc-a")))`
      returns a `SystemOwner` whose `clientId` matches the principal.
    - An exhaustive switch over `ConversationOwner` compiles without a
      `default` branch — proven by a small switch statement in the test
      class itself (the test compiles and runs; if a future contributor
      adds a third permitted subtype without updating the switch, the
      compiler fails).
  - `ConversationTest`:
    - Construction with all non-null fields succeeds; `title=null` is
      accepted (pre-first-message state).
    - Construction with any of `id`, `agentId`, `owner`, `messageCount`,
      `createdAt`, `updatedAt` null throws NPE with that field name.
    - `withTitle(new Title("renamed"), now)` returns a new instance with
      the new title and `updatedAt = now`; the source instance is
      unchanged (record immutability).
    - `withTitle(null, now)` throws `ValidationException` with field
      `title`.
    - `incrementMessageCount(now)` on a conversation with `messageCount =
      63` returns a new instance with `messageCount.value() == 64` and
      `updatedAt = now`.
    - `incrementMessageCount(now)` on a conversation with `messageCount =
      64` throws `ConversationFullException` whose message contains the
      conversation id.
  - `MessageTest`:
    - Construction with all non-null fields succeeds.
    - Each null field individually throws NPE with the relevant name.
- `LayeringArchTest` extension: a new rule
  `no_spring_imports_in_domain_conversation` asserts that the
  `domain.conversation` package contains zero Spring, JPA, or JJWT
  imports — only `java.*`, `domain.shared.*`, `domain.user.*`,
  `domain.agent.*`, and `domain.auth.*` (for `ClientId` and `Principal`
  on the `from(Principal)` factory).

### Out of scope

- The `ConversationJpa` rework + schema migration (US-10-002 / US-10-003).
  This story ships only the Spring-free domain.
- The use cases that compose with this aggregate (US-10-005 .. US-10-010).
- EPIC-11's `SendMessageService` and the title-derivation orchestration
  (when on the wire — REQ-CHAT-005 says "set at the time the first
  non-empty user message is recorded"; this story ships the pure
  `Title.fromFirstUserMessage` building block but not the orchestration).
- Delegating EPIC-12 — no `Message` is persisted for sub-agent turns;
  no domain field is added for delegation context.

### Requirements coverage

`REQ-CHAT-001` (chat domain — owner-scoped), `REQ-CHAT-002` (persistent
storage shape), `REQ-CHAT-005` (title rules — building blocks),
`REQ-CHAT-007` (owner-scoped access — domain side), `REQ-CHAT-009`
(message metadata: role, content ≤1024, timestamp), `REQ-CHAT-010` (64
cap — domain invariant via `MessageCount`), `REQ-CHAT-012` (USER /
ASSISTANT only), `REQ-AUTH-007` (SYSTEM principal scope — modeled via
`ConversationOwner.SystemOwner`), `REQ-ARC-002` (hexagonal — domain free
of Spring), `REQ-ARC-007` (exception typology — `ConflictException` /
`NotFoundException` subclasses).

### Design references

§3 project structure (`domain/conversation/`), §4.1 Conversation /
Message entities, §4.2 invariants (`Conversation total messages ≤ 64`,
`Messages persisted = USER or ASSISTANT only`, `Title derived once`),
§4.3 lifecycle, §8.6 authorization rules (SYSTEM scope on
`/conversations/**`).

### Dependencies

- US-06-001 (`AgentId` value object reused on `Conversation.agentId`).
- US-04-001 (`ClientId` value object reused on
  `ConversationOwner.SystemOwner`, and the sealed `Principal` hierarchy
  the `from(Principal)` factory dispatches on).
- US-03-002 (`UserId` value object reused on
  `ConversationOwner.UserOwner`).
- EPIC-02 (existence of the `conversations` / `messages` tables; the
  domain types are designed to match their column shapes — the actual
  schema rework lives in US-10-002).

---

## US-10-002 — Flyway `V005__conversation_owner_split.sql` — replace `conversations.owner_id` with mutually-exclusive `owner_user_id` / `owner_client_id`

- **Status**: Done
- **Priority**: MUST

**As a** platform operator
**I want** the `conversations` table to carry two **mutually-exclusive
nullable** owner columns — `owner_user_id` (FK to `users`) and
`owner_client_id` (FK to `api_keys.client_id`) — with a `check` constraint
enforcing exactly one being non-null, and the historic single `owner_id`
column dropped
**So that** SYSTEM-principal-owned conversations (REQ-AUTH-007, design
§8.6) can be persisted alongside USER-principal-owned conversations
through a single table without compromising the cascade-on-user-delete
guarantee (REQ-USR-006), without compromising the cascade-on-api-key-delete
guarantee (any future API-key delete cascades through `owner_client_id`),
and without leaving any row whose ownership is ambiguous.

### Description

`V001__init_schema.sql` ships `conversations.owner_id uuid not null
references users(id) on delete cascade`. That FK forecloses storing a
SYSTEM-principal-owned conversation, because SYSTEM principals (US-04-001)
are identified by `ClientId` and live in the `api_keys` table, not in
`users`. EPIC-10's scope ("Owner-scoped access for both `USER` and `SYSTEM`
principals") and the openapi contract (both `BearerAuth` and `ApiKeyAuth`
on every `/conversations/**` endpoint) require the schema to model both.

The migration takes the **two-column XOR** approach over the alternatives
(single nullable `owner_id` + `owner_kind` discriminator, or a seeded
"system-user" row in `users`). Reasons recorded in
`backend/backlog/DESIGN-CHOICES.md` as part of this story:

1. **Cascade fidelity** — two independent FK columns let PostgreSQL
   cascade-delete a user's conversations when the user is removed
   (REQ-USR-006) and cascade-delete a SYSTEM principal's conversations
   when its API key is removed (future story; v1 today has only soft
   revoke per US-04-008, but the FK column already prepares the ground).
   A `owner_kind` discriminator would need application-side cascade
   logic.
2. **No fake "system" user** — a seeded user row would pollute
   `GET /admin/users`, would conflict with the `users.email` unique
   constraint (no plausible canonical address), and would silently
   change the meaning of `REQ-USR-002`. Cleaner to keep the two
   identity systems separate.
3. **Type-safety upstream** — the domain side already models ownership
   as the sealed `ConversationOwner` (US-10-001); the two-column XOR
   maps to the sealed type by construction. A single nullable
   `owner_id` plus a string `owner_kind` would force the adapter to do
   string-equality on every read.

The migration is **forward-only** (the project convention; Flyway has no
down-migrations). The only existing row policy is: in the current
codebase, no conversation row has ever been persisted to a deployed
database — EPIC-09 was the last shipped EPIC and produced no conversation
rows. The migration therefore migrates existing rows by copying
`owner_id → owner_user_id` (defensive — handles dev databases that
already have hand-created test rows), then drops the old column. The
migration is idempotent against an empty `conversations` table.

### Acceptance criteria

- `backend/src/main/resources/db/migration/V005__conversation_owner_split.sql`
  is created and contains, in order:
  ```sql
  -- =============================================================================
  -- V005__conversation_owner_split.sql
  -- EPIC-10 / US-10-002.
  -- Replace conversations.owner_id with mutually-exclusive owner_user_id /
  -- owner_client_id columns so SYSTEM principals (REQ-AUTH-007) can own
  -- conversations alongside JWT-authenticated end users.
  -- Forward-only; no down-migration.
  -- =============================================================================

  -- 1. Add the two new nullable columns.
  alter table conversations
      add column owner_user_id   uuid references users(id)          on delete cascade,
      add column owner_client_id varchar(64) references api_keys(client_id) on delete cascade;

  -- 2. Backfill existing rows (defensive — empty in production at v1).
  update conversations set owner_user_id = owner_id where owner_id is not null;

  -- 3. Drop the legacy FK column and its index.
  drop index if exists idx_conversations_owner_created;
  alter table conversations drop column owner_id;

  -- 4. Enforce the XOR invariant.
  alter table conversations
      add constraint ck_conversations_owner_xor
      check ((owner_user_id is not null and owner_client_id is null)
          or (owner_user_id is null and owner_client_id is not null));

  -- 5. Indexes for the two read paths (US-10-006 listByOwner + agent filter).
  create index idx_conversations_user_created
      on conversations (owner_user_id, created_at desc, id desc)
      where owner_user_id is not null;
  create index idx_conversations_client_created
      on conversations (owner_client_id, created_at desc, id desc)
      where owner_client_id is not null;
  create index idx_conversations_agent_created
      on conversations (agent_id, created_at desc, id desc);
  ```
- The partial indexes are intentional: they keep the two owner-scoped
  list queries on a tight index without bloating it with rows of the
  wrong owner type.
- `idx_conversations_agent_created` supports the `agentId` query
  parameter on `GET /conversations` (US-10-006); the optimizer will pick
  the per-owner index plus this one as a bitmap-and intersection.
- The migration runs successfully against a fresh local PostgreSQL
  (V001 → V002 → V003 → V004 → V005) and idempotently against a copy
  with a few test rows (verified by `OwnerColumnSplitMigrationTest`
  below).
- `InitSchemaMigrationTest` (or its EPIC-10-time successor) is extended
  to assert post-migration:
  - The column `conversations.owner_id` is **absent**.
  - The columns `conversations.owner_user_id` and
    `conversations.owner_client_id` are **present** and nullable.
  - The two FKs target `users(id)` and `api_keys(client_id)` with
    `on delete cascade`.
  - The check constraint `ck_conversations_owner_xor` is present (read
    via `pg_constraint`).
  - The three indexes `idx_conversations_user_created`,
    `idx_conversations_client_created`,
    `idx_conversations_agent_created` are present (read via
    `pg_indexes`).
  - The pre-V005 index `idx_conversations_owner_created` is **absent**.
- A new `OwnerColumnSplitMigrationTest` (extending
  `PostgresIntegrationTest`):
  - Pre-stages a `users` row and a few `conversations` rows with the
    legacy `owner_id` populated by issuing a manual `insert` **before**
    running the migration (the test boots Flyway up to V004 only, then
    runs V005 manually via the Flyway API).
  - Asserts each pre-staged row, after the migration runs, has
    `owner_user_id` equal to its prior `owner_id` and
    `owner_client_id is null`.
  - Asserts that attempting to insert a row with both `owner_user_id`
    and `owner_client_id` populated fails with a
    constraint-violation referencing `ck_conversations_owner_xor`.
  - Asserts that attempting to insert a row with both null fails with
    the same constraint.
- `ConversationJpa` and `MessageJpa` mappers are NOT touched by this
  story — that rework is US-10-003. This story is migration-only on
  the schema side. Hibernate's `ddl-auto=validate` SHALL pass after
  US-10-003 lands, **not** after this story alone; the two stories
  land together in the same change set.
- A short note is added to
  `backend/implementation/DESIGN-CHOICES.md` capturing the two-column
  XOR vs `owner_kind` discriminator vs seeded "system user" trade-off
  (see Description). The note references this story's ID and the
  three reasons listed above.

### Out of scope

- The JPA / domain mapper rework that consumes the new columns
  (US-10-003).
- Hard-deleting an API key (i.e., `DELETE /admin/api-keys/{clientId}`).
  v1's API-key revocation is **soft** (`disabled` flag — US-04-008);
  the `on delete cascade` on `owner_client_id` is forward-prepared
  for a future hard-delete story but not exercised in v1.
- Seeding a "system" user row in `users`. Explicitly rejected for the
  three reasons listed above.

### Requirements coverage

`REQ-PRS-001`, `REQ-PRS-002`, `REQ-PRS-003`, `REQ-AUTH-007`,
`REQ-CHAT-002`, `REQ-USR-006`.

### Design references

§5 database schema (`conversations` table; this story revises it),
§5.1 Flyway migrations, §5.2 cascade rules, §8.6 authorization rules
(SYSTEM scope justifying the column split).

### Dependencies

- EPIC-02 (`V001__init_schema.sql` baseline, `PostgresIntegrationTest`
  Testcontainers harness, `InitSchemaMigrationTest` framework).
- US-04-003 (`api_keys.client_id` is the FK target of
  `owner_client_id`; that column shape — `varchar(64) primary key` —
  is the constraint the new column matches).

---

## US-10-003 — `ConversationJpa` rework + `ConversationRepository` JPA adapter + domain ↔ JPA mapper

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** `ConversationJpa` reworked to carry the two new owner columns
from US-10-002, `MessageJpa` left as-is, the `ConversationRepositoryAdapter`
implementing the domain port from US-10-001, and a `ConversationJpaMapper`
translating between the domain `Conversation` / `Message` aggregates and
the JPA entities (including the sealed `ConversationOwner` ↔ XOR column
translation)
**So that** every EPIC-10 use case persists through a single adapter, the
sealed `ConversationOwner` type from US-10-001 is the only place ownership
shape is handled, and Hibernate's `ddl-auto=validate` boots green against
the V005 schema.

### Description

This is mechanical adapter / mapper work, structurally identical to
US-06-002 (`AgentRepository` adapter). The only novelty is the two-column
XOR mapping for ownership — the `ConversationJpaMapper` exhaustively
switches on `ConversationOwner` to populate exactly one of the two
columns at write time, and reconstructs the sealed type at read time
based on which column is non-null. Because the schema enforces the XOR
via `ck_conversations_owner_xor`, the read-side reconstruction can
assert that exactly one column is non-null and fail-fast with a
descriptive `DatabaseAccessException` if both or neither are.

`appendMessage` increments the parent conversation's `message_count` via
the `Conversation.incrementMessageCount` domain helper — the adapter
caller (`SendMessageService` in EPIC-11) is responsible for the atomic
read-modify-write under a `@Transactional` boundary. The 64-cap check
itself lives in the domain (`MessageCount.increment`); the adapter only
persists what the domain hands it.

Pagination uses the existing keyset-style `Cursor` / `Page<T>` plumbing
from EPIC-04. The two listing queries (`listByOwner`, `listMessages`)
are implemented with Spring Data JPA derived methods plus a
`@Query` for the cursor predicate, mirroring `AgentRepositoryAdapter`.

### Acceptance criteria

- `infrastructure/persistence/entity/ConversationJpa.java` reworked:
  - Removes the `owner` `@ManyToOne UserJpa` field.
  - Adds `private UserJpa ownerUser;` (nullable, `@ManyToOne(fetch =
    LAZY) @JoinColumn(name = "owner_user_id")`).
  - Adds `private ApiKeyJpa ownerApiKey;` (nullable, `@ManyToOne(fetch =
    LAZY) @JoinColumn(name = "owner_client_id", referencedColumnName =
    "client_id")`).
  - Adds getters / setters for the two new fields; removes
    `getOwner()` / its setter.
  - Keeps the `agent`, `title`, `messageCount`, timestamps fields and
    accessors unchanged.
  - Default protected no-arg constructor and the canonical constructor
    are updated to take both nullable owner fields. The canonical
    constructor does NOT enforce XOR — that lives in the mapper +
    domain; the entity is a dumb data carrier.
- `infrastructure/persistence/entity/MessageJpa.java` is **unchanged**
  — its schema-side surface is already correct (V001 already created
  `messages.conversation_id` as a non-null FK to `conversations`).
- `infrastructure/persistence/adapter/ConversationRepositoryAdapter.java`
  — new `@Component` implementing
  `domain.conversation.ConversationRepository`:
  - Constructor-injected with `ConversationJpaRepository`,
    `MessageJpaRepository`, and the two JPA refs needed to attach
    parent entities for new conversations: `UserJpaRepository`,
    `ApiKeyJpaRepository`, `AgentJpaRepository`.
  - `save(Conversation)`:
    - Resolves the parent agent by id; throws
      `AgentNotFoundException` if absent (the caller is expected to
      have verified ownership upstream — this is defense in depth).
    - Switches exhaustively on `ConversationOwner` to resolve the
      `UserJpa` or `ApiKeyJpa` parent reference and to set exactly
      one of the two owner fields. The other is set to `null`.
    - Persists via `conversationJpaRepository.save(...)` and returns
      the mapper-built `Conversation` (the `save` path also handles
      updates — `withTitle` and `incrementMessageCount` both go
      through here).
  - `findById(ConversationId id)` — delegates to
    `conversationJpaRepository.findById(id.value())` and maps to
    `Optional<Conversation>`.
  - `listByOwner(ConversationOwner, Optional<AgentId>, Cursor,
    PageSize)`:
    - Switches on the owner type to dispatch to the appropriate
      derived finder (`findByOwnerUserIdAndAgentIdAndCursor` /
      `findByOwnerClientIdAndCursor` etc. — exact names left to
      Spring Data JPA naming conventions).
    - The `agentFilter` Optional, when present, narrows the where
      clause to `agent_id = :agentId`.
    - Cursor decoding uses the same `(createdAt desc, id desc)`
      keyset already proven by `AgentRepositoryAdapter`.
  - `deleteById(ConversationId id)` — delegates to
    `conversationJpaRepository.deleteById(id.value())`. The cascade
    on `messages.conversation_id` removes messages (V001).
  - `appendMessage(Message message)` — creates the `MessageJpa`,
    sets the parent `ConversationJpa` reference, persists via
    `messageJpaRepository.save(...)`, and returns the mapper-built
    `Message`. Does NOT bump `messageCount` — that is the caller's
    responsibility (the caller calls
    `conversationRepository.save(conversation.incrementMessageCount(now))`
    in the same transaction; this contract is documented in the
    method Javadoc).
  - `listMessages(ConversationId, Cursor, PageSize)` — same keyset
    pattern; orders **ascending** on `(createdAt asc, id asc)` per
    openapi documented default for the messages list.
  - `findLastN(ConversationId, int n)` — uses a `LIMIT n` query
    ordered `(createdAt desc, id desc)` then reverses to ascending
    before returning (the chronological-ascending ordering is what
    EPIC-11's memory-window code expects).
- `infrastructure/persistence/adapter/ConversationJpaMapper.java` —
  package-private static methods:
  - `toDomain(ConversationJpa jpa)`:
    - Asserts exactly one of `ownerUser` / `ownerApiKey` is non-null;
      otherwise throws
      `DatabaseAccessException("Inconsistent conversation row: " +
      jpa.getId() + " has " + (both ? "both" : "neither") + " owner
      column populated")`. The XOR check constraint prevents this in
      practice; the adapter check is defense in depth.
    - Builds `ConversationOwner.UserOwner(new UserId(jpa.getOwnerUser
      ().getId()))` or `ConversationOwner.SystemOwner(new ClientId
      (jpa.getOwnerApiKey().getClientId()))` accordingly.
    - Wraps `title` in `new Title(jpa.getTitle())` if non-null,
      otherwise leaves it null on the domain `Conversation`.
  - `toJpa(Conversation domain, UserJpa user, ApiKeyJpa apiKey,
    AgentJpa agent)` — caller passes already-resolved parent refs (the
    adapter performs the lookups, not the mapper).
  - `toDomain(MessageJpa jpa)` / `toJpa(Message domain, ConversationJpa
    parent)` — straightforward role-string ↔ enum translation.
- `ConversationJpaRepository` (existing, US-02-006) gains:
  - `Page<ConversationJpa> findByOwnerUser_Id(...)`-style derived
    methods (or a single `@Query` per direction — implementer's
    choice, as long as the cursor predicate is keyset and the keyset
    columns match `idx_conversations_user_created` /
    `idx_conversations_client_created`).
  - `Optional<ConversationJpa> findById(UUID id)` is inherited from
    `JpaRepository`.
- `MessageJpaRepository` (existing, US-02-006) gains:
  - `List<MessageJpa>
    findTopNByConversation_IdOrderByCreatedAtDescIdDesc(...)` — or
    equivalent — for `findLastN`.
  - `Page<MessageJpa> findByConversation_IdOrderByCreatedAtAscIdAsc
    (UUID, Pageable)` (or the cursor-style equivalent) for
    `listMessages`.
- A `ConversationRepositoryAdapterIntegrationTest` (extending
  `PostgresIntegrationTest`):
  - Round-trip a `Conversation` with a `UserOwner` — assert
    `owner_user_id` column populated, `owner_client_id` null;
    `findById` returns the same domain instance modulo timestamp
    truncation.
  - Round-trip a `Conversation` with a `SystemOwner` — assert
    `owner_client_id` populated, `owner_user_id` null; `findById`
    returns the same domain instance.
  - `listByOwner(UserOwner, Optional.empty(), ...)` returns only
    conversations owned by that user.
  - `listByOwner(SystemOwner, Optional.empty(), ...)` returns only
    conversations owned by that API-key principal.
  - `listByOwner(..., Optional.of(agentId), ...)` filters to a
    single agent.
  - `appendMessage` + `listMessages` returns messages in
    chronological ascending order.
  - `findLastN(id, 3)` on a 5-message conversation returns the last
    3 messages in ascending order.
  - Cascading delete: deleting a `UserJpa` cascades to their
    conversations and on through to messages (the EPIC-02
    `CascadeIntegrationTest` already exercises this path through
    the legacy column — this story extends the assertion to the new
    `owner_user_id` column).
  - Cascading delete via api-key hard-delete is **not** tested
    (v1 has no API-key hard-delete capability — US-04-008 is soft
    only).
- `ApplicationContextSmokeTest` still boots green: Hibernate's
  `ddl-auto=validate` does not flag any column / FK divergence
  between the V005 schema and the reworked `ConversationJpa`.
- `LayeringArchTest` still passes — the adapter and mapper live
  strictly under `infrastructure/persistence/**`; no `domain/**`
  type imports JPA annotations or Hibernate types.

### Out of scope

- The use cases that consume the adapter (US-10-005 .. US-10-010).
- Any logic involving message-count atomic increments — that
  lives in `SendMessageService` (EPIC-11) under a
  `@Transactional` boundary. This story ships only the persistence
  primitives.
- A separate `MessageRepository` port — `findLastN` and
  `appendMessage` / `listMessages` live on
  `ConversationRepository` because `Message` is a child aggregate
  of `Conversation`. This decision is consistent with the EPIC-06
  `AgentRepository` carrying `AgentTeam` operations.

### Requirements coverage

`REQ-PRS-001`, `REQ-PRS-002`, `REQ-PRS-003`, `REQ-PRS-005`,
`REQ-CHAT-002`, `REQ-CHAT-007`, `REQ-CHAT-009`, `REQ-CHAT-010`
(domain helper enforced at adapter boundary), `REQ-AUTH-007` (SYSTEM
ownership round-trip), `REQ-USR-006` (cascade delete from user),
`REQ-ARC-002` (hexagonal).

### Design references

§4.1 Conversation / Message, §5 schema (post-V005), §5.2 cascade
rules, §10 pagination.

### Dependencies

US-10-001 (`Conversation`, `Message`, `ConversationOwner`,
`ConversationRepository` port — what this adapter implements).
US-10-002 (the V005 schema this adapter reads / writes against).
EPIC-02 (`ConversationJpa` / `MessageJpa` / their Spring Data
interfaces from US-02-005 / US-02-006).
US-03-003 (`UserRepository` JPA adapter pattern reused for the
parent-resolution lookup).
US-04-003 (`ApiKeyRepository` JPA adapter, same).
US-06-002 (`AgentRepository` JPA adapter pattern reused for the
cursor / keyset implementation and the agent parent lookup).

---

## US-10-004 — `ConversationFullException` + `CONVERSATION_FULL` 409 mapping in `GlobalExceptionHandler`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `GlobalExceptionHandler` to map
`ConversationFullException` (shipped by US-10-001) to HTTP 409 with
`code = CONVERSATION_FULL` — distinct from the generic `CONFLICT` code
the parent `ConflictException` handler already emits
**So that** EPIC-11's `SendMessageService`, which throws this exception
when a 65th message is attempted, surfaces the openapi-documented
machine-readable code (`CONVERSATION_FULL`), and frontend clients (and
external programmatic callers) can distinguish "conversation full —
start a new one" from any other conflict.

### Description

`ConversationFullException` extends `ConflictException` (US-05-003's
generic handler maps the latter to `409 CONFLICT`). Without a
subclass-specific entry, the new exception would inherit the generic
mapping — wrong machine-readable code. This story adds one
`@ExceptionHandler(ConversationFullException.class)` method that
overrides the generic path with `code = CONVERSATION_FULL`.

This pattern was already established by US-06-003 (which added three
agent-specific 409 codes on top of the generic `ConflictException`
handler from US-05-003). This story follows that pattern by
construction — same method shape, same `ProblemDetails` body
template, same log structure.

The openapi spec already lists `CONVERSATION_FULL` in the
`ProblemDetails.code` enum and ships a matching example body — no
openapi change is required.

### Acceptance criteria

- `infrastructure/web/error/GlobalExceptionHandler.java` gains a new
  `@ExceptionHandler(ConversationFullException.class)` method
  `handleConversationFull(ConversationFullException ex,
  HttpServletRequest req)` that returns a `ResponseEntity` with:
  - HTTP status 409;
  - `Content-Type: application/problem+json`;
  - body matching:
    ```json
    {
      "type": "https://errors.multi-agent-platform/conversation-full",
      "title": "Conversation full",
      "status": 409,
      "detail": "Conversation has reached the 64-message cap.",
      "code": "CONVERSATION_FULL"
    }
    ```
- The new handler method is placed in `GlobalExceptionHandler.java`
  **immediately above** the three agent-conflict handlers from
  US-06-003, so all subclass-specific conflict handlers sit visually
  grouped together — eases future maintenance.
- The handler logs at `INFO` (not `WARN`) the request method/URI and
  the exception message. Rationale: hitting the cap is a documented
  user-facing constraint, not an error condition; `WARN` would
  drown operators in routine signal.
- The handler runs **before** the generic
  `handleConflict(ConflictException, ...)` method by virtue of
  `@ExceptionHandler` subclass priority — the more-specific handler
  wins. A unit test asserts this is the case (see below).
- Unit test `GlobalExceptionHandlerConversationFullTest` (Mockito +
  plain controller-advice unit test, NO `@SpringBootTest` — mirrors
  the existing agent-conflict tests from US-06-003):
  - Throws `new ConversationFullException(new ConversationId(
    UUID.fromString("a9b9bb11-1234-4abc-9def-1234567890ab")))` and
    asserts the produced `ResponseEntity` has status `409`, content
    type `application/problem+json`, and a body matching the
    documented shape with `code == "CONVERSATION_FULL"`.
  - Asserts the `detail` string is the **static**
    `"Conversation has reached the 64-message cap."` — locks the
    user-facing message in place so no future change accidentally
    leaks the conversation id.
  - Asserts that throwing the parent `ConflictException` (not the
    subclass) goes through the generic US-05-003 handler with
    `code == "CONFLICT"` — proves the handler-priority dispatch
    works as intended.
- Integration extension on `GlobalExceptionHandlerIntegrationTest`
  (the shared one used by US-06-003 / US-08-007 / US-09-003):
  - A test-classpath controller throws
    `new ConversationFullException(new ConversationId(UUID.randomUUID()))`
    from a `@PostMapping` endpoint, and a MockMvc call asserts the
    409 / `CONVERSATION_FULL` shape end-to-end through the real
    `RestControllerAdvice`.
- The new entry does NOT alter the existing
  `ConversationNotFoundException` handling — the generic
  `NotFoundException` handler from US-03-001 already maps it to
  `404 NOT_FOUND`, which is what the openapi documents for "not
  found" on `/conversations/**`. No new entry required.
- ArchUnit (US-01-008) still passes — the handler is the only new
  Spring-stereotype addition, in `infrastructure/web/error/`.

### Out of scope

- The `SendMessageService` orchestration that throws the exception
  on the 65th attempt — EPIC-11.
- A separate code for "agent deleted while you were typing" — that
  falls under the generic `NOT_FOUND` mapping.
- Any other 409 codes (`DUPLICATE_AGENT_NAME`,
  `NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER`) — already
  shipped by US-06-003.

### Requirements coverage

`REQ-CHAT-010`, `REQ-ARC-007`, `REQ-API-004`.

### Design references

§9.2 GlobalExceptionHandler, §9.3 error code table
(`CONVERSATION_FULL` 409).

### Dependencies

US-10-001 (`ConversationFullException` itself). US-05-003 (the
generic `ConflictException` handler this story specializes).
US-03-001 (existing `GlobalExceptionHandler` infrastructure +
`ProblemDetails` mapper).

---

## US-10-005 — `StartConversationUseCase` + `POST /conversations` (+ `/conversations/**` URL guard for STANDARD / ADMIN / SYSTEM)

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user (STANDARD or ADMIN) or as a SYSTEM
machine principal
**I want** to `POST /conversations { "agentId": "<uuid>" }` and receive
back a new, empty conversation
**So that** I have a persistent container into which EPIC-11 will then
stream user messages and assistant responses, and so that any of my
later list/get/delete calls (US-10-006 / 007 / 009) can address that
conversation by id.

### Description

Use case lives in `application/chat/StartConversationService`
(implements `StartConversationUseCase`). Owner is resolved from the
`Principal` on the security context via
`ConversationOwner.from(principal)`. Agent ownership is verified by the
service: for a `UserOwner`, the agent must be owned by the same user
(`agent.ownerId().equals(userId)` — REQ-AGT-006 / REQ-CHAT-001); for a
`SystemOwner`, the agent must be… well, **no agent in v1 is
SYSTEM-owned**, because agent ownership in `agents.owner_id` references
`users(id)` (V001) and no parallel schema work is in this EPIC. The
service therefore returns `404 NOT_FOUND` for any SYSTEM `POST
/conversations` call in v1 — the SYSTEM-owned-agents story is a future
EPIC. This decision is recorded in `DESIGN-CHOICES.md` alongside the
US-10-002 note (same story).

The new conversation has `title = null` and `messageCount = 0`; the
first non-empty user message (EPIC-11) auto-derives the title.

`/api/v1/conversations/**` is added to `SpringSecurityConfig` as a
new URL guard admitting **STANDARD, ADMIN, and SYSTEM**. This is the
only of the three feature surfaces (admin / agents / conversations)
that SYSTEM may reach (design §8.6).

### Acceptance criteria

- `application/chat/StartConversationUseCase.java` — interface with
  a single method:
  ```java
  Conversation start(StartConversationCommand command);

  record StartConversationCommand(
      ConversationOwner owner,
      AgentId agentId
  ) {}
  ```
- `application/chat/StartConversationService.java` —
  `@Service` implementing the use case:
  - Constructor-injected with `AgentRepository`, `ConversationRepository`,
    and `Clock`.
  - Algorithm:
    1. Load the agent by id; if absent, throw
       `AgentNotFoundException`.
    2. Verify the caller owns the agent:
       - `UserOwner(userId)` — agent's `ownerId()` MUST equal `userId`,
         else throw `AgentNotFoundException` (NOT 403 — REQ-AUTH-008
         hides existence, not just access).
       - `SystemOwner(clientId)` — always throw
         `AgentNotFoundException` in v1; no agent is SYSTEM-owned.
         The Javadoc on the throw site references the future
         SYSTEM-owned-agents story.
    3. Build a fresh `Conversation`:
       - `id` — random UUID;
       - `agentId`, `owner` — from the command;
       - `title` — `null`;
       - `messageCount` — `MessageCount.EMPTY`;
       - `createdAt`, `updatedAt` — `clock.instant()` as
         `OffsetDateTime.atOffset(UTC)`.
    4. Persist via `conversationRepository.save(...)` and return the
       persisted instance (which carries the DB-assigned timestamps
       if the adapter overwrites them — the adapter uses the
       command-supplied timestamps, so identity round-trip is exact).
- `infrastructure/web/conversation/ConversationsController.java` —
  new `@RestController` (no class-level `@RequestMapping` — the
  `/api/v1` prefix is applied centrally by `WebConfig`):
  - Constructor-injected with `StartConversationUseCase` (this story)
    and a `ConversationResponseMapper` (this story; future stories
    extend it). The `CursorCodec` injection lands when US-10-006
    arrives.
  - Methods:
    ```java
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(
            @AuthenticationPrincipal Principal principal,
            @Valid @RequestBody CreateConversationRequest request) {
        ConversationOwner owner = ConversationOwner.from(principal);
        Conversation created = startConversationUseCase.start(
                new StartConversationCommand(owner, new AgentId(request.agentId())));
        return ConversationResponseMapper.toResponse(created);
    }
    ```
  - The `@AuthenticationPrincipal` is the sealed `Principal` — both
    `UserPrincipal` and `SystemPrincipal` are valid here.
    `ConversationOwner.from(principal)` dispatches exhaustively.
- `infrastructure/web/conversation/CreateConversationRequest.java` —
  record `CreateConversationRequest(@NotNull UUID agentId)`.
- `infrastructure/web/conversation/ConversationResponse.java` — record
  matching the openapi `Conversation` schema:
  ```java
  public record ConversationResponse(
      UUID id,
      UUID agentId,
      String title,                 // nullable
      int messageCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt
  ) {}
  ```
  - The owner is **not** exposed in the response: the openapi
    `Conversation` schema does not include `ownerId` (intentionally,
    since the caller already knows who they are and SYSTEM /
    USER-owned distinction is internal). The
    `ConversationResponseMapper` does not include it.
- `infrastructure/web/conversation/ConversationResponseMapper.java` —
  package-private static method `toResponse(Conversation)`.
- `infrastructure/web/security/SpringSecurityConfig.java` adds **one
  new line** to `securityFilterChain`:
  ```java
  .requestMatchers(apiPrefix + "/conversations/**")
      .hasAnyRole("STANDARD", "ADMIN", "SYSTEM")
  ```
  - The line is placed immediately after the `agentsPattern` line so
    both feature-surface guards sit visually adjacent.
  - The SYSTEM role is granted by `ApiKeyAuthenticationFilter` (set on
    the `SystemPrincipal`'s `GrantedAuthority` list — verify this is
    already the case from US-04-009; if not, this story adds it).
- Unit test `StartConversationServiceTest` (Mockito):
  - User owner, agent owned by same user → returns the new
    conversation; `agentRepository.findById` called once;
    `conversationRepository.save` called once with the right shape.
  - User owner, agent owned by **different** user → throws
    `AgentNotFoundException`; `conversationRepository.save` never
    called.
  - User owner, agent not in DB → throws `AgentNotFoundException`.
  - System owner, any agent → throws `AgentNotFoundException`
    (deterministic v1 behavior).
- Integration test `CreateConversationIntegrationTest`
  (`@SpringBootTest`, MockMvc, full security chain, PostgreSQL via
  `PostgresIntegrationTest`):
  - **USER happy path**: STANDARD user with an owned agent →
    `201 Created` + response body shape matches the openapi
    `Conversation` schema; `title` is `null`, `messageCount` is `0`,
    `id` is a valid UUID.
  - **USER cross-owner agent**: STANDARD user posting with another
    user's agentId → `404 NOT_FOUND` with `code = NOT_FOUND`. No
    conversation row is created.
  - **SYSTEM caller (v1 behavior)**: API-key call with any
    agentId → `404 NOT_FOUND` with `code = NOT_FOUND`. The test
    documents this as the v1 SYSTEM behavior pending a future EPIC
    that adds SYSTEM-owned agents.
  - **Missing agentId in body**: 400 `VALIDATION_ERROR` with field
    `agentId`.
  - **Unauthenticated**: 401 `INVALID_CREDENTIALS`.
  - **Disabled user**: a STANDARD user with `disabled=true` →
    rejected at the JWT filter (same path as any other authenticated
    endpoint).
- A new `ConversationUrlGuardIntegrationTest`:
  - Asserts an unauthenticated GET on `/conversations` → 401, NOT
    302 (no form login).
  - Asserts that placing the new line in
    `SpringSecurityConfig` admits both JWT (STANDARD/ADMIN) and
    API-key (SYSTEM) callers to `/conversations/**`. A simple
    `POST /conversations` with each credential type proves it; the
    SYSTEM call's 404 is the documented v1 behavior, not a 403 —
    the test specifically asserts the response code is 404
    (`agent not found`), not 403 (`forbidden`).

### Out of scope

- `GET /conversations` (US-10-006), `GET
  /conversations/{conversationId}` (US-10-007), `PATCH
  /conversations/{conversationId}` (US-10-008), `DELETE
  /conversations/{conversationId}` (US-10-009),
  `GET /conversations/{conversationId}/messages` (US-10-010).
- `POST /conversations/{conversationId}/messages` (EPIC-11).
- Adding the SYSTEM-owned-agents capability. That is a future EPIC;
  this story lands the v1 SYSTEM 404-behavior contract that the
  schema (US-10-002) is forward-compatible with.

### Requirements coverage

`REQ-CHAT-001` (start a chat with own agent), `REQ-CHAT-002`
(persistent storage), `REQ-CHAT-004` (multiple concurrent
conversations — implicit; no per-user / per-agent cap),
`REQ-CHAT-007` (owner-scoped access), `REQ-CHAT-011` (no per-user
quota — no cap on number of conversations), `REQ-AUTH-007`
(SYSTEM scope on `/conversations/**`), `REQ-AUTH-008` (existence
hiding — cross-owner agent surfaces as 404, not 403),
`REQ-API-006` (no `/api/v1` repeat in controller mapping).

### Design references

§6.2.8 endpoints (`POST /conversations`), §4.1 Conversation
lifecycle, §8.6 authorization rules, §16.2 send-message sequence
(consumer of `start` — context only).

### Dependencies

US-10-001 (`Conversation`, `ConversationOwner`,
`ConversationRepository`). US-10-002 / US-10-003 (the persistence
layer). US-04-009 (`ApiKeyAuthenticationFilter` granting the
SYSTEM role authority). US-06-006 (`AgentRepository.findById` +
`AgentNotFoundException` reused for the ownership check).

---

## US-10-006 — `ListConversationsUseCase` + `GET /conversations` (with optional `agentId` filter)

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user or SYSTEM principal
**I want** to `GET /conversations?cursor=...&pageSize=...&agentId=...`
and receive my own conversations in most-recent-first order, optionally
filtered by agent
**So that** the frontend can render the conversation list panel and a
user can find a past conversation to restart or delete.

### Description

Use case lives in `application/chat/ListConversationsService`
(implements `ListConversationsUseCase`). Owner-scoping is enforced in
the repository query (US-10-003's `listByOwner`); the use case is a
thin coordinator. Ordering is `created_at DESC, id DESC` (default
keyset from `idx_conversations_user_created` /
`idx_conversations_client_created`). The optional `agentId` filter
narrows to a single agent — no existence check on the agent (the
service does not 404 if the agent doesn't exist or belongs to
someone else; an empty page is the right response). For an
unknown / cross-owner agent, the filter simply yields zero rows,
which is the natural outcome of the owner-scoped `where` clause.

The page size respects the existing
`PageSize.fromQueryParam(Integer)` plumbing from EPIC-04
(default 20, max 100).

### Acceptance criteria

- `application/chat/ListConversationsUseCase.java` — interface:
  ```java
  Page<Conversation> list(ListConversationsQuery query);

  record ListConversationsQuery(
      ConversationOwner owner,
      Optional<AgentId> agentFilter,
      Cursor cursor,
      PageSize pageSize
  ) {}
  ```
- `application/chat/ListConversationsService.java` — `@Service`
  implementing the use case:
  - Constructor-injected with `ConversationRepository`.
  - Method body is a single `return repository.listByOwner(query
    .owner(), query.agentFilter(), query.cursor(), query.pageSize())`.
- `ConversationsController` adds:
  ```java
  @GetMapping("/conversations")
  public PageDto<ConversationResponse> list(
          @AuthenticationPrincipal Principal principal,
          @RequestParam(name = "cursor",   required = false) String cursor,
          @RequestParam(name = "pageSize", required = false) Integer pageSize,
          @RequestParam(name = "agentId",  required = false) UUID agentId) {
      ConversationOwner owner = ConversationOwner.from(principal);
      Cursor decoded = cursorCodec.decode(cursor);
      PageSize ps = PageSize.fromQueryParam(pageSize);
      Optional<AgentId> filter = Optional.ofNullable(agentId).map(AgentId::new);
      Page<Conversation> page = listConversationsUseCase.list(
              new ListConversationsQuery(owner, filter, decoded, ps));
      return PageDto.of(page, cursorCodec, ConversationResponseMapper::toResponse);
  }
  ```
- The `CursorCodec` injection is added to `ConversationsController`'s
  constructor in this story (it was deferred in US-10-005).
- Unit test `ListConversationsServiceTest` (Mockito):
  - Forwards the query verbatim to the repository.
  - Returns the repository's `Page` unchanged.
- Integration test `ListConversationsIntegrationTest`
  (`@SpringBootTest`, MockMvc, PostgreSQL):
  - **Empty list**: a fresh user → 200 + empty `items`, no
    `nextCursor`.
  - **Owner isolation**: user A creates conversations C1, C2, C3;
    user B sees an empty list when calling `GET /conversations`.
  - **Order**: 3 conversations created in temporal order T1 < T2 < T3
    → response order is T3, T2, T1 (most recent first).
  - **Cursor pagination**: `pageSize=2` returns the first 2 + a
    `nextCursor`; following the cursor returns the third.
  - **`agentId` filter**: among conversations on agents X, Y, Z,
    requesting `?agentId=<X>` returns only conversations on X.
  - **`agentId` filter — unknown agent**: requesting `?agentId=
    <random UUID>` returns an empty page (200, NOT 404).
  - **`agentId` filter — cross-owner agent**: requesting `?agentId=
    <other user's agent UUID>` returns an empty page (no leak on the
    agent's existence).
  - **`pageSize=0` / `pageSize=101`**: rejected with 400
    `VALIDATION_ERROR` by the existing `PageSize.fromQueryParam`
    validation.
  - **Invalid cursor**: rejected with 400 `VALIDATION_ERROR` by the
    existing `CursorCodec` decoder.
  - **SYSTEM caller**: returns an empty page in v1 (no SYSTEM-owned
    conversation exists because no SYSTEM agent exists). 200, not
    403.

### Out of scope

- A `messageCount` filter / `title contains` filter — not in the
  openapi.
- Server-side sorting other than `created_at DESC` — not in scope per
  design §10 ("Messages list orders chronologically ASC, others DESC").

### Requirements coverage

`REQ-CHAT-007` (owner-scoped), `REQ-CHAT-011` (no quotas — page
through unlimited rows), `REQ-API-005` (cursor pagination), `REQ-AUTH-007`
(SYSTEM scope).

### Design references

§6.2.8 (`GET /conversations`), §10 pagination.

### Dependencies

US-10-001 .. US-10-003 (domain + persistence). US-04-005 (`Cursor`,
`Page<T>`, `PageDto<T>`, `CursorCodec`, `PageSize`).

---

## US-10-007 — `GetConversationUseCase` + `GET /conversations/{conversationId}`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user or SYSTEM principal
**I want** to `GET /conversations/{conversationId}` and receive that
single conversation's metadata
**So that** the frontend can render the conversation header panel (title,
agent, message count) when restoring a past conversation.

### Description

Use case lives in `application/chat/GetConversationService`
(implements `GetConversationUseCase`). Ownership enforcement: the
service loads the conversation by id and verifies the owner matches
the caller. A mismatch surfaces as `ConversationNotFoundException`
(404 NOT_FOUND), NOT 403 — REQ-AUTH-008's existence-hiding rule.

### Acceptance criteria

- `application/chat/GetConversationUseCase.java`:
  ```java
  Conversation get(GetConversationQuery query);
  record GetConversationQuery(ConversationOwner owner, ConversationId id) {}
  ```
- `application/chat/GetConversationService.java` — `@Service`:
  - Constructor-injected with `ConversationRepository`.
  - Loads the conversation; if absent, throw
    `ConversationNotFoundException`.
  - If the loaded conversation's `owner` does NOT equal the query
    owner, throw `ConversationNotFoundException` (same exception, no
    leak).
  - Otherwise, return the loaded conversation.
- `ConversationsController` adds:
  ```java
  @GetMapping("/conversations/{conversationId}")
  public ConversationResponse get(
          @AuthenticationPrincipal Principal principal,
          @PathVariable("conversationId") UUID conversationId) {
      Conversation c = getConversationUseCase.get(new GetConversationQuery(
              ConversationOwner.from(principal),
              new ConversationId(conversationId)));
      return ConversationResponseMapper.toResponse(c);
  }
  ```
- Unit test `GetConversationServiceTest`:
  - Owner match → returns the conversation.
  - Owner mismatch (USER vs USER) → throws
    `ConversationNotFoundException`.
  - Owner mismatch (USER vs SYSTEM) → throws
    `ConversationNotFoundException`.
  - Not found → throws `ConversationNotFoundException`.
- Integration test `GetConversationIntegrationTest`:
  - 200 + response shape for owner-match.
  - 404 for unknown id.
  - 404 for cross-owner read (USER A reading USER B's conversation).
  - 404 for SYSTEM trying to read a USER's conversation.
  - 401 unauthenticated.

### Out of scope

- Returning the embedded message list (separate endpoint, US-10-010).
- Returning the embedded agent (clients call `GET /agents/{id}`
  separately if they want the agent detail).

### Requirements coverage

`REQ-CHAT-003` (view past conversations), `REQ-CHAT-007`
(owner-scoped), `REQ-AUTH-007`, `REQ-AUTH-008` (existence hiding).

### Design references

§6.2.8 (`GET /conversations/{conversationId}`), §8.6 authorization.

### Dependencies

US-10-001 .. US-10-003.

---

## US-10-008 — `EditConversationTitleUseCase` + `PATCH /conversations/{conversationId}`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user or SYSTEM principal
**I want** to `PATCH /conversations/{conversationId} { "title": "..." }`
and have my conversation's title updated to that value
**So that** I can give meaningful names to my saved conversations,
overriding the auto-derived first-message-based title (REQ-CHAT-005
user-edit clause).

### Description

Use case lives in `application/chat/EditConversationTitleService`. The
ownership check is identical to US-10-007 (cross-owner → 404).
Validation of the new title (non-blank, ≤32 chars) lives in the
domain `Title` constructor (US-10-001). The new title is applied
via the domain helper `Conversation.withTitle(newTitle, now)`.

### Acceptance criteria

- `application/chat/EditConversationTitleUseCase.java`:
  ```java
  Conversation edit(EditConversationTitleCommand command);
  record EditConversationTitleCommand(
      ConversationOwner owner,
      ConversationId id,
      Title newTitle
  ) {}
  ```
- `application/chat/EditConversationTitleService.java` — `@Service`:
  - Constructor-injected with `ConversationRepository`, `Clock`.
  - Loads the conversation, verifies owner (404 on mismatch), applies
    `withTitle`, persists, returns the updated conversation.
- `ConversationsController` adds:
  ```java
  @PatchMapping("/conversations/{conversationId}")
  public ConversationResponse patch(
          @AuthenticationPrincipal Principal principal,
          @PathVariable("conversationId") UUID conversationId,
          @Valid @RequestBody UpdateConversationRequest request) {
      Conversation c = editConversationTitleUseCase.edit(
              new EditConversationTitleCommand(
                      ConversationOwner.from(principal),
                      new ConversationId(conversationId),
                      new Title(request.title())));
      return ConversationResponseMapper.toResponse(c);
  }
  ```
- `infrastructure/web/conversation/UpdateConversationRequest.java` —
  record `UpdateConversationRequest(@NotBlank @Size(max = 32) String title)`.
- Unit test `EditConversationTitleServiceTest`:
  - Happy path → returns updated conversation; `updatedAt` advances.
  - Owner mismatch → throws `ConversationNotFoundException`.
  - Not found → throws `ConversationNotFoundException`.
- Integration test `EditConversationTitleIntegrationTest`:
  - 200 + response body shape; `title` matches the request; previously
    null `title` is now populated (the path also covers the case
    where the user names the conversation **before** sending the first
    message — REQ-CHAT-005 user-edit clause is independent of the
    auto-derivation lifecycle).
  - 400 `VALIDATION_ERROR` with field `title` for blank, null, or
    33-char title.
  - 404 for unknown id / cross-owner.
  - 401 unauthenticated.

### Out of scope

- Editing fields other than `title` (e.g., re-targeting the
  conversation to a different agent). Not in the openapi.
- Clearing the title back to `null`. Not in the openapi
  (`UpdateConversationRequest.title` is `required`).

### Requirements coverage

`REQ-CHAT-005` (user-edit clause), `REQ-CHAT-007` (owner-scoped),
`REQ-AUTH-007`, `REQ-AUTH-008`.

### Design references

§6.2.8 (`PATCH /conversations/{conversationId}`), §4.1 Title rules.

### Dependencies

US-10-001 .. US-10-003.

---

## US-10-009 — `DeleteConversationUseCase` + `DELETE /conversations/{conversationId}`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user or SYSTEM principal
**I want** to `DELETE /conversations/{conversationId}` and have that
conversation (and all of its messages) removed
**So that** I can prune my conversation list per REQ-CHAT-003.

### Description

Use case lives in `application/chat/DeleteConversationService`. Same
ownership check; cascade-on-delete to messages is handled by the V001
FK (`messages.conversation_id … on delete cascade`).

### Acceptance criteria

- `application/chat/DeleteConversationUseCase.java`:
  ```java
  void delete(DeleteConversationCommand command);
  record DeleteConversationCommand(ConversationOwner owner, ConversationId id) {}
  ```
- `application/chat/DeleteConversationService.java` — `@Service`:
  - Constructor-injected with `ConversationRepository`.
  - Loads the conversation (404 on missing or cross-owner), then
    `repository.deleteById(id)`.
- `ConversationsController` adds:
  ```java
  @DeleteMapping("/conversations/{conversationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
          @AuthenticationPrincipal Principal principal,
          @PathVariable("conversationId") UUID conversationId) {
      deleteConversationUseCase.delete(new DeleteConversationCommand(
              ConversationOwner.from(principal),
              new ConversationId(conversationId)));
  }
  ```
- Unit test `DeleteConversationServiceTest`:
  - Owner match → calls `repository.deleteById` exactly once.
  - Owner mismatch → throws `ConversationNotFoundException`;
    `deleteById` never called.
  - Not found → throws `ConversationNotFoundException`;
    `deleteById` never called.
- Integration test `DeleteConversationIntegrationTest`:
  - 204 on owner-match; the conversation row is gone; any prior
    messages are gone (cascade).
  - 404 on unknown id / cross-owner.
  - 401 unauthenticated.
- A small cascade test: pre-stage a conversation with 3 messages;
  call DELETE; assert `messages` table has zero rows for that
  conversation id. (Defense in depth — the EPIC-02
  `CascadeIntegrationTest` already proves the FK works at schema
  level, but a REST-path test catches a regression where the
  adapter accidentally bypassed the cascade by using a
  manual-delete loop.)

### Out of scope

- Bulk delete (`DELETE /conversations?ids=...`) — not in the
  openapi.
- Soft delete — explicitly rejected by REQ-USR-006 (hard delete
  only).

### Requirements coverage

`REQ-CHAT-003` (delete past conversations), `REQ-CHAT-007`
(owner-scoped), `REQ-CHAT-008` (cascade — already via FK from
EPIC-02; this story's test proves the REST path doesn't
short-circuit it), `REQ-AUTH-007`, `REQ-AUTH-008`.

### Design references

§6.2.8 (`DELETE /conversations/{conversationId}`), §5.2 cascade.

### Dependencies

US-10-001 .. US-10-003.

---

## US-10-010 — `ListMessagesUseCase` + `GET /conversations/{conversationId}/messages`

- **Status**: Done
- **Priority**: MUST

**As an** authenticated end-user or SYSTEM principal
**I want** to `GET /conversations/{conversationId}/messages?cursor=
&pageSize=` and receive my conversation's messages in chronological
ascending order
**So that** the frontend can render the message history when restoring
a past conversation (REQ-CHAT-003 view + restart) and so that EPIC-11
has the read endpoint a frontend client polls right after a
`completed` SSE frame.

### Description

Use case lives in `application/chat/ListMessagesService`. Ownership of
the parent conversation is verified first (404 on mismatch via the
same exception type used elsewhere). Messages are returned in
chronological ascending order (`created_at ASC, id ASC`) — the
opposite of the conversations list, and what the openapi documents.

The `findLastN` primitive on the repository (US-10-001) is NOT
exercised here — that's EPIC-11's memory-window concern. This
endpoint uses `listMessages` with cursor pagination.

### Acceptance criteria

- `application/chat/ListMessagesUseCase.java`:
  ```java
  Page<Message> list(ListMessagesQuery query);
  record ListMessagesQuery(
      ConversationOwner owner,
      ConversationId conversationId,
      Cursor cursor,
      PageSize pageSize
  ) {}
  ```
- `application/chat/ListMessagesService.java` — `@Service`:
  - Constructor-injected with `ConversationRepository`.
  - Loads the parent conversation; throws
    `ConversationNotFoundException` if absent OR owner mismatches.
  - Returns `repository.listMessages(conversationId, cursor,
    pageSize)`.
- `infrastructure/web/conversation/MessageResponse.java` — record
  matching the openapi `Message` schema:
  ```java
  public record MessageResponse(
      UUID id,
      String role,         // "USER" | "ASSISTANT"
      String content,
      OffsetDateTime createdAt
  ) {}
  ```
- `infrastructure/web/conversation/MessageResponseMapper.java` —
  package-private static method.
- `ConversationsController` adds:
  ```java
  @GetMapping("/conversations/{conversationId}/messages")
  public PageDto<MessageResponse> listMessages(
          @AuthenticationPrincipal Principal principal,
          @PathVariable("conversationId") UUID conversationId,
          @RequestParam(name = "cursor",   required = false) String cursor,
          @RequestParam(name = "pageSize", required = false) Integer pageSize) {
      ConversationOwner owner = ConversationOwner.from(principal);
      Cursor decoded = cursorCodec.decode(cursor);
      PageSize ps = PageSize.fromQueryParam(pageSize);
      Page<Message> page = listMessagesUseCase.list(new ListMessagesQuery(
              owner, new ConversationId(conversationId), decoded, ps));
      return PageDto.of(page, cursorCodec, MessageResponseMapper::toResponse);
  }
  ```
- Unit test `ListMessagesServiceTest`:
  - Owner-match → returns the repository page.
  - Owner mismatch → throws `ConversationNotFoundException`;
    `listMessages` never called.
  - Not found → throws `ConversationNotFoundException`;
    `listMessages` never called.
- Integration test `ListMessagesIntegrationTest`:
  - Empty conversation → 200 + empty `items`, no `nextCursor`.
  - 5 messages, `pageSize=2` → first page has 2 messages in
    chronological ASC order + `nextCursor`; following the cursor
    yields the next 2; the third call yields the last 1 and no
    cursor.
  - Owner isolation: user B cannot read user A's messages — 404
    with `code = NOT_FOUND`.
  - SYSTEM caller trying to read a USER's messages → 404.
  - 401 unauthenticated.
  - 400 on invalid cursor / pageSize.

### Out of scope

- `findLastN` exposure as an HTTP endpoint — EPIC-11 owns memory
  window assembly server-side; the read path here is for client
  display.
- Tool-call requests / results — REQ-CHAT-012 keeps them off the
  persisted surface; the repository only ever returns USER and
  ASSISTANT messages.

### Requirements coverage

`REQ-CHAT-002` (storage), `REQ-CHAT-003` (view past), `REQ-CHAT-006`
(memory window — supporting primitive on repository),
`REQ-CHAT-007` (owner-scoped), `REQ-CHAT-009` (message metadata),
`REQ-CHAT-012` (USER/ASSISTANT only), `REQ-API-005` (cursor
pagination), `REQ-AUTH-007`, `REQ-AUTH-008`.

### Design references

§6.2.8 (`GET /conversations/{conversationId}/messages`), §10
pagination (chronological ASC for messages, DESC for everything
else).

### Dependencies

US-10-001 .. US-10-003. US-04-005 (cursor / page plumbing).

---

## EPIC-10 Definition of Done

EPIC-10 is **Done** when, in addition to every story being individually
`Done`:

- `mvn test` runs every test from previous EPICs green; the EPIC-10
  unit and integration tests run green against PostgreSQL via the
  existing `PostgresIntegrationTest` Testcontainers harness.
- Hibernate `ddl-auto=validate` boots green against the V005 schema;
  the reworked `ConversationJpa` matches the post-migration columns
  column-for-column.
- The `conversations` table carries exactly one populated owner
  column per row (`owner_user_id` XOR `owner_client_id`), enforced
  by the `ck_conversations_owner_xor` check constraint.
- Every `/conversations/**` endpoint:
  - admits STANDARD, ADMIN, and SYSTEM at the URL guard;
  - rejects unauthenticated requests with 401 `INVALID_CREDENTIALS`;
  - enforces owner-scoping in the service (cross-owner reads /
    edits / deletes surface as 404 `NOT_FOUND`, never as 403 —
    REQ-AUTH-008 existence-hiding).
- A STANDARD user can create / list / get / patch-title / delete
  their own conversations and list a conversation's messages.
- A SYSTEM caller can call every `/conversations/**` endpoint and
  receives consistent v1 behavior:
  - `POST /conversations` → 404 `NOT_FOUND` (no SYSTEM-owned agent
    exists; documented and tested as the v1 contract pending a
    future SYSTEM-owned-agents EPIC).
  - `GET /conversations` → empty page.
  - `GET /conversations/{id}` → 404.
  - `PATCH /conversations/{id}` → 404.
  - `DELETE /conversations/{id}` → 404.
  - `GET /conversations/{id}/messages` → 404.
- `ConversationFullException` is mapped to 409 `CONVERSATION_FULL`
  by `GlobalExceptionHandler`; the openapi-documented body shape is
  exercised end-to-end through a test-classpath stub controller
  (the real producer ships in EPIC-11).
- `ConversationNotFoundException` is mapped to 404 `NOT_FOUND` by
  the generic `NotFoundException` handler from US-03-001 — no new
  entry needed.
- Cursor pagination works on both `GET /conversations` and `GET
  /conversations/{id}/messages` with default `pageSize=20`, max
  `pageSize=100`, opaque base64-encoded keyset cursors. Conversations
  list orders most-recent-first; messages list orders chronological
  ascending.
- ArchUnit (US-01-008) still passes: `domain/conversation/**` has
  zero Spring / JPA / JJWT imports; the `ConversationRepositoryAdapter`
  and `ConversationJpaMapper` live strictly under
  `infrastructure/persistence/**`; the `ConversationsController`
  lives under `infrastructure/web/conversation/**`.
- A new ArchUnit rule `no_spring_imports_in_domain_conversation`
  enforces the domain-purity invariant at build time.
- `backend/implementation/DESIGN-CHOICES.md` carries the
  two-column-XOR decision recorded by US-10-002 (rejected
  alternatives: `owner_kind` discriminator, seeded "system" user).
