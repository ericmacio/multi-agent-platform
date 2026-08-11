# DESIGN-CHOICES.md — Backend implementation log

This file records implementation-time decisions that diverged from, supplemented, or
clarified the original story acceptance criteria during the EPIC-04 → EPIC-07 build-out.
Each entry captures the choice, the rationale, and the downstream consequence so that
future EPICs and reviewers can see why the code looks the way it does without having to
mine commit history or PR discussions.

## Conventions

- **Tags** classify the nature of the decision so a reader can filter quickly:
  - `[scope-spillover]` — a story acquired or shed scope vs. its original AC.
  - `[env-quirk]` — the JDK, Spring Boot 4, Postgres, or Mockito forced our hand.
  - `[layering]` — ArchUnit or the hexagonal rule constrained the placement.
  - `[business]` — a behavior that the design / requirements left silent and that we
    chose deliberately.
  - `[security/privacy]` — a choice driven by a confidentiality concern.
  - `[performance]` — a choice driven by a fast/lean concern.
- **Sources** point at the EPIC, story, and (where it exists) the relevant
  `EPIC-<n>-US.md` scope-spillover note.
- One entry per decision. Newest EPICs go at the bottom — keep this file in build order
  for quick chronological scanning.

---

## EPIC-04 — API-key authentication

### `ClientId` shipped in US-04-001, not US-04-002

- **Tag**: `[scope-spillover]`
- **Decision**: The `domain/auth/ClientId` value object was created during US-04-001,
  even though the original AC for US-04-001 only called for `SystemPrincipal` and the
  AC for US-04-002 was the one that listed `ClientId`.
- **Why**: `SystemPrincipal` carries a `ClientId` at the type level. Without `ClientId`,
  US-04-001 cannot compile. We could have used a raw `String` placeholder and refactored
  later, but typed identity through the principal is the whole point of the sealed
  `Principal` hierarchy.
- **Consequence**: US-04-002's AC was rewritten to remove `ClientId` from its scope and
  to point at US-04-001 in a "Note on scope spillover" block. `ClientIdTest` lives in
  the US-04-001 batch.

### `Principal` sealed-type exhaustiveness uses `instanceof`, not `switch`

- **Tag**: `[env-quirk]`
- **Decision**: The `SystemPrincipalTest` exhaustiveness assertion uses an
  `instanceof` chain over the sealed `Principal` type, not the more idiomatic
  pattern-matching `switch`.
- **Why**: Pattern matching for `switch` is a **preview feature** in Java 17
  (JEP 406) and standard only in Java 21. The project compiles with `--release 17` and
  no `--enable-preview`. The `instanceof` chain reaches every permitted variant; if a
  third variant is later added to `permits`, the chain falls through to its
  `UnsupportedOperationException` and fails the test — the desired forcing function.
- **Consequence**: US-04-001 AC includes an explicit note about this; if the project
  ever upgrades to Java 21, refactoring to a `switch` expression is a strict
  improvement.

### `Cursor` and `Page<T>` live under `domain/shared`, not `application/shared`

- **Tag**: `[layering]`, `[scope-spillover]`
- **Decision**: The cursor-pagination value objects (`Cursor`, `Page<T>`) sit in
  `domain/shared/` rather than `application/shared/` as the US-04-005 AC originally
  placed them.
- **Why**: The domain repository port `ApiKeyRepository.listAll(Cursor, int)` returns
  `Page<ApiKey>`. The ArchUnit rule
  `domain_does_not_depend_on_application_or_infrastructure` forbids `domain/**` from
  referencing `application/**`, so the value objects must live where the domain can see
  them. The HTTP-layer plumbing (`CursorCodec`, `PageDto`, `PageSize`) stays under
  `infrastructure/web/pagination` and `application/shared/` respectively.
- **Consequence**: US-04-002's scope-spillover note records that `Cursor` / `Page<T>`
  were shipped early under `domain/shared`. US-04-005's AC was edited to read
  "already exist under `domain/shared`; do not re-create them."

### `ListApiKeysQuery` carries a domain `Cursor`, not the encoded wire string

- **Tag**: `[layering]`
- **Decision**: The query record in `application/apikey/ListApiKeysUseCase` takes
  `Cursor cursor` (the domain value object) rather than `String encodedCursor` (the
  wire form).
- **Why**: A first cut had the service depend on `CursorCodec` to decode the wire
  string. `CursorCodec` lives in `infrastructure/web/pagination`. The application →
  infrastructure ArchUnit rule made that wiring illegal. Moving the decode step into
  the REST adapter (`ApiKeysAdminController` already imports `CursorCodec`) keeps the
  application layer free of any wire-format coupling.
- **Consequence**: The same pattern applies to `ListUsersQuery` (EPIC-05) and
  `ListAgentsQuery` (EPIC-06). All three take a domain `Cursor` and let the REST
  adapter handle the codec.

### `CursorCodec` owns a private `ObjectMapper`, not the Spring-managed one

- **Tag**: `[env-quirk]`, `[architecture]`
- **Decision**: `CursorCodec` instantiates its own `ObjectMapper` (with
  `JavaTimeModule`) at construction rather than injecting the Spring-managed
  `@Bean ObjectMapper`.
- **Why**: A first cut had `@Component public class CursorCodec(ObjectMapper mapper)`.
  The Spring context failed to start: no `ObjectMapper` bean was available in our
  Spring Boot 4.0.6 wiring — Jackson auto-configuration didn't expose one as expected
  in the test context. Owning the codec's mapper internally is also architecturally
  cleaner: the cursor wire format is opaque to clients, and we don't want a future
  Spring/Jackson configuration tweak to silently change cursor encodings.
- **Consequence**: Documented as a design note in `CursorCodec`'s class Javadoc.

### `SecureRandom` determinism test uses `SHA1PRNG`, not default-platform PRNG

- **Tag**: `[env-quirk]`
- **Decision**: `SecureRandomApiKeyGeneratorAdapterTest.cleartext_is_deterministic_…`
  constructs `SecureRandom.getInstance("SHA1PRNG")` and seeds it before the first
  `nextBytes` call.
- **Why**: The first cut used `new SecureRandom(); setSeed(42L)` and expected
  determinism. It failed: the JDK documents `SecureRandom.setSeed(long)` as
  *supplementing* the existing seed, not replacing it, so two default platform
  instances seeded with the same value still emit different bytes. `SHA1PRNG` is the
  JDK-guaranteed algorithm whose `setSeed`-before-first-`nextBytes` path produces a
  deterministic stream.
- **Consequence**: US-04-004 AC records this trade-off explicitly so the next person
  doesn't repeat the mistake.

### Hikari pool size bounded to 2 per Spring test context

- **Tag**: `[env-quirk]`, `[performance]`
- **Decision**: `src/test/resources/application.yaml` sets
  `spring.datasource.hikari.maximum-pool-size: 2`, `minimum-idle: 0`,
  `connection-timeout: 10000`, `idle-timeout: 10000`.
- **Why**: After EPIC-04 introduced multiple `@DynamicPropertySource` /
  `@ActiveProfiles` combinations, the Spring test context cache held a dozen-plus
  contexts simultaneously. With the default pool size of 10, that's 120+ open Postgres
  connections — past the default Postgres `max_connections=100` and into the
  reserved-for-superuser zone, producing cascading `FATAL: remaining connection slots
  are reserved` errors.
- **Consequence**: The full integration test suite (now ~441 tests) runs cleanly
  against a single local Postgres. If a future EPIC pushes context-cache size further,
  consider adding `@DirtiesContext` to the worst offenders rather than shrinking the
  pool more.

---

## EPIC-05 — Admin user management

### `UserNotFoundException` already existed; we unified the message format

- **Tag**: `[scope-spillover]`
- **Decision**: `UserNotFoundException` was shipped during EPIC-03 / US-03-011
  (`ChangeOwnPasswordService`) with a `UUID` constructor and a different message
  format. US-05-001 added a `UserId` overload, kept the `UUID` constructor for
  backwards compatibility, and unified both to the EPIC-05 AC format
  `"User not found: <uuid>"`.
- **Why**: Keeping two different message shapes for the same exception in two
  call sites would be a long-term papercut. The existing `ChangeOwnPasswordServiceTest`
  only asserts `instanceof UserNotFoundException`, so the format change is safe.
- **Consequence**: Documented in US-05-001's scope-spillover note. The unified format
  is what every EPIC-05/06 endpoint surfaces.

### `withDisabled` tests live in `UserTest`, not `UserWithDisabledTest`

- **Tag**: `[scope-spillover]`
- **Decision**: The three `withDisabled` test cases were added to the existing
  `UserTest.java` instead of creating a separate `UserWithDisabledTest` as the AC
  prescribed.
- **Why**: The existing convention groups every `User` aggregate mutation test
  (`withNewPasswordHash`, constructor null checks, `isActive`) in `UserTest`. Splitting
  one mutation into its own file would diverge from that convention without gaining
  clarity — the test names are already `with_disabled_…` for easy IDE outline scanning.
- **Consequence**: A small note in the implementation summary. No follow-up needed.

### `MethodArgumentTypeMismatchException` → 400 handler added in US-05-006

- **Tag**: `[architecture]`
- **Decision**: US-05-006 added a small handler to `GlobalExceptionHandler` mapping
  Spring's `MethodArgumentTypeMismatchException` (raised on malformed path UUIDs) to
  400 `VALIDATION_ERROR` with `errors[].field` set to the parameter name.
- **Why**: The US-05-006 AC explicitly expected 400 for malformed UUIDs, but Spring's
  default fall-through produced 500 from the catch-all `Throwable` handler. Returning
  5xx for client-supplied bad input is a real UX regression, not just a story-AC
  literal mismatch. EPIC-14 (cross-cutting handler consolidation) was the formal home
  for this, but ~15 lines now improves every existing and future path-variable UUID
  endpoint immediately.
- **Consequence**: Used by EPIC-06 (US-06-006 `GET /agents/{agentId}` malformed-UUID
  test) without further work.

### Admin-created users get `mustChangePassword=true`

- **Tag**: `[business]`, `[security/privacy]`
- **Decision**: `CreateUserService` (US-05-004) persists new users with
  `mustChangePassword=true`, forcing them to change the admin-set temporary password
  on first login.
- **Why**: `REQ-USR-007` mandates this only for the seeded admin; the requirement is
  silent on admin-created users. We extended the same pattern as a security
  best-practice default: admins shouldn't be able to set a permanent password on
  another user's behalf without that user's consent. The story carries this rationale.
- **Consequence**: The frontend (when built) will route every freshly-created user
  through the password-change screen on first login, exactly like the seeded admin.

### Self-disable and self-delete are permitted

- **Tag**: `[business]`
- **Decision**: An admin patching their own account to `disabled=true` (US-05-007) or
  deleting their own account (US-05-008) succeeds. Both behaviors are documented in
  integration tests.
- **Why**: The design is silent on whether self-disable/-delete should be forbidden.
  Rather than adding a hidden invariant, we documented the current behavior explicitly.
  The downstream effect of self-disable surfaces naturally — `LoginService` rejects
  disabled accounts with `INVALID_CREDENTIALS`, so a self-disabled admin loses access
  on next login.
- **Consequence**: A future EPIC can revisit this if product requires "the last admin
  cannot disable themselves" semantics. The integration tests will fail loudly if the
  behavior is intentionally changed.

---

## EPIC-06 — Agent CRUD

### `Team` value object enforces local invariants only

- **Tag**: `[layering]`, `[architecture]`
- **Decision**: `domain/agent/Team.java` enforces only dedup + no-null-entries. The
  cross-owner (REQ-AGT-012), single-level (REQ-AGT-013), and no-self-reference rules
  live in `CreateAgentService` / `UpdateAgentService`.
- **Why**: The non-local rules need repository access (`findOwnerOf`,
  `hasNonEmptyTeam`). The domain layer must not depend on the repository ports'
  implementations — a value object that does I/O is a smell. The split mirrors design
  §4.2 where "team members share owner" is documented as an application-layer concern
  while "memory size ∈ [1, 36]" stays in the domain value object.
- **Consequence**: Three application-layer exceptions
  (`CrossOwnerTeamMemberException`, `NestedTeamForbiddenException`, the
  self-reference case via `NestedTeamForbiddenException.selfReference(...)`) cover
  every non-local rule. Their `@ExceptionHandler` mapping (US-06-003) produces the
  specific 409 codes documented in `openapi.yaml`.

### Cross-owner GET / PUT / DELETE returns 404, not 403

- **Tag**: `[security/privacy]`
- **Decision**: When user A operates on an `agentId` that belongs to user B, the
  service raises `AgentNotFoundException` (HTTP 404), not a `ForbiddenException` (HTTP
  403).
- **Why**: A 403 response would confirm to user A that the id exists for some other
  owner — that's a small but real existence leak. The 404 response is byte-equivalent
  to "no such id" (truly unknown), so an attacker cannot enumerate other users' agent
  IDs by probing.
- **Consequence**: Documented in design §8.6 and the EPIC-06 stories. The integration
  tests (`GetAgentEndpointIntegrationTest`, `UpdateAgentEndpointIntegrationTest`,
  `DeleteAgentEndpointIntegrationTest`) explicitly assert byte-equivalence between the
  truly-not-found and cross-owner cases.

### Tool / MCP reference validation is **deferred** but the seam ships in EPIC-06

- **Tag**: `[scope-spillover]`, `[architecture]`
- **Decision**: `application/agent/ToolReferenceValidator` and `McpReferenceValidator`
  were defined in EPIC-06 with `Noop*ReferenceValidator` Spring `@Component` stubs
  under `infrastructure/agent/validation/`. EPIC-06's agent write path consumes the
  ports through dependency injection.
- **Why**: `EPICS.md` lists EPIC-07 / EPIC-08 as dependencies of EPIC-06, but the
  build-order diagram (and pragmatism) has EPIC-06 ship first with the validation
  hook in place. EPIC-07 / EPIC-08 then drop catalog-backed implementations into the
  same ports without touching EPIC-06 code.
- **Consequence**: EPIC-07 (US-07-005) deleted `NoopToolReferenceValidator` and shipped
  `CatalogToolReferenceValidator`. EPIC-08 will do the same for the MCP validator
  without any EPIC-06 changes.

### `AgentRepositoryAdapter` uses `getReferenceById` for the owner FK

- **Tag**: `[performance]`, `[architecture]`
- **Decision**: When saving an `Agent`, the adapter resolves the `UserJpa` reference
  via `userJpaRepository.getReferenceById(ownerId)` rather than `findById(...).get()`.
- **Why**: The EPIC-02 `AgentJpa` entity uses `@ManyToOne(fetch = LAZY) UserJpa owner`
  rather than a raw `UUID ownerId` column. `findById` would issue a SELECT just to
  populate the association. `getReferenceById` returns a Hibernate proxy bound only by
  primary key — no DB round trip — and the foreign-key write still succeeds on flush.
- **Consequence**: One fewer SELECT per agent save. The EPIC-02 cascade contract test
  remains valid because the JPA-association model is preserved.

---

## EPIC-07 — Tools catalog

### `@ToolGroup` class-level annotation, decoupled from Spring AI's `@Tool`

- **Tag**: `[architecture]`
- **Decision**: Catalog discovery is driven by a project-defined `@ToolGroup` class-level
  annotation in `domain/tool/`, not by scanning for Spring AI's `@Tool`
  method-level annotation.
- **Why**: Design §13 calls for both ("beans with `@Tool` methods") but the source of
  truth for catalog identity (name, description) is naturally a class-level concern.
  `@ToolGroup` makes that contract explicit and decouples the catalog from Spring AI
  version churn. `@Tool` annotations remain on methods for the runtime tool execution
  EPIC-11 will wire up — the two concerns are orthogonal.
- **Consequence**: `AwsS3Tool` carries both annotations (`@ToolGroup` for catalog,
  `@Tool` on each operation method). The synthetic `TestToolFixture` carries only
  `@ToolGroup`, proving the catalog isn't coupled to `@Tool`.

### AWS SDK pinned to `2.30.0`, not via the AWS BOM

- **Tag**: `[performance]`
- **Decision**: `pom.xml` adds `software.amazon.awssdk:s3` alone with an explicit
  `aws-sdk.version=2.30.0` property. The `aws-sdk-bom` is **not** imported.
- **Why**: The BOM pulls dozens of transitive modules into the resolved dependency tree
  even when only the S3 client is used. Keeping the fat JAR lean matters for the
  EC2-via-`scp` deployment path (`REQ-DEP-002`).
- **Consequence**: If a future EPIC pulls in a second AWS service, switching to the
  BOM at that point is the right move — single-module version drift becomes hard to
  manage past two services.

### `AwsS3Tool.readPdfFileFromS3` ships as `UnsupportedOperationException`

- **Tag**: `[scope-spillover]`, `[performance]`
- **Decision**: One of the six S3 tool methods (`readPdfFileFromS3`) throws
  `UnsupportedOperationException` instead of doing real PDF extraction.
- **Why**: The reference example called into an undeclared `S3.readPdfFileFromS3`
  helper that relied on a PDF library not yet on the classpath. Pulling Apache PDFBox
  (~5 MB) just to keep one method working — for a feature EPIC-11 doesn't drive yet —
  felt like the wrong v1 trade-off. The catalog entry surfaces correctly with the
  other five working operations; PDF can be wired in a focused follow-up.
- **Consequence**: A natural follow-up story: "Wire Apache PDFBox into
  `AwsS3Tool.readPdfFileFromS3`." Until then, agents that the LLM routes to the PDF
  operation will see a `502 LLM_UNAVAILABLE` (or whatever EPIC-11 maps the exception
  to) at chat time — not at agent-write time.

---

## EPIC-08 — MCP servers integration

### `McpServerCatalogAdapter` injects `ObjectProvider<McpStdioClientProperties>`, not the bean directly

- **Tag**: `[env-quirk]`, `[architecture]`
- **Decision**: The adapter takes `ObjectProvider<McpStdioClientProperties>` in
  its constructor and resolves the bean via `getIfAvailable()` at
  `@PostConstruct` time. If the bean is absent, the catalog is empty (no
  startup failure).
- **Why**: The test profile excludes Spring AI's MCP autoconfig
  (`StdioTransportAutoConfiguration`) so test contexts never spawn `npx`
  subprocesses. That exclusion also unbinds `McpStdioClientProperties`, so a
  hard `@Autowired` dependency would fail every `@SpringBootTest` in the
  codebase. `ObjectProvider` lets the adapter degrade gracefully: empty
  catalog in tests, full catalog in production where the autoconfig is loaded.
- **Consequence**: The `McpServerCatalogAdapterIntegrationTest` supplies its
  own `@Primary McpStdioClientProperties` via `@TestConfiguration` to exercise
  the catalog wiring end-to-end without touching the autoconfig.

### `MCP_FS_BASE` defaults to a relative path

- **Tag**: `[env-quirk]`
- **Decision**: `app.mcp.filesystem.base` defaults to
  `${MCP_FS_BASE:./var/lib/multi-agent/fs}` — a **relative** path — in both
  the production and test `application.yaml`.
- **Why**: Design §15 suggests `/var/lib/multi-agent/fs` for production EC2.
  But on the Windows laptop described in `backend/docs/SPECS.md` (no admin
  rights), writing to an absolute Unix-style path either fails at mount time
  or silently creates a directory at `C:\var\lib\multi-agent\fs`. A relative
  default makes `mvn test` and `mvn spring-boot:run` work on a stock
  developer machine; production deployments override via `MCP_FS_BASE` env
  var.
- **Consequence**: Documented inline in `application.yaml`. The
  `FilesystemMcpUserScopeAdapter` (US-08-004) normalizes the base path to
  absolute via `toAbsolutePath().normalize()` so downstream MCP code always
  sees an unambiguous path regardless of the default.

### Existing agent integration tests now seed an in-test MCP catalog via `@TestConfiguration`

- **Tag**: `[env-quirk]`, `[scope-spillover]`
- **Decision**: `CreateAgentEndpointIntegrationTest` and
  `UpdateAgentEndpointIntegrationTest` ship a nested `@TestConfiguration` that
  exposes a `@Primary McpStdioClientProperties` bean seeded with the
  `brave-search` and `filesystem` connections. The same pattern is used by
  `McpServerCatalogAdapterIntegrationTest` and `McpServersEndpointIntegrationTest`.
- **Why**: With `NoopMcpReferenceValidator` deleted in US-08-006, the existing
  happy-path agent tests (which use `"enabledMcpServers":["brave-search"]`)
  started failing in the test profile — the test profile excludes the Spring AI
  MCP autoconfig, so the catalog adapter returns empty and the new validator
  rejects every name. Adding the test-config bean keeps the happy paths working
  AND lets the new "valid name accepted / unknown name rejected" cases live
  next to the existing assertions.
- **Consequence**: Any future EPIC integration test that exercises an agent
  write path with non-empty `enabledMcpServers` must follow the same pattern.
  EPIC-11 (chat-turn runtime) will need its own variant that wires real
  Spring AI MCP plumbing — at that point the `@TestConfiguration` here can
  evolve into a shared `@TestConfiguration` under `src/test/java/.../mcp/`.

### `FilesystemMcpUserScopeAdapter` containment check uses `Path.startsWith`, message never leaks the offending path

- **Tag**: `[security/privacy]`
- **Decision**: The adapter normalizes both the configured base and the
  resolved target via `toAbsolutePath().normalize()`, then asserts
  `target.startsWith(base)`. If the assertion fails it throws
  `McpServerException("resolved per-user MCP root escapes the configured base")`
  — explicitly without the offending path in the message. The `IOException`
  wrapping branch is symmetric: it uses
  `"failed to create per-user MCP filesystem root"` and never embeds the
  `UserId`.
- **Why**: `UserId` wraps a UUID so traversal isn't reachable from the public
  API today. The check is defense-in-depth: if a future caller bypasses the
  domain value object (or if Java path semantics on Windows surprise us with
  drive-relative resolution), the adapter is the last line of defense.
  Excluding user-controlled fragments from the message satisfies REQ-SEC-004
  even though the cause chain (logged at WARN by `GlobalExceptionHandler`)
  still gives operators the IOException details for diagnosis.
- **Consequence**: `FilesystemMcpUserScopeAdapterTest`'s IOException-wrapping
  case explicitly asserts the UUID never appears in the exception message;
  a future change that helpfully includes the path will fail loudly.

### Test-profile autoconfig exclusion list uses Spring AI 1.1.0's new package names

- **Tag**: `[env-quirk]`
- **Decision**: The `spring.autoconfigure.exclude` list in
  `src/test/resources/application.yaml` was corrected from the old 1.0.3 path
  (`org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration`)
  to the new 1.1.0 path
  (`org.springframework.ai.mcp.client.common.autoconfigure.{StdioTransportAutoConfiguration,
  McpClientAutoConfiguration, McpToolCallbackAutoConfiguration}`).
- **Why**: The pre-EPIC-08 exclusion was silently no-oping under Spring AI
  1.1.0 because the autoconfigs had moved package. Tests had been booting
  with the MCP autoconfig loaded; we got away with it only because the test
  yaml declares no MCP connections (so the autoconfig's transport list was
  empty and no `npx` subprocess was spawned). Now that EPIC-08 introduces
  the catalog adapter, we want the exclusion to be **actually effective**
  so the adapter's "no autoconfig → empty catalog" code path is exercised
  by every `@SpringBootTest` that doesn't opt in to a test-config bean.
- **Consequence**: The contract is now explicit: tests never load MCP
  autoconfig; the adapter handles this case. Future EPICs (notably
  EPIC-11's chat-turn runtime) that need real MCP wiring must opt in via
  `@TestConfiguration` or by overriding `spring.autoconfigure.exclude` in
  the specific test class.

---

## EPIC-09 — LLM provider integration (OpenAI)

### WireMock-based integration tests replaced by Mockito-based adapter tests

- **Tag**: `[env-quirk]`
- **Decision**: US-09-004's `OpenAiChatClientAdapterCallTest` and US-09-005's
  `OpenAiChatClientAdapterStreamTest` were specified as WireMock-based
  integration tests (`@SpringBootTest`, `@AutoConfigureWireMock`,
  `spring.ai.openai.base-url` pointed at a fake server). We ship them as
  Mockito-based unit tests instead: each stubs Spring AI's `ChatModel`
  interface and asserts on the captured `Prompt` argument plus the
  `Flux<ChatChunk>` / `ChatResult` returned. No WireMock dependency was
  added.
- **Why**: Spring AI 1.1.0 was compiled against Spring Framework 6.x and
  calls `HttpHeaders.addAll(MultiValueMap)`, which now returns
  `HttpHeaders` in Spring Framework 7. Instantiating `OpenAiApi`
  (the bean `OpenAiChatAutoConfiguration` constructs to back
  `OpenAiChatModel`) throws `NoSuchMethodError` at bean creation —
  documented in the test yaml's autoconfig-exclusion comment. With the
  autoconfig disabled there is no real `OpenAiChatModel` for WireMock to
  serve, and re-enabling it crashes the context. Mockito-stubbing
  `ChatModel` tests strictly more: it asserts on the `Prompt` translation
  (the load-bearing contract of `OpenAiChatOptionsTranslator`) which a
  WireMock body-matcher would only assert indirectly through Spring AI's
  JSON serialization. The error classification is exercised by feeding
  `HttpClientErrorException`, `HttpServerErrorException`, and
  `ResourceAccessException` directly to the mock.
- **Consequence**: `WireMock` is NOT a dependency of the project.
  `reactor-test` is added (test scope) so the streaming tests use
  `StepVerifier`. When Spring AI ships a 4.0-compatible release and the
  autoconfig binary incompat is gone, the EPIC-09 tests can be promoted to
  full WireMock-based integration tests without changing the adapter's
  public surface — the Mockito tests already pin every behavior the
  WireMock tests would assert. The corresponding language was added to the
  test JavaDoc of both adapter test classes so future readers see the
  rationale inline.

### `@ConditionalOnBean(ChatModel.class)` on `OpenAiChatClientAdapter`

- **Tag**: `[env-quirk]`
- **Decision**: `OpenAiChatClientAdapter` is annotated
  `@ConditionalOnBean(ChatModel.class)` so it loads only when a Spring AI
  `ChatModel` bean is present in the context.
- **Why**: The test profile excludes `OpenAiChatAutoConfiguration` (binary
  incompat — see previous entry), so no `ChatModel` bean exists in any
  `@SpringBootTest`. Without the conditional, every `@SpringBootTest` in
  the suite fails refresh with `NoSuchBeanDefinitionException` for the
  adapter's constructor parameter — including unrelated tests like
  `ApplicationPropertiesTest`. The conditional makes the adapter
  invisible in the test profile and present in production (where the
  autoconfig provides `OpenAiChatModel`).
- **Consequence**: Tests that need to exercise the adapter as a bean
  (none in v1; this lands with EPIC-11) must provide a `ChatModel` bean
  via `@TestConfiguration`. EPIC-09's own adapter tests construct the
  adapter directly with `new OpenAiChatClientAdapter(mockChatModel)` and
  do not need Spring at all.

### `OpenAiConfig` injects only `Environment`, not `ApplicationProperties`

- **Tag**: `[scope-spillover]`
- **Decision**: The `OpenAiConfig` `@Configuration` class injects only
  `Environment` for the `OPENAI_API_KEY` fail-fast check. The US-09-002
  AC suggested injecting `ApplicationProperties` too "as a natural home
  for future @Bean methods"; we declined.
- **Why**: Injecting an unused dependency adds noise without buying
  anything — the project convention is to inject only what's needed
  ("no premature abstraction"). Adding `ApplicationProperties` when a
  future `@Bean` method needs it is a one-line change.
- **Consequence**: `OpenAiConfig` reads
  `spring.ai.openai.api-key` directly via the `Environment`.

---

## EPIC-10 — Conversations & messages

### Two-column XOR for conversation ownership, not a discriminator or a seeded "system" user

- **Tag**: `[business]`, `[layering]`
- **Decision**: `V005__conversation_owner_split.sql` drops the single
  `conversations.owner_id uuid not null references users(id)` column and
  replaces it with two **mutually-exclusive nullable** columns:
  `owner_user_id` (FK → `users(id)`) and `owner_client_id`
  (FK → `api_keys(client_id)`). A `check (owner_user_id is not null xor
  owner_client_id is not null)` constraint enforces the invariant. On the
  domain side, ownership is modeled as the sealed `ConversationOwner`
  type with two members `UserOwner(UserId)` and `SystemOwner(ClientId)`
  (US-10-001), mirroring the existing `Principal` sealed hierarchy
  (`UserPrincipal | SystemPrincipal`).
- **Why**: The openapi contract lists both `BearerAuth` and `ApiKeyAuth`
  on every `/conversations/**` endpoint (REQ-AUTH-007 + design §8.6), so
  SYSTEM principals must be able to own conversations alongside
  JWT-authenticated end users. Three approaches were considered:
  1. **Two-column XOR (chosen)** — two independent FK columns. PostgreSQL
     cascades-delete from `users` (REQ-USR-006) and from `api_keys`
     independently, no application-side cascade logic needed. The sealed
     `ConversationOwner` maps to the XOR by construction (exhaustive
     switch in the JPA mapper).
  2. **`owner_id` + `owner_kind` discriminator string** — single nullable
     FK plus a `varchar` discriminator. Cheaper schema diff, but the
     adapter would need string-equality on every read and the foreign-key
     integrity would have to be enforced in application code (PostgreSQL
     cannot conditional-FK a column).
  3. **Seeded "system" user row** — insert a placeholder user at
     bootstrap and route SYSTEM principals through its `UserId`. Cheapest
     in raw SQL, but it pollutes `GET /admin/users`, conflicts with
     `users.email` uniqueness (no plausible canonical address), and
     silently re-interprets REQ-USR-002. Rejected.
- **Consequence**:
  - `ConversationJpa` carries `@ManyToOne UserJpa ownerUser` and
    `@ManyToOne ApiKeyJpa ownerApiKey` as nullable associations (US-10-003).
  - `ConversationJpaMapper.toDomain` exhaustively asserts exactly one
    owner column is non-null and throws
    `DatabaseAccessException("Inconsistent conversation row …")` on
    violation — defense-in-depth on top of the DB check constraint.
  - The migration is forward-prepared for a future hard-delete on API
    keys (current `PATCH /admin/api-keys/{clientId}` is soft-only,
    US-04-008). The `on delete cascade` on `owner_client_id` is not
    exercised in v1 but is correct by the time it matters.
  - In v1, `POST /conversations` from a SYSTEM caller will deterministically
    `404 NOT_FOUND` because no agent is SYSTEM-owned (agents.owner_id
    still references users(id) only). The schema split is what makes a
    future "SYSTEM-owned agents" EPIC possible without another
    `conversations` migration.

---

## EPIC-12 — Agent team delegation

### TBD-3 resolution — delegation is a Spring AI `@Tool`, not a server-side post-step

- **Tag**: `[business]`, `[layering]`
- **Decision**: `delegate(...)` is exposed to the LLM as a Spring AI
  `@Tool`-annotated method on `DelegateTool` (US-12-003), discovered by the
  same `ToolCallbackResolver` that already plumbs `AwsS3Tool` through the
  OpenAI adapter. The alternative considered in design §19 — a server-side
  post-step inspecting the LLM output for a `delegate:<id>:<task>` marker —
  was rejected.
- **Why**:
  1. **Tool-calling already works.** EPIC-09's adapter forwards
     `ChatRequest.tools` to Spring AI's `ChatClient` tool-callback resolver
     unchanged; adding one more `@Component @Tool` bean is a single
     registration plus a filter in `ChatRequestBuilder`. The post-step
     alternative would have required a custom marker parser embedded in
     the streaming Flux — a second test surface for behavior the LLM
     already does natively.
  2. **Composes with streaming.** Spring AI interleaves tool calls and
     content deltas seamlessly: the parent's stream pauses while the tool
     runs, the sub-agent's reply flows back into the model as a tool
     result, the parent resumes generating tokens. The end-user sees only
     the parent's aggregated stream (REQ-AGT-015) by construction; no
     marker rewriting is required.
  3. **Aligns with REQ-CHAT-012 by construction.** Tool-call requests and
     tool-call results are transient artifacts of an LLM turn and are
     NEVER persisted as messages. The tool path naturally inherits this
     invariant — the tool's input/output never reaches the persistence
     layer because they live inside Spring AI's tool-callback loop, not
     inside `SendMessageService`'s persist step.
  4. **Smaller test surface.** The unit tests can mock
     `LlmChatClient.call(...)` for the sub-agent path (US-12-002) and
     `DelegationService.delegate(...)` for the tool callback path
     (US-12-003); the end-to-end test (US-12-004) layers WireMock atop
     both. The marker-parser variant would have demanded a fourth test
     surface for the parser itself.
- **Consequence**:
  - `DelegateTool` (US-12-003) is the single public entry point from the
    LLM to `DelegationService`. EPIC-11's `SendMessageService` is
    unchanged except for populating the request-scoped
    `ChatTurnContext` so the tool callback can resolve "which turn am I
    running in".
  - The `delegate` tool descriptor is appended to `ChatRequest.tools`
    iff the parent agent's team is non-empty. Leaf agents (empty team)
    never see the descriptor — the LLM literally cannot call
    `delegate(...)` for them. This is the runtime guarantee on top of
    EPIC-06's static REQ-AGT-013 single-level rule.

### `DelegationServiceImpl` has zero conversation-persistence dependencies — enforced structurally

- **Tag**: `[layering]`, `[security/privacy]`
- **Decision**: `DelegationServiceImpl` is constructor-injected with
  `AgentRepository`, `ToolCatalog`, `McpServerCatalog`,
  `FilesystemMcpUserScope`, `LlmChatClient`, and a `@Value`-bound default
  model. It is NOT injected with `ConversationRepository` — even though
  injecting it would superficially "work" (the implementation would never
  call any method). A new ArchUnit rule
  `delegation_service_impl_does_not_depend_on_conversation_repository` in
  `LayeringArchTest` forbids the dependency at build time. A reflection
  check in `DelegationServiceImplTest` mirrors the rule as a unit-test
  assertion so a regression fails fast in both surfaces.
- **Why**: REQ-AGT-015 mandates that the sub-agent's exchanges with the
  LLM be NOT persisted — neither in the parent's conversation, nor in a
  separate sub-agent-owned conversation, nor in long-lived memory.
  Discipline is fragile; the structural guarantee that the class CANNOT
  reach the persistence port is the load-bearing piece. A future
  contributor wanting to "log the delegation for audit" would have to
  add the dependency, which trips both the ArchUnit rule and the
  reflection test.
- **Consequence**:
  - The `LayeringArchTest` import set now references
    `DelegationServiceImpl` and `ConversationRepository` directly — the
    same pattern already used for the `Clock.systemUTC` ban on security
    adapters.
  - If a future audit-logging requirement is introduced (REQ-OBS is
    currently `SHOULD`, no audit clause), the trail will be a separate
    operator-only log stream, NOT a write into the `messages` table.

---

## EPIC-13 — Rate limiting (Bucket4j)

### Bucket rebuild on admin update discards remaining tokens

- **Tag**: `[business]`
- **Decision**: When an admin saves a new `RateLimitConfig`,
  `Bucket4jRateLimitGate.onRateLimitConfigChanged(...)` builds a **fresh** bucket
  (full to capacity) and atomically swaps the `volatile` reference. The
  previously-consumed tokens are NOT carried across — the new bucket starts at
  `perMinute` / `perHour` full.
- **Why**: Bucket4j's `replaceConfiguration` API exists but adds complexity
  (token-count scaling under three different `TokensInheritanceStrategy` modes,
  each with subtle edge cases). For the v1 sizing (REQ-NFR-005: 64 concurrent
  users, 16 SSE streams) the worst case is a one-shot grace allotment of up to
  `perMinute` requests immediately after an admin update — harmless.
- **Consequence**: Admins who tighten the limit see the new limit take effect
  on the next request after the bucket is refilled to the new (smaller)
  capacity. Admins who loosen the limit get the new ceiling immediately. The
  trade-off is documented in `Bucket4jRateLimitGate`'s Javadoc and asserted by
  the integration test in US-13-007.

### Listener exceptions during update are logged and swallowed, not propagated

- **Tag**: `[business]`
- **Decision**: `UpdateRateLimitConfigService` calls `repository.save(...)`
  inside `@Transactional`, then iterates `RateLimitConfigChangeListener`
  beans. If a listener throws, the service logs at WARN and continues — the
  row is already committed.
- **Why**: Rolling back the save because the in-JVM bucket failed to rebuild
  would surface as a 500 to the admin while leaving the persisted config out
  of step with the live cache. The persisted row is the source of truth; the
  cache will catch up on the next admin update or process restart.
- **Consequence**: A listener bug cannot wedge the admin endpoint. The
  unit test `UpdateRateLimitConfigServiceTest.listener_exception_is_logged_at_warn_and_does_not_propagate`
  pins this behavior with a Logback `ListAppender`.

### Filter-thrown `RateLimitedException` reaches `GlobalExceptionHandler` via the standard `HandlerExceptionResolver` bridge

- **Tag**: `[layering]`
- **Decision**: `RateLimitFilter` does NOT write to the response itself. On a
  denied request it calls
  `handlerExceptionResolver.resolveException(req, res, null, new RateLimitedException(...))`,
  letting the `@RestControllerAdvice` write the 429 + `Retry-After` + ProblemDetails
  body — exactly the same response surface the rest of the API uses.
- **Why**: Option B (the filter writes the body directly) would duplicate the
  RFC 7807 formatting code that already lives in `GlobalExceptionHandler` and
  would risk drift from the openapi `RateLimited` example. The
  `HandlerExceptionResolver` pattern is already used by
  `JwtAuthenticationFilter` (US-03-007) and `ApiKeyAuthenticationFilter`
  (US-04-009), so EPIC-13 reuses the existing seam instead of inventing a new
  one.
- **Consequence**: A single code path produces the 429 envelope for every
  caller — filter-thrown, `@RestController`-thrown, or
  `@ExceptionHandler`-direct. The integration test in US-13-007 asserts the
  envelope byte-shape against the openapi `RateLimited` example.

### `/actuator/**` is skipped via `shouldNotFilter`, not via an `authorizeHttpRequests` permit

- **Tag**: `[business]`
- **Decision**: `RateLimitFilter.shouldNotFilter` returns `true` for any
  request whose servlet path starts with `/actuator`. The rate-limit gate is
  never consulted for those paths.
- **Why**: The actuator probe is operator-oriented and runs on a fast
  cadence; counting it against the global bucket would let monitoring traffic
  starve real users at the v1 sizing. `REQ-OBS-003` makes the health probe
  load-bearing for operators, so excluding it at the filter level is the
  right scope.
- **Consequence**: Even with the bucket fully exhausted, `GET
  /actuator/health` still returns 200 — pinned by US-13-007's "actuator
  excluded" scenario.

---

## EPIC-16 — Build, packaging & AWS deployment

### Spring AI bumped from 1.1.0 → 2.0.0-M4 to clear a Spring 7 binary-incompat

- **Tag**: `[env-quirk]`, `[layering]`
- **Decision**: `<spring-ai.version>` in `pom.xml` moved from `1.1.0` (the
  version originally chosen for the Spring AI BOM) to `2.0.0-M4`. We also
  added an explicit `jackson-datatype-jsr310` dependency — Spring AI 1.x
  pulled it in transitively; 2.0.x dropped that transitive edge, and
  `CursorCodec` uses `JavaTimeModule` for `OffsetDateTime` keyset cursors.
- **Why**: First production deployment to AWS EC2 surfaced a runtime
  `NoSuchMethodError` at context startup: every `OpenAi*Api` constructor
  in Spring AI 1.x calls `HttpHeaders.addAll(MultiValueMap)`. That method
  returned `void` in Spring Framework 6 (which Spring AI 1.x was compiled
  against) but returns `HttpHeaders` in Spring Framework 7 (which Spring
  Boot 4.0.6 transitively brings in). The change is binary-incompatible
  even though it's source-compatible: the descriptor `(Lorg/springframework/util/MultiValueMap;)V`
  no longer matches any method on Spring 7's `HttpHeaders`.
  - Three resolutions were considered. (a) Downgrade Spring Boot to 3.x:
    rejected because the spec pins Spring Boot 4 (`REQ-ARC-001`) and
    rolling back Spring 7 would invalidate the entire test suite's
    assumptions about Tomcat 11 / Hibernate 7 behavior. (b) Hand-build a
    `ChatModel` bean to bypass the broken autoconfig path: rejected as
    too much surface area for a Spring AI patch we don't own, and would
    have to be revisited the moment Spring AI ships a fix. (c) Bump
    Spring AI to a Spring-7-compiled line: chosen — `2.0.0-M4` is the
    first milestone whose `OpenAiApi` / `OpenAiAudioApi` use the
    `addAll(HttpHeaders)` overload instead. Verified by class-file
    inspection (`org.springframework.ai.openai.api.OpenAiApi.class` in
    the 2.0.0-M4 jar contains descriptor
    `addAll (Lorg/springframework/http/HttpHeaders;)V` instead of the
    1.x `(Lorg/springframework/util/MultiValueMap;)V`).
- **Consequence**:
  - Full backend test suite stays green on the bump (817 tests, 0
    failures, 0 errors). No source-level break from Spring AI 2.0's
    package re-organization hit us — every API we consume
    (`ChatModel`, `ChatResponse`, `AssistantMessage`, `Prompt`,
    `ChatOptions`, `@Tool`, `@ToolParam`) is unchanged.
  - The five OpenAI sub-model autoconfigs we don't use
    (`OpenAiAudioSpeech`, `OpenAiAudioTranscription`, `OpenAiEmbedding`,
    `OpenAiImage`, `OpenAiModeration`) are now explicitly excluded in
    `src/main/resources/application.yaml` mirroring the test-profile
    exclusion. Defensible regardless of version: smaller bean graph, no
    eager validation of API keys we don't have.
  - `2.0.0-M4` is a **milestone** release on a major-version line; it
    can be re-cut or withdrawn. A follow-up ticket (fits naturally in
    EPIC-16 scope) tracks pinning to a GA `2.0.x` once it ships. Until
    then, the JAR running on EC2 depends on a non-GA artifact — a known
    operational caveat documented here and in `application.yaml`.
  - The test suite previously never caught this bug because every test
    that touches an LLM uses `@MockitoBean LlmChatClient` (US-11-007,
    US-12-004) — the real Spring AI autoconfig never loaded during a
    test run, so the `NoSuchMethodError` only surfaced at production
    startup. EPIC-16 should consider adding a `@SpringBootTest` against
    the **default** (non-`dev`) profile to catch autoconfig-time
    failures earlier, before the JAR reaches EC2.

---

## How to add an entry

When a future story takes an implementation decision worth recording (anything where
the next person might reasonably ask "why is it like this?"), append an entry under
the relevant EPIC section with:

1. A short title (sentence case, ≤ 80 chars).
2. **Tag** — one or more of the documented tags above.
3. **Decision** — the chosen behavior in one or two sentences.
4. **Why** — the rationale: what alternative was considered, what forced the choice.
5. **Consequence** — what this entry buys us downstream (cross-EPIC impact,
   follow-up story it implies, file/test that codifies it).
