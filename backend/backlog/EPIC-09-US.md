# EPIC-09-US.md — User stories for EPIC-09

EPIC-09 — **LLM provider integration (OpenAI)**

This file lists the user stories that deliver EPIC-09. The EPIC ships the
provider-agnostic chat-completion port (`LlmChatClient`) consumed by the chat
orchestration EPICs (EPIC-11 streaming send-message, EPIC-12 delegation) and
the OpenAI adapter that backs it in v1. It also wires the configuration of the
default model, the `OPENAI_API_KEY` env var, and the
`LLM_UNAVAILABLE` 502 mapping for provider failures.

> **Scope split with EPIC-07 / EPIC-08 / EPIC-11 / EPIC-12.**
> - EPIC-09 stops at the **application port** (`LlmChatClient`) and its
>   **OpenAI adapter**. It does NOT introduce any REST endpoint and does
>   NOT touch the agent write/read paths (those are owned by EPIC-06).
> - The chat-turn orchestrator that calls `llmChatClient.stream(...)`,
>   builds the memory window, applies the agent's per-turn config, persists
>   the assistant message, and emits SSE frames is **EPIC-11**. EPIC-09
>   ships only the port and the adapter; how the port is invoked from a
>   real chat turn is EPIC-11's concern.
> - Per-agent **tool wiring** (filtering the EPIC-07 catalog by
>   `agent.tools`) and per-agent **MCP wiring** (filtering the EPIC-08
>   catalog by `agent.enabledMcpServers`, plus the per-user filesystem
>   scoping of TBD-2) are EPIC-11's responsibility. EPIC-09 carries them
>   only as input fields on `ChatRequest` — the adapter translates those
>   fields into Spring AI's tool-callback / MCP-client surface, but the
>   business logic that decides what to attach lives upstream.
> - The `ExternalServiceException` abstract base shipped by EPIC-08
>   (US-08-007) is reused here: this EPIC adds the `LlmUnavailableException`
>   subclass and the corresponding `LLM_UNAVAILABLE` 502 handler entry.
>   The choice of a typed subclass hierarchy (vs a single class with a
>   `code` field) was recorded in `backend/backlog/DESIGN-CHOICES.md` by
>   US-08-007 — this EPIC follows the same pattern.
> - The non-streaming `call(ChatRequest)` method is shipped in v1 because
>   EPIC-12's delegation execution model (REQ-AGT-015) may choose to call
>   the LLM synchronously for a sub-agent turn (TBD-3). Even if EPIC-12
>   eventually picks the streaming path, shipping `call()` here costs
>   little and keeps the port symmetric with the design §12 surface.

## Conventions

- **ID format**: `US-09-<nnn>` — `09` matches the EPIC number; `<nnn>` is a
  sequential three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as
  `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short
  description, a bullet list of testable acceptance criteria, the
  requirements coverage, the design references, and its dependencies.

## Story list

| ID         | Title                                                                                          | Priority | Status | Depends on                |
|------------|------------------------------------------------------------------------------------------------|----------|--------|---------------------------|
| US-09-001  | `LlmChatClient` port + `ChatRequest` / `ChatChunk` / `ChatResult` records (application layer)   | MUST     | Done   | EPIC-01, EPIC-07          |
| US-09-002  | `application.yaml` LLM configuration + `app.llm.openai.*` property binding + fail-fast on `OPENAI_API_KEY` | MUST | Draft | US-09-001                 |
| US-09-003  | `LlmUnavailableException` + `LLM_UNAVAILABLE` 502 mapping in `GlobalExceptionHandler`           | MUST     | Done   | US-08-007                 |
| US-09-004  | `OpenAiChatClientAdapter` — synchronous `call(ChatRequest)` + Spring AI `ChatOptions` translation + provider error mapping | MUST | Draft | US-09-001, US-09-002, US-09-003 |
| US-09-005  | `OpenAiChatClientAdapter` — streaming `stream(ChatRequest)` + reactive error mapping + client-cancel handling | MUST | Draft | US-09-004                 |

---

## US-09-001 — `LlmChatClient` port + `ChatRequest` / `ChatChunk` / `ChatResult` records (application layer)

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the application-layer port `LlmChatClient` and the value records
that describe a chat completion request, an incremental streamed chunk, and
a non-streaming result
**So that** the chat orchestration EPICs (EPIC-11 send-message, EPIC-12
delegation) can drive the LLM through a provider-agnostic surface
(REQ-LLM-004 / REQ-ARC-005), the OpenAI adapter shipped by US-09-004 /
US-09-005 has a stable contract to implement, and a future
`AnthropicChatClientAdapter` (or any other provider) can replace OpenAI
without touching domain or use-case code.

### Description

The port and its three companion records live in `application/chat/`
(design §3, design §12). They are pure Java with **no Spring AI imports** —
the whole point of the abstraction is that the only place Spring AI types
appear is the adapter. `Flux<ChatChunk>` is fine on the port signature
because Project Reactor's `reactor.core.publisher.Flux` is a third-party
type independent of any LLM provider, and the design (§12) already commits
to it.

`ChatRequest` is a snapshot of what the agent's configuration looks like at
the start of a single turn (REQ-AGT-014). The chat-turn use case in
EPIC-11 will build it from the current `Agent` aggregate, the configured
memory window of past `Message`s, and the resolved tool/MCP catalogs. The
adapter never reads back into the domain or catalogs — it consumes
`ChatRequest` as-is.

`ToolDescriptor` (from `domain/tool/`, shipped by EPIC-07) is reused
verbatim on `ChatRequest.tools` so the application layer doesn't have to
re-shape catalog data when handing it to the adapter. MCP servers are
carried as plain strings on `ChatRequest.enabledMcpServers` — they are
names from the EPIC-08 catalog, and the adapter resolves them to Spring
AI MCP-client tool callbacks at request-build time.

The owning user's id is carried on `ChatRequest.ownerUserId` so the
adapter can resolve the per-user filesystem MCP root via
`FilesystemMcpUserScope` (EPIC-08, US-08-004) when EPIC-11 wires up the
runtime path argument (TBD-2). EPIC-09 does NOT itself use the field; it
exposes it on the request so EPIC-11's wiring can flow without changing
the port.

### Acceptance criteria

- `application/chat/Role.java` — enum `{ USER, ASSISTANT }`. Matches the
  persisted `MessageRole` values in `domain/chat/` and the openapi
  `MessageRole` enum. Tool-call requests/results are NOT modelled here
  (REQ-CHAT-012 keeps them off the persisted surface; for the LLM call,
  the adapter expands tool turns transparently inside Spring AI).
- `application/chat/ChatMessage.java` — record `ChatMessage(Role role,
  String content)`:
  - non-null `role`;
  - non-null, non-blank `content` (`content.length() <= 1024` per
    REQ-CHAT-009 — defensive cap; the upstream validator already
    enforces it but the adapter input keeps the same invariant);
  - violations throw `ValidationException` with field `content`.
- `application/chat/SamplingParameters.java` — record
  `SamplingParameters(Double temperature, Integer maxOutputTokens,
  Double topP)`:
  - every field is **nullable** — a null value means "use the provider /
    Spring AI default", matching REQ-AGT-001 where the per-agent
    overrides are optional;
  - canonical-constructor validation when fields are non-null:
    - `temperature`: `0.0 <= temperature <= 2.0` (OpenAI's documented
      range, conservative);
    - `maxOutputTokens`: `>= 1`;
    - `topP`: `0.0 < topP <= 1.0`;
    - violations throw `ValidationException` with the relevant field
      name (`temperature`, `maxOutputTokens`, `topP`).
  - a static `SamplingParameters.none()` factory returns
    `new SamplingParameters(null, null, null)` so use-case code reads
    cleanly when an agent has no overrides.
  - **Note**: TBD-4 in the design carries the broader question of whether
    the platform should accept any value the provider accepts or apply
    its own narrower clamping. This story commits to the conservative
    bounds above so the adapter never forwards a malformed value to the
    provider; future tightening lives behind the same validation.
- `application/chat/ChatRequest.java` — record `ChatRequest`:
  ```java
  public record ChatRequest(
      String model,                              // resolved model name; non-null, non-blank
      String systemPrompt,                       // non-null, non-blank, <= 1024 chars
      List<ChatMessage> history,                 // non-null, possibly empty, size <= 36 (memorySize cap)
      List<ToolDescriptor> tools,                // non-null, possibly empty
      List<String> enabledMcpServers,            // non-null, possibly empty
      SamplingParameters sampling,               // non-null (use SamplingParameters.none() if no overrides)
      UUID ownerUserId                           // non-null — used by EPIC-11 for MCP per-user scoping
  ) {}
  ```
  - Canonical-constructor validation:
    - `model`: non-blank, `length <= 64` (matches `agents.llm_model
      varchar(64)` and the openapi cap);
    - `systemPrompt`: non-blank, `length <= 1024`;
    - `history`: non-null; defensively copied via `List.copyOf(history)`
      to make the record genuinely immutable;
    - `tools`, `enabledMcpServers`: non-null; defensively copied via
      `List.copyOf(...)`;
    - `sampling`: non-null (callers pass `SamplingParameters.none()` when
      the agent has no overrides);
    - `ownerUserId`: non-null;
    - violations throw `ValidationException` with the relevant field
      name. **Rationale**: the chat-turn code in EPIC-11 should be able
      to fail-fast with a meaningful field name during local testing;
      mass-validating via a Jakarta validator would force `@Valid` on the
      adapter signature and add a Spring dependency the application
      layer doesn't otherwise need.
- `application/chat/ChatChunk.java` — record `ChatChunk(String text)`:
  - `text` non-null; **MAY be empty** (a heartbeat / role-only frame
    from the provider becomes an empty-text chunk, and EPIC-11's SSE
    emitter elides empty deltas);
  - violations on null throw `ValidationException` with field `text`.
- `application/chat/ChatResult.java` — record `ChatResult(String text)`:
  - `text` non-null, possibly empty (a model can legitimately answer
    with the empty string);
  - violations on null throw `ValidationException` with field `text`.
- `application/chat/LlmChatClient.java` — interface:
  ```java
  public interface LlmChatClient {

      /** Non-streaming call; the entire assistant response is returned at once. */
      ChatResult call(ChatRequest request);

      /** Streaming call; emits one or more {@link ChatChunk} elements then completes.
       *  Implementations MUST propagate {@code LlmUnavailableException} (US-09-003) via
       *  {@code Flux.error(...)} for any provider failure. */
      reactor.core.publisher.Flux<ChatChunk> stream(ChatRequest request);
  }
  ```
  - No JavaDoc reference to Spring AI types — the port stays
    provider-agnostic.
  - Both methods are documented to NOT throw checked exceptions; the
    sync `call(...)` throws `LlmUnavailableException` (unchecked,
    infrastructure-side); the reactive `stream(...)` signals failures
    through `Flux.error(LlmUnavailableException)`.
- Pure-Java unit tests under `src/test/java/.../application/chat/`:
  - `RoleTest` — enum has exactly the two values `USER` and `ASSISTANT`,
    in that order (locked-in by EPIC-10's persistence mapping).
  - `ChatMessageTest` — accepts a valid pair; rejects null role,
    null/blank content, and over-1024 content with the field name in
    the `ValidationException`.
  - `SamplingParametersTest` — accepts all-null fields; rejects
    out-of-range `temperature` / `topP` and non-positive
    `maxOutputTokens` with the relevant field name; `SamplingParameters
    .none()` returns the all-null instance; the record is immutable
    (record equality + identity-on-fields).
  - `ChatRequestTest` — accepts a fully-populated request; rejects
    null/blank `model`, null/blank/over-1024 `systemPrompt`, null
    `history`/`tools`/`enabledMcpServers`/`sampling`/`ownerUserId`
    with the relevant field name. Verifies defensive copy: mutating
    the source list after construction does not mutate
    `chatRequest.history()`.
  - `ChatChunkTest` — accepts non-null `text` including the empty
    string; rejects null with field `text`.
  - `ChatResultTest` — accepts non-null `text` including the empty
    string; rejects null with field `text`.
  - `LlmChatClientContractTest` (interface-level) — uses a Mockito
    `mock(LlmChatClient.class)` to assert the API surface compiles
    against the documented signatures and that a `Flux.error(new
    LlmUnavailableException("boom"))` is observable via
    `StepVerifier` (this also exercises the `reactor-test` dependency
    that US-09-005's tests rely on).
- `application/chat/package-info.java` is updated to drop the "Populated
  by EPIC-09 / EPIC-10 / EPIC-11 / EPIC-12" placeholder for the EPIC-09
  half — the file still mentions the remaining EPICs that will land
  conversation/streaming/delegation work.
- ArchUnit (US-01-008) still passes: `application/chat/**` may use Spring
  stereotypes (none introduced by this story; the records are pure-Java),
  and Project Reactor's `Flux` is allowed (already in the classpath via
  Spring's reactive support / Spring AI's starter). No Spring AI imports
  are added to `application/chat/**` — a new ArchUnit rule
  `no_spring_ai_imports_in_application_chat` enforces this at build time.

### Out of scope

- The `OpenAiChatClientAdapter` itself (US-09-004 / US-09-005). This story
  ships only the application-side contracts.
- The chat-turn orchestration that builds `ChatRequest` from the agent
  aggregate, memory window, and catalogs (EPIC-11).
- Persistence of tool-call messages — REQ-CHAT-012 explicitly excludes
  them from the persisted message log; the adapter handles tool turns
  transparently inside Spring AI and the port surface never exposes them.
- Pre-emptive support for response-content other than text (audio /
  images). The v1 LLM surface is text-only.

### Requirements coverage

`REQ-LLM-001`, `REQ-LLM-002`, `REQ-LLM-004`, `REQ-AGT-001` (sampling
parameters surface), `REQ-AGT-014` (per-turn config carried on the
request), `REQ-ARC-002`, `REQ-ARC-003`, `REQ-ARC-005`, `REQ-ARC-007`.

### Design references

§3 project structure (`application/chat/`), §12 LLM integration (port and
record shapes), §4.1 (Agent's optional sampling parameters).

### Dependencies

EPIC-01 (`application/chat/package-info.java` stub, ArchUnit
infrastructure). EPIC-07 (`domain/tool/ToolDescriptor` used by
`ChatRequest.tools`).

---

## US-09-002 — `application.yaml` LLM configuration + `app.llm.openai.*` property binding + fail-fast on `OPENAI_API_KEY`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the OpenAI starter wired in `application.yaml` (api key from
`OPENAI_API_KEY`, default model name from `app.llm.openai.default-model`),
plus an `ApplicationProperties.Llm` nested record binding the app-scoped
LLM configuration, plus a startup fail-fast check that aborts context
refresh when `OPENAI_API_KEY` is missing in a non-test profile
**So that** the OpenAI adapter (US-09-004 / US-09-005) reads the default
model through a single typed surface, operators override both via
environment variables without rebuilding the JAR (REQ-NFR-003 /
REQ-DEP-003), and a missing API key never silently turns into a runtime
502 on the first chat turn — it fails the application at startup
(REQ-SEC-003 / REQ-LLM-003).

### Description

Spring AI's `spring-ai-starter-model-openai` (already on the classpath
per `pom.xml`) reads its credentials from
`spring.ai.openai.api-key` and its default model from
`spring.ai.openai.chat.options.model`. We bind both to env vars in
`application.yaml`, and we expose `app.llm.openai.default-model`
**separately** so the adapter has a typed property to read without
having to crack open Spring AI's own configuration types. Both keys
resolve to the same env-var-backed source (`OPENAI_MODEL`), so they
cannot drift.

The fail-fast on `OPENAI_API_KEY` is required by REQ-LLM-003 +
REQ-SEC-003. Spring AI's starter does NOT fail-fast on a missing key in
1.1.0 — it builds the client lazily and surfaces the failure on the
first call. We add an explicit `@PostConstruct` check on a small
`OpenAiConfig` `@Configuration` class (design §3 already names this
class) that reads the resolved property and throws if it is blank.

### Acceptance criteria

- `infrastructure/config/ApplicationProperties.java` grows a new nested
  record `Llm(@Valid Openai openai)` with
  `Openai(@NotBlank @Size(max = 64) String defaultModel)`, exposed as
  `properties.llm().openai().defaultModel()`. The root record gains
  one more parameter `@Valid Llm llm` in the canonical-constructor
  ordering matching the existing convention (`api`, `cors`, `security`,
  `aws`, `mcp`, `llm`).
- `infrastructure/llm/openai/OpenAiConfig.java` — new
  `@Configuration` class:
  - Constructor-injected with `ApplicationProperties` and Spring
    `Environment` (the `Environment` is needed to read
    `spring.ai.openai.api-key` after Spring AI has resolved the
    placeholder).
  - `@PostConstruct void verifyOpenAiKeyPresentOnStartup()`:
    1. Reads `spring.ai.openai.api-key` via
       `environment.getProperty("spring.ai.openai.api-key")`.
    2. If the resolved value is null or blank, throws
       `IllegalStateException` with the message
       `"OPENAI_API_KEY environment variable is missing or empty;
       cannot start (REQ-LLM-003 / REQ-SEC-003)."`. The message
       deliberately names the env var so operators see exactly what is
       missing.
    3. The message MUST NOT include the (resolved) value itself, even
       if non-blank (REQ-SEC-004 — no API keys in logs).
  - This class is the natural place for any future OpenAI-specific
    `@Bean` definitions (e.g. a custom `RestClient.Builder`); v1 ships
    only the `@PostConstruct` check.
- `application.yaml` (main) gets:
  ```yaml
  spring:
    ai:
      openai:
        api-key: ${OPENAI_API_KEY}
        chat:
          options:
            model: ${app.llm.openai.default-model}

  app:
    llm:
      openai:
        default-model: ${OPENAI_MODEL:gpt-4o-mini}
  ```
  - The `app.llm.openai.default-model` default is the literal string
    `gpt-4o-mini` (REQ-LLM-002).
  - The `spring.ai.openai.chat.options.model` placeholder reads from
    the app property so the two keys stay in sync — operators can
    override either `OPENAI_MODEL` (preferred) or, in a pinch,
    `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` if a deployment needs to
    diverge from the app-scoped default.
  - The header comment block at the top of `application.yaml` already
    lists `OPENAI_API_KEY` with the "EPIC-09" tag (US-01-004); this
    story removes the "EPIC-09" annotation and adds `OPENAI_MODEL`
    (with its default `gpt-4o-mini`) as a documented optional env var.
- Test `application.yaml` (under `src/test/resources/`) gets:
  - The same `app.llm.openai.default-model: gpt-4o-mini` default
    (so tests don't need to set `OPENAI_MODEL` explicitly).
  - `spring.ai.openai.api-key: test-openai-key` so the
    `OpenAiConfig` `@PostConstruct` check passes during the test
    boot. **No** real OpenAI traffic happens in tests (those use
    WireMock — US-09-004 / US-09-005).
  - Spring AI's outbound base URL is overridden to a localhost
    address via `spring.ai.openai.base-url:
    http://localhost:${wiremock.server.port}` in the **specific**
    integration tests that need it (set via
    `@DynamicPropertySource`, not in the global test yaml — keeps
    other tests fast and offline).
- `ApplicationPropertiesTest` (extending the existing US-01-004 /
  US-07-003 / US-08-002 test class) gains two cases:
  - `properties.llm().openai().defaultModel()` binds the default
    `gpt-4o-mini` when `OPENAI_MODEL` is absent.
  - `properties.llm().openai().defaultModel()` binds the env-var
    override when `OPENAI_MODEL=gpt-4.1-mini` is set.
- New test `OpenAiApiKeyMissingFailsFastTest` (`@SpringBootTest`,
  `@DynamicPropertySource` removes `spring.ai.openai.api-key` /
  `OPENAI_API_KEY` from the resolved environment) asserts that
  context refresh fails with an exception whose message contains
  `OPENAI_API_KEY`. **Symmetric** with `BraveApiKeyMissingFailsFastTest`
  (US-08-002) — the two tests prove both env vars fail-fast at startup.
- New test `OpenAiApiKeyPresentBootsFineTest` (positive case) — boots
  the context with `spring.ai.openai.api-key=test-openai-key`,
  resolves `OpenAiConfig` from the context, and asserts no exception
  is thrown.
- New test `OpenAiDefaultModelWiringTest` boots the context and
  asserts:
  - `properties.llm().openai().defaultModel()` returns
    `"gpt-4o-mini"` (default).
  - `environment.getProperty("spring.ai.openai.chat.options.model")`
    returns the same value — proving the placeholder relay is
    correct.
- No code change to `JjwtTokenServiceAdapter`,
  `ChangeOwnPasswordService`, `McpServerCatalogAdapter`,
  `FilesystemMcpUserScopeAdapter`, or any other existing bean — this
  story is configuration-only on the code side.
- ArchUnit (US-01-008) still passes; `OpenAiConfig` lives strictly
  under `infrastructure/llm/openai/`.

### Out of scope

- The `OpenAiChatClientAdapter` itself (US-09-004 / US-09-005). This
  story only wires configuration and the startup fail-fast check.
- Validation of `OPENAI_API_KEY`'s **format** (e.g. that it starts
  with `sk-`). The provider returns 401 on an invalid-looking key
  anyway, and 401 from the provider is already mapped to
  `LLM_UNAVAILABLE` 502 by US-09-003 / US-09-004 — the platform
  treats provider-side credential rejection as an external-service
  failure, never reflected as our 401 (REQ-LLM-005).
- Per-environment model rosters (e.g. `gpt-4o-mini` in dev and
  `gpt-4o` in prod). The single `OPENAI_MODEL` env var is enough for
  v1; richer rosters can be carried by a future
  `app.llm.openai.allowed-models` list without breaking the binding
  surface this story introduces.

### Requirements coverage

`REQ-LLM-001`, `REQ-LLM-002`, `REQ-LLM-003`, `REQ-SEC-003`,
`REQ-SEC-004`, `REQ-NFR-003`, `REQ-DEP-003`.

### Design references

§12 LLM integration (default model, env-var-backed credentials),
§15 configuration (env-var contract).

### Dependencies

US-09-001 (the application port the adapter will implement next; this
story does not consume the port but lands ahead of the adapter so the
adapter has a fail-fast guarantee on the env var by the time it boots).

---

## US-09-003 — `LlmUnavailableException` + `LLM_UNAVAILABLE` 502 mapping in `GlobalExceptionHandler`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an `LlmUnavailableException` infrastructure exception (subclass
of `ExternalServiceException` shipped by US-08-007) and a corresponding
`@ExceptionHandler` in `GlobalExceptionHandler` that maps it to HTTP 502
with `code = LLM_UNAVAILABLE`
**So that** design §9.3's error-code table is honored end-to-end, the
OpenAI adapter (US-09-004 / US-09-005) has a well-defined exception to
throw on provider failure, and EPIC-11's chat-turn code does not need to
do its own provider-error translation — the boundary between "provider
failed" and "user sees a 502" is owned by the exception type alone.

### Description

US-08-007 shipped the abstract `ExternalServiceException` base in
`infrastructure/error/` and committed the project (via
`DESIGN-CHOICES.md`) to a typed-subclass hierarchy for external-service
failures. This EPIC adds the LLM half: a concrete final subclass and a
single handler entry in `GlobalExceptionHandler`. The handler mirrors
the existing `handleMcpServerError(...)` method byte-for-byte except
for the response code, the `code` string, and the static detail
message.

The pre-shipped openapi spec already lists `LLM_UNAVAILABLE` in the
`ProblemDetails.code` enum — no openapi change is required.

### Acceptance criteria

- `infrastructure/error/LlmUnavailableException.java` — `public final
  class LlmUnavailableException extends ExternalServiceException`
  with constructors `(String message)` and `(String message, Throwable
  cause)`. JavaDoc spells out:
  - Mapped to HTTP 502 `LLM_UNAVAILABLE`.
  - Thrown by infrastructure adapters that wrap LLM-provider runtime
    failures (`OpenAiChatClientAdapter`, US-09-004 / US-09-005, and
    any future provider adapter).
  - The constructor message MUST NOT contain provider payloads, raw
    prompt text, or `OPENAI_API_KEY` fragments (REQ-SEC-004 — log
    redaction).
- `infrastructure/web/error/GlobalExceptionHandler.java` gains a new
  `@ExceptionHandler(LlmUnavailableException.class)` method
  `handleLlmUnavailable(LlmUnavailableException ex,
  HttpServletRequest req)` that:
  - Logs at `WARN` with the request method/URI, the exception
    message, and the cause's class name (when the cause is non-null).
    Mirrors the `handleMcpServerError` log shape exactly so log
    consumers see a uniform structure across the two
    external-service codes.
  - Returns a `ProblemDetails` body with:
    ```json
    {
      "type": "https://errors.multi-agent-platform/llm-unavailable",
      "title": "LLM unavailable",
      "status": 502,
      "detail": "The language-model provider is currently unavailable.",
      "code": "LLM_UNAVAILABLE"
    }
    ```
  - HTTP status 502.
  - The `instance` field is filled in by the existing handler
    infrastructure (US-03-001) from `req.getRequestURI()`; this
    story does not add bespoke handling for it.
- The new handler method is placed in `GlobalExceptionHandler.java`
  **immediately above** the existing `handleMcpServerError(...)`
  block so the two 502 mappings sit visually adjacent — eases
  future review.
- Unit test `GlobalExceptionHandlerLlmUnavailableTest` (Mockito +
  plain controller-advice unit test, NO `@SpringBootTest` — mirrors
  the `GlobalExceptionHandlerMcpServerErrorTest` from US-08-007):
  - Throws `LlmUnavailableException("openai 503 service
    unavailable")` and asserts the produced `ResponseEntity` has
    status `502`, content type `application/problem+json`, and a
    body matching the documented shape with
    `code == "LLM_UNAVAILABLE"`.
  - Throws `LlmUnavailableException("openai connection refused",
    new java.net.ConnectException("Connection refused"))` and
    asserts the response shape is unchanged (the cause is logged
    but does NOT leak into the body).
  - Asserts the response body's `detail` is the static string
    `"The language-model provider is currently unavailable."` —
    locks the user-facing message in place so no future change
    accidentally leaks provider state.
- Integration test extension on the existing
  `GlobalExceptionHandlerIntegrationTest` (or, if a unified one
  doesn't exist yet, a new minimal one): a stub controller exposed
  only in the test classpath throws
  `new LlmUnavailableException("test")` from a `@GetMapping`
  endpoint, and a MockMvc call asserts the 502 / Problem-Details
  shape end-to-end through the real `RestControllerAdvice`. This
  is the same pattern US-08-007 used for `McpServerException`; if a
  shared test fixture exists, this story extends it rather than
  re-creating one.
- `backend/backlog/DESIGN-CHOICES.md` is **not** modified — the
  choice between option (a) typed subclasses and option (b)
  single-class-with-code was already recorded by US-08-007. This
  story follows the same pattern by construction.
- ArchUnit (US-01-008) still passes — the exception lives in
  `infrastructure/error/**`, never imported from `domain/**` or
  `application/**`. The `LlmChatClient` port from US-09-001 does
  not declare `LlmUnavailableException` in its `throws` clause
  (the exception is unchecked); the application layer's only
  contract with provider failure is "the adapter may throw an
  unchecked `RuntimeException` and the handler renders it".

### Out of scope

- Logging the full provider-error JSON for support debugging — the
  adapter logs the **classification** (HTTP status from the
  provider, whether the failure was a timeout, etc.) at `WARN`
  itself, before throwing. The handler logs the wrapped
  exception's `getMessage()` plus the cause's class name; deeper
  forensic logging belongs to the adapter (US-09-004) where the
  raw provider response is available.
- A separate `code = LLM_RATE_LIMITED` for provider 429s. Per
  REQ-LLM-005 + design §12, the platform deliberately surfaces
  provider 429 as `LLM_UNAVAILABLE` 502 so the upstream rate
  limit is treated as an infrastructure problem, not reflected as
  our own 429 (our 429 is reserved for the global Bucket4j
  filter, EPIC-13).

### Requirements coverage

`REQ-LLM-005`, `REQ-ARC-007`, `REQ-API-004`, `REQ-SEC-004`.

### Design references

§9.1 typology, §9.2 GlobalExceptionHandler, §9.3 error code table
(`LLM_UNAVAILABLE` 502), §12 LLM integration (error mapping).

### Dependencies

US-08-007 (`ExternalServiceException` abstract base + the matching
`handleMcpServerError` pattern this story copies). US-03-001
(existing `GlobalExceptionHandler` and `ProblemDetails` mapper).

---

## US-09-004 — `OpenAiChatClientAdapter` — synchronous `call(ChatRequest)` + Spring AI `ChatOptions` translation + provider error mapping

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the OpenAI adapter implementing
`LlmChatClient.call(ChatRequest)` — translating a `ChatRequest` into a
Spring AI `ChatClient` invocation, applying per-agent overrides on top
of the platform-default `ChatOptions`, and mapping every provider
failure to `LlmUnavailableException`
**So that** the non-streaming half of the port is wired, EPIC-12's
delegation can call the LLM synchronously for a sub-agent turn if
TBD-3 settles on that path, and the streaming half (US-09-005) can
reuse the same translation / error-mapping helpers without
duplicating logic.

### Description

The adapter sits in `infrastructure/llm/openai/` next to the
`OpenAiConfig` shipped by US-09-002. It is constructor-injected with:
- Spring AI's `org.springframework.ai.openai.OpenAiChatModel` (auto-configured
  by `spring-ai-starter-model-openai`),
- `ApplicationProperties` (for the default model, used when
  `ChatRequest.model()` is itself null/blank — which is NOT supposed
  to happen because the chat-turn code in EPIC-11 resolves the model
  upstream, but the adapter defends against it).

**Translation**:
- `ChatRequest.systemPrompt` → Spring AI `SystemMessage`.
- `ChatRequest.history` → ordered list of Spring AI `UserMessage` /
  `AssistantMessage` (one per `ChatMessage`).
- `ChatRequest.sampling` → `OpenAiChatOptions.builder()
  .temperature(...).maxTokens(...).topP(...)` (null fields are
  skipped — Spring AI inherits the starter default).
- `ChatRequest.model` → `OpenAiChatOptions.builder().model(...)`.
- `ChatRequest.tools` → Spring AI `ToolCallback` instances resolved
  from the Spring bean catalog (the same `@Tool`-annotated beans
  EPIC-07 discovers). The adapter maps each `ToolDescriptor.name()`
  back to its bean callback via Spring AI's `ToolCallbackResolver`
  (or equivalent 1.1.0 API); unknown names are **silently dropped**
  (the catalog-backed reference validator in EPIC-07 already
  guarantees this can't happen for a persisted agent — the silent
  drop is defense-in-depth, not a swallowed business rule, and is
  logged at `WARN`).
- `ChatRequest.enabledMcpServers` → wired to Spring AI's
  MCP-client tool callbacks for the named connections. **TBD-2**'s
  resolution between "per-user MCP process" and "shared MCP process
  with path rewriting" is not made here — EPIC-11 owns the runtime
  wiring; this adapter exposes whatever Spring AI's MCP client
  surface already returns at construction time.
- `ChatRequest.ownerUserId` is not used by this story (sync call
  does not yet trigger MCP-server scoping in v1 — the
  filesystem-MCP per-user root is a chat-turn concern owned by
  EPIC-11). The field is preserved on the request so EPIC-11's
  streaming wiring can read it without changing the port.

**Error mapping** (REQ-LLM-005, design §12):
- Provider HTTP 4xx (including 401 / 403 / 404 / 422) →
  `LlmUnavailableException` with the provider status in the
  message (no payload).
- Provider HTTP 429 → `LlmUnavailableException` (NOT our 429 —
  our rate limit is independent).
- Provider HTTP 5xx → `LlmUnavailableException`.
- Connection timeout / read timeout / connection refused / SSL
  handshake failure → `LlmUnavailableException`.
- Any other unchecked exception from Spring AI's `ChatClient` →
  `LlmUnavailableException` wrapped with the original cause.
- The adapter logs the **classification** (status code, exception
  class) at `WARN` immediately before throwing, so operators can
  correlate. Provider payload / prompt text is NEVER logged
  (REQ-SEC-004).

### Acceptance criteria

- `infrastructure/llm/openai/OpenAiChatClientAdapter.java` —
  `@Component` implementing `application.chat.LlmChatClient`.
  Constructor-injected as described above.
- `call(ChatRequest request)` body:
  1. **Resolve the model**: prefer `request.model()`; if
     null/blank, fall back to
     `properties.llm().openai().defaultModel()`. (Defensive — the
     upstream code already resolves it.)
  2. **Build `OpenAiChatOptions`** with model + sampling
     parameters (skipping null fields).
  3. **Build the message list**: prepend a `SystemMessage(systemPrompt)`
     then iterate `history` to `UserMessage`/`AssistantMessage`.
  4. **Resolve tool callbacks** from `request.tools()`; attach
     them to the options.
  5. **Resolve MCP callbacks** from `request.enabledMcpServers()`
     against the Spring AI MCP-client surface; attach them.
  6. Invoke Spring AI's `ChatClient.prompt(...).options(...).call()
     .content()` (1.1.0 fluent API — exact accessor confirmed at
     implementation time) and wrap the resulting string in
     `new ChatResult(content == null ? "" : content)`.
  7. Catch every exception coming out of `ChatClient` and re-throw
     as `LlmUnavailableException(message, cause)`. The message
     follows the shape `"openai provider failure: <classification>"`
     where `<classification>` is one of `http_4xx <code>`,
     `http_429`, `http_5xx <code>`, `timeout`, `connection_refused`,
     or `unknown` — never the provider payload.
- A small private helper `OpenAiErrorMapper.translate(Throwable)`
  centralizes the classification → message string mapping so
  US-09-005's streaming path reuses the same logic.
- A small private helper `OpenAiChatOptionsTranslator.translate(
  ChatRequest)` produces the `OpenAiChatOptions` instance,
  shared with US-09-005.
- The adapter is **stateless** — no fields beyond the
  constructor-injected dependencies. Spring AI's `ChatClient` is
  thread-safe; concurrent `call(...)` invocations are supported
  out of the box.
- **No** business retry logic — a single failed call surfaces
  immediately as `LlmUnavailableException`. Retries (with
  exponential backoff) are a v2 concern carried by EPIC-15 /
  observability.
- WireMock-based integration test
  `OpenAiChatClientAdapterCallTest` (`@SpringBootTest`,
  `@AutoConfigureWireMock(port = 0)`, sets
  `spring.ai.openai.base-url=http://localhost:${wiremock.server
  .port}` via `@DynamicPropertySource`):
  - **Happy path**: WireMock stubs `POST /v1/chat/completions`
    returning a canned JSON `{"choices":[{"message":{"role":
    "assistant","content":"hello"}}]}`; the adapter's
    `call(request)` returns `new ChatResult("hello")`.
  - **Provider 401**: WireMock returns 401 with an empty body;
    the adapter throws `LlmUnavailableException` whose message
    contains `http_4xx 401` and whose cause is non-null.
  - **Provider 429**: WireMock returns 429; the adapter throws
    `LlmUnavailableException` with `http_429` in the message —
    NOT a `TooManyRequestsException` or any other
    rate-limit-flavored exception.
  - **Provider 500**: WireMock returns 500; the adapter throws
    `LlmUnavailableException` with `http_5xx 500` in the message.
  - **Connection refused**: WireMock is stopped before the call;
    the adapter throws `LlmUnavailableException` with
    `connection_refused` (or `timeout`, whichever the underlying
    HTTP client surfaces — the test accepts either) in the
    message.
  - **Sampling translation**: the `request` body sent to
    WireMock contains the `temperature` / `max_tokens` / `top_p`
    values from `ChatRequest.sampling`; null fields are omitted
    (asserted via WireMock's body-matchers, not by reading
    Spring AI internals).
  - **Model override**: a `ChatRequest` with `model("gpt-4o")`
    produces a request body whose `"model"` field is `"gpt-4o"`,
    NOT `"gpt-4o-mini"`.
  - **History ordering**: a request with `history = [USER:a,
    ASSISTANT:b, USER:c]` produces a request body whose
    `"messages"` array preserves that order after the
    system-prompt message.
  - **System prompt**: the first element of the WireMock-observed
    `"messages"` array has `role: "system"` and content matching
    `ChatRequest.systemPrompt`.
  - **No `OPENAI_API_KEY` in logs**: a CapturingAppender (Logback
    test fixture, if one exists; otherwise a SLF4J test helper)
    asserts that across the happy-path and all four failure-mode
    tests, the captured log lines never contain the substring
    `test-openai-key`.
- Unit test `OpenAiErrorMapperTest` (Mockito-free, plain-Java):
  - Maps a synthetic `org.springframework.web.client
    .HttpClientErrorException` with status 401 → message
    `"openai provider failure: http_4xx 401"`.
  - Maps a `HttpClientErrorException.TooManyRequests` → message
    contains `http_429`.
  - Maps a `HttpServerErrorException` with status 503 →
    message contains `http_5xx 503`.
  - Maps a `ResourceAccessException(IOException)` →
    `connection_refused` or `timeout`.
  - Maps a generic `RuntimeException("boom")` → `unknown`.
- Unit test `OpenAiChatOptionsTranslatorTest` (Mockito-free):
  - Translates a `SamplingParameters(0.7, 256, 0.9)` into an
    `OpenAiChatOptions` whose `getTemperature() == 0.7`,
    `getMaxTokens() == 256`, `getTopP() == 0.9`.
  - Translates `SamplingParameters.none()` into options where
    `getTemperature()`, `getMaxTokens()`, `getTopP()` are all
    null.
  - Translates a `ChatRequest.model("gpt-4o")` into options
    whose `getModel() == "gpt-4o"`.
  - Falls back to `properties.llm().openai().defaultModel()` when
    `ChatRequest.model()` is null/blank — the test constructs an
    in-test `ApplicationProperties` builder for this case (the
    same builder pattern US-08-004's `FilesystemMcpUserScope
    AdapterTest` uses).
- ArchUnit (US-01-008) still passes — the adapter lives strictly
  in `infrastructure/llm/openai/**`; no `application/**` or
  `domain/**` code imports Spring AI types.
- The `OpenAiChatClientAdapter` is the **sole** Spring bean
  implementing `application.chat.LlmChatClient` — a context
  smoke test (`LlmChatClientWiringTest`) asserts
  `applicationContext.getBeanNamesForType(LlmChatClient.class)
  .length == 1`.

### Out of scope

- The streaming `stream(ChatRequest)` half — US-09-005.
- Persistence of the assistant message returned from `call(...)`
  — that is EPIC-10 / EPIC-11's concern; the adapter only
  produces the text and lets the caller decide what to do with
  it.
- Server-sent retry / hedging across multiple providers — v1
  ships a single provider and no retry. REQ-LLM-004 keeps the
  port future-friendly; concrete retry strategies are out of
  scope.
- Token / cost accounting. The adapter does NOT surface usage
  metadata in v1; if EPIC-15 wants it, that's a follow-up.

### Requirements coverage

`REQ-LLM-001`, `REQ-LLM-002`, `REQ-LLM-004`, `REQ-LLM-005`,
`REQ-AGT-001` (sampling parameter overrides), `REQ-AGT-014`
(per-turn config), `REQ-ARC-005`, `REQ-SEC-003`, `REQ-SEC-004`.

### Design references

§12 LLM integration (adapter responsibilities, ChatOptions
translation, error mapping), §3 project structure
(`infrastructure/llm/openai/`).

### Dependencies

US-09-001 (`LlmChatClient` port + records the adapter implements
and consumes), US-09-002 (`OpenAiConfig` fail-fast +
`ApplicationProperties.Llm.Openai.defaultModel()`),
US-09-003 (`LlmUnavailableException` the adapter throws).
EPIC-07 (`ToolDescriptor` + the `@Tool`-annotated bean catalog
the adapter resolves tool callbacks from). EPIC-08 (MCP-client
surface the adapter resolves MCP callbacks from — present but
not yet routed per-user; routing lands in EPIC-11).

---

## US-09-005 — `OpenAiChatClientAdapter` — streaming `stream(ChatRequest)` + reactive error mapping + client-cancel handling

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the OpenAI adapter implementing
`LlmChatClient.stream(ChatRequest)` — emitting one `ChatChunk` per
incremental token chunk from Spring AI's streaming surface, mapping
every provider failure to a `Flux.error(LlmUnavailableException)`,
and propagating downstream cancellation so the upstream provider
call is released when the SSE client disconnects (REQ-STR-003)
**So that** EPIC-11's `SendMessageService` can bridge Spring MVC's
`SseEmitter` onto a `Flux<ChatChunk>` without any provider-specific
glue, the failure mode is identical to the sync half (US-09-004),
and an SSE client disconnect promptly cancels the in-flight OpenAI
request rather than leaving it running on the server.

### Description

This story completes the `LlmChatClient` port by implementing the
streaming half on the same adapter instance shipped by US-09-004.
It reuses `OpenAiChatOptionsTranslator` and `OpenAiErrorMapper` from
US-09-004 — the translation and error-classification logic is shared
between `call` and `stream`.

**Streaming semantics**:
- The method returns a **cold** `Flux<ChatChunk>` — the underlying
  Spring AI streaming call is **not** initiated until a subscriber
  attaches (this is the natural behavior of `ChatClient.stream()`
  in Spring AI 1.1.0; the adapter does not need to do anything
  special).
- Each Spring AI `ChatResponse` element is mapped to a
  `ChatChunk(content)` where `content` is the delta text. Empty
  delta strings ARE emitted as `ChatChunk("")` and EPIC-11's SSE
  emitter is expected to elide them; this story does not
  pre-filter.
- The stream completes normally when Spring AI's `Flux` completes.
  EPIC-11 will use the completion signal as a hook to persist the
  assistant message.
- Errors are mapped via `Flux.onErrorMap(...)` — never via a
  blocking `try/catch` around `subscribe()` — so the failure
  surfaces inside the reactive chain.

**Client cancellation** (REQ-STR-003):
- Downstream cancellation (the SSE emitter closing because the
  client disconnected) propagates upstream automatically — Spring
  AI's `Flux<ChatResponse>` honors Reactor's cancel signal and
  closes the underlying HTTP request to OpenAI. The adapter does
  not need a `doOnCancel` for correctness, but adds a
  `doOnCancel(() -> log.debug("openai stream cancelled by
  downstream"))` for operator visibility.
- A failed provider call mid-stream (e.g. the SSE drops at byte
  N) emits `LlmUnavailableException` and the stream terminates
  with `Flux.error(...)`. Whatever chunks were already emitted
  remain emitted — EPIC-11 chooses what to do with a partial
  response (the design notes assistant messages are persisted
  only on `completed`, not on `error`).

### Acceptance criteria

- `infrastructure/llm/openai/OpenAiChatClientAdapter.java` gains
  the `stream(ChatRequest request)` method:
  1. Resolve the model + build `OpenAiChatOptions` + build the
     message list + resolve tool / MCP callbacks — identical to
     US-09-004's `call(...)`, via the shared
     `OpenAiChatOptionsTranslator`.
  2. Invoke Spring AI's `ChatClient.prompt(...).options(...)
     .stream().content()` (1.1.0 fluent API — exact accessor
     confirmed at implementation time).
  3. Map each emitted string delta to
     `new ChatChunk(delta == null ? "" : delta)` via `Flux.map`.
  4. Apply `.onErrorMap(t -> new
     LlmUnavailableException(OpenAiErrorMapper.translate(t),
     t))` so every failure inside the reactive chain becomes the
     project's typed exception.
  5. Add `.doOnCancel(() -> log.debug("openai stream cancelled by
     downstream"))` and `.doOnError(err -> log.warn("openai
     stream failed: {} ({})", err.getMessage(),
     err.getClass().getName()))`.
  6. Return the `Flux<ChatChunk>` to the caller.
- The method does NOT log the streamed chunk text at any level
  (REQ-SEC-004 — user content is at TRACE only per design §8.7,
  and v1 keeps it strictly off until/unless EPIC-15 says
  otherwise).
- The method is **non-blocking** — the adapter never calls
  `.block()` on the returned `Flux`. Verified by an ArchUnit rule
  (or, if a global one already enforces "no `.block()` in
  `infrastructure/llm/**`", just confirmed it covers this
  path).
- WireMock-based integration test
  `OpenAiChatClientAdapterStreamTest` (`@SpringBootTest`,
  `@AutoConfigureWireMock(port = 0)`, `@DynamicPropertySource`
  pointing `spring.ai.openai.base-url` at WireMock,
  reactor-test's `StepVerifier`):
  - **Happy path**: WireMock stubs `POST /v1/chat/completions`
    (with `stream=true` in the body) returning a streaming SSE
    body with three chunks `Hello`, ` `, `world!`; the adapter's
    `stream(request)` emits exactly three `ChatChunk`s with
    those texts (in order) and completes.
  - **Empty-text chunk**: WireMock returns an SSE chunk with an
    empty delta (`{"choices":[{"delta":{"content":""}}]}`);
    the adapter still emits a `ChatChunk("")` — proves the
    "let the SSE emitter elide" contract.
  - **Provider 401 at request time**: WireMock returns 401
    before any SSE bytes; the adapter's `Flux` signals
    `LlmUnavailableException` with `http_4xx 401` in the
    message and no `ChatChunk` is emitted.
  - **Provider 5xx mid-stream**: WireMock emits two chunks
    successfully then closes the connection abruptly with a
    500; the adapter emits two `ChatChunk`s then signals
    `LlmUnavailableException` with `http_5xx` (or `unknown`,
    depending on whether Spring AI's reactive client classifies
    a mid-stream abort as a status-aware failure; the test
    accepts either as long as the exception type matches).
  - **Provider 429**: WireMock returns 429; the adapter's `Flux`
    signals `LlmUnavailableException` with `http_429` —
    crucially, NOT mapped to our `RATE_LIMITED` 429.
  - **Client cancellation**: a `StepVerifier` subscribes,
    awaits one chunk, then cancels via `thenCancel().verify()`;
    a `CapturingAppender` confirms the
    `"openai stream cancelled by downstream"` debug line is
    emitted. WireMock's request-journal (or `verify(0,
    postRequestedFor(...))` after a short wait) confirms no
    further bytes are read from the WireMock body — the
    upstream connection is released. **Note**: the precise
    WireMock probe depends on whether 1.1.0's reactive
    client closes the connection synchronously on cancel; the
    test accepts either an immediate close or a close within
    1 second.
  - **Sampling / model / history / system prompt translation**:
    same body-matcher assertions as US-09-004, replicated for
    the streaming endpoint to lock in symmetry.
  - **No `OPENAI_API_KEY` in logs**: a `CapturingAppender`
    confirms no captured log line across all six cases above
    contains the substring `test-openai-key`.
- The `OpenAiErrorMapper` helper shipped by US-09-004 is used as
  the **sole** translator — no duplication between sync and
  stream. A unit test
  `OpenAiChatClientAdapterStreamErrorMappingTest` builds a
  synthetic `Flux.error(new
  WebClientResponseException("...", 503, ...))` (or whichever
  exception type Spring AI's reactive surface forwards from
  the underlying HTTP client), runs it through the adapter's
  `.onErrorMap(...)` chain in isolation (via a small
  reflectively-exposed helper or by extracting the operator
  into a static), and asserts the emitted error is an
  `LlmUnavailableException` whose message comes from
  `OpenAiErrorMapper`. If extracting the operator is awkward,
  the WireMock test above is sufficient — this unit-test
  case is optional.
- Reactor's `reactor-test` dependency is on the test classpath
  (already pulled in transitively via Spring's reactive
  support; if it is NOT, the implementer adds an explicit
  `<scope>test</scope>` `reactor-test` dependency and notes it
  in `DESIGN-CHOICES.md`).
- ArchUnit (US-01-008) still passes — no new package layout
  changes; `infrastructure/llm/openai/` stays the only
  Spring-AI-importing package.
- `LlmChatClientWiringTest` from US-09-004 still asserts
  exactly one bean implements `LlmChatClient`.

### Out of scope

- The SSE emitter wiring on the controller side — that is
  EPIC-11 (`POST /conversations/{id}/messages` with
  `text/event-stream`). EPIC-09 stops at the `Flux<ChatChunk>`
  surface.
- Persisting the assistant message after stream completion —
  EPIC-11 attaches that side effect to `Flux.doOnComplete(...)`
  in its `SendMessageService`.
- A `LlmUnavailableException` carrying structured token-usage
  metadata. v1 is text-only.
- Real OpenAI traffic in any test — every test in this story
  uses WireMock. A separate manual smoke-test script (under
  `backend/docs/` or as a documentation snippet) MAY exist for
  developers to exercise the real provider; it is not part of
  the `mvn test` lifecycle.

### Requirements coverage

`REQ-LLM-001`, `REQ-LLM-002`, `REQ-LLM-004`, `REQ-LLM-005`,
`REQ-STR-003`, `REQ-AGT-001`, `REQ-AGT-014`, `REQ-ARC-005`,
`REQ-SEC-003`, `REQ-SEC-004`.

### Design references

§7 streaming (cancel propagation), §12 LLM integration
(streaming variant + error mapping), §16.2 send-message
sequence (consumer of this stream — context only).

### Dependencies

US-09-004 (the adapter class + `OpenAiChatOptionsTranslator` +
`OpenAiErrorMapper` helpers reused here). US-09-003
(`LlmUnavailableException`). US-09-001 (`ChatChunk` record).

---

## EPIC-09 Definition of Done

EPIC-09 is **Done** when, in addition to every story being individually
`Done`:

- `mvn test` runs every test from previous EPICs green; the EPIC-09 unit
  and integration tests run green against WireMock without any real
  OpenAI traffic.
- `OPENAI_API_KEY` is required at startup in non-test profiles; a
  missing value fails the application fast with an
  `IllegalStateException` whose message contains `OPENAI_API_KEY`
  (REQ-LLM-003 / REQ-SEC-003) — symmetric with
  `BRAVE_API_KEY`'s fail-fast from EPIC-08.
- `properties.llm().openai().defaultModel()` defaults to
  `gpt-4o-mini` (REQ-LLM-002); production deployments override via the
  `OPENAI_MODEL` env var without rebuilding the JAR (REQ-NFR-003 /
  REQ-DEP-003).
- Exactly one Spring bean implements `application.chat.LlmChatClient`
  — `OpenAiChatClientAdapter`. A future
  `AnthropicChatClientAdapter` (or any other provider) would replace
  it without changing the port (REQ-LLM-004 / REQ-ARC-005).
- A synchronous `LlmChatClient.call(ChatRequest)` against WireMock
  returns a `ChatResult` whose `text()` matches the canned response.
  Provider 4xx / 429 / 5xx / timeouts / connection refused all
  surface as `LlmUnavailableException` mapped to HTTP 502 with
  `code = LLM_UNAVAILABLE`.
- A streaming `LlmChatClient.stream(ChatRequest)` against WireMock
  emits one `ChatChunk` per SSE delta and completes normally. A
  mid-stream provider failure terminates the `Flux` with
  `LlmUnavailableException`. A downstream cancellation
  (`StepVerifier.thenCancel()`) propagates upstream and releases
  the WireMock-served connection.
- Across every test in the EPIC, no log line contains the literal
  `test-openai-key` value used for `spring.ai.openai.api-key`
  (REQ-SEC-004).
- `application/chat/**` contains zero Spring AI imports — verified by
  the new ArchUnit rule
  `no_spring_ai_imports_in_application_chat`. All Spring AI types
  live strictly under `infrastructure/llm/openai/**`.
- The 502 `LLM_UNAVAILABLE` mapping in `GlobalExceptionHandler` is
  exercised by both an isolated unit test and an end-to-end MockMvc
  integration test, and is byte-shape-identical to the design §9.3
  table entry.
- ArchUnit (US-01-008) still passes: `domain/**` and
  `application/**` are free of Spring AI / WireMock / Reactor-test
  imports; the OpenAI adapter, the LLM-unavailable exception, and
  the OpenAI configuration class all live strictly under
  `infrastructure/**`.
