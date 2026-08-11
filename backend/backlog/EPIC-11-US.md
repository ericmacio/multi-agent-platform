# EPIC-11-US.md — User stories for EPIC-11

EPIC-11 — **SSE streaming chat**

This file lists the user stories that deliver EPIC-11. The EPIC ships the
single streaming surface of the platform: `POST /conversations/{id}/messages`
returning `text/event-stream`. Every other chat-related capability (CRUD on
conversations and messages, LLM port, tool catalog, MCP catalog, agent
config) has already landed; this EPIC stitches them into the actual chat
turn and emits typed SSE frames.

> **Scope split with EPIC-09 / EPIC-10 / EPIC-12.**
> - **The LLM port and OpenAI adapter** are EPIC-09. EPIC-11 consumes
>   `LlmChatClient.stream(ChatRequest)` and is responsible for building the
>   request from the live agent config; it never touches Spring AI types.
> - **Conversation / message persistence and the non-streaming CRUD** are
>   EPIC-10. EPIC-11 consumes `ConversationRepository.findById`,
>   `appendMessage`, `findLastN`, and `save` for the read-modify-write of
>   `messageCount`. The 64-message cap exception
>   (`ConversationFullException`) and its `CONVERSATION_FULL` 409 mapping
>   are already in place from US-10-001 / US-10-004 — this EPIC throws
>   them at the right moments and the handler renders the response.
> - **Agent-team delegation** is EPIC-12. EPIC-11's
>   `SendMessageService` and `ChatRequestBuilder` are written so that
>   EPIC-12 can plug in a `DelegateTool` via the same `ChatRequest.tools`
>   surface (no API or wire-format change). The constraints of
>   REQ-AGT-015 (B's exchanges not persisted, B's call not counted) are
>   honored by routing every persistence write of EPIC-11 through the
>   user's conversation — sub-agent turns deliberately bypass that code
>   path.
> - **Tool catalog reference resolution** (US-09-004) already turns
>   `ToolDescriptor` names into Spring AI `ToolCallback` instances inside
>   the OpenAI adapter. EPIC-11's `ChatRequestBuilder` only has to filter
>   the agent's `tools` list against the static `ToolCatalog` and pass
>   the resolved `ToolDescriptor`s into `ChatRequest.tools` — the adapter
>   handles the rest.
> - **MCP per-user filesystem scoping** (REQ-MCP-005) — the
>   `FilesystemMcpUserScope` port + adapter shipped by US-08-004 already
>   resolves `{base}/users/{userId}` on demand. EPIC-11's
>   `ChatRequestBuilder` invokes it when the agent's
>   `enabledMcpServers` contains `filesystem`. The "per-user MCP
>   process vs shared process with path rewriting" trade-off (TBD-2)
>   is implementation-internal to the MCP adapter; EPIC-11 only sees
>   the `FilesystemMcpUserScope.rootFor(UserId)` return value.
> - **Live agent mutation** (REQ-AGT-014) — `ChatRequestBuilder` calls
>   `AgentRepository.findById` at the start of *every* turn. No
>   per-conversation snapshot is taken, so editing the agent
>   (system prompt, sampling, team) takes effect on the very next
>   message.

## Conventions

- **ID format**: `US-11-<nnn>` — `11` matches the EPIC number; `<nnn>` is a
  sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as
  `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short
  description, a bullet list of testable acceptance criteria, the
  requirements coverage, the design references, and its dependencies.

## Story list

| ID         | Title                                                                                                  | Priority | Status | Depends on                                                  |
|------------|--------------------------------------------------------------------------------------------------------|----------|--------|-------------------------------------------------------------|
| US-11-001  | `SendMessageUseCase` port + `TurnEvent` sealed type (`Started` / `Delta` / `Completed` / `Error`)       | MUST     | Done   | US-09-001 (`LlmChatClient`), US-10-001 (`Message`)          |
| US-11-002  | `MemoryWindowAssembler` — last-N USER/ASSISTANT message slice from `ConversationRepository.findLastN` | MUST     | Done   | US-10-001..003 (`findLastN`)                                |
| US-11-003  | `ChatRequestBuilder` — Agent + memory + new user message → `ChatRequest` (tool + MCP wiring)            | MUST     | Done   | US-11-002, US-07-001 (`ToolCatalog`), US-08-003..004 (`McpCatalog`, `FilesystemMcpUserScope`) |
| US-11-004  | `SendMessageService` — orchestration: ownership, cap, user persist, title, LLM stream, assistant persist, error mapping | MUST | Draft | US-11-001..003, US-09-005 (`stream(...)`), US-10-001..003   |
| US-11-005  | `POST /conversations/{id}/messages` REST adapter — `SseEmitter` bridge, Accept negotiation (406), content validation (400), `CONVERSATION_FULL` 409 | MUST | Draft | US-11-004, US-10-005..010                                   |
| US-11-006  | Client cancellation (REQ-STR-003) — `SseEmitter.onCompletion/onTimeout` → upstream `Disposable.dispose()` | MUST | Draft  | US-11-005                                                   |
| US-11-007  | End-to-end WireMock LLM integration test — golden path, mid-stream error, cancellation, 64-cap, 406, content-cap, owner-scoping | MUST | Draft | US-11-005, US-11-006                                        |

---

## US-11-001 — `SendMessageUseCase` port + `TurnEvent` sealed type

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the application-layer `SendMessageUseCase` port and a sealed
`TurnEvent` sum type describing the four typed events of a chat turn
(`Started`, `Delta`, `Completed`, `Error`)
**So that** the REST adapter (US-11-005) bridges a `Flux<TurnEvent>` onto
an `SseEmitter` without knowing anything about Spring AI types, and the
orchestration in US-11-004 returns a clean reactive surface that can be
unit-tested with `StepVerifier` independently of Spring MVC.

### Description

The port and its companion types live in `application/chat/`. They are
pure Java with no Spring stereotypes and no Spring AI imports — the
`ChatChunk` type from US-09-001 is the only reactive surface they
borrow, and Project Reactor's `Flux` is allowed under the layering rule
(it is the reactive primitive the whole platform commits to, not a
provider-specific type).

The sealed `TurnEvent` mirrors the wire shapes documented in §7.1 of the
design and the openapi `SseStartedEvent` / `SseDeltaEvent` /
`SseCompletedEvent` schemas. Carrying it as an in-process sealed type
(rather than directly as wire JSON) means:

- the orchestration can be tested with `StepVerifier` without spinning
  up any HTTP machinery;
- the REST adapter (US-11-005) is the one and only point where the
  events are translated to `event: …\ndata: …\n\n` SSE frames;
- an alternative future transport (WebSocket, gRPC) could be plugged in
  by writing a different adapter over the same `Flux<TurnEvent>`.

The `Error` variant carries a `String code` + `String message` pair —
just enough for the REST adapter to populate the matching
`ProblemDetails` body. The actual exception → code translation happens
inside `SendMessageService` (US-11-004), so the adapter never sees a
domain exception type.

### Acceptance criteria

- `application/chat/TurnEvent.java` — sealed interface:
  ```java
  public sealed interface TurnEvent
          permits TurnEvent.Started, TurnEvent.Delta,
                  TurnEvent.Completed, TurnEvent.Error {

      record Started(UUID userMessageId, UUID conversationId) implements TurnEvent { ... }
      record Delta(String text) implements TurnEvent { ... }
      record Completed(UUID assistantMessageId, String title, int messageCount) implements TurnEvent { ... }
      record Error(String code, String message) implements TurnEvent { ... }
  }
  ```
  - Each record's canonical constructor non-null-checks the relevant
    fields. `Completed.title` is intentionally **nullable** (REQ-CHAT-005
    says the title is non-null only on the first turn).
  - `Delta.text` is non-null but MAY be empty — the OpenAI adapter
    emits empty deltas occasionally (heartbeats / role-only frames) and
    the SSE adapter elides empty frames at the wire boundary (US-11-005).
  - `Error.code` non-null, non-blank (one of the documented openapi
    `ProblemDetails.code` enum values).
- `application/chat/SendMessageUseCase.java` — interface:
  ```java
  public interface SendMessageUseCase {
      reactor.core.publisher.Flux<TurnEvent> send(SendMessageCommand command);

      record SendMessageCommand(
          ConversationOwner owner,
          ConversationId conversationId,
          MessageContent content
      ) {
          public SendMessageCommand {
              Objects.requireNonNull(owner, "owner");
              Objects.requireNonNull(conversationId, "conversationId");
              Objects.requireNonNull(content, "content");
          }
      }
  }
  ```
  - Returns a **cold** `Flux<TurnEvent>` — the underlying LLM call is
    NOT initiated until a subscriber attaches (the REST adapter is the
    sole subscriber in production).
  - The `Flux` emits exactly one `Started` (after the user message is
    persisted), zero-or-more `Delta` frames, and exactly one terminal
    event: either `Completed` or `Error`. On `Error` the flux signals
    `Flux.error(...)` rather than emitting an `Error` record — the REST
    adapter detects the exception in `doOnError` and writes the
    matching `error` SSE frame, then completes the emitter. This keeps
    the typed surface aligned with Reactor's normal error contract.
- `application/chat/package-info.java` is updated to mention the new
  port + sealed type alongside the existing list. The placeholder for
  "EPIC-11 / EPIC-12 streaming and delegation" is removed for the
  EPIC-11 half.
- Pure-Java unit tests under `src/test/java/.../application/chat/`:
  - `TurnEventTest` — each variant accepts the documented fields;
    rejects nulls on every non-nullable field;
    `Completed.title` accepts both null and a non-null `String` (the
    record itself is non-validating beyond the type — REST adapter
    handles the title elision); `Delta.text = ""` is accepted (empty
    delta) but `Delta.text = null` throws NPE.
  - `SendMessageCommandTest` — accepts a fully-populated command;
    rejects null on any required field with the relevant message.
- `LayeringArchTest.no_spring_ai_imports_in_application_chat` (added in
  US-09-001) still passes — the new types use only `java.*` +
  `reactor.core.publisher.Flux` + the existing
  `domain/conversation/**` types.
- A new ArchUnit rule
  `application_chat_does_not_import_spring_stereotypes` would already
  be implied by the broader
  `application_does_not_use_spring_mvc_or_jpa`; no new rule needed.

### Out of scope

- The implementation of `send(...)` (US-11-004).
- The REST adapter writing SSE frames (US-11-005).
- Persistence of the user/assistant messages (handled inside US-11-004).

### Requirements coverage

`REQ-STR-001` (SSE), `REQ-STR-004` (frontend compatibility — the typed
events round-trip into the documented JSON shapes), `REQ-CHAT-009`
(message metadata carried on `Started` / `Completed`), `REQ-ARC-002`,
`REQ-ARC-003`, `REQ-ARC-005`.

### Design references

§7.1 SSE frame format, §7.2 server flow, §16.2 send-message sequence
(consumer of the port), §3 project structure (`application/chat/`).

### Dependencies

US-09-001 (`Flux` already on the application classpath via the
`LlmChatClient` port). US-10-001 (`ConversationOwner`,
`ConversationId`, `MessageContent` — value objects on the
`SendMessageCommand`).

---

## US-11-002 — `MemoryWindowAssembler` — last-N USER/ASSISTANT message slice

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a small, pure helper that takes an agent's `MemorySize` and a
conversation id and returns the last `memorySize` persisted USER /
ASSISTANT messages in chronological ascending order (REQ-AGT-005)
**So that** `ChatRequestBuilder` (US-11-003) has a single trusted source
of memory-window assembly, the trim-oldest-first rule is exercised by a
focused unit test that does not need to mock the LLM, and EPIC-12's
`DelegationService` can deliberately bypass this helper when building a
sub-agent's `ChatRequest` (REQ-AGT-015 — no parent history).

### Description

`MemoryWindowAssembler` lives in `application/chat/`. It is a
constructor-injected `@Service` (so Spring resolves
`ConversationRepository`) but exposes a single pure method:

```java
List<Message> assemble(ConversationId conversationId, MemorySize memorySize);
```

`ConversationRepository.findLastN` (US-10-001 / US-10-003) already
returns the last N messages in chronological ASCENDING order (the
adapter reverses the underlying DESC query before returning), which is
exactly what an LLM chat history wants. The assembler therefore
**directly delegates** to `findLastN(id, memorySize.value())` — but it
exists as its own class for three reasons:

1. The `memorySize → int` unwrap is centralized (the repository port
   takes a plain int because the domain cannot depend on
   `application.shared.PageSize`, and `MemorySize` lives in
   `domain.agent`).
2. EPIC-12 needs an explicit hook to **not** call this helper for
   sub-agent turns — having a named helper makes the bypass discoverable
   in code review.
3. A future "summarize older history into a single synthetic message"
   strategy (not in v1) would land as a new method on this class
   without touching `ChatRequestBuilder`.

### Acceptance criteria

- `application/chat/MemoryWindowAssembler.java` — `@Service` with the
  single method documented above. Returns `List.copyOf(...)` of the
  repository result for defensive immutability.
- Javadoc cross-links REQ-AGT-005, REQ-CHAT-006, and notes the EPIC-12
  bypass rationale.
- Mockito unit test `MemoryWindowAssemblerTest` covering:
  - empty conversation → empty list;
  - conversation with fewer messages than `memorySize` → returns all
    messages in chronological ASC order;
  - conversation with more messages than `memorySize` → returns exactly
    `memorySize.value()` items (the most recent N, oldest-first);
  - the assembler forwards exactly one call to
    `conversationRepository.findLastN(conversationId, memorySize.value())`;
  - asserting that the assembler rejects null arguments with the
    relevant field name.
- ArchUnit (US-01-008) still passes; no new package or import.

### Out of scope

- The repository port itself (US-10-001) — already shipped.
- The order-reversal logic — already inside
  `ConversationRepositoryAdapter` (US-10-003).
- "Token-budget-aware" memory windowing — REQ-AGT-005 commits to a
  fixed message count for v1.
- Persisted tool-call messages — REQ-CHAT-012 keeps them off the
  persisted log, so `findLastN` already returns only USER / ASSISTANT
  records.

### Requirements coverage

`REQ-AGT-005` (memory window semantics), `REQ-CHAT-006` (memory window
applied to the LLM call), `REQ-CHAT-012` (USER / ASSISTANT only —
already enforced upstream).

### Design references

§7.2 server flow step 3, §4.3 conversation lifecycle (Restart semantics
— a restarted conversation re-runs through this helper on every turn).

### Dependencies

US-10-001 / US-10-003 (`findLastN`).

---

## US-11-003 — `ChatRequestBuilder` — Agent + memory + new user message → `ChatRequest`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a `ChatRequestBuilder` that reads the **current** agent
configuration (REQ-AGT-014), the memory window (US-11-002), the just-
persisted user message, and produces a fully-populated `ChatRequest`
(US-09-001) ready to hand to `LlmChatClient.stream(...)` — including
the resolved set of `ToolDescriptor`s (filtered from the EPIC-07 static
catalog) and the resolved set of MCP server names (validated against the
EPIC-08 configured catalog and with the filesystem MCP per-user-scoped
via `FilesystemMcpUserScope`)
**So that** `SendMessageService` (US-11-004) is a thin coordinator that
doesn't have to know how to translate an `Agent` into an LLM request,
and the live-config invariant (REQ-AGT-014) is enforced in one place
that's easy to test.

### Description

`ChatRequestBuilder` lives in `application/chat/`. It is constructor-
injected with `AgentRepository`, `ToolCatalog`, `McpServerCatalog`,
`FilesystemMcpUserScope`, and `ApplicationProperties` (for the
platform-default model fallback). It exposes a single method:

```java
ChatRequest build(
    ConversationId conversationId,
    AgentId agentId,
    ConversationOwner owner,
    List<Message> memoryWindow,
    Message newUserMessage);
```

Algorithm:

1. **Re-fetch the agent** by id. If absent (which can only happen if
   the agent was deleted between the conversation read and the
   ChatRequest build — a narrow race window), throw
   `AgentNotFoundException`. This is the REQ-AGT-014 enforcement
   point — no per-conversation snapshot.
2. **Resolve the model**: `agent.samplingParams().llmModel()` if
   non-blank, else `properties.llm().openai().defaultModel()`.
3. **Resolve sampling parameters**: `SamplingParameters` record built
   from `agent.samplingParams()` (nullable fields propagate; the OpenAI
   adapter from US-09-004 skips nulls).
4. **Build the `history` list**: map the memory window's `Message`
   records to `ChatMessage(Role, String)`, then append the new user
   message at the end. The translation is straightforward because both
   sides commit to USER / ASSISTANT only.
5. **Resolve tools**: for each `tool` name in `agent.tools()`, look up
   the `ToolDescriptor` in the static `ToolCatalog`. Unknown names
   should be impossible (EPIC-07's `CatalogToolReferenceValidator`
   blocks them at write time), but defensive: throw a
   `BusinessException` ("agent references a tool no longer in the
   catalog") that the global handler maps to 500 — this is a system
   inconsistency, not a user input error.
6. **Resolve MCP servers**: for each MCP name in
   `agent.enabledMcpServers()`, verify the name still exists in the
   `McpServerCatalog`. Defensive same as tools (the catalog is loaded
   at startup and frozen). The names are carried as plain strings on
   `ChatRequest.enabledMcpServers` — translation to Spring AI MCP
   callbacks is the adapter's job (US-09-004 / US-09-005).
7. **Filesystem MCP per-user scoping**: if
   `agent.enabledMcpServers()` contains `"filesystem"` AND the
   `ConversationOwner` is a `UserOwner` (SYSTEM doesn't reach this
   branch in v1 because they can't start conversations — US-10-005),
   call `filesystemMcpUserScope.rootFor(userId)` to materialize the
   `{base}/users/{userId}` root on demand (REQ-MCP-005). The returned
   path is NOT placed on `ChatRequest` — the FilesystemMcpUserScope
   adapter handles path injection internally per the TBD-2 strategy
   it picks. Calling `rootFor` here is the side effect that ensures
   the directory exists before the LLM call streams.
8. Build and return the `ChatRequest` record (US-09-001).

### Acceptance criteria

- `application/chat/ChatRequestBuilder.java` — `@Service`, single
  public method as documented. Javadoc cross-links REQ-AGT-014 and
  the seven steps above.
- `application/chat/UnknownAgentToolException` and
  `UnknownAgentMcpServerException` (or a single
  `AgentConfigurationDriftException`) — added to
  `domain/agent/` or `application/chat/` depending on where the
  consensus places them. These are internal-error exceptions
  (mapped to 500), not 400-validation: by the time a chat turn runs,
  the agent has already been validated at write time, so this
  branch only fires on a true drift (catalog mutated at runtime,
  agent deleted mid-turn, etc.). The handler maps them via the
  generic `Throwable.class` path that already returns
  `INTERNAL_ERROR`.
- Mockito unit test `ChatRequestBuilderTest`:
  - **Happy path**: agent with `tools=["AwsS3Tool"]`,
    `enabledMcpServers=["brave-search"]`, `llmModel="gpt-4o"`,
    `temperature=0.7`, memory window of 5 messages, new user
    message "hi" → produced `ChatRequest` has model `"gpt-4o"`,
    sampling `(0.7, null, null)` (or whatever the agent supplies),
    history of 6 messages in order, tools containing the resolved
    `ToolDescriptor("AwsS3Tool", ...)`, `enabledMcpServers
    = ["brave-search"]`, `ownerUserId = ownerA.id()`.
  - **Model fallback**: agent with `llmModel = null` falls back to
    `properties.llm().openai().defaultModel()` (the test wires a
    `gpt-4o-mini` default).
  - **Agent deletion mid-turn**: `agentRepository.findById` returns
    empty → `AgentNotFoundException`.
  - **Live agent mutation**: a test seeds `Agent v1` with `tools=[]`,
    builds a request, then "updates" to `Agent v2` with
    `tools=["AwsS3Tool"]`, and rebuilds — the v2 result includes the
    tool descriptor. This is the REQ-AGT-014 assertion: the
    re-fetch is the load-bearing piece.
  - **Filesystem per-user scope**: agent with
    `enabledMcpServers=["filesystem"]` triggers
    `filesystemMcpUserScope.rootFor(userId)` exactly once with the
    owner's id; non-filesystem-enabled agents never call the port.
  - **Unknown tool drift**: agent with `tools=["DroppedTool"]` whose
    name is no longer in the catalog → throws
    `AgentConfigurationDriftException` with the offending name in
    the message; no `ChatRequest` is returned.
  - **Unknown MCP drift**: same shape for `enabledMcpServers`.
- ArchUnit (US-01-008) + `no_spring_ai_imports_in_application_chat`
  (US-09-001) still pass — the builder uses only application-layer
  types.

### Out of scope

- The actual Spring AI tool-callback resolution — that lives in
  the OpenAI adapter (US-09-004 already shipped).
- The OpenAI streaming call — US-11-004 invokes it via the port.
- A "conversation summary" message — REQ-AGT-005 says fixed-N memory,
  no summarization in v1.
- Per-agent system-prompt templating (variable substitution). The
  prompt goes through as-is.

### Requirements coverage

`REQ-AGT-001` (carries every configurable attribute through the
turn), `REQ-AGT-005` (memory window), `REQ-AGT-008` (tool
attachment), `REQ-AGT-009` (per-agent MCP enablement), `REQ-AGT-014`
(live mutation — load-bearing), `REQ-MCP-004` (per-agent per-MCP),
`REQ-MCP-005` (filesystem per-user root materialization),
`REQ-LLM-002` (default model fallback).

### Design references

§7.2 server flow step 4, §12 LLM integration, §13 tools, §14 MCP
servers, §15 configuration (`app.llm.openai.default-model`).

### Dependencies

US-11-002 (memory window assembler — supplies the `List<Message>`
input). US-07-001 (`ToolCatalog`). US-08-003 (`McpServerCatalog`).
US-08-004 (`FilesystemMcpUserScope`). US-09-001 (`ChatRequest` /
`ChatMessage` / `SamplingParameters` / `Role`). US-06-001..002
(`AgentRepository`, `Agent`).

---

## US-11-004 — `SendMessageService` — full orchestration

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the default `SendMessageUseCase` implementation that runs the
full chat turn: ownership + cap pre-flight, user message persistence
(with title derivation on the first message), `started` emission, memory
window + ChatRequest build, `llmChatClient.stream(...)` invocation,
`delta` emission per chunk, assistant message persistence on completion
with `completed` emission, and the error path (provider error mapped to
`LLM_UNAVAILABLE` 502, MCP error to `MCP_SERVER_ERROR` 502)
**So that** the REST adapter (US-11-005) is a thin transport translator
and EPIC-12 can plug delegation in by augmenting `ChatRequest.tools`
without touching the orchestration here.

### Description

`SendMessageService` lives in `application/chat/`. It is constructor-
injected with: `ConversationRepository`, `AgentRepository`,
`ChatRequestBuilder` (US-11-003), `MemoryWindowAssembler` (US-11-002),
`LlmChatClient` (US-09-001), and `Clock`. It is annotated `@Service`
and exposes `Flux<TurnEvent> send(SendMessageCommand)`.

Algorithm (the `Flux` is assembled with reactive operators; the
description shows the logical order):

1. **Load + verify** (synchronous, runs before the Flux subscription):
   - Find the parent conversation by id; throw
     `ConversationNotFoundException` if absent or owner mismatch
     (existence hiding — REQ-AUTH-008).
   - Check `messageCount < 64`; throw `ConversationFullException` if
     at the cap (REQ-CHAT-010). The handler from US-10-004 maps this
     to 409 `CONVERSATION_FULL`.
   - Build the new user `Message` (random id, USER role, validated
     `MessageContent`, `clock.instant().atOffset(UTC)`).
2. **Persist USER message + bump count + derive title** (single
   transaction):
   - Append the new user message via
     `conversationRepository.appendMessage(...)`.
   - Increment via `Conversation.incrementMessageCount(now)` and save.
   - If the conversation's `title` is currently null,
     auto-derive via `Title.fromFirstUserMessage(content)`. Fall back
     to `Title.defaultFor(conversationId)` if the derived title is
     empty. Apply via `withTitle(...)` and re-save.
   - This whole step runs inside a single `@Transactional` boundary.
     If it fails (e.g., DB constraint, FK race), the exception
     propagates out of the synchronous prefix — no SSE frame is
     emitted, the REST adapter writes the matching 4xx/5xx body.
3. **Emit `Started`** — the first frame of the returned `Flux` is
   `TurnEvent.Started(newUserMessage.id().value(), conversationId.value())`.
   This frame is emitted **only after** step 2 has committed.
4. **Assemble memory + build `ChatRequest`** — via the helpers from
   US-11-002 / US-11-003.
5. **Invoke `llmChatClient.stream(request)`** — the returned
   `Flux<ChatChunk>` is mapped to `TurnEvent.Delta(chunk.text())` via
   `.map(...)`.
6. **On Flux completion** (the LLM emitted its last delta), persist
   the ASSISTANT message:
   - Accumulate the deltas in a `StringBuilder` via `Flux.doOnNext`
     or `.scan(...)` — the simpler `StringBuilder` approach is
     fine because the operator chain runs on a single subscriber.
   - On `doOnComplete`, run a small `Mono.fromRunnable` that:
     - builds `Message(random id, ASSISTANT, MessageContent(accumulated),
       now)`,
     - appends via the repository,
     - increments + saves the conversation,
     - emits the `Completed` event with `title` = the auto-derived
       title (non-null only on the first turn) or null otherwise,
       and `messageCount = updated.messageCount().value()`.
   - The emission is via a `Sinks.One<TurnEvent>` (or
     `Flux.concatWith(Mono.defer(...))`) so the `Completed` event
     becomes the last element of the returned Flux.
7. **Error path** — `Flux.onErrorResume(t -> Mono.error(translate(t)))`:
   - `LlmUnavailableException` from the OpenAI adapter
     (US-09-004 / 005) propagates as-is — the global handler maps to
     `LLM_UNAVAILABLE` 502.
   - `McpServerException` from EPIC-08 propagates as-is →
     `MCP_SERVER_ERROR` 502.
   - Any other unchecked exception inside the reactive chain is
     wrapped in an `LlmUnavailableException` with the original cause
     so the wire response stays in the documented enum.
   - **Persistence ordering** (REQ-STR-002): if the error fires
     after the USER message is persisted but before the ASSISTANT
     message would have been persisted, the conversation has
     advanced by exactly 1 (the user message), not 2. The REST
     adapter writes the matching `error` SSE frame after the
     `started` frame already went out.
8. **Cleanup hooks** — `Flux.doOnCancel(...)` logs at DEBUG; no
   persistence happens on cancellation (REQ-STR-002 — the assistant
   message is persisted only on `Completed`).

### Acceptance criteria

- `application/chat/SendMessageService.java` — `@Service` implementing
  `SendMessageUseCase`. Constructor-injection of every dependency.
- `application/chat/SendMessagePipeline.java` (or inline helpers) —
  the synchronous-prefix + reactive-tail split is documented in
  Javadoc so future maintainers don't accidentally move the cap /
  ownership check inside the Flux (which would defer the 404 / 409
  past the `started` frame, breaking the openapi contract that says
  `started` is the **first** signal a successful turn emits).
- The first `Flux` element MUST be `Started`; the last element MUST
  be either `Completed` or a Reactor error signal (NEVER both).
- `@Transactional` boundaries:
  - The user-persistence + count-bump + title-derivation is inside
    one transaction (REQ-PRS-003).
  - The assistant-persistence + count-bump is inside a separate
    transaction at completion time. (Splitting them is required —
    the LLM call is reactive and outside any sync transaction.)
- Mockito unit tests `SendMessageServiceTest` (NO `@SpringBootTest`):
  - **Happy path single chunk**: 1-chunk LLM response → exactly 3
    events (Started, Delta, Completed); user + assistant rows
    persisted; count bumped by 2; first-turn title auto-derived.
  - **Happy path multi-chunk**: 3-chunk response → Started, Delta(*3),
    Completed; assistant content is the concatenated chunks.
  - **First-message title rule**: an empty-conversation turn with
    user content `"  hello  "` → `Completed.title == "hello"`.
  - **Default-title fallback**: a first-message turn whose content
    trims to empty after `strip()` (impossible because
    `MessageContent` already rejects blank, but the test inputs a
    content that contains only whitespace + a single character) →
    derives a `Title` that fits within 32 chars.
  - **Subsequent-turn title null**: turn #2 → `Completed.title` is
    null.
  - **Cap reached pre-flight**: a conversation at `messageCount=64`
    → throws `ConversationFullException` synchronously (the Flux
    is never even subscribed); user message NOT persisted.
  - **Cross-owner**: a conversation owned by user B + a USER A
    command → `ConversationNotFoundException` synchronously; user
    message NOT persisted.
  - **Unknown id**: `ConversationNotFoundException` synchronously.
  - **LLM error mid-stream**: WireMock-equivalent mock emits 1
    Delta then a `Flux.error(new LlmUnavailableException(...))` →
    Flux signals `Flux.error(LlmUnavailableException)`; user
    message persisted; assistant message NOT persisted; count
    advanced by 1.
  - **LLM error before first chunk**: mock returns
    `Flux.error(new LlmUnavailableException(...))` → Started was
    emitted (because user persistence already happened); Delta /
    Completed never emitted; Flux signals error; assistant message
    NOT persisted.
  - **Agent deleted mid-turn**: `ChatRequestBuilder.build(...)`
    throws `AgentNotFoundException` → bubbles through Reactor as
    a `Flux.error`; assistant message NOT persisted; user message
    still persisted (it happened before the build call).
- `StepVerifier`-based reactive assertions are used for the
  Flux-shape tests; the synchronous-prefix tests use
  `assertThatThrownBy(...)` directly.
- ArchUnit (US-01-008) + `no_spring_ai_imports_in_application_chat`
  (US-09-001) still pass.

### Out of scope

- The REST adapter writing SSE frames (US-11-005).
- Client cancellation propagation (US-11-006).
- Agent-team delegation — EPIC-12 plugs into `ChatRequestBuilder`
  (US-11-003) via the tool catalog, not into `SendMessageService`.
- Token usage / cost accounting on `Completed`. v1 ships
  `messageCount` only.

### Requirements coverage

`REQ-AGT-014` (live config — via `ChatRequestBuilder`),
`REQ-CHAT-005` (title rule), `REQ-CHAT-006` (memory window applied),
`REQ-CHAT-009` (message metadata), `REQ-CHAT-010` (cap enforcement),
`REQ-CHAT-012` (no tool messages persisted — adapter handles tool
turns transparently), `REQ-STR-001` (SSE shape), `REQ-STR-002`
(persistence ordering), `REQ-NFR-005` (concurrent stream sizing —
the implementation is non-blocking by construction).

### Design references

§7.2 server flow (sketch), §7.4 persistence ordering, §16.2
send-message sequence.

### Dependencies

US-11-001 (`SendMessageUseCase`, `TurnEvent`),
US-11-002 (memory window),
US-11-003 (ChatRequest builder),
US-09-005 (`LlmChatClient.stream`),
US-10-001..003 (`ConversationRepository`, `ConversationFullException`).

---

## US-11-005 — `POST /conversations/{id}/messages` REST adapter (SSE)

- **Status**: Done
- **Priority**: MUST

**As an** authenticated user (or SYSTEM principal)
**I want** to `POST /conversations/{conversationId}/messages` with
`Accept: text/event-stream` and `{"content": "<my message>"}` and
receive the assistant's response as a stream of typed SSE frames
**So that** the React frontend's `EventSource` consumer renders the
assistant message token by token, and external machine clients running
under an API key get the same streaming surface without protocol
divergence.

### Description

The endpoint lives on the existing `ConversationsController`
(EPIC-10). Wiring:

1. **Method**: `@PostMapping("/conversations/{conversationId}/messages",
   produces = MediaType.TEXT_EVENT_STREAM_VALUE)` — Spring MVC's
   `produces` does the Accept negotiation (returns 406 automatically if
   the client doesn't accept `text/event-stream`).
2. **Validation**: `@Valid @RequestBody SendMessageRequest` whose
   `content` field is `@NotBlank @Size(max = 1024)` (REQ-CHAT-009 cap).
   The domain `MessageContent` re-validates at the boundary.
3. **Owner resolution**: `ConversationOwner.from(principal)` — the
   sealed dispatch from US-10-005.
4. **Use case invocation**: build `SendMessageCommand` and call
   `sendMessageUseCase.send(command)` → `Flux<TurnEvent>`.
5. **SSE emitter bridge**: `SseEmitter` (10-minute timeout by default
   — a chat turn shouldn't take more than that; configurable via
   `app.streaming.emitter-timeout`) is created and subscribed to the
   Flux. Each `TurnEvent` is translated to an SSE frame via the
   `SseFrameWriter` helper:
   - `Started(uid, cid)` → `event: started\ndata: {"userMessageId":
     "...", "conversationId": "..."}`
   - `Delta(text)` → `event: delta\ndata: {"text": "..."}` —
     **empty deltas are elided** at the wire (no frame written when
     `text.isEmpty()`), matching the design note in §7.1.
   - `Completed(aid, title, count)` → `event: completed\ndata:
     {...}`, then `SseEmitter.complete()`.
   - On `Flux.onError(...)` → `event: error\ndata: <ProblemDetails
     JSON>`, then `SseEmitter.complete()`. The ProblemDetails is
     built by the same code path that
     `GlobalExceptionHandler` would use for a synchronous 502 —
     reusing `ProblemDetails.of(code, title, status, detail,
     instance)`.

### Acceptance criteria

- `infrastructure/web/conversation/SendMessageRequest.java` — record
  with `@NotBlank @Size(max = 1024) String content`.
- `infrastructure/web/conversation/SseFrameWriter.java` — package-
  private helper that takes an `SseEmitter` + a `TurnEvent` and
  writes the correct frame. Pure-Java; unit-testable without Spring.
- `ConversationsController.sendMessage(...)` method added:
  ```java
  @PostMapping(
      value = "/conversations/{conversationId}/messages",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public SseEmitter sendMessage(
          @AuthenticationPrincipal Principal principal,
          @PathVariable("conversationId") UUID conversationId,
          @Valid @RequestBody SendMessageRequest request) {
      // build command, subscribe, return emitter
  }
  ```
- The `SseEmitter` timeout is read from `ApplicationProperties.Streaming
  .emitterTimeout` (new nested record) — default `PT10M`, configurable
  via `app.streaming.emitter-timeout`.
- The Flux subscription is wired in this method body — NOT inside the
  service — so the controller can attach the `doOnNext` /
  `doOnComplete` / `doOnError` hooks that drive the emitter.
- `infrastructure/config/ApplicationProperties.java` gains a new
  `Streaming(@NotNull Duration emitterTimeout)` nested record.
- `application.yaml` (main + test) sets `app.streaming.emitter-timeout:
  PT10M`.
- Unit test `SseFrameWriterTest` (no `@SpringBootTest`, no MockMvc):
  - Each of the four TurnEvent variants writes the documented
    `event: …\ndata: …\n\n` shape (capture via the `SseEmitter`'s
    callback registry or a small fake — the existing tests for
    other slim writers in the codebase pick the latter).
  - Empty `Delta.text` produces no frame (asserted by checking the
    capture buffer is unchanged after the call).
  - `Completed.title = null` emits `"title":null` (JSON literal,
    matches the openapi `nullable` field).
  - Error frames carry a valid `ProblemDetails` JSON shape with the
    correct `code`.
- Slice MockMvc test
  `SendMessageEndpointMockMvcTest` (`@WebMvcTest(ConversationsController.class)`
  with `SendMessageUseCase` mocked) — for the **synchronous** failure
  modes that bypass the Flux:
  - `Accept: application/json` → 406 (Spring negotiation).
  - Body missing `content` → 400 `VALIDATION_ERROR` with field
    `content`.
  - `content` 1025 chars → 400 `VALIDATION_ERROR` with field
    `content` (bean-validation `@Size`).
  - `content` empty string → 400 (`@NotBlank`).
  - Path-variable malformed UUID → 400.
- The full streaming-path test lives in US-11-007 (it needs
  WireMock + Postgres).
- The `SseEmitter` is subscribed with a small reactive snippet that
  uses Reactor's `subscribe(...)` overloads — NOT with `.block()`
  anywhere. An ArchUnit rule (or extension to an existing one)
  forbids `.block()` calls under `infrastructure/web/conversation/**`.

### Out of scope

- The integration test exercising the full streaming path
  (US-11-007).
- Cancellation propagation (US-11-006).
- A second SSE endpoint for any other feature.

### Requirements coverage

`REQ-STR-001` (SSE), `REQ-STR-004` (frontend EventSource
compatibility), `REQ-CHAT-009` (content cap enforced at REST
boundary), `REQ-CHAT-010` (the 409 path is reached by the
synchronous prefix in US-11-004 — the adapter just lets it
propagate to `GlobalExceptionHandler`), `REQ-API-006` (no
class-level `/api/v1` repeat — already enforced on
`ConversationsController`).

### Design references

§7.1 SSE frame format, §6.2.8 endpoints
(`POST /conversations/{conversationId}/messages`), §16.2.

### Dependencies

US-11-004 (the use case the adapter consumes). US-10-005..010
(`ConversationsController` skeleton + existing endpoints — the
new method sits next to them).

---

## US-11-006 — Client cancellation (REQ-STR-003)

- **Status**: Done
- **Priority**: MUST

**As a** user who closes the browser tab mid-response
**I want** the server to immediately stop generating tokens, close the
upstream OpenAI HTTP connection, and release the database / thread
resources tied to my turn
**So that** a load of 16 concurrent streams (REQ-NFR-005) plus a
fraction of impatient users does not silently leak connections, the
OpenAI bill is not inflated by tokens nobody will read, and operators
can run the platform at v1 sizing without periodic restarts.

### Description

Cancellation in Spring MVC SSE works through `SseEmitter`'s callbacks
(`onCompletion`, `onTimeout`, `onError`). Tomcat fires `onCompletion`
when the client TCP connection drops. The controller wires those
callbacks to `Disposable.dispose()` on the Flux subscription, which
propagates `cancel()` upstream through every Reactor operator including
the OpenAI adapter's reactive HTTP call (US-09-005 already documents
the cancel-respecting behavior).

The persistence side effect is **none**: the assistant message is
persisted only on `Flux.onComplete`, so a cancelled stream leaves the
conversation at the post-USER state (count = previous + 1), consistent
with REQ-STR-002.

### Acceptance criteria

- `ConversationsController.sendMessage(...)` registers callbacks on
  the returned `SseEmitter`:
  ```java
  Disposable subscription = flux.subscribe(...);
  emitter.onCompletion(subscription::dispose);
  emitter.onTimeout(subscription::dispose);
  emitter.onError(t -> subscription.dispose());
  ```
- The `subscription::dispose` call MUST be idempotent (Reactor's
  `Disposable.dispose()` already is — no extra guarding needed).
- `Flux.doOnCancel(...)` inside `SendMessageService` (or as a chain
  step in the controller) logs at DEBUG: `"chat turn cancelled by
  downstream: conversationId={}"`.
- Operator-visible: the `Started` frame's `userMessageId` is still in
  the DB after cancellation — the user message persistence is
  unconditional. Only the assistant message is conditional.
- WireMock-backed integration test
  `SendMessageCancellationIntegrationTest`:
  - Subscribe via `StepVerifier` (the `Flux<TurnEvent>` is reachable
    directly through `sendMessageUseCase.send(...)` — testing
    through the SSE wire would require a real HTTP client; the use
    case test is sufficient because the cancel propagates the
    same way).
  - After 1 `Delta`, call `thenCancel().verify()`.
  - Assert the DB has 1 message (USER), not 2 — the cancellation
    happened before `Flux.onComplete`.
  - Assert via WireMock's request journal that the OpenAI
    chat-completions request stopped receiving further bytes
    within 1 second of cancel (Reactor's HTTP client closes the
    connection on cancel — already exercised by US-09-005's
    cancellation test, but re-asserted here at the
    `SendMessageService` level).
- A separate **wire-level** cancellation test (optional but
  recommended) uses a raw `HttpClient` against the live `MockMvc`
  (or `WebTestClient`) to confirm that closing the client TCP
  stream triggers `emitter.onCompletion` and the
  `subscription.dispose()` runs.
- ArchUnit assertion (or sibling): no `.block()` calls in
  `infrastructure/web/conversation/**` — the entire SSE path is
  non-blocking.

### Out of scope

- Re-subscribing / resuming after cancel — the openapi protocol
  has no resumption semantics; cancelled streams are terminal.
- Server-side timeout other than the configurable
  `SseEmitter` timeout (default `PT10M`). The application-level
  cancellation has no separate timer.

### Requirements coverage

`REQ-STR-003` (cancellation), `REQ-STR-002` (persistence ordering
honored on cancel), `REQ-NFR-005` (16-stream sizing — the cancel
path is the load-bearing piece for sustainable resource usage).

### Design references

§7.3 cancellation.

### Dependencies

US-11-004 (the cold Flux that respects `cancel()`), US-11-005 (the
controller wiring the dispose), US-09-005 (the OpenAI adapter's
cancel-respecting streaming surface).

---

## US-11-007 — End-to-end WireMock-backed integration test

- **Status**: Done
- **Priority**: MUST

**As a** maintainer
**I want** a single end-to-end integration test that runs the entire
chat turn against a WireMock-stubbed OpenAI server: stubs a streaming
response, exercises the `POST /conversations/{id}/messages` endpoint
over MockMvc / `WebTestClient`, and asserts the wire-format SSE frames,
the DB persistence shape, the 64-cap, the 406 / 400 / 404 / 409 / 502
error mappings, and the cross-owner isolation
**So that** every wiring point introduced by US-11-001 .. US-11-006 is
proven together rather than just in isolation, and any future
regression in the streaming surface trips a single, high-signal test
suite.

### Description

Lives in
`src/test/java/.../infrastructure/web/conversation/SendMessageEndpointIntegrationTest.java`.
Uses the same `@SpringBootTest @AutoConfigureMockMvc
@ActiveProfiles("dev")` pattern as the other EPIC-10 integration
tests, plus `@AutoConfigureWireMock(port = 0)` and a
`@DynamicPropertySource` redirecting
`spring.ai.openai.base-url` to the WireMock port (same setup as
`OpenAiChatClientAdapterStreamTest` from US-09-005).

MockMvc handles HTTP request building, but the SSE response body is
**read as a `String`** and parsed by a small `SseFrameParser` helper
(also added in this story under `src/test/java/.../web/conversation/`):
splits on `"\n\n"` and yields an ordered list of `(eventType,
dataJson)` tuples. This avoids depending on a third-party SSE client.

### Acceptance criteria

- `SendMessageEndpointIntegrationTest`:
  - **Happy path** — stub WireMock with a 3-chunk SSE response;
    pre-seed a STANDARD user + agent + empty conversation; POST a
    user message → assert the response is `text/event-stream`,
    Status 200, and contains exactly 1 `started`, 3 `delta`, 1
    `completed` frame. The `Completed.title` is non-null
    (auto-derived from the user message, ≤32 chars). The DB has 2
    rows in `messages` and `conversations.message_count = 2`.
  - **Second turn** — repeat against the same conversation; the
    `Completed.title` is now null (no re-derivation per
    REQ-CHAT-005), and `messageCount = 4`.
  - **64-message cap** — pre-seed a conversation at
    `messageCount = 64`; POST another message → response is
    `application/problem+json` 409 with `code = CONVERSATION_FULL`.
    Notably, the response is NOT `text/event-stream` because the
    sync prefix throws before the emitter is created and the
    `GlobalExceptionHandler` writes the problem-details body
    instead (Spring MVC's content-negotiation falls back to JSON
    on the exception path).
  - **Content over 1024 chars** — POST a 1025-char content → 400
    `VALIDATION_ERROR` with field `content`. Status / body are JSON
    problem-details, same as the cap case.
  - **Empty content** — POST `{"content":""}` → 400 with field
    `content`.
  - **Missing `Accept: text/event-stream`** — happy-path body but
    `Accept: application/json` → 406. The handler returns a generic
    406 ProblemDetails.
  - **Cross-owner conversation** — STANDARD user A POSTs to
    STANDARD user B's conversation → 404 `NOT_FOUND` (existence
    hiding, REQ-AUTH-008).
  - **Unknown conversation id** — 404 `NOT_FOUND`.
  - **SYSTEM principal against a USER conversation** — same 404 as
    cross-owner; documented as the v1 SYSTEM contract.
  - **Unauthenticated** — 401 `INVALID_CREDENTIALS`.
  - **OpenAI 503 mid-stream** — WireMock emits 2 chunks then 503;
    response contains `started`, `delta` (×2), and `error` with
    `code = LLM_UNAVAILABLE`. The DB has 1 message
    (USER), `message_count = 1` — the assistant message is NOT
    persisted (REQ-STR-002).
  - **OpenAI 429** — WireMock returns 429 before any SSE bytes;
    response contains `started`, then `error` with
    `code = LLM_UNAVAILABLE` (NOT `RATE_LIMITED` — provider 429 is
    treated as infrastructure failure, REQ-LLM-005).
  - **Empty-delta frame elision** — WireMock emits a chunk with
    empty `delta.content`; the response has the matching
    `delta` frame absent. Asserts the §7.1 elision rule.
  - **No `OPENAI_API_KEY` in logs** — `CapturingAppender` confirms
    `test-openai-key` never appears in any captured log line
    across every test in this class (REQ-SEC-004 — same assertion
    pattern as US-09-005).
- `SseFrameParser` (test-side helper) is a small package-private
  utility that splits a response body on `\n\n` and parses each
  block into `(event, data)` pairs. Has its own micro unit test
  asserting the split / parse logic against canned bodies.
- All tests in the class pass under both a cold and a warm Hibernate
  context (the EPIC-10 integration tests re-run Flyway before each
  test method via the existing `@BeforeEach flyway.clean();
  flyway.migrate();` pattern in `CreateConversationEndpointIntegrationTest`).

### Out of scope

- Real OpenAI traffic — every test goes through WireMock.
- Load / soak testing for the 16-concurrent-stream sizing. The
  unit-test sizing assertion stays in US-11-004's reactive tests.
- Recording a smoke-test script for developers (it lives under
  `backend/docs/` if a contributor adds one; not part of this story).

### Requirements coverage

`REQ-STR-001`, `REQ-STR-002`, `REQ-STR-003`, `REQ-STR-004`,
`REQ-CHAT-005`, `REQ-CHAT-009`, `REQ-CHAT-010`, `REQ-AUTH-007`,
`REQ-AUTH-008`, `REQ-LLM-005`, `REQ-API-005` (negotiation).

### Design references

§7 streaming, §16.2 send-message sequence, §6.2.8 endpoint.

### Dependencies

US-11-005 (the endpoint), US-11-006 (cancellation hooks — the
cancel test in this class would otherwise leak a thread), US-09-005
(WireMock fixture pattern), every EPIC-10 use case the test seeds
through.

---

## EPIC-11 Definition of Done

EPIC-11 is **Done** when, in addition to every story being individually
`Done`:

- `mvn test` runs the full backend suite green; every EPIC-10
  integration test still passes against the now-shipped streaming
  endpoint.
- A STANDARD user can `POST /conversations/{id}/messages` with
  `Accept: text/event-stream` and a valid `{"content":"..."}` body,
  and receive the documented sequence of typed SSE frames terminating
  in `completed`. The user message is persisted **before** the first
  `delta`; the assistant message is persisted **after** the
  `completed` frame's emission has been computed.
- A SYSTEM API-key caller can reach the same endpoint at the URL
  guard layer, but every attempt 404s in v1 because no SYSTEM-owned
  conversation can exist yet (forward-compatible with a future
  SYSTEM-owned-agents EPIC).
- The 64-message cap is enforced in the synchronous prefix of
  `SendMessageService` — a 65th attempt returns `application/problem+json`
  409 `CONVERSATION_FULL` (the SSE stream is never opened).
- The content cap (≤1024) is enforced at the REST adapter via
  bean-validation, and re-enforced inside the domain
  `MessageContent` value object at the application boundary.
- Every chunk emitted by the OpenAI adapter becomes either a `delta`
  frame (when non-empty) or is elided (when empty), matching §7.1.
- An OpenAI provider failure mid-stream produces an `error` SSE frame
  with `code = LLM_UNAVAILABLE` and leaves the conversation in a
  consistent state: USER persisted, ASSISTANT not persisted, count
  advanced by 1.
- Client disconnect during a stream calls
  `Disposable.dispose()` on the Reactor subscription within Tomcat's
  `onCompletion` callback; WireMock's request journal confirms the
  upstream OpenAI HTTP connection is released within 1 second.
- ArchUnit (US-01-008) still passes; no Spring AI imports leak into
  `application/chat/**`; no `.block()` calls live under
  `infrastructure/web/conversation/**`; the
  `application_does_not_use_spring_mvc_or_jpa` rule continues to hold.
- Live agent mutation (REQ-AGT-014) is exercised by a dedicated unit
  test in `ChatRequestBuilderTest`: updating an agent between turn N
  and turn N+1 surfaces the new config on turn N+1 without restarting
  any conversation.
- The Flyway migration ledger is unchanged — EPIC-11 introduces no
  schema work; every persistence shape was already in place by EPIC-10.
