# EPIC-08-US.md — User stories for EPIC-08

EPIC-08 — **MCP servers integration**

This file lists the user stories that deliver EPIC-08. The EPIC configures the two
preconfigured MCP servers (`brave-search` and `filesystem`), exposes them via
`GET /mcp-servers` (REQ-MCP-006), enforces per-user scoping of the `filesystem`
server's root directory (REQ-MCP-005), plugs the catalog-backed reference validator
into the agent write path — completing the seam EPIC-06 left open via
`NoopMcpReferenceValidator` — and adds the `MCP_SERVER_ERROR` 502 mapping for MCP
runtime failures (design §9, §14).

> **Scope split with EPIC-06 / EPIC-07 / EPIC-11.**
> - The `McpReferenceValidator` application-layer port (declared in
>   `application/agent/`) was shipped by EPIC-06 with the `NoopMcpReferenceValidator`
>   stub. US-08-006 deletes the stub and replaces it with a catalog-backed
>   implementation. EPIC-06's agent write path (US-06-004 / US-06-007) does not
>   change.
> - The tool catalog, `GET /tools`, and the corresponding reference validator are
>   owned by EPIC-07; this EPIC mirrors that structure for MCP but does not touch
>   tool-side code.
> - Per-agent MCP wiring during a chat turn (`agent.enabledMcpServers` filtered
>   against the catalog and attached to the LLM `ChatRequest`) is owned by EPIC-11.
>   This EPIC stops at the catalog, the per-user filesystem-root resolution, and the
>   write-time validation; runtime invocation of MCP tools from a chat turn is out
>   of scope.
> - **TBD-2** (per-user filesystem MCP wiring): design §14 leaves two viable
>   options under Spring AI 1.1.0 — a per-user MCP process or a shared process with
>   path-argument rewriting. EPIC-08 ships the user-scoped *root resolution*
>   (`FilesystemMcpUserScopeAdapter`) and configures the **shared** filesystem MCP
>   connection at the configured base path; EPIC-11 chooses and wires whichever
>   variant of TBD-2 plays best with Spring AI's runtime once the chat turn exists
>   to exercise it. This story split lets EPIC-08 ship the catalog and the
>   per-user-root primitive without prejudging TBD-2.
> - The v1 MCP catalog contains exactly two entries: `brave-search` and
>   `filesystem`. Both names come from `application.yaml`; they are declared by
>   configuration, not by code (REQ-MCP-001).

## Conventions

- **ID format**: `US-08-<nnn>` — `08` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description,
  a bullet list of testable acceptance criteria, the requirements coverage, the
  design references, and its dependencies.

## Story list

| ID         | Title                                                                            | Priority | Status | Depends on                |
|------------|----------------------------------------------------------------------------------|----------|--------|---------------------------|
| US-08-001  | `McpServerName` + `UnknownMcpServerException` + `McpServerCatalog` port + `ListMcpServersUseCase` | MUST | Done | EPIC-01                   |
| US-08-002  | `application.yaml` MCP configuration + `app.mcp.filesystem.base` property binding | MUST    | Done   | US-08-001                 |
| US-08-003  | `McpServerCatalogAdapter` — Spring AI configuration discovery with startup caching | MUST    | Done   | US-08-001, US-08-002      |
| US-08-004  | `FilesystemMcpUserScope` port + `FilesystemMcpUserScopeAdapter` (per-user root on-demand) | MUST | Done   | US-08-002                 |
| US-08-005  | `McpServersController` & `GET /mcp-servers` REST adapter                          | MUST    | Done   | US-08-001                 |
| US-08-006  | `CatalogMcpReferenceValidator` replaces EPIC-06 `NoopMcpReferenceValidator`       | MUST    | Done   | US-08-003, US-06-004      |
| US-08-007  | `McpServerException` + `MCP_SERVER_ERROR` 502 mapping in `GlobalExceptionHandler` | MUST    | Done   | US-03-001                 |

---

## US-08-001 — `McpServerName` + `UnknownMcpServerException` + `McpServerCatalog` port + `ListMcpServersUseCase`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the domain primitives that describe an entry in the MCP-server catalog,
plus the small application use case that surfaces the catalog to the REST adapter
**So that** EPIC-08 and every downstream EPIC reads the catalog through a
Spring-free contract that mirrors EPIC-07's `ToolCatalog` surface, keeping the two
catalogs symmetrical at the domain / application boundary.

### Description

The MCP catalog is read-only, small (two entries in v1), and populated entirely from
`application.yaml` (REQ-MCP-001). The domain side is a thin value object plus the
already-stubbed `UnknownMcpServerException`; the application side is the catalog
port plus a thin forwarder use case. No pagination — the entire catalog fits in a
single response (design §6.2.6).

The `McpServerDescriptor` record is **not** part of the domain (the catalog has no
behavior worth modelling there): it lives in `application/mcp/` as the wire shape
returned by the port. This mirrors `domain/tool/ToolDescriptor` from EPIC-07 except
that the descriptor here doubles as the application's value carrier — there is no
separate domain entity to back it.

The `domain/mcp/package-info.java` stub committed in EPIC-01 promised `McpServerName`
and `UnknownMcpServerException`; this story honors that promise.

### Acceptance criteria

- `domain/mcp/McpServerName.java` — record `McpServerName(String value)`:
  - non-null, non-blank `value`; `value.length() <= 64` (matches the openapi cap
    and the `agent_mcp.mcp_server_name varchar(64)` column);
  - canonicalization: the constructor stores the value **as-is** (case-sensitive
    — Spring AI's MCP `connections.<name>` keys are matched verbatim);
  - violations throw `ValidationException` with field `enabledMcpServers`
    (the only context this VO is constructed from at the boundary);
  - pure-Java, no Spring / JPA imports.
- `domain/mcp/UnknownMcpServerException.java` — extends `BusinessException`
  (sibling of `domain/tool/UnknownToolException.java` shipped by EPIC-07). Carries
  the offending name in the message and exposes it via `name()`.
- `application/mcp/McpServerDescriptor.java` — record
  `McpServerDescriptor(String name, String description)`:
  - `name`: non-null, non-blank, `name.length() <= 64`;
  - `description`: nullable (Spring AI's MCP stdio config has no description
    field — the adapter US-08-003 derives one from a small constant lookup keyed
    on the connection name, or returns `null` for unknown names);
  - violations throw `ValidationException` with the relevant field name.
- `application/mcp/McpServerCatalog.java` — port returning the snapshot of the
  catalog:
  ```java
  List<McpServerDescriptor> all();
  boolean contains(String name);   // backs US-08-006's reference validator
  ```
  Both methods are thread-safe; the adapter returns an unmodifiable snapshot from
  `all()`.
- `application/mcp/ListMcpServersUseCase.java` — interface
  `List<McpServerDescriptor> list();` (no command record — there are no inputs).
- `application/mcp/ListMcpServersService.java` — `@Service`, pure forwarder to
  `mcpServerCatalog.all()`. Marked `@Transactional(readOnly = true)` for symmetry
  with the other read use cases.
- Pure-Java unit tests:
  - `McpServerNameTest` — accepts a valid value; rejects null / blank /
    over-64 with the field name `enabledMcpServers` in the
    `ValidationException`. Verifies case is preserved verbatim
    (`new McpServerName("Brave-Search").value()` is the literal input).
  - `McpServerDescriptorTest` — accepts a valid pair with non-null description;
    accepts a valid pair with null description; rejects null / blank / over-64
    on `name` with the field name in the `ValidationException`.
  - `UnknownMcpServerExceptionTest` — the exposed `name()` matches the value
    passed to the constructor and is included in `getMessage()`.
- `domain/mcp/package-info.java` is updated to drop the "Populated by EPIC-08"
  placeholder once the classes land.
- `application/mcp/package-info.java` is updated likewise — same delete.
- ArchUnit (US-01-008) still passes: `domain/mcp/**` has no Spring / JPA /
  Jackson imports; `application/mcp/**` may use Spring stereotypes
  (`@Service`, `@Transactional`) but no JPA / Jackson.

### Requirements coverage

`REQ-MCP-001`, `REQ-MCP-006`, `REQ-AGT-009`, `REQ-ARC-002`, `REQ-ARC-003`,
`REQ-ARC-007`.

### Design references

§4.1 (no MCP entity in the domain), §6.2.6 MCP servers endpoint, §14 MCP servers,
§3 project structure (`domain/mcp/`, `application/mcp/`).

### Dependencies

EPIC-01.

---

## US-08-002 — `application.yaml` MCP configuration + `app.mcp.filesystem.base` property binding

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `brave-search` and `filesystem` MCP servers declared in
`application.yaml` under Spring AI's `spring.ai.mcp.client.stdio.connections.*` tree,
plus the `app.mcp.filesystem.base` property bound to a new
`ApplicationProperties.Mcp.Filesystem` nested record
**So that** the catalog adapter (US-08-003) finds two connections at startup, the
filesystem MCP server's per-user root resolution (US-08-004) has a configurable
base directory with a sensible local-dev default, and operators can override both
via environment variables without rebuilding the JAR (REQ-MCP-001 / REQ-NFR-003).

### Description

This story is the configuration half of the EPIC; US-08-003 is the code half. The
two are split because the configuration tree (and its env-var contract:
`BRAVE_API_KEY`, `MCP_FS_BASE`) is independently testable and the EPIC scope
explicitly calls out the env-var requirement.

The `filesystem` connection is configured to point at the **shared** base directory
(`${app.mcp.filesystem.base}`), not at a per-user subpath. The per-user scoping
lives in `FilesystemMcpUserScopeAdapter` (US-08-004) — that adapter is responsible
for *resolving and creating* per-user roots; the actual wiring that hands a
per-user path to the Spring AI MCP runtime is deferred to EPIC-11 (TBD-2). Shipping
the shared connection here is what makes `McpServerCatalogAdapter.contains(
"filesystem")` return `true` in US-08-003 without prejudging TBD-2.

### Acceptance criteria

- `infrastructure/config/ApplicationProperties.java` grows a new nested record
  `Mcp(@Valid Filesystem filesystem)` with `Filesystem(@NotBlank String base)`,
  exposed as `properties.mcp()`.
- `application.yaml` (main) gets:
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
                args: [-y, "@modelcontextprotocol/server-filesystem", "${app.mcp.filesystem.base}"]

  app:
    mcp:
      filesystem:
        base: ${MCP_FS_BASE:./var/lib/multi-agent/fs}
  ```
  - The local default `./var/lib/multi-agent/fs` is **relative** so `mvn test`
    against a stock developer laptop never tries to write into a path that
    requires admin rights — a hard requirement on Windows per the project's
    local-environment notes. Production overrides via `MCP_FS_BASE`
    (design §15 suggests `/var/lib/multi-agent/fs` on EC2).
  - The yaml ordering keeps Spring's tree (`spring.ai.mcp.*`) above the app
    tree (`app.mcp.*`) so that `app.mcp.filesystem.base` is defined **after**
    the placeholder in `spring.ai.mcp...args` — Spring resolves placeholders
    after the full document is loaded, so file order is informational only,
    but matching the design §14 / §15 ordering keeps diffs reviewable.
- The header comment block at the top of `application.yaml` already lists
  `BRAVE_API_KEY` and `MCP_FS_BASE` with the "EPIC-08" tag (US-01-004); this
  story removes the "EPIC-08" annotation since they're now actually consumed.
- Test `application.yaml` (under `src/test/resources/`) gets:
  - The same `app.mcp.filesystem.base` default (relative path).
  - **No** `spring.ai.mcp.client.stdio.connections.*` block — tests must not
    spawn `npx` subprocesses. Instead, the test profile defines an empty
    connections map (`spring.ai.mcp.client.enabled: false` if Spring AI exposes
    such a flag in 1.1.0, otherwise an explicit empty `connections: {}` block).
    The implementer documents the exact mechanism in the commit.
  - A test-only override sets `MCP_FS_BASE` to a JUnit `@TempDir` via
    `@DynamicPropertySource` in the integration tests that need it (US-08-004).
- `ApplicationPropertiesTest` (extending the existing US-01-004 / US-07-003
  test class) gains two cases:
  - `properties.mcp().filesystem().base()` binds the default
    `./var/lib/multi-agent/fs` when `MCP_FS_BASE` is absent.
  - `properties.mcp().filesystem().base()` binds the env-var override when
    `MCP_FS_BASE=/tmp/x` is set.
- A new test `BraveApiKeyEnvVarBindingTest` (`@SpringBootTest`,
  `@ActiveProfiles("dev")`, sets `BRAVE_API_KEY=test-secret` via
  `@DynamicPropertySource`) asserts that the resolved Spring AI config
  for the `brave-search` connection exposes the env-var value under
  `env.BRAVE_API_KEY` (read via Spring AI's config model — the exact accessor
  is the same one US-08-003 uses, so this test doubles as a contract test
  for the discovery API). If Spring AI 1.1.0 does not expose a public reader
  for `env`, the test reads the underlying
  `spring.ai.mcp.client.stdio.connections.brave-search.env.BRAVE_API_KEY`
  property directly via `Environment#getProperty` and asserts the same value
  — fail-fast guarantee on the env-var name.
- Fail-fast on missing `BRAVE_API_KEY`: when `BRAVE_API_KEY` is unset in a
  non-test profile, application startup MUST fail with a clear error
  (carries-over from REQ-SEC-003 / REQ-MCP-003). A new integration test
  `BraveApiKeyMissingFailsFastTest` (boots the context with `BRAVE_API_KEY`
  explicitly removed and no fallback) asserts the context refresh fails
  with the env-var name in the error message. **Implementation note**: the
  fail-fast may be carried by Spring AI's own config validation; if not, a
  small `@PostConstruct` check in `McpServerCatalogAdapter` (US-08-003)
  fulfills the requirement.
- No code change to `JjwtTokenServiceAdapter`, `ChangeOwnPasswordService`, or
  any other existing bean — this story is configuration-only on the code side.

### Out of scope

- The choice between "per-user MCP process" and "shared MCP process with
  path-argument rewriting" (TBD-2). Both options can be implemented on top
  of the shared base path declared here.
- Tests that actually invoke the `brave-search` or `filesystem` MCP servers
  via Spring AI's runtime — those exercise EPIC-11 paths.
- Validating that `MCP_FS_BASE` points at a writable directory at startup
  (the per-user adapter validates on demand at first use, US-08-004).

### Requirements coverage

`REQ-MCP-001`, `REQ-MCP-002`, `REQ-MCP-003`, `REQ-MCP-005`, `REQ-SEC-003`,
`REQ-NFR-003`, `REQ-DEP-003`.

### Design references

§14 MCP servers (configuration), §15 configuration (env-var contract).

### Dependencies

US-08-001 (`McpServerCatalog` port the catalog adapter implements next).

---

## US-08-003 — `McpServerCatalogAdapter` — Spring AI configuration discovery with startup caching

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an infrastructure adapter that reads Spring AI's MCP-client
configuration model at startup, builds one `McpServerDescriptor` per declared
stdio connection, validates uniqueness, and caches the resulting list
**So that** the catalog is populated exactly once per JVM (REQ-MCP-001), every
catalog read is O(1), and a configuration mistake (duplicate name, malformed
connection block) fails the application at startup rather than at a chat turn.

### Description

Spring AI 1.1.0's `spring-ai-starter-mcp-client` exposes its configured
connections through a public configuration-properties bean. The exact bean class
in Spring AI 1.1.0 is to be confirmed by the implementer at build time — the
two viable options are:
- (a) Inject the `org.springframework.ai.mcp.client.autoconfigure.properties
  .McpStdioClientProperties` (or its 1.1.0-equivalent) bean and read its
  `getConnections()` map.
- (b) If Spring AI 1.1.0 exposes the parsed connection list as a higher-level
  bean (e.g. `McpClientCommonProperties`), use that surface instead.

Either way, the adapter:
- Snapshots the connection names at startup (the catalog is static per
  REQ-MCP-001).
- Builds one `McpServerDescriptor` per name, using a small internal map for
  the human-readable description (the Spring AI config has no description
  field):
  - `brave-search` → "Web search via Brave."
  - `filesystem` → "Per-user local filesystem access."
  - any other declared name → `description = null`.
- Caches the result for the lifetime of the JVM (no rescan after startup).

If the implementer finds that Spring AI 1.1.0 does not expose the connection
list as a bean, the fallback is to read the raw
`spring.ai.mcp.client.stdio.connections.*` keys from the `Environment`. The
implementer documents the chosen mechanism in the commit; the behavior visible
to the rest of the system is identical.

### Acceptance criteria

- `infrastructure/mcp/McpServerCatalogAdapter.java` — `@Component` implementing
  `application.mcp.McpServerCatalog`. Constructor-injected with whichever
  Spring AI configuration bean the implementer settles on (or with `Environment`
  in the fallback). Behavior:
  - In `@PostConstruct` (or an `ApplicationListener<ApplicationReadyEvent>`):
    1. Enumerate every declared stdio MCP connection.
    2. For each connection name, validate it through the `McpServerDescriptor`
       canonical constructor (so the same length / blank rules apply), and
       produce one descriptor with the description from the internal lookup
       (or `null` if unknown).
    3. Build a `Map<String, McpServerDescriptor>` keyed by `name`. If Spring
       AI returns duplicate keys (shouldn't be possible — the yaml is a map),
       throw `IllegalStateException` with both names — the application fails
       fast at startup.
    4. Replace the in-memory snapshot atomically (single-write at startup;
       reads afterwards are lock-free).
  - `all()` returns an unmodifiable copy of the descriptor list (sorted by
    `name` for deterministic catalog ordering).
  - `contains(String name)` returns whether the name is present
    (case-sensitive, matching the `agent_mcp.mcp_server_name` column
    collation).
- The adapter never re-scans after startup — the catalog is static per
  REQ-MCP-001.
- **Description-lookup constant**: defined as a `private static final
  Map<String, String> KNOWN_DESCRIPTIONS = Map.of("brave-search", "Web search
  via Brave.", "filesystem", "Per-user local filesystem access.")`. The map
  is the only place strings live; the `McpServerDescriptor` is built from
  `KNOWN_DESCRIPTIONS.getOrDefault(name, null)`.
- Integration test `McpServerCatalogAdapterIntegrationTest`
  (`@SpringBootTest`, `@ActiveProfiles("dev")`):
  - Uses the test-profile `application.yaml` extended to declare a synthetic
    third connection `test-mcp` under
    `spring.ai.mcp.client.stdio.connections.*` (alongside the two production
    ones — `BRAVE_API_KEY` is satisfied with a test value via
    `@DynamicPropertySource`).
  - Asserts `mcpServerCatalog.all()` returns three descriptors with names
    `["brave-search", "filesystem", "test-mcp"]` (sorted alphabetically).
  - Asserts `brave-search.description() == "Web search via Brave."`,
    `filesystem.description() == "Per-user local filesystem access."`,
    `test-mcp.description() == null`.
  - Asserts `contains("brave-search")` is `true` and
    `contains("does-not-exist")` is `false`.
- A separate test `McpServerCatalogProductionWiringTest` (no synthetic
  override, boots the production-profile config) asserts the catalog has
  exactly the two production entries `["brave-search", "filesystem"]`.
- Duplicate-name unit test — if and only if the chosen discovery mechanism
  could in principle yield duplicates (the Spring AI yaml map naturally
  cannot — but a custom merge path could): a Mockito-driven unit test feeds
  the adapter a fake configuration source returning two entries with the
  same name and asserts the adapter throws `IllegalStateException` from
  `@PostConstruct`. If the discovery mechanism makes duplicates structurally
  impossible (the most likely outcome), this test case is documented as
  "structurally impossible" in the test class Javadoc and omitted.
- The `BraveApiKeyMissingFailsFastTest` from US-08-002 — if its fail-fast
  behavior is delegated to this adapter — is satisfied here: when
  `BRAVE_API_KEY` is missing, the application context refresh fails with
  a message naming `BRAVE_API_KEY`. (If Spring AI's own validation
  fails-fast first, the adapter need not duplicate the check.)
- ArchUnit (US-01-008) still passes; the adapter lives strictly in
  `infrastructure/mcp/**`.

### Requirements coverage

`REQ-MCP-001`, `REQ-MCP-002`, `REQ-MCP-006`, `REQ-ARC-005`.

### Design references

§14 MCP servers (catalog populated once at startup, reads Spring AI's MCP
configuration model), §3 project structure (`infrastructure/mcp/`).

### Dependencies

US-08-001 (`McpServerCatalog`, `McpServerDescriptor`), US-08-002 (the
`application.yaml` block this adapter scans).

---

## US-08-004 — `FilesystemMcpUserScope` port + `FilesystemMcpUserScopeAdapter` (per-user root on-demand)

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** a per-user filesystem-root resolver that returns
`{app.mcp.filesystem.base}/users/{userId}` and creates the directory on the
first call for a given user
**So that** REQ-MCP-005 is met end-to-end: a user can never reach files
belonging to another user through the `filesystem` MCP server, the per-user
root is created lazily (not eagerly at user creation time), and EPIC-11 has a
ready-made primitive to plug into whichever variant of TBD-2 (per-user MCP
process vs path rewriting) the chat-turn implementation chooses.

### Description

The port returns a `java.nio.file.Path` for the calling user's root. The
adapter:
- Reads the base directory from `ApplicationProperties.Mcp.Filesystem.base()`
  (US-08-002).
- Resolves the per-user subpath via
  `Path.of(base).resolve("users").resolve(userId.value().toString())`.
- Creates the directory tree if it does not yet exist
  (`Files.createDirectories(...)` is idempotent).
- Validates that the resolved path stays under the configured base — defensive
  check against path-traversal attempts (the `UserId` is a UUID so this can
  never trigger in practice, but the check makes the security property local
  to the adapter and easy to audit).
- Wraps `IOException` from `Files.createDirectories` in
  `McpServerException` (US-08-007) so the REST boundary maps it to 502
  `MCP_SERVER_ERROR`.

The adapter is not yet wired to Spring AI's `filesystem` MCP connection — that
wiring is EPIC-11's job (TBD-2). What this story delivers is the
**per-user-root primitive**, callable from EPIC-11's chat-turn code, plus the
guarantee that an on-disk folder exists when the chat turn first invokes the
filesystem MCP for that user.

### Acceptance criteria

- `application/mcp/FilesystemMcpUserScope.java` — port:
  ```java
  /**
   * Returns the per-user root for the {@code filesystem} MCP server, creating
   * the directory tree on demand at first use (REQ-MCP-005).
   *
   * <p>Throws {@code McpServerException} on filesystem failure (mapped to
   * 502 {@code MCP_SERVER_ERROR} at the REST boundary, design §9 / §14).
   */
  java.nio.file.Path resolveUserRoot(UserId userId);
  ```
- `infrastructure/mcp/FilesystemMcpUserScopeAdapter.java` — `@Component`,
  constructor-injected with `ApplicationProperties`. Behavior:
  - Reads the base path from `properties.mcp().filesystem().base()` once at
    construction and stores it as a `private final Path` (normalized via
    `Path.of(base).toAbsolutePath().normalize()`).
  - `resolveUserRoot(UserId userId)`:
    1. Compute `target = base.resolve("users").resolve(userId.value()
       .toString()).normalize()`.
    2. Defensive containment check: assert `target.startsWith(base)`; if not,
       throw `McpServerException` with a message that does NOT include the
       offending path (REQ-SEC-004 — defense-in-depth against logging
       user-controlled path fragments).
    3. `Files.createDirectories(target)` — idempotent, no-op if it exists.
    4. Wrap any `IOException` in
       `McpServerException(message, cause)`; do not let the raw IOException
       leak past the adapter (REQ-ARC-007 / EXCEPTIONS.md).
    5. Return `target`.
- The adapter is **thread-safe** — `Files.createDirectories` is atomic on
  POSIX/NTFS and the adapter holds no mutable state beyond the immutable
  `base` field.
- The adapter does NOT eagerly create the `{base}/users/` parent at
  construction time — the requirement is on-demand (REQ-MCP-005). The
  parent is created by `Files.createDirectories` on the first
  `resolveUserRoot` call anyway.
- Unit test `FilesystemMcpUserScopeAdapterTest` (JUnit 5 `@TempDir`):
  - `@TempDir Path tmp` is wired via a small in-test
    `ApplicationProperties` builder to the adapter.
  - First call for `userId=u1` creates `{tmp}/users/u1` and returns the path;
    `Files.isDirectory(...)` is true.
  - Second call for the same `u1` is a no-op (the existing directory is
    preserved; idempotent).
  - First call for `u2` creates a sibling `{tmp}/users/u2`; the two roots
    do not overlap.
  - A simulated IOException (e.g. the test makes `{tmp}/users/u3`'s parent
    a regular file before calling, forcing `Files.createDirectories` to
    throw) is wrapped in `McpServerException` whose `cause()` is the
    underlying `IOException`. The exception message does NOT include the
    user ID.
- Integration test `FilesystemMcpUserScopeAdapterIntegrationTest`
  (`@SpringBootTest`, `@ActiveProfiles("dev")`, `@DynamicPropertySource`
  sets `MCP_FS_BASE` to a `@TempDir`):
  - Resolves the adapter from the Spring context.
  - First call for an arbitrary `UserId` creates the per-user folder under
    the temp dir.
  - After the call, the folder exists on disk and is empty.
  - The path returned is absolute (no relative segments) — protects EPIC-11
    when it hands the path to `npx ... server-filesystem`.
- ArchUnit (US-01-008) still passes; the port lives in `application/mcp/**`,
  the adapter in `infrastructure/mcp/**`.

### Out of scope

- Wiring the resolved per-user path into Spring AI's `filesystem` MCP
  connection at chat-turn time — that is EPIC-11 (TBD-2).
- Cleanup / GC of per-user folders when a user is hard-deleted
  (REQ-USR-006). The design carries this as an internal note for EPIC-05's
  delete-user use case; whether to delete the folder synchronously,
  asynchronously, or never is not decided in v1 and is not part of EPIC-08.
- Disk-quota enforcement.

### Requirements coverage

`REQ-MCP-002`, `REQ-MCP-005`, `REQ-ARC-007`, `REQ-SEC-004`, `REQ-NFR-003`.

### Design references

§14 MCP servers (per-user filesystem scoping; on-demand directory creation),
§15 configuration (`app.mcp.filesystem.base`), §19 TBD-2 (deferred to
EPIC-11).

### Dependencies

US-08-002 (`app.mcp.filesystem.base` binding). US-08-007 provides
`McpServerException`; if US-08-007 has not yet landed when this story is
implemented, the adapter MAY throw a `RuntimeException` placeholder and the
implementer files a follow-up — but the EPIC-08 Definition of Done requires
US-08-007 before the EPIC closes.

---

## US-08-005 — `McpServersController` & `GET /mcp-servers` REST adapter

- **Status**: Done
- **Priority**: MUST

**As an** authenticated caller (STANDARD or ADMIN JWT, or SYSTEM API-key)
**I want** to fetch the configured MCP-server catalog
**So that** I (or the frontend on my behalf) can render the list of MCP
servers available for enablement on an agent (REQ-MCP-006 / design §6.2.6).

### Description

The endpoint mirrors `GET /tools` from EPIC-07 (US-07-004) almost verbatim:
read-only, owner-agnostic, accepts any authenticated principal (design §8.6
says tools / mcp-servers are `read` for all three roles). No new URL guard
is needed — the existing `apiPattern.authenticated()` catch-all rule in
`SpringSecurityConfig` admits every authenticated principal.

### Acceptance criteria

- `infrastructure/web/catalog/McpServersController.java` — `@RestController`,
  constructor-injected with `ListMcpServersUseCase`:
  - `@GetMapping("/mcp-servers")` returns the wire envelope below.
  - No class-level `@RequestMapping`; the `/api/v1` prefix is applied
    centrally.
  - The controller lives **next to** `ToolsController` from EPIC-07 (design
    §3 places both under `infrastructure/web/catalog/`).
- DTOs (records, next to the controller):
  - `McpServerDescriptorResponse(String name, String description)` —
    matches openapi `McpServerDescriptor` (`description` is nullable per
    the openapi schema).
  - `McpServerListResponse(List<McpServerDescriptorResponse> items)` —
    matches openapi `McpServerList`. (No `nextCursor` / `pageSize` — the
    catalog is small and static.)
- `McpServerResponseMapper.toResponse(McpServerDescriptor)` — pure-static
  mapper; the controller uses it to convert the use-case result.
- Integration test `McpServersEndpointIntegrationTest` (`@SpringBootTest`,
  `@AutoConfigureMockMvc`, `@ActiveProfiles("dev")` so the synthetic
  `test-mcp` connection from US-08-003 is in the context, and
  `@DynamicPropertySource` for `BRAVE_API_KEY` and `MCP_FS_BASE`):
  - STANDARD JWT → 200; body contains a JSON array under `items` with the
    documented shape; `brave-search` and `filesystem` are both among the
    items.
  - ADMIN JWT → 200 with the same body.
  - SYSTEM API-key (valid `X-Client-Id` + `X-Api-Key`) → 200 with the same
    body — confirms the `/mcp-servers` endpoint admits the SYSTEM
    principal even though `/admin/**` and `/agents/**` don't.
  - Anonymous request → 401 `INVALID_CREDENTIALS`.
  - The `description` field is present in the response for `brave-search`
    and `filesystem`, and is `null` for the synthetic `test-mcp` entry —
    asserts the nullable contract per the openapi schema.
- The response body is deterministic across calls (the catalog is static);
  two successive GETs return byte-identical bodies.
- The endpoint is documented to match the openapi `GET /mcp-servers`
  contract exactly — a small `OpenApiContractTest` (if one already exists
  per EPIC-07) is extended to cover this path; otherwise the integration
  test's response-shape assertions stand in for the contract check.

### Requirements coverage

`REQ-MCP-006`, `REQ-AUTH-001`, `REQ-AUTH-007`, `REQ-AUTH-008`,
`REQ-API-004`, `REQ-API-006`.

### Design references

§6.2.6 MCP servers endpoint, §8.6 authorization rules.

### Dependencies

US-08-001 (`ListMcpServersUseCase`), US-08-003 (catalog populated at
startup).

---

## US-08-006 — `CatalogMcpReferenceValidator` replaces EPIC-06 `NoopMcpReferenceValidator`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `McpReferenceValidator` port shipped by EPIC-06 to surface
`VALIDATION_ERROR` with field `"enabledMcpServers"` on any unknown MCP-server
name, backed by the catalog assembled by US-08-003
**So that** REQ-AGT-009 is met end-to-end: an agent owner cannot persist an
`agent_mcp` row that does not match a configured MCP server, and the failure
point moves from "silent at chat time" to "explicit at write time".

### Description

Replace the EPIC-06 no-op stub
(`infrastructure/agent/validation/NoopMcpReferenceValidator.java`) with a
`CatalogMcpReferenceValidator` `@Component` that consults
`McpServerCatalog.contains`. Deletion of the stub is preferable to `@Primary`
shadowing — the no-op served only as a build-time placeholder, and shipping
two beans implementing the same port adds ambiguity. This mirrors US-07-005
verbatim for the MCP side.

### Acceptance criteria

- `infrastructure/agent/validation/CatalogMcpReferenceValidator.java` —
  `@Component` implementing `application.agent.McpReferenceValidator`.
  Constructor-injected with `McpServerCatalog`. Behavior:
  - Iterates `mcpServerNames` (which the `Agent` domain has already deduped
    and length-validated upstream).
  - For the first name not in the catalog, throws
    `ValidationException("enabledMcpServers", "unknown MCP server: " + name)`.
    If every name is in the catalog, returns silently.
  - Short-circuits — does NOT consult the catalog beyond the first
    offending entry; does NOT call `contains` at all when the input list
    is empty.
- `infrastructure/agent/validation/NoopMcpReferenceValidator.java` is
  **deleted** — the EPIC-06 stub no longer has a reason to exist now that
  the real implementation ships.
- The agent write paths (`CreateAgentService` US-06-004, `UpdateAgentService`
  US-06-007) are unchanged — they still call the same port via the same
  interface; the injected bean is just the new validator.
- Unit test `CatalogMcpReferenceValidatorTest` (Mockito):
  - Empty list → no exception, no catalog calls (asserts `contains` is
    **not** invoked when the list is empty).
  - Every name in the catalog → no exception; `contains` is called once
    per entry.
  - First name absent → `ValidationException` with field
    `"enabledMcpServers"` and the offending name in the message; the
    second name (if any) is NOT inspected (short-circuit).
- Integration test extension on the existing
  `CreateAgentEndpointIntegrationTest` (US-06-004) — a new test case:
  - `POST /agents` with `"enabledMcpServers": ["does-not-exist"]` → 400
    `VALIDATION_ERROR` with `errors[0].field == "enabledMcpServers"`.
  - `POST /agents` with `"enabledMcpServers": ["brave-search"]` → 201
    (continues to pass because `brave-search` IS in the catalog, registered
    by US-08-002 / US-08-003).
  - `POST /agents` with `"enabledMcpServers": ["filesystem"]` → 201 (same
    reasoning).
  - `POST /agents` with `"enabledMcpServers": ["brave-search",
    "does-not-exist"]` → 400 `VALIDATION_ERROR` with
    `errors[0].field == "enabledMcpServers"` (the order of the offending
    name is preserved in the message).
- The symmetric integration test extension on `UpdateAgentEndpointIntegrationTest`
  (US-06-007) — same four cases against `PUT /agents/{id}`.
- ArchUnit (US-01-008) still passes — the validator lives in
  `infrastructure/agent/validation/**` (next to the catalog-backed
  `CatalogToolReferenceValidator` shipped by EPIC-07).

### Requirements coverage

`REQ-MCP-006`, `REQ-AGT-009`, `REQ-API-004`.

### Design references

§14 MCP servers (write-time reference validation), §9 error handling.

### Dependencies

US-08-003 (`McpServerCatalog`), US-06-004 (existing agent write path that
consumes the port).

---

## US-08-007 — `McpServerException` + `MCP_SERVER_ERROR` 502 mapping in `GlobalExceptionHandler`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an `McpServerException` infrastructure exception (subclass of the
project's `ExternalServiceException` family) and a corresponding handler in
`GlobalExceptionHandler` that maps it to HTTP 502 with `code =
MCP_SERVER_ERROR`
**So that** the design §9 error table is honored, the `FilesystemMcpUserScopeAdapter`
(US-08-004) has a well-defined exception to throw on filesystem failure, and
EPIC-11's chat-turn code has the right primitive ready when it wraps Spring
AI MCP runtime failures into a user-facing error (REQ-MCP-* / REQ-LLM-005
analogue for MCP).

### Description

Design §9.1 defines the infrastructure-layer `ExternalServiceException`
mapped to 502 — used by both the LLM provider (EPIC-09, `LLM_UNAVAILABLE`)
and MCP servers (this EPIC, `MCP_SERVER_ERROR`). EPIC-08 ships the MCP
half; EPIC-09 will ship the LLM half.

Two design options for the exception hierarchy:
- (a) An abstract `ExternalServiceException` base with concrete subclasses
  `McpServerException` (here) and `LlmUnavailableException` (EPIC-09).
- (b) A single `ExternalServiceException(String code)` that the handler
  switches on.

**Choose (a)** — it lets the handler match on type rather than on a string
code, which is the standard `@RestControllerAdvice` idiom and keeps the
Problem-Details `type` URI machine-derivable per class. (b) saves a class
at the cost of a stringly-typed switch.

If `ExternalServiceException` does not yet exist (EPIC-09 has not yet
introduced it), this story creates it as an abstract base in
`infrastructure/error/` — EPIC-09 will reuse it. The exact package is
`infrastructure/error/` because the exception is purely a transport concern;
domain code never imports it.

### Acceptance criteria

- `infrastructure/error/ExternalServiceException.java` — `public abstract
  class ExternalServiceException extends RuntimeException` with one
  constructor `(String message, Throwable cause)`. Marked `abstract` so
  callers always reach for a concrete subclass.
- `infrastructure/error/McpServerException.java` — `public final class
  McpServerException extends ExternalServiceException` with constructors
  `(String message)` and `(String message, Throwable cause)`. Javadoc spells
  out:
  - Mapped to HTTP 502 `MCP_SERVER_ERROR`.
  - Thrown by infrastructure adapters that wrap Spring AI MCP runtime
    failures or local filesystem-side failures of MCP-supporting code (e.g.
    `FilesystemMcpUserScopeAdapter`, US-08-004).
  - The constructor message MUST NOT contain MCP-server payloads or
    user-controlled paths (REQ-SEC-004 — log redaction).
- `infrastructure/web/error/GlobalExceptionHandler.java` gains a new
  `@ExceptionHandler(McpServerException.class)` method that:
  - Logs the exception at `WARN` with the cause's class name and message
    (the cause is operator-relevant; the user-facing body is sanitized).
    No stack trace included in the response (REQ-API-004).
  - Returns a `ProblemDetails` body with:
    ```json
    {
      "type": "https://errors.multi-agent-platform/mcp-server-error",
      "title": "MCP server error",
      "status": 502,
      "detail": "The MCP server is currently unavailable.",
      "code": "MCP_SERVER_ERROR"
    }
    ```
  - HTTP status 502.
- Unit test `GlobalExceptionHandlerMcpServerErrorTest` (Mockito + plain
  controller-advice unit test, no `@SpringBootTest`):
  - Throws an `McpServerException("npx subprocess died unexpectedly")` and
    asserts the produced `ResponseEntity` has status 502 and the body
    matches the documented shape with `code == "MCP_SERVER_ERROR"`.
  - Throws an `McpServerException("...", new IOException("disk full"))` and
    asserts the response shape is unchanged (the cause is logged but does
    not leak into the body).
- A short documentation note in `backend/backlog/DESIGN-CHOICES.md` records
  the choice of option (a) (typed subclass hierarchy) over option (b)
  (single class with a `code` parameter), so EPIC-09's
  `LlmUnavailableException` follows the same pattern.
- ArchUnit (US-01-008) still passes — the exceptions live in
  `infrastructure/error/**`, never imported from `domain/**` or
  `application/**` (the application's only contract with the failure mode
  is via the `McpServerCatalog` / `FilesystemMcpUserScope` ports, which do
  not throw infrastructure exceptions in their declared signatures —
  unchecked `RuntimeException` flows through as an implementation detail
  of the adapter).
- The `MCP_SERVER_ERROR` value is already listed in the
  `ProblemDetails.code` enum in `openapi.yaml` (it is — line 802 / 845);
  no openapi change is required.

### Out of scope

- The matching `LlmUnavailableException` for EPIC-09 (502
  `LLM_UNAVAILABLE`). This story creates the `ExternalServiceException`
  base if absent, but does not pre-implement the LLM subclass.
- Catching generic Spring AI MCP runtime exceptions at the boundary of the
  chat turn — that is EPIC-11. EPIC-08 only contributes the type, the
  handler, and the one in-EPIC thrower (US-08-004's filesystem adapter).
- A Problem-Details `instance` field carrying the request path — the
  existing handler infrastructure (US-03-001) already fills it in
  generically.

### Requirements coverage

`REQ-MCP-006` (catalog robustness), `REQ-ARC-007` (layered exception
typology), `REQ-API-004` (error response shape), `REQ-SEC-004` (log
redaction).

### Design references

§9.1 typology, §9.2 GlobalExceptionHandler, §9.3 error code table
(`MCP_SERVER_ERROR` 502), §14 MCP servers (error handling).

### Dependencies

US-03-001 (existing `GlobalExceptionHandler` and `ProblemDetails` mapper).
Logically also US-08-004 (the first in-EPIC thrower), but US-08-007 may
land before or after US-08-004 — the two are decoupled.

---

## EPIC-08 Definition of Done

EPIC-08 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every test from previous EPICs green; the EPIC-08 unit and
  integration tests run green against a local PostgreSQL and a local writable
  temp directory for `MCP_FS_BASE`.
- A fresh start of the application registers exactly two catalog entries under
  the production profile — `brave-search` and `filesystem` — discoverable via:
  - `GET /api/v1/mcp-servers` (returns two items in `items`);
  - `McpServerCatalog.contains("brave-search")` and
    `McpServerCatalog.contains("filesystem")` both returning `true`
    programmatically.
- An agent owner trying to create or replace an agent with
  `"enabledMcpServers": ["does-not-exist"]` is rejected with `400
  VALIDATION_ERROR` and `errors[0].field == "enabledMcpServers"`.
- An agent owner trying to create or replace an agent with
  `"enabledMcpServers": ["brave-search", "filesystem"]` succeeds (the EPIC-06
  happy-path tests still pass with the real validator wired in).
- `app.mcp.filesystem.base` defaults to `./var/lib/multi-agent/fs` when
  `MCP_FS_BASE` is absent; production deployments override via env var or
  Spring property without rebuilding the JAR (REQ-NFR-003 / REQ-DEP-003).
- `BRAVE_API_KEY` is required at startup in non-test profiles; a missing
  value fails the application fast with a clear error naming the env var
  (REQ-SEC-003 / REQ-MCP-003).
- The first call to `FilesystemMcpUserScope.resolveUserRoot(userId)` for a
  given user creates `{app.mcp.filesystem.base}/users/{userId}/` on disk;
  subsequent calls are idempotent; the resolved path is absolute and stays
  under the base (REQ-MCP-005).
- A simulated MCP failure thrown from any infrastructure adapter under
  `infrastructure/mcp/**` produces a 502 response with `code =
  MCP_SERVER_ERROR` and the documented Problem-Details body.
- No call to the real `brave-search` MCP server (no live web search) or the
  real `filesystem` MCP server's `npx` subprocess happens during the test
  suite (the catalog assertion is purely structural; runtime MCP traffic
  lives in EPIC-11 / actual chat turns).
- ArchUnit (US-01-008) still passes: `domain/mcp/**` and `application/mcp/**`
  are free of Spring / JPA / Jackson imports; the catalog adapter, the
  filesystem user-scope adapter, the REST controller, the reference
  validator, and the MCP-server exception live strictly under
  `infrastructure/**`.
