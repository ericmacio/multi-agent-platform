# EPIC-01-US.md — User stories for EPIC-01

EPIC-01 — **Project foundation & hexagonal skeleton**

This file lists the user stories that deliver EPIC-01. The EPIC stands up the runnable Spring
Boot project, the hexagonal package layout, externalized configuration, the open MVC/Security
skeleton, the test infrastructure, and the architectural-rule enforcement. No business endpoint
is delivered here.

## Conventions

- **ID format**: `US-01-<nnn>` — `01` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of acceptance criteria, the requirements it carries, the design references, and
  its dependencies.
- Acceptance criteria are testable statements; "verified by …" calls out the proof artifact
  (build command, unit test, integration test).

## Story list

| ID         | Title                                                  | Priority | Status | Depends on        |
|------------|--------------------------------------------------------|----------|--------|-------------------|
| US-01-001  | Maven project & runnable fat-JAR                       | MUST     | Draft  | —                 |
| US-01-002  | Hexagonal package skeleton                             | MUST     | Draft  | US-01-001         |
| US-01-003  | Spring Boot application bootstrap                      | MUST     | Draft  | US-01-001, US-01-002 |
| US-01-004  | Externalized configuration & `ApplicationProperties`   | MUST     | Draft  | US-01-003         |
| US-01-005  | Centralized `/api/v1` base path                        | MUST     | Draft  | US-01-004         |
| US-01-006  | Spring MVC + Security skeleton (open chain)            | MUST     | Draft  | US-01-003         |
| US-01-007  | Test infrastructure (JUnit 5 + AssertJ + Mockito)      | MUST     | Draft  | US-01-001         |
| US-01-008  | Architectural layering enforcement (ArchUnit)          | MUST     | Draft  | US-01-002, US-01-007 |

---

## US-01-001 — Maven project & runnable fat-JAR

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a Maven project pre-configured with Spring Boot 4.0.6, Java 17, and Spring AI 1.1.0
**So that** every subsequent story builds on a single, opinionated, reproducible build.

### Description

Create the Maven project that produces a single runnable fat JAR. This is the very first
deliverable: it freezes our build technology, framework versions, and source/target compiler
levels. It must compile and package even though no business code exists yet.

### Acceptance criteria

- A single `pom.xml` exists at `backend/pom.xml`. There is **no** multi-module setup.
- `pom.xml` declares Java 17 (`maven.compiler.source/target=17`) and inherits from the Spring
  Boot 4.0.6 parent.
- Spring AI 1.1.0 BOM is imported in `<dependencyManagement>` and at least the
  `spring-ai-openai-spring-boot-starter` and `spring-ai-mcp-client-spring-boot-starter` artifacts
  are present (verified by `mvn dependency:tree`).
- The `spring-boot-maven-plugin` is configured to produce an executable fat JAR.
- `mvn -f backend/pom.xml clean package` succeeds end-to-end and produces
  `backend/target/multi-agent-platform-<version>.jar`.
- The produced JAR is executable: `java -jar backend/target/multi-agent-platform-*.jar` exits
  with status 0 when the app is started without a Spring context (or starts the empty context
  when wired in US-01-003).
- The artifact `groupId/artifactId/version` follows the Java package convention
  `com.cognizant.emk.multiagent` / `multi-agent-platform` / `0.1.0-SNAPSHOT`.
- No Lombok dependency is present (records-only policy from `JAVA-CODING-STANDARD.md`).

### Requirements coverage

`REQ-ARC-001`, `REQ-ARC-006`, `REQ-DEP-004`, `REQ-NFR-001`, `REQ-NFR-003`.

### Design references

§2.4 Module/artifact, §17 Build & deployment.

### Dependencies

None.

---

## US-01-002 — Hexagonal package skeleton

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the three top-level Java packages (`domain`, `application`, `infrastructure`) and an
empty stub package per bounded context
**So that** every subsequent story drops code into a predetermined location and the layering
rule is visible in the source tree.

### Description

Create the package skeleton exactly as documented in design §3. Each bounded context exists as
an empty (or marker-only) package under `domain/`, `application/`, and `infrastructure/`.
No business code is produced; this story is structural only.

### Acceptance criteria

- The Java root package is `com.cognizant.emk.multiagent` and lives under
  `backend/src/main/java/`.
- The three layer packages exist directly beneath the root: `domain`, `application`,
  `infrastructure`.
- For every bounded context — `user`, `agent`, `conversation`, `tool`, `mcp`, `ratelimit`,
  `auth` — an empty package exists under both `domain/` and `application/`. Empty packages
  are made visible to Maven via a `package-info.java` file (no compiled classes required).
- The infrastructure layer exposes the sub-packages from design §3:
  `web/`, `web/auth`, `web/admin`, `web/agent`, `web/conversation`, `web/catalog`, `web/error`,
  `web/pagination`, `web/security`, `web/ratelimit`, `persistence/entity`, `persistence/springdata`,
  `persistence/mapper`, `persistence/adapter`, `llm/openai`, `tool`, `mcp`, `security`, `config`.
- A `domain/shared/` package contains the `BusinessException` abstract class plus the four
  framework-shaped subclasses (`ValidationException`, `NotFoundException`, `ConflictException`,
  `ForbiddenException`), all pure Java with no Spring imports. (The full per-context concrete
  exception classes belong to their feature EPICs.)
- The structure compiles: `mvn -f backend/pom.xml compile` succeeds.
- A README at `backend/src/main/java/com/cognizant/emk/multiagent/package-info.java` documents
  the layering rule (`infrastructure → application → domain`) in one short Javadoc block.

### Requirements coverage

`REQ-ARC-002`, `REQ-ARC-003`, `REQ-ARC-004`, `REQ-ARC-007`.

### Design references

§2.1 Hexagonal style, §3 Project structure, §3.1 Conventions, §3.2 Layering rule.

### Dependencies

US-01-001.

---

## US-01-003 — Spring Boot application bootstrap

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a Spring Boot main class that starts a minimal application context
**So that** the project is runnable end-to-end and integration tests can boot the context.

### Description

Add the `Application.java` entry point and a smoke test verifying that the Spring context
starts. The application is functionally empty at this point — no database, no security beyond
defaults, no business endpoints — but it must boot and stop cleanly.

### Acceptance criteria

- `com.cognizant.emk.multiagent.Application` exists, annotated with `@SpringBootApplication`.
- A `main(String[] args)` method delegates to `SpringApplication.run(Application.class, args)`.
- Running `mvn -f backend/pom.xml spring-boot:run` starts the context on the default port
  `8080` and logs "Started Application in … seconds" within 30s.
- Pressing `Ctrl+C` shuts the context down gracefully (Spring Boot default behavior).
- A Spring Boot integration test class
  `com.cognizant.emk.multiagent.ApplicationContextSmokeTest` is annotated with
  `@SpringBootTest` and asserts that the context loads (`assertThat(context).isNotNull()`).
- Database auto-configuration is **disabled** at this stage so the test passes without a
  Postgres instance — either via `@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })`
  **temporarily** (with a TODO referencing EPIC-02) or by ensuring no JDBC driver is on the
  classpath yet.
- `mvn -f backend/pom.xml test` runs the smoke test green.

### Requirements coverage

`REQ-ARC-001`, `REQ-NFR-002`.

### Design references

§3 Project structure, §17 Build & deployment.

### Dependencies

US-01-001, US-01-002.

---

## US-01-004 — Externalized configuration & `ApplicationProperties`

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** every environment-specific value bound to a single typed `ApplicationProperties` record
**So that** adding new configuration is centralized and impossible to forget.

### Description

Create `application.yaml` with the `app.*` prefix and bind it to a single
`ApplicationProperties` Java record at startup. This story does not connect to any real
external system — it just sets up the config surface so future EPICs (LLM, security, MCP, rate
limiting) plug into a structure that already exists.

### Acceptance criteria

- `backend/src/main/resources/application.yaml` exists with at minimum the `app.api.base-path`,
  `app.cors.allowed-origins`, and a placeholder `app.security.jwt.lifetime` keys (further
  sub-keys are added by their owning EPICs).
- A Java record `com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties` is
  annotated with `@ConfigurationProperties(prefix = "app")` and registered via
  `@ConfigurationPropertiesScan` (or an explicit `@EnableConfigurationProperties`).
- The record uses nested records for each section (`Api`, `Cors`, `Security` …) — no
  `Map<String, Object>`, no field `@Autowired`, constructor binding only.
- Required environment variables documented in design §15 are listed in a short comment block
  at the top of `application.yaml` (even when not yet consumed).
- A unit test loads the record via `@SpringBootTest(properties = { … })` and asserts the
  expected values are bound.
- Defaults are sensible for local dev: `app.api.base-path=/api/v1`,
  `app.cors.allowed-origins=http://localhost:5173`.
- The application still boots when only the documented required env vars are absent (because
  the consuming EPICs are not yet active — fail-fast happens in their respective stories).

### Requirements coverage

`REQ-NFR-003`, `REQ-API-006`, `REQ-API-003`, `REQ-ARC-006`.

### Design references

§15 Configuration.

### Dependencies

US-01-003.

---

## US-01-005 — Centralized `/api/v1` base path

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the `/api/v1` prefix configured once, centrally, instead of repeated in every
`@RestController`
**So that** changing it in one place relocates every endpoint, per `REQ-API-006`.

### Description

Wire Spring MVC so that `app.api.base-path` from `ApplicationProperties` is automatically
prepended to every controller mapping. This story adds a tiny demonstration controller (kept
or removed at the developer's discretion) to prove the wiring works.

### Acceptance criteria

- A `WebConfig` class under `infrastructure/config/` implements `WebMvcConfigurer` and
  configures `configurePathMatch(...)` (or equivalent) to register the configured base path as
  a path prefix for **every** class annotated with `@RestController`.
- No `@RestController` in the codebase repeats `/api/v1` in its `@RequestMapping`.
- A throw-away controller (e.g., `PingController` mapping `GET /ping` → 200 `{ "ok": true }`)
  is reachable at `GET /api/v1/ping` and **not** at `GET /ping`. A `MockMvc` slice test asserts
  both. (The `PingController` may be deleted at the end of EPIC-01 or kept as a debug aid; if
  kept, it is marked `@Profile("dev")`.)
- Changing `app.api.base-path` in `application.yaml` to `/api/v2` and re-running the
  `MockMvc` slice test relocates the endpoint accordingly (validated by a parameterized test
  or two distinct test classes with `@TestPropertySource`).

### Requirements coverage

`REQ-API-006`.

### Design references

§6.1 Conventions, §15 Configuration.

### Dependencies

US-01-004.

---

## US-01-006 — Spring MVC + Security skeleton (open chain)

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** Spring MVC and Spring Security wired with an open filter chain
**So that** later EPICs slot real authentication filters into a chain that already exists, and
CORS already works for the configured origins.

### Description

Stand up the security `SecurityFilterChain` bean, the CORS configuration, and the JSON
content-type defaults. The chain is intentionally open (`permitAll()` everywhere) at this
stage — real authentication is delivered by EPIC-03 and EPIC-04. CSRF is disabled (we are a
stateless REST API).

### Acceptance criteria

- A `SpringSecurityConfig` class under `infrastructure/web/security/` declares a single
  `SecurityFilterChain` bean. The chain `permitAll()` on every request, disables CSRF, sets
  session creation policy to `STATELESS`, and applies the CORS configuration below.
- A `CorsConfigurationSource` bean reads `app.cors.allowed-origins` from
  `ApplicationProperties` and exposes them with the typical method/header allow-list for a
  REST API consumed by a SPA (`GET, POST, PUT, PATCH, DELETE, OPTIONS`; standard headers).
- A `MockMvc` test confirms that:
  - An unauthenticated `GET /api/v1/ping` returns 200 (no auth enforced yet).
  - A pre-flight `OPTIONS` request from `http://localhost:5173` is allowed; from
    `http://evil.example` it is rejected.
- The default content type for controller responses is `application/json; charset=UTF-8`
  (verified by inspecting the `Content-Type` header of the smoke endpoint).
- No real authentication or authorization is implemented in this story — comments in
  `SpringSecurityConfig` reference EPIC-03 (JWT), EPIC-04 (API key) and EPIC-13 (rate-limit
  filter) as the next plug points.

### Requirements coverage

`REQ-API-003`, `REQ-API-001`, `REQ-NFR-004`.

### Design references

§8.1 Filter chain, §15 Configuration.

### Dependencies

US-01-003.

---

## US-01-007 — Test infrastructure (JUnit 5 + AssertJ + Mockito)

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the test stack declared once with sensible defaults
**So that** every later story writes domain/application/infrastructure tests with no extra
plumbing.

### Description

Wire JUnit 5, AssertJ, and Mockito into the build, and add a tiny exemplar test for each of
the three layers (domain pure unit test, application Mockito test, infrastructure `@SpringBootTest`).
These exemplars serve as templates and as guards — if any of the three idioms breaks, the
build fails.

### Acceptance criteria

- `pom.xml` declares JUnit Jupiter 5.x, AssertJ, and Mockito (and the
  `spring-boot-starter-test` dependency, which already brings most of them transitively, in
  `<scope>test</scope>`).
- Surefire is configured to run JUnit 5; `mvn -f backend/pom.xml test` discovers and runs the
  exemplar tests.
- Three exemplar tests exist and pass:
  - `DomainExemplarTest` — pure JUnit 5 + AssertJ; instantiates a domain value object (e.g.,
    a placeholder from `domain/shared/`) and asserts immutability.
  - `ApplicationExemplarTest` — JUnit 5 + Mockito; verifies that a stub use case interacts
    with a mocked port the expected number of times.
  - `InfrastructureExemplarTest` — `@SpringBootTest` with `webEnvironment=NONE`; verifies the
    context loads (re-use or compose with `ApplicationContextSmokeTest` from US-01-003).
- AssertJ assertions are favored over JUnit's built-in assertions; a project Checkstyle rule
  or a documentation note in `JAVA-CODING-STANDARD.md` makes this explicit (Checkstyle
  enforcement is not required for v1 — the documentation note is enough).
- The build's test phase produces JUnit XML reports under `backend/target/surefire-reports/`.

### Requirements coverage

`REQ-NFR-002`, `REQ-ARC-006`.

### Design references

§18 Test strategy.

### Dependencies

US-01-001.

---

## US-01-008 — Architectural layering enforcement (ArchUnit)

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the `infrastructure → application → domain` rule enforced by the test suite
**So that** an accidental Spring import in the domain layer breaks the build instead of
sneaking in unnoticed.

### Description

Add an ArchUnit test that codifies the layering rule and the "no framework imports in the
domain" rule from design §3.2. This is a guard rail: every later EPIC inherits it for free.

### Acceptance criteria

- ArchUnit is on the `<scope>test</scope>` classpath.
- A test class `LayeringArchTest` (under `backend/src/test/java/.../arch/`) asserts:
  - Classes in `..domain..` may NOT depend on classes in `..application..` or
    `..infrastructure..`.
  - Classes in `..application..` may NOT depend on classes in `..infrastructure..`.
  - Classes in `..domain..` may NOT depend on packages `org.springframework..`,
    `jakarta.persistence..`, `org.hibernate..`, `com.fasterxml.jackson..`,
    `org.springframework.ai..`, or `lombok..`.
  - Classes in `..application..` may use Spring stereotypes (`@Service`, `@Transactional`)
    but NOT Spring MVC (`org.springframework.web..`) or JPA (`jakarta.persistence..`).
- Each rule has its own `@Test` method so failures are individually identifiable.
- A second test asserts the **package-by-context** convention: every class under
  `..domain..` lives in one of the documented bounded-context sub-packages
  (`shared`, `user`, `agent`, `conversation`, `tool`, `mcp`, `ratelimit`, `auth`).
- `mvn -f backend/pom.xml test` runs the ArchUnit tests; deliberately introducing a Spring
  import in a domain class causes the build to fail (verified manually before the story is
  closed).

### Requirements coverage

`REQ-ARC-002`, `REQ-ARC-003`, `REQ-ARC-006`, `REQ-ARC-007`.

### Design references

§2.1 Hexagonal style, §3.2 Layering rule.

### Dependencies

US-01-002, US-01-007.

---

## EPIC-01 Definition of Done

EPIC-01 is **Done** when, in addition to every story being individually `Done`:

- `mvn -f backend/pom.xml clean package` produces a runnable fat JAR.
- `java -jar backend/target/multi-agent-platform-*.jar` starts the empty context.
- `mvn -f backend/pom.xml test` runs all exemplar and ArchUnit tests green.
- The package skeleton documented in design §3 is fully present, with the layering rule
  enforced by ArchUnit.
- The configuration surface (`ApplicationProperties`, base path, CORS) is bound and used by a
  smoke endpoint.
- No business endpoint, no real authentication, and no database access are introduced — those
  belong to later EPICs.
