# EPIC-12-US.md — User stories for EPIC-12

EPIC-12 — **Agent team delegation**

This file lists the user stories that deliver EPIC-12. The EPIC wires the
`delegate(...)` capability so an agent A can dispatch a sub-task to a team
member B during its turn, per the constrained execution model spelled out
in `REQ-AGT-015`. Every other piece of the chat pipeline is in place by
the end of EPIC-11; this EPIC inserts a small, well-isolated extension
point that the streaming surface never has to know about.

> **TBD-3 resolution.** The design (§19) deliberately left open the
> choice between (a) exposing `delegate` as a Spring AI `@Tool` so the
> LLM picks it via tool-calling, and (b) implementing it as a
> server-side post-step that inspects the LLM output for a marker. This
> EPIC commits to **option (a) — the Spring AI tool path** — for the
> following reasons:
>
> 1. **Tool-calling already works.** The OpenAI adapter shipped by
>    US-09-004 / US-09-005 plumbs `ChatRequest.tools` through to Spring
>    AI's `ChatClient` tool-callback resolver. Plugging in one more
>    `@Tool`-annotated bean is a single registration plus filtering — no
>    new orchestration code in the chat pipeline.
> 2. **Composes with streaming.** Spring AI's chat client interleaves
>    tool calls and content deltas seamlessly: A's stream pauses while
>    the tool runs, B's reply flows back into the model as a tool result,
>    A resumes generating tokens. The end-user sees only A's stream
>    (REQ-AGT-015) by construction; no marker parsing is required.
> 3. **Aligns with REQ-CHAT-012.** Tool-call requests and tool-call
>    results are transient artifacts of an LLM turn and are NEVER
>    persisted as messages. The tool path naturally inherits this
>    invariant — the tool's input/output never reaches the persistence
>    layer because they live inside Spring AI's tool-callback loop.
> 4. **Easier to test.** The tool is invoked through the same
>    `LlmChatClient.stream(...)` mock used by EPIC-11; the WireMock
>    fixture only needs an additional canned tool-call response. The
>    post-step alternative would require a custom marker parser
>    embedded in the streaming Flux, which adds its own test surface.
>
> The decision is recorded as a `DESIGN-CHOICES.md` entry by US-12-002.
>
> **Out of scope for this EPIC (deferred).** Nested delegation
> (REQ-AGT-013 already blocks the static team shape; EPIC-12 enforces it
> at execution time too). Parallel delegation to multiple team members
> in a single turn — `LlmChatClient` will sequence them through repeated
> tool calls, which is correct for v1. A "delegate to many in parallel"
> primitive is a future EPIC. Streaming the sub-agent's output back
> through A's stream is explicitly forbidden by REQ-AGT-015 — A's
> aggregate answer is what the user sees.

> **Scope split with EPIC-06 / EPIC-09 / EPIC-11.**
> - **Static team shape** (single-level, same-owner, no self-reference)
>   is already enforced at agent write time by EPIC-06's three conflict
>   exceptions. EPIC-12 re-asserts the rule at **runtime**: when the
>   `DelegateTool` is invoked, the requested target id must (a) be in
>   the parent agent's team list and (b) refer to an agent that has its
>   own empty team. Defense in depth on top of the write-time check.
> - **LLM port** (`LlmChatClient.call`) was shipped synchronously by
>   US-09-004 specifically so EPIC-12 has a non-streaming primitive
>   available — sub-agent turns deliberately don't stream (REQ-AGT-015 —
>   the user sees A's aggregate answer, not B's incremental output).
> - **Chat pipeline** (EPIC-11) is the consumer. EPIC-12 plugs in by
>   adding a single descriptor to `ChatRequest.tools` when the parent
>   agent has a non-empty team. `SendMessageService` is otherwise
>   unchanged.

## Conventions

- **ID format**: `US-12-<nnn>` — `12` matches the EPIC number; `<nnn>`
  is a sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start
  as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a
  short description, a bullet list of testable acceptance criteria, the
  requirements coverage, the design references, and its dependencies.

## Story list

| ID         | Title                                                                                                                | Priority | Status | Depends on                                                  |
|------------|----------------------------------------------------------------------------------------------------------------------|----------|--------|-------------------------------------------------------------|
| US-12-001  | `DelegationService` port + `DelegationCommand` / `DelegationResult` records + runtime team-membership invariant      | MUST     | Done   | US-06-001 (`Agent`, `AgentRepository`), US-09-001 (`LlmChatClient`) |
| US-12-002  | `DelegationServiceImpl` — sync `LlmChatClient.call(...)` against a minimal B-only `ChatRequest`; no persistence; TBD-3 DESIGN-CHOICES note | MUST | Done | US-12-001                                                   |
| US-12-003  | `DelegateTool` Spring AI `@Tool` bean + `ChatRequestBuilder` integration (registered iff `agent.team` is non-empty) | MUST     | Done   | US-12-001, US-12-002, US-11-003                             |
| US-12-004  | End-to-end WireMock integration test — golden path, persistence invariants, runtime team-membership rejection, sub-agent error isolation | MUST | Done | US-12-003, US-11-007                                        |

---

## US-12-001 — `DelegationService` port + records + runtime invariant

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an application-layer `DelegationService` port with a single
`delegate(DelegationCommand) → DelegationResult` method, plus the runtime
team-membership invariant that re-asserts REQ-AGT-013's single-level rule
at execution time (defense in depth on top of EPIC-06's write-time
enforcement)
**So that** US-12-003's `DelegateTool` has a clean seam to call without
depending on `LlmChatClient` or `AgentRepository` directly, the
runtime-side rule survives even if a future agent edit slips past
EPIC-06's validation, and the unit-test surface for the delegation
mechanism is independent of the LLM call.

### Description

`DelegationService` lives in `application/chat/`. It is the application-
layer surface that the Spring AI tool callback (US-12-003) calls into
when the parent agent's LLM emits a `delegate(...)` tool call. The
service is responsible for:

1. Resolving and validating the target — the target member id must be in
   the parent agent's team **as it stands at the moment of the call**
   (re-read from the repository — REQ-AGT-014 live config also applies
   to the team list), and the target agent itself must have an empty
   team (REQ-AGT-013 single-level rule, re-checked at runtime).
2. Building a minimal `ChatRequest` for B: only the delegated task as
   the user message (no parent conversation history per REQ-AGT-015),
   B's own system prompt, B's own sampling parameters, B's own tools,
   B's own MCP servers (resolved through the same catalogs EPIC-11's
   `ChatRequestBuilder` uses). B's `ChatRequest.tools` does NOT include
   any `DelegateTool` descriptor — B is a leaf by the single-level rule.
3. Calling `llmChatClient.call(B-request)` synchronously (US-09-004)
   and returning B's final text as a `DelegationResult`.
4. Persisting **nothing** — B's exchange is transient (REQ-AGT-015).

`DelegationCommand` carries the parent context the tool callback already
knows: the parent agent id, the parent's owner, the target member id,
and the delegated task string. `DelegationResult` carries B's text
answer and (for observability / logs only) the target member id.

### Acceptance criteria

- `application/chat/DelegationService.java` — interface with one
  method:
  ```java
  public interface DelegationService {
      DelegationResult delegate(DelegationCommand command);

      record DelegationCommand(
          AgentId parentAgentId,
          UserId parentOwner,
          AgentId targetMemberId,
          String task
      ) {
          public DelegationCommand {
              Objects.requireNonNull(parentAgentId, "parentAgentId");
              Objects.requireNonNull(parentOwner, "parentOwner");
              Objects.requireNonNull(targetMemberId, "targetMemberId");
              if (task == null || task.isBlank()) {
                  throw new ValidationException("task", "must not be empty");
              }
              if (task.length() > 1024) {
                  throw new ValidationException(
                      "task", "must be at most 1024 characters");
              }
          }
      }

      record DelegationResult(AgentId targetMemberId, String text) {
          public DelegationResult {
              Objects.requireNonNull(targetMemberId, "targetMemberId");
              Objects.requireNonNull(text, "text");  // may be empty
          }
      }
  }
  ```
- New domain exception
  `domain/agent/InvalidDelegationTargetException` extending
  `BusinessException` (mapped to 500 via the generic `Throwable`
  handler — this state is impossible if EPIC-06's validators
  worked correctly, so its surface is operator-debug, not user
  input). The exception carries the parent agent id and the
  offending target id; the message is sanitized for log redaction
  (no agent names, no descriptions).
- Javadoc on the port spells out the four responsibilities above and
  links REQ-AGT-011, REQ-AGT-013, REQ-AGT-015.
- Pure-Java tests under `src/test/java/.../application/chat/`:
  - `DelegationCommandTest` — accepts a well-formed command; rejects
    nulls on every reference field; rejects a blank / null /
    over-1024 `task` with field `task`.
  - `DelegationResultTest` — accepts a well-formed result with
    empty text; rejects null on either field.
  - `InvalidDelegationTargetExceptionTest` — message contains the
    parent + target ids; no agent names appear.
- ArchUnit (US-01-008) + `no_spring_ai_imports_in_application_chat`
  (US-09-001) still pass — the port + records are pure Java.

### Out of scope

- The implementation that actually invokes the LLM (US-12-002).
- The Spring AI tool bean (US-12-003).
- The wiring of `DelegateTool` into `ChatRequestBuilder` (US-12-003).

### Requirements coverage

`REQ-AGT-011` (delegation capability), `REQ-AGT-013` (single-level
rule — runtime side), `REQ-AGT-015` (execution model — task-only
input, no persistence).

### Design references

§16.3 send-message-with-delegation sequence, §4.1 Agent (team
field), §19 TBD-3 (resolution chosen above in EPIC-12 preamble).

### Dependencies

US-06-001 (`Agent` aggregate, `AgentRepository`). US-09-001
(`LlmChatClient` — the implementation in US-12-002 consumes it).

---

## US-12-002 — `DelegationServiceImpl` — sync LLM call against a minimal B-only `ChatRequest`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the default `DelegationService` implementation that:
re-fetches the parent agent, asserts the target is a team member of
the parent and itself has an empty team, builds a minimal `ChatRequest`
for the target agent (its own system prompt, its own tools / MCP, only
the delegated task as the user message), invokes
`llmChatClient.call(...)`, and returns the result with **zero** writes
to any repository
**So that** US-12-003's `DelegateTool` callback has a single function
to call, the REQ-AGT-015 invariants are honored by construction (not by
discipline), and the operational footprint of a delegation is exactly
"one synchronous OpenAI request".

### Description

`DelegationServiceImpl` lives in `application/chat/`. Constructor-
injected with `AgentRepository`, `ToolCatalog`, `McpServerCatalog`,
`FilesystemMcpUserScope`, `LlmChatClient`, and `ApplicationProperties`
(default model fallback).

Algorithm:

1. **Resolve the parent agent** via `agentRepository.findById(
   command.parentAgentId())`. If absent, throw
   `InvalidDelegationTargetException` (the parent vanished between the
   chat-turn build and the tool callback — narrow race window). The
   parent's `team` list is the runtime authority.
2. **Verify the target is in the parent's team**. If
   `parent.team().members()` does not contain `command.targetMemberId()`,
   throw `InvalidDelegationTargetException`. This is the runtime
   single-level invariant.
3. **Resolve the target agent**. Throw
   `InvalidDelegationTargetException` if absent or owned by a different
   user — both states are theoretically impossible if EPIC-06's
   validators ran (REQ-AGT-012 same-owner check), but the runtime
   re-check defends against a write-side regression.
4. **Verify the target's team is empty**. If
   `target.team().members().isEmpty() == false`, throw
   `InvalidDelegationTargetException` — single-level rule.
5. **Build a minimal `ChatRequest` for the target**:
   - `model` = `target.samplingParams().llmModel()` or platform
     default;
   - `systemPrompt` = `target.systemPrompt()`;
   - `history` = single `ChatMessage(USER, command.task())` — no
     parent conversation history (REQ-AGT-015 explicit);
   - `tools` = filtered `ToolDescriptor` list against the static
     catalog, exactly as `ChatRequestBuilder` does;
   - `enabledMcpServers` = target's MCP names; filesystem MCP
     per-user scope materialized via
     `filesystemMcpUserScope.rootFor(command.parentOwner())` if the
     target has `filesystem` enabled (the **parent's** owner is the
     effective principal — REQ-MCP-005 scopes per the calling
     user, not per the target agent's owner, which is the same user
     anyway thanks to REQ-AGT-012);
   - `sampling` = target's overrides;
   - `ownerUserId` = `command.parentOwner().value()`.
6. **Invoke `llmChatClient.call(targetRequest)`** — the sync (non-
   streaming) variant from US-09-004. The OpenAI adapter handles
   tool turns transparently inside Spring AI; B's eventual answer
   text comes back as `ChatResult.text()`.
7. **Return** `new DelegationResult(target.id(), result.text())`. No
   persistence; no SSE frame; no count increment.

### Acceptance criteria

- `application/chat/DelegationServiceImpl.java` — `@Service`
  implementing `DelegationService`. Javadoc spells out the seven
  steps above and links REQ-AGT-011 / 013 / 015.
- `DelegationCommand` records the `parentOwner` UserId, which the
  implementation uses to populate `ChatRequest.ownerUserId` for B —
  enforcing that B's filesystem MCP root resolves under the same
  user as the parent's turn (REQ-MCP-005).
- No injection of `ConversationRepository`. The class **must not**
  touch conversation persistence. An ArchUnit rule (or extension to
  an existing one) forbids
  `application.chat.DelegationServiceImpl` from depending on
  `ConversationRepository`:
  ```java
  noClasses()
      .that().areAssignableTo(DelegationServiceImpl.class)
      .should().dependOnClassesThat()
      .areAssignableTo(ConversationRepository.class);
  ```
  This is the load-bearing piece — it forecloses a future
  refactor that accidentally persists B's turn.
- Mockito unit test `DelegationServiceImplTest`:
  - **Happy path**: parent A with `team=[B]`, B's team empty,
    `llmChatClient.call(...)` mocked to return
    `new ChatResult("hello from B")`. Assert: the captured
    `ChatRequest` has B's system prompt; history is exactly
    `[ChatMessage(USER, task)]` (no parent history); tools are
    B's tools; MCP is B's MCP; `ownerUserId` matches the parent
    owner. Assert: `DelegationResult` carries B's text.
  - **Parent agent vanished**: `findById(parent)` → empty →
    `InvalidDelegationTargetException`.
  - **Target not in parent's team**: parent has `team=[X]`, call
    asks for `target=Y` → `InvalidDelegationTargetException`.
    Verify `llmChatClient.call(...)` never invoked.
  - **Target with non-empty team**: parent has `team=[B]`, B has
    `team=[C]` (somehow, despite EPIC-06's static rule) →
    `InvalidDelegationTargetException`. The exception's message
    contains B's id but NOT C's (no nested-team leakage).
  - **Target owned by different user**: contradiction state (also
    forbidden by REQ-AGT-012 statically) →
    `InvalidDelegationTargetException`.
  - **No persistence side effects**: a mock
    `ConversationRepository` (even if injected) → assert zero
    interactions. Actually since the ArchUnit rule forbids the
    injection at compile time, this test asserts the behavior
    via the absence of the field — a small reflection assertion
    in the test confirms `DelegationServiceImpl` has no
    `ConversationRepository` field.
  - **Filesystem MCP scoping**: target has `enabledMcpServers=[
    filesystem]`; verify `filesystemMcpUserScope.rootFor(parentOwner)`
    invoked exactly once with the **parent**'s owner id.
- `backend/implementation/DESIGN-CHOICES.md` gains an EPIC-12 entry
  recording the TBD-3 resolution:
  - **Decision**: Spring AI `@Tool` path (vs server-side post-step).
  - **Why**: cleanest composition with the streaming surface,
    inherits REQ-CHAT-012 by construction, smaller test surface,
    no marker-parsing brittleness.
  - **Consequence**: `DelegateTool` (US-12-003) is the single
    public entry point from the LLM to `DelegationService`;
    EPIC-11's chat pipeline is unchanged.
- ArchUnit (US-01-008) + `no_spring_ai_imports_in_application_chat`
  (US-09-001) still pass.

### Out of scope

- The Spring AI tool bean itself (US-12-003).
- Streaming the target agent's response — REQ-AGT-015 explicitly
  forbids it; `LlmChatClient.call(...)` is the sync path on
  purpose.
- A "delegation chain" — single-level only, runtime-enforced here.

### Requirements coverage

`REQ-AGT-011`, `REQ-AGT-012` (runtime same-owner re-check),
`REQ-AGT-013` (runtime single-level re-check), `REQ-AGT-014`
(parent agent re-fetch — live config also applies to the team
list), `REQ-AGT-015` (no parent history; no persistence; sync
sub-call), `REQ-MCP-005` (filesystem MCP scoped to parent owner),
`REQ-LLM-001..004`.

### Design references

§16.3 sequence diagram, §12 LLM integration (the sync `call(...)`
half), §13 tools, §14 MCP servers, §19 TBD-3 resolution.

### Dependencies

US-12-001 (port + records), US-09-004 (sync `call(...)` adapter),
US-07-001 / US-08-003 / US-08-004 (catalogs + filesystem scope),
US-06-001 (`AgentRepository`, `Agent`).

---

## US-12-003 — `DelegateTool` Spring AI `@Tool` bean + `ChatRequestBuilder` integration

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a Spring AI `@Tool`-annotated `DelegateTool` bean that the
LLM can call via tool-calling — its method accepts a `targetMemberId`
(string UUID) and a `task` (string), translates them into a
`DelegationCommand` using request-scoped context for `parentAgentId` /
`parentOwner`, calls `delegationService.delegate(...)`, and returns
B's text — and the integration into `ChatRequestBuilder` (US-11-003) so
that a parent agent's `ChatRequest.tools` includes the `DelegateTool`
descriptor iff `agent.team` is non-empty
**So that** parent agents with team members get the capability
automatically, leaf agents don't (preventing accidental nested
delegation at the LLM level), and the existing OpenAI adapter
(US-09-004 / US-09-005) plumbs the tool through to Spring AI without
any further wiring.

### Description

`DelegateTool` lives in `infrastructure/tool/`. It is the Spring AI
bridge — the only place in the codebase where the `@Tool` annotation
from Spring AI meets `DelegationService`. The tool is annotated
`@Component` and Spring AI's `ToolCallbackResolver` discovers it via
the existing scanner (US-09-004 already exercises that path for
`AwsS3Tool`).

The challenge: a Spring AI tool method's signature is fixed by the
schema description (`@Tool` annotation parameters), but the
`DelegationCommand` needs `parentAgentId` and `parentOwner` which
are NOT inputs from the LLM — they are facts about the current chat
turn. The solution: a small request-scoped context bean
(`ChatTurnContext`) populated by `SendMessageService` at the start of
each turn and consumed by `DelegateTool.delegate(...)`. This pattern
is documented in the Javadoc and is the smallest possible surface
that lets a Spring-managed `@Tool` method know which turn it's
running in.

`ChatRequestBuilder` (US-11-003) needs one additional rule: if the
agent's `team` is non-empty, append the `DelegateTool`'s
`ToolDescriptor` to the `ChatRequest.tools` list. The descriptor's
`name` is `"delegate"` (matches the `@Tool(name = "delegate")`
annotation parameter). Agents whose team is empty never see the
descriptor and so the LLM cannot call it.

### Acceptance criteria

- `infrastructure/tool/DelegateTool.java` — `@Component` bean.
  Method signature mirrors the `AwsS3Tool` example:
  ```java
  @Tool(name = "delegate",
        description = "Delegate a sub-task to one of this agent's team members. "
                    + "Returns the team member's final answer as plain text.")
  public String delegate(
          @ToolParam(description = "UUID of the team member to delegate to") String targetMemberId,
          @ToolParam(description = "The sub-task to ask of the team member") String task) {
      // resolve parent context, call DelegationService, return text
  }
  ```
- `application/chat/ChatTurnContext.java` — request-scoped Spring bean
  holding `parentAgentId`, `parentOwner`, optionally the
  `conversationId`. Populated at the start of every turn by
  `SendMessageService` (US-11-004 amendment in this story); cleared
  on completion. Throws `IllegalStateException` from `DelegateTool` if
  `ChatTurnContext.parentAgentId()` is null at invocation — the LLM
  cannot legally invoke `delegate(...)` outside of a chat turn.
- The `ChatTurnContext` bean is `@Scope("request")` so a parallel
  turn on a different HTTP request thread gets its own instance.
  Spring's request scope works under MVC; the `SseEmitter` path
  stays inside the request thread until the controller returns, so
  the context is reachable from inside the Spring AI tool callback
  (which runs synchronously inside the LLM client's call).
- **Wiring for non-team agents**: `ChatRequestBuilder` (US-11-003)
  is updated so that the `tools` list passed to `ChatRequest`
  includes the `delegate` descriptor **only when**
  `agent.team().members().isEmpty() == false`. The tool catalog
  itself (EPIC-07) does not know about `DelegateTool` — it stays
  under `infrastructure/tool/` as a special-case bean, not in the
  catalog. The descriptor is built inline in the builder, sourced
  from a `DelegateTool.DESCRIPTOR` constant.
- `DelegateTool.DESCRIPTOR` — public static final
  `ToolDescriptor("delegate", "...")` exposing the same description
  string as the `@Tool` annotation. The constant is the **single**
  source of truth shared between the `@Tool` annotation processor
  and the `ChatRequestBuilder` wiring.
- `SendMessageService` (US-11-004) is amended to populate
  `ChatTurnContext` immediately before invoking `ChatRequestBuilder
  .build(...)` and to clear it via `Flux.doFinally(...)` (covers
  completion + error + cancellation). This amendment is part of
  US-12-003's scope.
- Unit test `DelegateToolTest`:
  - Sets up a `ChatTurnContext` fake with a non-null parent agent
    id + owner.
  - Calls `delegateTool.delegate("uuid-of-B", "summarize this")`;
    `DelegationService` mock returns `new DelegationResult(B,
    "summary")`. Assert: the return value is `"summary"`; the
    captured `DelegationCommand` carries the parent context + the
    `task` from the LLM input.
  - Empty `ChatTurnContext` → `IllegalStateException` with the
    word "delegate" in the message (operator-debug, never leaks
    to the LLM unless the LLM is calling outside a turn, which is
    impossible).
  - Malformed `targetMemberId` (not a UUID) →
    `IllegalArgumentException` is wrapped in the appropriate
    Spring AI tool-error path; the wire response surfaces as an
    `LLM_UNAVAILABLE` 502 (the LLM returned a malformed tool
    call). This is the regression-prevention case — the LLM
    cannot crash the entire chat turn with bad tool input.
- `ChatRequestBuilderTest` (US-11-003) is extended with:
  - **Agent with empty team**: `ChatRequest.tools` does NOT
    include `"delegate"` even when the agent has `tools=[]`.
  - **Agent with non-empty team**: `ChatRequest.tools` includes
    `delegate` as the **last** entry (after the agent's own
    catalog tools).
  - **Agent with non-empty team and `tools=[AwsS3Tool]`**: both
    descriptors present.
- ArchUnit (US-01-008): `DelegateTool` lives in
  `infrastructure/tool/`, never imported by anything under
  `domain/**` or `application/**`. The pattern matches the
  existing `AwsS3Tool` placement.

### Out of scope

- Streaming B's response — `DelegationService` is sync.
- Parallel delegation in a single turn — Spring AI sequences
  multiple tool calls naturally; no special wiring.
- Showing the delegation in the SSE stream — REQ-AGT-015 forbids
  it; A's stream is unchanged.

### Requirements coverage

`REQ-AGT-011`, `REQ-AGT-013` (the **absence** of the descriptor for
leaf agents is the runtime guarantee), `REQ-AGT-014` (the
descriptor is added based on the agent's **current** team — live
config), `REQ-AGT-015`, `REQ-TOOL-002` (Spring AI tool contract),
`REQ-CHAT-012` (tool turns transient — naturally inherited).

### Design references

§13 tools, §16.3 send-message-with-delegation, §19 TBD-3 (the
chosen mechanism).

### Dependencies

US-12-001, US-12-002 (`DelegationService`), US-11-003
(`ChatRequestBuilder`), US-11-004 (`SendMessageService` — populates
the context), US-07-001 (`ToolDescriptor`).

---

## US-12-004 — End-to-end WireMock integration test

- **Status**: Done
- **Priority**: MUST

**As a** maintainer
**I want** a single end-to-end test exercising the full delegation
flow: a STANDARD user starts a turn with a parent agent that has a
team member; the WireMock-stubbed OpenAI server emits a tool call to
`delegate(...)`, then a follow-up content stream that incorporates B's
answer; the test asserts the SSE wire-format is unchanged (no delegation
frames leak), the persistence invariants hold (B's exchange not
persisted, parent's count bumps by exactly 2), and the runtime team-
membership rule rejects an LLM that tries to delegate to a non-member
**So that** every wiring point between EPIC-11 (chat pipeline) and
EPIC-12 (delegation mechanism) is proven together, and a future
regression in either layer trips a high-signal test.

### Description

The test lives next to US-11-007's `SendMessageEndpointIntegrationTest`
and reuses its `SseFrameParser` helper. WireMock now stubs **two**
endpoints (or one endpoint that returns different bodies based on the
request shape):

1. **First call** — for the parent agent's turn. The request body
   carries the `delegate` tool descriptor in its `tools` array. The
   stubbed response is an SSE stream that emits a tool-call
   (`{"tool_calls": [{"name": "delegate", "arguments":
   "{\"targetMemberId\":\"...\", \"task\":\"summarize\"}"}]}`).
2. **Second call** — for the sub-agent (B). The request body carries
   only the `task` as the user message (no parent history) and B's
   own system prompt. The stubbed response is a non-streaming JSON
   `{"choices":[{"message":{"role":"assistant","content":"summary
   from B"}}]}`.
3. **Third call** — Spring AI's chat client resumes A's stream with
   B's answer fed back as a tool result. The stubbed response is
   the final SSE stream emitting `delta` frames containing A's
   aggregated answer (e.g., `"based on B's summary: …"`).

The test asserts the wire-level SSE frames seen by the client match
exactly the EPIC-11 contract (started + delta(*N) + completed). No
new frame types. No frames leak any reference to delegation.

### Acceptance criteria

- `src/test/java/.../infrastructure/web/conversation/SendMessageDelegationIntegrationTest.java`:
  - **Happy path with one delegation**:
    - Seed user A; seed parent agent P with `team=[M]` and `system
      prompt = "you can delegate"`; seed member agent M with
      `team=[]` and `system prompt = "you are M"`; seed empty
      conversation between A and P.
    - Stub WireMock for the three calls described above.
    - POST `/conversations/{id}/messages` with content `"please
      summarize X"`.
    - Assert the response stream is `text/event-stream` and
      contains exactly: 1 `started` + N `delta` + 1 `completed`.
      None of the frames carry any "delegate" or "tool" reference.
      The `Completed.title` is non-null (first turn).
    - Assert the DB: `messages` table has exactly 2 rows for the
      parent conversation (USER + ASSISTANT); B's exchange is
      NOT persisted anywhere; `messageCount = 2`.
    - Assert via WireMock's request journal: exactly 2 outbound
      streaming calls (A's initial + A's resume after the tool)
      and exactly 1 non-streaming call (B's sync turn). The sync
      call's request body contains M's system prompt and the
      task as the user message; does NOT contain any of A's
      conversation history.
  - **Runtime team-membership rejection**: parent P with `team=[M]`;
    WireMock stubs A's first call to emit a `delegate` tool call
    with `targetMemberId` set to a **different** agent X (not in P's
    team). Assert the stream ends with an `error` frame
    (`code = LLM_UNAVAILABLE` — the tool callback's
    `InvalidDelegationTargetException` propagates as an
    infrastructure failure via the same path EPIC-11's error
    handler uses). DB invariants: 1 message (USER), not 2;
    `messageCount = 1`.
  - **Sub-agent error isolation**: WireMock returns 500 for B's
    sync call. Assert the parent stream ends with `error`
    (`code = LLM_UNAVAILABLE`); parent's USER message is
    persisted; parent's ASSISTANT message is NOT persisted; B's
    state is not persisted (it never could be anyway). The
    operator-side log contains B's failure but the response body
    is sanitized.
  - **Leaf agent has no `delegate` descriptor**: a separate test
    case starts a turn with M (the leaf) directly. The
    WireMock-captured first call's request body MUST NOT include
    the `delegate` tool in its `tools` array. This is the
    REQ-AGT-013 runtime guarantee — the LLM literally cannot ask
    to delegate.
  - **Cancelled mid-tool**: client cancels the SSE while A is in
    the tool-call phase. Assert: B's sync call may or may not
    complete (Spring AI's tool callback is synchronous; the
    cancel signal arrives after the sync call returns). The
    test accepts either outcome but asserts the DB ends in a
    consistent state (1 message, USER, no assistant) and no
    further WireMock calls happen after the cancel propagates.
  - **No `OPENAI_API_KEY` in logs**: `CapturingAppender` confirms
    no test-key fragment appears across any case.
- All assertions use the same `SseFrameParser` helper from
  US-11-007 — no duplication.
- The test class extends or composes with the same
  `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("dev")`
  setup as the other EPIC-11 integration tests.

### Out of scope

- Performance / soak testing of delegation. v1 is one turn,
  one delegation at a time.
- A `DelegationService` adapter against a different LLM provider.
  v1 is OpenAI-only.
- Audit logging of who delegated to whom. Not a v1 requirement
  (REQ-OBS is `SHOULD`, no audit clause).

### Requirements coverage

`REQ-AGT-011`, `REQ-AGT-013` (runtime team-membership rejection
exercised), `REQ-AGT-014` (parent re-fetch exercised by a
follow-up test that edits P's team between two turns and
re-asserts delegation behavior), `REQ-AGT-015` (every invariant —
no parent history, no persistence, user sees A only, count
unchanged — exercised), `REQ-STR-001..004`,
`REQ-LLM-001..005`.

### Design references

§16.3 sequence diagram (the load-bearing reference), §18 test
strategy.

### Dependencies

US-12-001..003 (the mechanism). US-11-007 (the
`SseFrameParser` helper + WireMock setup pattern). US-09-004
(sync `call(...)`).

---

## EPIC-12 Definition of Done

EPIC-12 is **Done** when, in addition to every story being individually
`Done`:

- `mvn test` runs the full backend suite green; every EPIC-11
  integration test still passes alongside the new delegation tests.
- A parent agent A with `team=[B]` can delegate a sub-task to B during
  its chat turn via the Spring AI tool-calling mechanism. B's exchange
  with the LLM is NOT persisted; A's user / assistant messages are
  persisted normally; A's conversation message count bumps by exactly
  2 (USER + ASSISTANT), regardless of how many delegations happened in
  the turn.
- The end-user's SSE stream contains exactly the documented
  EPIC-11 frame types (`started` / `delta` / `completed` / `error`).
  No new frame type is introduced; no delegation reference leaks into
  any frame payload.
- A leaf agent (empty team) does NOT receive the `delegate`
  descriptor in its `ChatRequest.tools` — the LLM cannot call
  `delegate(...)` for such an agent. This is the runtime guarantee
  on top of EPIC-06's static rule.
- The runtime team-membership rule (REQ-AGT-013 single-level) is
  re-checked inside `DelegationServiceImpl` at every invocation:
  the target must be in the parent's current team list, and the
  target must itself have an empty team. Either check failing
  surfaces as an `InvalidDelegationTargetException` → 502
  `LLM_UNAVAILABLE` SSE error frame (the LLM emitted an invalid
  tool call → treated as an infrastructure failure of the model).
- An ArchUnit rule confirms `DelegationServiceImpl` does NOT depend
  on `ConversationRepository` — the implementation cannot
  accidentally persist sub-agent turns. This is the load-bearing
  guarantee of REQ-AGT-015's "B's exchanges with the LLM SHALL NOT
  be persisted".
- `backend/implementation/DESIGN-CHOICES.md` carries the EPIC-12
  entry recording the TBD-3 resolution (Spring AI `@Tool` path)
  and its rationale.
- The Flyway migration ledger is unchanged — EPIC-12 introduces no
  schema work.
