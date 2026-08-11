# EPIC-07-US.md — User stories for EPIC-07

EPIC-07 — **Tools catalog**

This file lists the user stories that deliver EPIC-07. The EPIC stands up the static
tool catalog (REQ-TOOL-001 / -003), exposes it via `GET /tools`, ships the v1 reference
tool `AwsS3Tool`, and plugs the catalog-backed reference validator into the agent write
path — completing the seam EPIC-06 left open via `NoopToolReferenceValidator`.

> **Scope split with EPIC-06 / EPIC-08 / EPIC-11.**
> - The `ToolReferenceValidator` application-layer port (declared in `application/agent/`)
>   was shipped by EPIC-06 with the `NoopToolReferenceValidator` stub. US-07-005 deletes
>   the stub and replaces it with a catalog-backed implementation. EPIC-06's agent write
>   path (US-06-004 / US-06-007) does not change.
> - MCP-server reference validation, `GET /mcp-servers`, and the corresponding catalog
>   are owned by EPIC-08; this EPIC does not touch the `NoopMcpReferenceValidator`
>   shipped by EPIC-06.
> - Per-agent tool wiring during a chat turn (`agent.tools` filtered against the catalog
>   and attached to the LLM `ChatRequest`) is owned by EPIC-11. This EPIC stops at the
>   catalog and the write-time validation; runtime execution is out of scope.
> - The v1 tool catalog contains exactly one entry: `AwsS3Tool`. The reference example
>   at `backend/docs/AwsS3Tool.java` is the starting point — US-07-003 adapts it into a
>   Spring `@Component` per design §13.

## Conventions

- **ID format**: `US-07-<nnn>` — `07` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                          | Priority | Status | Depends on                |
|------------|--------------------------------------------------------------------------------|----------|--------|---------------------------|
| US-07-001  | `ToolDescriptor` + `@ToolGroup` + `ToolCatalog` port + `ListToolsUseCase`      | MUST     | Done   | EPIC-01                   |
| US-07-002  | `ToolCatalogAdapter` — Spring bean scanner with startup caching                | MUST     | Done   | US-07-001                 |
| US-07-003  | `AwsS3Tool` Spring `@Component` adapted from `backend/docs/AwsS3Tool.java`     | MUST     | Done   | US-07-001, US-07-002      |
| US-07-004  | `ToolsController` & `GET /tools` REST adapter                                  | MUST     | Done   | US-07-001                 |
| US-07-005  | `CatalogToolReferenceValidator` replaces EPIC-06 `NoopToolReferenceValidator`  | MUST     | Done   | US-07-002, US-06-004      |

---

## US-07-001 — `ToolDescriptor` + `@ToolGroup` + `ToolCatalog` port + `ListToolsUseCase`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the domain primitives that describe an entry in the tool catalog, plus the
small application use case that surfaces the catalog to the REST adapter
**So that** EPIC-07 and every downstream EPIC reads the catalog through a Spring-free
contract and EPIC-08's MCP-server catalog can ship a symmetrical surface.

### Description

The catalog is static, read-only, and small (one entry in v1). The domain side is a
simple value object and a port; the application side is a thin forwarder use case. No
pagination — the entire catalog fits in a single response (design §6.2.5).

The `@ToolGroup` class-level annotation introduced here is the contract a Spring bean
follows to declare itself as a catalog entry. The infrastructure adapter delivered in
US-07-002 scans for it.

### Acceptance criteria

- `domain/tool/ToolDescriptor.java` — record
  `ToolDescriptor(String name, String description)`:
  - non-null, non-blank `name`, `name.length() <= 64` (matches the openapi cap and the
    `agent_tools.tool_name varchar(64)` column);
  - non-null, non-blank `description`;
  - violations throw `ValidationException` with the relevant field name.
- `domain/tool/ToolGroup.java` — class-level annotation with attributes:
  - `String name()` — the catalog entry's name; required, ≤ 64 chars enforced by the
    adapter at startup;
  - `String description()` — the human-readable description; required, non-blank.
  - Retention: `RUNTIME`. Target: `TYPE`. No Spring imports — the annotation is pure
    Java metadata that the adapter reads reflectively.
- `domain/tool/ToolCatalog.java` — port returning the snapshot of the catalog:
  ```java
  List<ToolDescriptor> all();
  boolean contains(String name);   // backs US-07-005's reference-validator
  ```
  Both methods are thread-safe; the adapter returns an unmodifiable snapshot from
  `all()`.
- `application/tool/ListToolsUseCase.java` — interface
  `List<ToolDescriptor> list();` (no command record — there are no inputs).
- `application/tool/ListToolsService.java` — `@Service`, pure forwarder to
  `toolCatalog.all()`. Marked `@Transactional(readOnly = true)` for symmetry with the
  other read use cases even though there's no DB hit; keeps the wiring uniform.
- Pure-Java unit tests:
  - `ToolDescriptorTest` — accepts a valid pair; rejects null / blank / over-64 on
    each field with the field name in the `ValidationException`.
  - `ToolGroupAnnotationTest` — a tiny synthetic class annotated with `@ToolGroup`,
    asserts both attributes round-trip via reflection.
- ArchUnit (US-01-008) still passes: `domain/tool/**` has no Spring / JPA / Jackson
  imports.

### Requirements coverage

`REQ-TOOL-001`, `REQ-TOOL-002`, `REQ-TOOL-003`, `REQ-ARC-002`, `REQ-ARC-003`,
`REQ-ARC-007`.

### Design references

§4.1 / §13 tools, §3 project structure (`domain/tool/`, `application/tool/`,
`infrastructure/tool/`).

### Dependencies

EPIC-01.

---

## US-07-002 — `ToolCatalogAdapter` — Spring bean scanner with startup caching

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** an infrastructure adapter that walks the Spring application context at
startup, finds every bean annotated with `@ToolGroup`, builds a `ToolDescriptor` from
each annotation, validates uniqueness, and caches the result
**So that** the catalog is populated exactly once per JVM (`REQ-TOOL-001`), every
catalog read is O(1), and a configuration mistake (duplicate name, malformed
annotation) fails the application at startup rather than at a chat turn.

### Description

Spring AI's `@Tool` annotation marks methods on a bean as callable by the LLM at chat
time — that's an EPIC-11 concern. EPIC-07's catalog is decoupled from `@Tool`: the
catalog adapter walks `@ToolGroup`-annotated beans only. A bean is free to also expose
`@Tool` methods (and `AwsS3Tool` will, US-07-003); the two annotations have orthogonal
responsibilities.

### Acceptance criteria

- `infrastructure/tool/ToolCatalogAdapter.java` — `@Component` implementing
  `ToolCatalog`. Constructor-injected with `ApplicationContext`. Behavior:
  - In `@PostConstruct` (or an `ApplicationListener<ApplicationReadyEvent>`):
    1. Enumerate every Spring bean annotated with `@ToolGroup`
       (`applicationContext.getBeansWithAnnotation(ToolGroup.class)`).
    2. For each bean, read the `@ToolGroup` annotation off its class, validate the
       attributes through the {@code ToolDescriptor} canonical constructor (so the
       same length / blank rules apply), and produce one descriptor.
    3. Build a `Map<String, ToolDescriptor>` keyed by `name`. If two beans declare the
       same name, throw `IllegalStateException` with both bean class names — the
       application fails fast at startup.
    4. Replace the in-memory snapshot atomically (single-write at startup; reads
       afterwards are lock-free).
  - `all()` returns an unmodifiable copy of the descriptor list (sorted by `name` for
    deterministic catalog ordering).
  - `contains(String name)` returns whether the name is present (case-sensitive,
    matching the `agent_tools.tool_name` column collation).
- The adapter never re-scans after startup — the catalog is static per
  `REQ-TOOL-001`.
- Integration test `ToolCatalogAdapterIntegrationTest` (`@SpringBootTest`,
  `@ActiveProfiles("dev")`):
  - Defines a small synthetic test bean annotated with `@ToolGroup(name = "TestTool",
    description = "Used by tests only.")` inside the test classpath (gated by
    `@Profile("dev")` so it never reaches production).
  - Asserts `toolCatalog.all()` contains `TestTool` and at least `AwsS3Tool` (from
    US-07-003, when run end-to-end).
  - Asserts `toolCatalog.contains("TestTool")` is true and
    `toolCatalog.contains("does-not-exist")` is false.
- Duplicate-name unit test (Mockito or plain Spring `AnnotationConfigApplicationContext`
  in isolation, not `@SpringBootTest`): registers two beans with the same `@ToolGroup`
  name and asserts the adapter throws `IllegalStateException` from
  `@PostConstruct` — the Spring context refresh fails.
- ArchUnit (US-01-008) still passes; the adapter lives strictly in
  `infrastructure/tool/**`.

### Requirements coverage

`REQ-TOOL-001`, `REQ-TOOL-002`, `REQ-TOOL-003`, `REQ-ARC-005`.

### Design references

§13 tools (catalog populated once at startup and cached), §3 project structure
(`infrastructure/tool/`).

### Dependencies

US-07-001 (`ToolDescriptor`, `ToolGroup`, `ToolCatalog`).

---

## US-07-003 — `AwsS3Tool` Spring `@Component` adapted from `backend/docs/AwsS3Tool.java`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the v1 reference tool ported from the static `backend/docs/AwsS3Tool.java`
example into a Spring `@Component` that the catalog adapter picks up at startup, with
its AWS region read from `app.aws.region` and the S3 client wired via constructor
injection
**So that** `REQ-TOOL-005` is met end-to-end: the catalog reports exactly one entry
(`AwsS3Tool`) on a fresh start of the application, and the same JAR runs locally and on
EC2 without further code changes.

### Description

Adapt the existing static example to:
- Be a Spring `@Component`, not a class with `static { ... }` initialization.
- Read its AWS region from `app.aws.region` (default `eu-west-3`, preserving the
  example's behavior).
- Use the AWS SDK's default credentials chain (env vars locally, instance role on
  EC2) — `REQ-DEP-003`'s "same JAR runs locally and in AWS" requirement.
- Carry a class-level `@ToolGroup(name = "AwsS3Tool", description = "...")` annotation
  so the catalog adapter (US-07-002) picks it up.
- Keep the `@Tool`-annotated methods from the reference example, so EPIC-11 can wire
  them into the chat request without further changes.

This story also extends the build (`pom.xml`) with the AWS SDK S3 client dependency
and grows `ApplicationProperties` with the `aws.region` section.

### Acceptance criteria

- `pom.xml` adds `software.amazon.awssdk:s3` (pin to a recent stable
  2.x version — the implementer documents the exact version in the commit). No other
  AWS SDK module is pulled in; we explicitly do NOT depend on the full
  `aws-sdk-bom` to keep the fat JAR lean.
- `infrastructure/config/ApplicationProperties.java` grows a new nested record
  `Aws(String region)` with `@NotBlank` validation, exposed as `properties.aws()`.
  Default value `eu-west-3` is set in `application.yaml`; production deployments
  override via `AWS_REGION` env var or Spring property.
- `application.yaml` (main) gets:
  ```yaml
  app:
    aws:
      region: ${AWS_REGION:eu-west-3}
  ```
  Test `application.yaml` mirrors the same default.
- `infrastructure/tool/AwsS3Tool.java` — `@Component`,
  `@ToolGroup(name = "AwsS3Tool", description = "Perform actions on AWS S3 buckets: "
    + "list buckets and folders, read text and PDF files, write data, delete objects.")`,
  constructor-injected with `ApplicationProperties`. Pipeline:
  - Constructor builds an `S3Client` once via
    `S3Client.builder().region(Region.of(properties.aws().region())).build()` and
    stores it in a `private final` field.
  - Methods from the reference example are preserved as instance methods, each
    annotated `@Tool(description = "...")`:
    - `readBucketContent(String bucketName)` → `List<String>`
    - `readFolderContent(String bucketName, String folderName)` → `List<String>`
    - `readDataFromS3(String bucketName, String filePath)` → `String`
    - `readPdfFileFromS3(String bucketName, String filePath)` → `String`
    - `writeDataInS3(String bucketName, String filePath, String content)` → `void`
      (the static modifier from the example is dropped — it would break the
      `@Tool` annotation contract under a non-static bean)
    - `deleteObjectFromBucket(String bucketName, String filePath)` → `void`
  - PDF reading uses a small helper that delegates to a PDF library (Apache PDFBox
    is the lightest match; the implementer picks a version and adds the dep to
    `pom.xml`). If pulling PDFBox is contentious, ship `readPdfFileFromS3` as a stub
    that throws `UnsupportedOperationException` and open a follow-up — the catalog
    entry still surfaces correctly either way.
  - No reliance on the static `S3` helper class from the reference; the implementation
    inlines the SDK calls or extracts them into a `private` instance helper. The
    point is: no static state.
- Integration test `AwsS3ToolCatalogIntegrationTest`:
  - Boots the full Spring context (`@SpringBootTest`, no `@ActiveProfiles("dev")` —
    we want to see the production wiring).
  - Asserts `toolCatalog.all()` contains exactly one descriptor with
    `name = "AwsS3Tool"` and a non-blank `description`.
  - Asserts `toolCatalog.contains("AwsS3Tool")` is `true`.
  - Does **not** invoke any of the S3 methods (no real S3 traffic during tests).
- An ApplicationPropertiesTest case (extending the existing US-01-004 test class)
  asserts `properties.aws().region()` binds the default `eu-west-3` when the env var
  is absent.
- ArchUnit (US-01-008) still passes; the AwsS3Tool lives strictly in
  `infrastructure/tool/**`.

### Requirements coverage

`REQ-TOOL-002`, `REQ-TOOL-005`, `REQ-NFR-003`, `REQ-DEP-003`, `REQ-SEC-003`.

### Design references

§13 tools (v1 catalog has one tool `AwsS3Tool`; region from `app.aws.region`; default
`eu-west-3`; application IAM credentials), §15 configuration, §17 deployment (S3
permissions via instance role).

### Dependencies

US-07-001 (`@ToolGroup`), US-07-002 (catalog adapter that discovers it).

---

## US-07-004 — `ToolsController` & `GET /tools` REST adapter

- **Status**: Done
- **Priority**: MUST

**As an** authenticated caller (STANDARD or ADMIN JWT, or SYSTEM API-key)
**I want** to fetch the static tool catalog
**So that** I (or the frontend on my behalf) can render the list of tools available
for assignment to an agent (`REQ-TOOL-003` / design §6.2.5).

### Description

The endpoint is read-only, owner-agnostic, accepts any authenticated principal (design
§8.6 says tools / mcp-servers are `read` for all three roles). No new URL guard is
needed — the existing `apiPattern.authenticated()` catch-all rule in
`SpringSecurityConfig` admits every authenticated principal.

### Acceptance criteria

- `infrastructure/web/tool/ToolsController.java` — `@RestController`,
  constructor-injected with `ListToolsUseCase`:
  - `@GetMapping("/tools")` returns the wire envelope below.
  - No class-level `@RequestMapping`; the `/api/v1` prefix is applied centrally.
- DTOs (records, next to the controller):
  - `ToolDescriptorResponse(String name, String description)` — matches openapi
    `ToolDescriptor`.
  - `ToolListResponse(List<ToolDescriptorResponse> items)` — matches openapi
    `ToolList`. (No `nextCursor` / `pageSize` — the catalog is small and static.)
- `ToolResponseMapper.toResponse(ToolDescriptor)` — pure-static mapper; the controller
  uses it to convert the use-case result.
- Integration test `ToolsEndpointIntegrationTest` (`@SpringBootTest`,
  `@AutoConfigureMockMvc`, `@ActiveProfiles("dev")` so the synthetic `TestTool` from
  US-07-002 is in the context):
  - STANDARD JWT → 200; body contains a JSON array under `items` with the documented
    shape; `AwsS3Tool` is among the items (assertion uses
    `Matchers.hasItem(hasProperty("name", equalTo("AwsS3Tool")))` or equivalent).
  - ADMIN JWT → 200 with the same body.
  - SYSTEM API-key (valid `X-Client-Id` + `X-Api-Key`) → 200 with the same body —
    confirms the `/tools` endpoint admits the SYSTEM principal even though
    `/admin/**` and `/agents/**` don't.
  - Anonymous request → 401 `INVALID_CREDENTIALS`.
- The response body is deterministic across calls (the catalog is static); two
  successive GETs return byte-identical bodies.

### Requirements coverage

`REQ-TOOL-003`, `REQ-AUTH-001`, `REQ-AUTH-007`, `REQ-AUTH-008`, `REQ-API-004`,
`REQ-API-006`.

### Design references

§6.2.5 tools endpoint, §8.6 authorization rules.

### Dependencies

US-07-001 (`ListToolsUseCase`).

---

## US-07-005 — `CatalogToolReferenceValidator` replaces EPIC-06 `NoopToolReferenceValidator`

- **Status**: Done
- **Priority**: MUST

**As a** backend developer
**I want** the `ToolReferenceValidator` port shipped by EPIC-06 to surface
`VALIDATION_ERROR` with field `"tools"` on any unknown tool name, backed by the
catalog assembled by US-07-002
**So that** REQ-TOOL-004 is met end-to-end: an agent owner cannot persist an
`agent_tools` row that does not match a real tool, and the failure point moves from
"silent at chat time" to "explicit at write time".

### Description

Replace the EPIC-06 no-op stub (`NoopToolReferenceValidator`) with a
`CatalogToolReferenceValidator` `@Component` that consults `ToolCatalog.contains`.
Deletion of the stub is preferable to `@Primary` shadowing — the no-op served only as a
build-time placeholder, and shipping two beans implementing the same port adds
ambiguity.

### Acceptance criteria

- `infrastructure/agent/validation/CatalogToolReferenceValidator.java` — `@Component`
  implementing `application.agent.ToolReferenceValidator`. Constructor-injected with
  `ToolCatalog`. Behavior:
  - Iterates `toolNames` (which the `Agent` domain has already deduped and length-
    validated upstream).
  - For the first name not in the catalog, throws
    `ValidationException("tools", "unknown tool: " + name)`. If every name is in the
    catalog, returns silently.
- `infrastructure/agent/validation/NoopToolReferenceValidator.java` is **deleted** —
  the EPIC-06 stub no longer has a reason to exist now that the real implementation
  ships.
- The agent write paths (`CreateAgentService` US-06-004, `UpdateAgentService`
  US-06-007) are unchanged — they still call the same port via the same interface; the
  injected bean is just the new validator.
- Unit test `CatalogToolReferenceValidatorTest` (Mockito):
  - Empty list → no exception, no catalog calls beyond `contains` lookups (asserts
    `contains` is **not** invoked when the list is empty).
  - Every name in the catalog → no exception.
  - First name absent → `ValidationException` with field `"tools"` and the offending
    name in the message; the second name (if any) is NOT inspected (short-circuit).
- Integration test extension on the existing `CreateAgentEndpointIntegrationTest`
  (US-06-004) — a new test case:
  - `POST /agents` with `"tools": ["does-not-exist"]` → 400 `VALIDATION_ERROR` with
    `errors[0].field == "tools"`. The existing happy-path case using
    `"tools": ["AwsS3Tool"]` continues to pass because `AwsS3Tool` IS in the catalog
    (registered by US-07-003).
- ArchUnit (US-01-008) still passes — the validator lives in
  `infrastructure/agent/validation/**` (next to its sibling, the soon-to-be-replaced
  EPIC-08 MCP stub).

### Requirements coverage

`REQ-TOOL-004`, `REQ-AGT-008`, `REQ-API-004`.

### Design references

§13 tools (write-time reference validation), §9 error handling.

### Dependencies

US-07-002 (`ToolCatalog`), US-06-004 (existing agent write path that consumes the
port).

---

## EPIC-07 Definition of Done

EPIC-07 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every test from previous EPICs green; the EPIC-07 unit and
  integration tests run green against a local PostgreSQL.
- A fresh start of the application registers exactly one catalog entry under the
  production profile — `AwsS3Tool` — discoverable via:
  - `GET /api/v1/tools` (returns one item in `items`);
  - `ToolCatalog.contains("AwsS3Tool")` returning `true` programmatically.
- An agent owner trying to create or replace an agent with
  `"tools": ["does-not-exist"]` is rejected with `400 VALIDATION_ERROR` and
  `errors[0].field == "tools"`.
- An agent owner trying to create or replace an agent with `"tools": ["AwsS3Tool"]`
  succeeds (the EPIC-06 happy-path test still passes verbatim).
- `app.aws.region` defaults to `eu-west-3` when `AWS_REGION` is absent; production
  deployments override via env var or Spring property without rebuilding the JAR
  (REQ-NFR-003 / REQ-DEP-003).
- A configuration mistake — two beans declaring the same `@ToolGroup(name = ...)` —
  fails the application at startup with a clear error naming both bean classes
  (no silent override, no chat-time surprise).
- No call to AWS S3 happens during the test suite (the catalog assertion is purely
  structural; runtime S3 traffic lives in EPIC-11 / actual chat turns).
- ArchUnit (US-01-008) still passes: `domain/tool/**` and `application/tool/**` are
  free of Spring / JPA / Jackson imports; the catalog adapter, the AwsS3Tool, the REST
  controller, and the reference validator live strictly under `infrastructure/**`.
