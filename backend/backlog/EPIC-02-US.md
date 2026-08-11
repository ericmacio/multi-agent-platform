# EPIC-02-US.md — User stories for EPIC-02

EPIC-02 — **Persistence foundation (PostgreSQL + Flyway)**

This file lists the user stories that deliver EPIC-02. The EPIC stands up the persistence
foundation: JDBC + JPA + Flyway wiring, the Testcontainers integration-test infrastructure,
the full database schema, the seed migrations, and the JPA entities + Spring Data JPA
interfaces that feature EPICs will use to persist data.

> **Scope split with feature EPICs.** The domain repository INTERFACES (e.g.
> `UserRepository`), the domain↔JPA MAPPERS, and the domain repository ADAPTERS naturally
> belong with the bounded context that owns each aggregate. They are therefore delivered by
> the corresponding feature EPICs (EPIC-04 / EPIC-05 / EPIC-06 / EPIC-10 / EPIC-13), each
> following the JPA-entity + Spring-Data-JPA-interface pattern shipped here. EPIC-02 confirms
> the pattern works end-to-end via a single example assertion in US-02-007 but does not
> ship every adapter speculatively.

## Conventions

- **ID format**: `US-02-<nnn>` — `02` matches the EPIC number; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`.
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the requirements coverage, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                            | Priority | Status | Depends on        |
|------------|------------------------------------------------------------------|----------|--------|-------------------|
| US-02-001  | Persistence dependencies & Spring Data JPA / Flyway wiring        | MUST     | Draft  | EPIC-01           |
| US-02-002  | Local PostgreSQL integration-test infrastructure                  | MUST     | Draft  | US-02-001         |
| US-02-003  | Init schema migration `V001__init_schema.sql`                     | MUST     | Draft  | US-02-001, 002    |
| US-02-004  | Seed migrations `V002__seed_admin.sql` + `V003__seed_rate_limit_config.sql` | MUST | Draft | US-02-003 |
| US-02-005  | JPA entity classes for every aggregate                            | MUST     | Draft  | US-02-003         |
| US-02-006  | Spring Data JPA repository interfaces                             | MUST     | Draft  | US-02-005         |
| US-02-007  | Cascade-rule integration test                                     | MUST     | Draft  | US-02-004, 005, 006 |

---

## US-02-001 — Persistence dependencies & Spring Data JPA / Flyway wiring

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the JDBC, JPA, PostgreSQL, and Flyway dependencies wired into the project, with
externally configurable connection settings
**So that** the application can connect to a real PostgreSQL database and the schema is
managed exclusively by Flyway.

### Description

Add the persistence dependency stack and configure it so Hibernate validates against the
schema rather than generating it (Flyway owns DDL). This story does not yet ship migrations
or entities — it sets up the wiring so subsequent stories can.

### Acceptance criteria

- `pom.xml` declares `spring-boot-starter-data-jpa`, `org.postgresql:postgresql` (runtime
  scope), and `org.flywaydb:flyway-core` plus `flyway-database-postgresql`.
- `application.yaml` exposes the production `spring.datasource.*` keys bound to the env vars
  `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. The application MUST fail fast at startup if these
  are missing in a non-test profile.
- `spring.jpa.hibernate.ddl-auto` is set to `validate` and `spring.flyway.enabled` is set
  to `true`, with `spring.flyway.locations=classpath:db/migration`. Both values are
  documented as load-bearing in a YAML comment.
- The EPIC-01 dummy `DataSourceAutoConfiguration` workaround is removed: Application.java
  no longer needs to suppress JDBC autoconfig (it now activates naturally).
- The shared test-only `src/test/resources/application.yaml` is updated so that **non-
  persistence tests** continue to boot without a database — by adding
  `DataSourceAutoConfiguration`, `JpaRepositoriesAutoConfiguration`, `HibernateJpaAutoConfiguration`,
  and `FlywayAutoConfiguration` to `spring.autoconfigure.exclude`. Persistence tests opt
  back in (US-02-002).
- All existing EPIC-01 tests still pass: `mvn test` reports 19/19 green.

### Requirements coverage

`REQ-PRS-001`, `REQ-PRS-002`, `REQ-PRS-004`, `REQ-NFR-003`.

### Design references

§5 Database schema (orientation), §15 Configuration (DB env vars).

### Dependencies

EPIC-01 must be `Done`.

---

## US-02-002 — Local PostgreSQL integration-test infrastructure

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a single, reusable base class that points integration tests at a real
**locally-installed** PostgreSQL server and lets each test class start from a clean
schema
**So that** every persistence-related test in this and later EPICs reuses the same
sandbox without duplicating setup.

### Description

The project specs forbid Docker in the local development environment ("Local environment
will not allow to use any docker container") but call out that "A postgreSQL server is
however available locally". Tests therefore connect to that local server through a
dedicated test database. The original SW-DESIGN §18 mention of Testcontainers is
superseded by this constraint.

### Acceptance criteria

- A test support class lives under `src/test/java/.../persistence/PostgresIntegrationTest.java`,
  declared `abstract`. It:
  - Carries a `@SpringBootTest` annotation that overrides
    `spring.datasource.url`, `username`, `password` from the env vars `TEST_DB_URL`,
    `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`, with documented localhost defaults
    (`jdbc:postgresql://localhost:5432/multi_agent_test`, `postgres`, `postgres`).
  - Re-enables `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration`,
    `DataJpaRepositoriesAutoConfiguration`, and `FlywayAutoConfiguration` (excluded by
    default in `src/test/resources/application.yaml` per US-02-001) by overriding the
    `spring.autoconfigure.exclude` property to keep only the unrelated Spring AI
    exclusions.
  - Enables `spring.flyway.clean-disabled=false` (test-only) and runs `Flyway.clean()`
    + `Flyway.migrate()` once per JVM in a `@BeforeAll` static hook so each test class
    starts from an empty, freshly-migrated schema.
- A trivial `PersistenceContextLoadTest` extends this support and asserts that the JPA
  `EntityManager`, the `DataSource`, and the Flyway bean are wired (no entities required
  yet — purely a context-load smoke test).
- A short note in the class Javadoc explains the prerequisite: a local PostgreSQL
  reachable at `localhost:5432` with a `multi_agent_test` database created (a one-line
  `CREATE DATABASE multi_agent_test;` SQL). The note also documents how to override the
  defaults via the three `TEST_DB_*` env vars.
- If the configured Postgres is unreachable, the test fails with a clear message naming
  the URL — not a generic Hibernate stack trace.

### Requirements coverage

`REQ-PRS-001`, `REQ-NFR-002`, `REQ-DEP-001` (no Docker required).

### Design references

§18 Test strategy (Testcontainers reference superseded by the SPECS no-Docker constraint).

### Dependencies

US-02-001.

---

## US-02-003 — Init schema migration `V001__init_schema.sql`

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** the full database schema described in design §5 codified as a Flyway migration
**So that** every environment (local dev, CI, AWS) provisions an identical, versioned
schema at startup.

### Description

Author `V001__init_schema.sql` matching the design exactly: every table, primary key,
foreign key, check constraint, unique constraint, and index. The migration is the contract
between the JPA entities (US-02-005) and the database; it is also what Hibernate will
validate against (`ddl-auto=validate`).

### Acceptance criteria

- The file lives at `src/main/resources/db/migration/V001__init_schema.sql`.
- It creates exactly the tables enumerated in design §5: `users`, `agents`, `agent_tools`,
  `agent_mcp_servers`, `agent_team`, `conversations`, `messages`, `api_keys`,
  `jwt_denylist`, `rate_limit_config`.
- All primary keys are UUID v4 with `default gen_random_uuid()` (the migration enables the
  `pgcrypto` extension if not present).
- All timestamp columns are `timestamptz` with `default now()` for `created_at` /
  `updated_at`.
- Cascade rules are exactly as documented in §5.2:
  - `users` → `agents` (cascade delete)
  - `agents` → `agent_tools`, `agent_mcp_servers`, `agent_team`, `conversations` (cascade)
  - `conversations` → `messages` (cascade)
  - `users` → `conversations` (cascade — supports the hard-delete rule of `REQ-USR-006`)
- Check constraints are present:
  - `users.role IN ('ADMIN','STANDARD')`
  - `agents.memory_size BETWEEN 1 AND 36`
  - `conversations.message_count BETWEEN 0 AND 64`
  - `messages.role IN ('USER','ASSISTANT')`
  - `agent_team.parent_agent_id <> member_agent_id`
  - `rate_limit_config.id = 1`
- Unique constraints: `users.email`, `(agents.owner_id, agents.name)`.
- Indexes: `idx_conversations_owner_created`, `idx_messages_conv_created`,
  `idx_jwt_denylist_expires`.
- A persistence integration test `InitSchemaMigrationTest` (extends the
  `PostgresIntegrationTest` base) asserts that:
  - Flyway has applied `V001` (querying `flyway_schema_history`).
  - Every documented table exists (queried via `information_schema.tables`).
  - The check constraint on `messages.role` rejects `INSERT INTO messages (..., role)
    VALUES ('TOOL', ...)` with a constraint-violation SQL error.

### Requirements coverage

`REQ-PRS-001`, `REQ-PRS-002`, `REQ-PRS-005`, `REQ-USR-001`, `REQ-USR-006`, `REQ-AGT-010`,
`REQ-AGT-013`, `REQ-CHAT-002`, `REQ-CHAT-008`, `REQ-CHAT-009`, `REQ-CHAT-010`,
`REQ-CHAT-012`, `REQ-AGT-002`, `REQ-AGT-004`.

### Design references

§5 Database schema (full DDL), §5.2 Cascade rules.

### Dependencies

US-02-001, US-02-002.

---

## US-02-004 — Seed migrations `V002__seed_admin.sql` + `V003__seed_rate_limit_config.sql`

- **Status**: Draft
- **Priority**: MUST

**As a** platform operator
**I want** the database to come pre-populated with a forced-password-change admin user and
the default rate-limit configuration row
**So that** a fresh deployment is immediately usable without manual SQL and the rate
limiter has a config row to read on the very first request.

### Description

Two seed migrations applied at first boot. The admin row is consumed by `REQ-USR-007`
(forced password change on first login). The rate-limit row is consumed by EPIC-13.

### Acceptance criteria

- `V002__seed_admin.sql` exists at `src/main/resources/db/migration/`. Using **Flyway
  placeholders** (`${app_bootstrap_admin_email}`, `${app_bootstrap_admin_password_hash}`),
  it inserts a single user row with `role='ADMIN'`, `disabled=false`,
  `must_change_password=true`. The application MUST fail fast at startup if either env
  var (`APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH`) is missing or
  empty in a non-test profile.
- Spring Boot is configured to map those two env vars to Flyway placeholders via
  `spring.flyway.placeholders.app_bootstrap_admin_email` and
  `spring.flyway.placeholders.app_bootstrap_admin_password_hash`.
- `V003__seed_rate_limit_config.sql` exists at the same location and inserts the single
  row `(id=1, per_minute=10, per_hour=50, updated_by=NULL)`.
- A `SeedMigrationsTest` (extends `PostgresIntegrationTest`) supplies dummy values for the
  two admin placeholders and asserts:
  - Exactly one admin user exists with the seeded email and `must_change_password=true`.
  - Exactly one rate-limit row exists with the documented defaults.
- The admin password hash placeholder accepts a BCrypt-shaped string (`^\$2[aby]\$.{56}$`)
  in the migration's typecheck; a non-conforming value still inserts (Flyway has no
  knowledge of BCrypt) but the design's expectation is documented in a SQL comment.

### Requirements coverage

`REQ-USR-007`, `REQ-RL-004`, `REQ-PRS-002`, `REQ-NFR-003`.

### Design references

§5.1 Flyway migrations.

### Dependencies

US-02-003.

---

## US-02-005 — JPA entity classes for every aggregate

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a JPA entity class per database table, exactly mirroring the schema
**So that** Hibernate's `validate` mode passes, and feature EPICs only need to write
domain↔JPA mappers (not entities).

### Description

Author the JPA entity classes under `infrastructure/persistence/entity/`. Per the
`JAVA-CODING-STANDARD.md`, JPA entities are NOT records (mutable identity is intrinsic to
JPA); they are POJO classes with constructor injection-friendly access. Lombok is not used.
Each entity matches its table column-for-column.

### Acceptance criteria

- The following entity classes exist under
  `infrastructure/persistence/entity/`: `UserJpa`, `AgentJpa`, `AgentToolJpa`,
  `AgentMcpJpa`, `AgentTeamJpa`, `ConversationJpa`, `MessageJpa`, `ApiKeyJpa`,
  `JwtDenylistJpa`, `RateLimitConfigJpa`.
- Every entity is annotated with `@Entity` and `@Table(name = "...")` matching
  `V001__init_schema.sql`.
- Column types match the SQL types: `UUID` for primary keys (`@Id`), `String` for
  `varchar`, `OffsetDateTime` for `timestamptz`, `Boolean` for `boolean`, `Integer` for
  `int`, `Double` for `double precision`. No use of `@Type` workarounds.
- Composite-key tables (`agent_tools`, `agent_mcp_servers`, `agent_team`) use
  `@IdClass` or `@EmbeddedId` records for their composite keys.
- Relationships between entities are modeled with explicit FK columns and
  `@ManyToOne(fetch = LAZY)`; no `@OneToMany` collection navigations are introduced
  unless the feature EPIC genuinely needs eager traversal (we keep the JPA model small
  and pull collections via repository queries instead).
- Every entity has a no-arg constructor (required by JPA) plus a constructor that takes
  the persisted state — both visibility-restricted as needed; equals/hashCode based on
  the primary key.
- A `HibernateValidateContractTest` (extends `PostgresIntegrationTest`) starts the full
  Spring context with `ddl-auto=validate`. The test passes only if Hibernate finds every
  declared entity in the live schema. **This is the load-bearing contract test of
  EPIC-02.**

### Requirements coverage

`REQ-PRS-001`, `REQ-PRS-002`, `REQ-PRS-005`, `REQ-USR-001`, `REQ-AGT-001`, `REQ-CHAT-009`,
`REQ-AUTH-007`.

### Design references

§4 Domain model, §5 Database schema, §5.2 Cascade rules.

### Dependencies

US-02-003.

---

## US-02-006 — Spring Data JPA repository interfaces

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** a Spring Data JPA interface per JPA entity, with only the methods strictly
needed at this stage
**So that** feature EPICs can plug their domain repository adapters into existing
infrastructure without re-deriving query plumbing.

### Description

Ship the Spring Data JPA interfaces under `infrastructure/persistence/springdata/` —
each extends `JpaRepository<EntityJpa, Id>` so feature EPICs immediately have CRUD plus
paging. Custom finder methods that depend on aggregate-specific business semantics
(`findByEmail`, `existsByOwnerIdAndName`, …) are NOT added speculatively — they accrue
naturally as EPIC-04 / EPIC-05 / EPIC-06 / EPIC-10 / EPIC-13 implement their adapters.

### Acceptance criteria

- The following interfaces exist under `infrastructure/persistence/springdata/`:
  `UserJpaRepository`, `AgentJpaRepository`, `AgentToolJpaRepository`,
  `AgentMcpJpaRepository`, `AgentTeamJpaRepository`, `ConversationJpaRepository`,
  `MessageJpaRepository`, `ApiKeyJpaRepository`, `JwtDenylistJpaRepository`,
  `RateLimitConfigJpaRepository`.
- Each extends `JpaRepository<EntityJpa, Id>` where `Id` matches the entity's primary key
  type (UUID for most; String for `ApiKeyJpa.clientId`; Short for `RateLimitConfigJpa.id`).
- A composite-key entity uses its `@EmbeddedId` / `@IdClass` type as the JPA `Id`
  parameter.
- A `RepositoriesContextTest` (extends `PostgresIntegrationTest`) asserts:
  - Every interface above is registered as a Spring bean (looked up by type).
  - For the simplest repository (`RateLimitConfigJpaRepository`), `findById((short) 1)`
    returns the seeded row from US-02-004.
- No business-specific finder methods are added at this stage — adding one is explicitly
  out of scope and a code review must reject such a PR until the consuming feature EPIC
  picks it up.

### Requirements coverage

`REQ-PRS-001`, `REQ-PRS-005`.

### Design references

§3 Project structure (`infrastructure/persistence/springdata/`), §5 Database schema.

### Dependencies

US-02-005.

---

## US-02-007 — Cascade-rule integration test

- **Status**: Draft
- **Priority**: MUST

**As a** backend developer
**I want** an automated test that proves the database-level cascades enforce the hard-
delete semantics of `REQ-USR-006` and `REQ-AGT-010`
**So that** future schema changes cannot silently break the cascade contract.

### Description

End-to-end persistence test using only the Spring Data JPA interfaces shipped in
US-02-006. It seeds a small graph (user → agent → conversation → message), then performs
the two cascading deletes, and verifies the descendants are gone. Because we have not yet
written domain↔JPA mappers, the test deals exclusively in `*Jpa` types.

### Acceptance criteria

- `CascadeIntegrationTest` lives under `src/test/java/.../persistence/` and extends
  `PostgresIntegrationTest`.
- Setup: persist a `UserJpa`, an `AgentJpa` owned by that user, a `ConversationJpa`
  referencing the agent and the user, two `MessageJpa` referencing the conversation.
- Test 1 — agent deletion cascade: delete the agent, then assert that the agent's
  conversations and the messages of those conversations no longer exist (count == 0).
- Test 2 — user deletion cascade: re-seed the graph, delete the user, then assert that
  agents, conversations, and messages owned by that user no longer exist (count == 0).
- Both tests use `@Transactional`-aware flushing or explicit `EntityManager.flush()` /
  `clear()` to make sure cascades have actually hit the database, not just the
  first-level cache.
- The test is independent of any feature-EPIC code and will continue to pass once the
  domain repository adapters of EPIC-04 / EPIC-05 / EPIC-06 / EPIC-10 are added.

### Requirements coverage

`REQ-USR-006`, `REQ-AGT-010`, `REQ-CHAT-008`, `REQ-PRS-003`.

### Design references

§5.2 Cascade rules.

### Dependencies

US-02-004, US-02-005, US-02-006.

---

## EPIC-02 Definition of Done

EPIC-02 is **Done** when, in addition to every story being individually `Done`:

- `mvn test` runs every existing EPIC-01 test green; persistence integration tests run
  green when a local PostgreSQL is reachable (a `multi_agent_test` database exists on
  `localhost:5432`).
- Starting the application against a real local PostgreSQL (no Docker) provisions the
  schema (`V001`), the seeded admin (`V002`), and the rate-limit config (`V003`) on first
  boot. A second start does not re-apply any migration.
- `spring.jpa.hibernate.ddl-auto=validate` succeeds at startup against the migrated schema
  — the `HibernateValidateContractTest` of US-02-005 is the executable proof.
- The JPA-entity ↔ Spring-Data-JPA-interface pattern is in place for every aggregate so
  feature EPICs only have to author their domain aggregate, repository interface, mapper,
  and adapter.
- The test/resources `application.yaml` keeps non-persistence tests fast (no Postgres
  required); persistence-needing tests opt into a local PostgreSQL via the
  `PostgresIntegrationTest` support shipped in US-02-002.
