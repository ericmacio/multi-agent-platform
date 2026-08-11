-- =============================================================================
-- V001__init_schema.sql
-- Multi-Agent Platform — initial PostgreSQL schema (EPIC-02 / SW-DESIGN §5).
-- Hibernate runs in `validate` mode against this schema; the JPA entities in
-- infrastructure/persistence/entity/ MUST match column-for-column.
-- =============================================================================

-- gen_random_uuid() lives in pgcrypto.
create extension if not exists pgcrypto;

-- -----------------------------------------------------------------------------
-- users
-- -----------------------------------------------------------------------------
create table users (
    id                   uuid          primary key default gen_random_uuid(),
    email                varchar(254)  not null unique,
    password_hash        varchar(72)   not null,
    role                 varchar(16)   not null check (role in ('ADMIN', 'STANDARD')),
    disabled             boolean       not null default false,
    must_change_password boolean       not null default false,
    created_at           timestamptz   not null default now(),
    updated_at           timestamptz   not null default now()
);

-- -----------------------------------------------------------------------------
-- agents (owned by a user; cascade delete from owner)
-- -----------------------------------------------------------------------------
create table agents (
    id                uuid             primary key default gen_random_uuid(),
    owner_id          uuid             not null references users(id) on delete cascade,
    name              varchar(32)      not null,
    description       varchar(1024)    not null,
    system_prompt     varchar(1024)    not null,
    memory_size       integer          not null default 12 check (memory_size between 1 and 36),
    llm_model         varchar(64),
    temperature       double precision,
    max_output_tokens integer,
    top_p             double precision,
    created_at        timestamptz      not null default now(),
    updated_at        timestamptz      not null default now(),
    unique (owner_id, name)
);

-- -----------------------------------------------------------------------------
-- agent_tools — per-agent attached tool names (validated at write time against
-- the static tool catalog; no DB-level FK to a catalog table).
-- -----------------------------------------------------------------------------
create table agent_tools (
    agent_id  uuid         not null references agents(id) on delete cascade,
    tool_name varchar(64)  not null,
    primary key (agent_id, tool_name)
);

-- -----------------------------------------------------------------------------
-- agent_mcp_servers — per-agent enabled MCP server names (validated at write
-- time against the configured set; no DB-level FK).
-- -----------------------------------------------------------------------------
create table agent_mcp_servers (
    agent_id        uuid         not null references agents(id) on delete cascade,
    mcp_server_name varchar(64)  not null,
    primary key (agent_id, mcp_server_name)
);

-- -----------------------------------------------------------------------------
-- agent_team — flat (single-level) team membership; nesting is enforced in
-- application code per REQ-AGT-013.
-- -----------------------------------------------------------------------------
create table agent_team (
    parent_agent_id uuid not null references agents(id) on delete cascade,
    member_agent_id uuid not null references agents(id) on delete cascade,
    primary key (parent_agent_id, member_agent_id),
    check (parent_agent_id <> member_agent_id)
);

-- -----------------------------------------------------------------------------
-- conversations
-- owner_id is denormalized (== agents.owner_id) so the user-delete cascade
-- can hit conversations directly per REQ-USR-006.
-- -----------------------------------------------------------------------------
create table conversations (
    id            uuid         primary key default gen_random_uuid(),
    agent_id      uuid         not null references agents(id) on delete cascade,
    owner_id      uuid         not null references users(id)  on delete cascade,
    title         varchar(32),
    message_count integer      not null default 0 check (message_count between 0 and 64),
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);
create index idx_conversations_owner_created on conversations (owner_id, created_at desc, id desc);

-- -----------------------------------------------------------------------------
-- messages — only USER and ASSISTANT messages are persisted (REQ-CHAT-012).
-- -----------------------------------------------------------------------------
create table messages (
    id              uuid         primary key default gen_random_uuid(),
    conversation_id uuid         not null references conversations(id) on delete cascade,
    role            varchar(16)  not null check (role in ('USER', 'ASSISTANT')),
    content         varchar(1024) not null,
    created_at      timestamptz  not null default now()
);
create index idx_messages_conv_created on messages (conversation_id, created_at, id);

-- -----------------------------------------------------------------------------
-- api_keys — machine-to-machine credentials. The cleartext key is shown once
-- at creation (REQ-AUTH-007) and persisted only as a BCrypt hash.
-- -----------------------------------------------------------------------------
create table api_keys (
    client_id    varchar(64)  primary key,
    api_key_hash varchar(72)  not null,
    label        varchar(128),
    disabled     boolean      not null default false,
    created_at   timestamptz  not null default now()
);

-- -----------------------------------------------------------------------------
-- jwt_denylist — bounded by JWT lifetime (REQ-AUTH-011). v1 single-node
-- deployments use the in-memory adapter; this table exists for the design's
-- future Redis/DB-backed swap-in (TBD-1).
-- -----------------------------------------------------------------------------
create table jwt_denylist (
    jti        uuid         primary key,
    expires_at timestamptz  not null
);
create index idx_jwt_denylist_expires on jwt_denylist (expires_at);

-- -----------------------------------------------------------------------------
-- rate_limit_config — single-row table updated live by admin (REQ-RL-004).
-- -----------------------------------------------------------------------------
create table rate_limit_config (
    id          smallint     primary key default 1 check (id = 1),
    per_minute  integer      not null,
    per_hour    integer      not null,
    updated_at  timestamptz  not null default now(),
    updated_by  uuid         references users(id)
);
