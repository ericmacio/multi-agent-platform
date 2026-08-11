-- =============================================================================
-- V005__conversation_owner_split.sql
-- EPIC-10 / US-10-002.
--
-- Replace conversations.owner_id with mutually-exclusive owner_user_id /
-- owner_client_id columns so SYSTEM principals (REQ-AUTH-007 / design §8.6) can
-- own conversations alongside JWT-authenticated end users. The two columns are
-- XOR-constrained by ck_conversations_owner_xor: exactly one is non-null.
--
-- Forward-only; no down-migration. The migration is idempotent against an empty
-- conversations table (production state at v1) and round-trips existing
-- owner_id values into owner_user_id (defensive — handles dev databases that
-- carry hand-created test rows).
--
-- Hibernate ddl-auto=validate boots green against this schema only after
-- US-10-003 reworks ConversationJpa to map the new columns. The two stories
-- land together in the same change set.
-- =============================================================================

-- 1. Add the two new nullable columns.
alter table conversations
    add column owner_user_id   uuid         references users(id)          on delete cascade,
    add column owner_client_id varchar(64)  references api_keys(client_id) on delete cascade;

-- 2. Backfill existing rows (no-op against an empty table; defensive against
--    dev databases that already have hand-created conversations).
update conversations set owner_user_id = owner_id where owner_id is not null;

-- 3. Drop the legacy index that referenced the old single owner_id column.
drop index if exists idx_conversations_owner_created;

-- 4. Drop the legacy column (the FK to users(id) goes with it).
alter table conversations drop column owner_id;

-- 5. Enforce the XOR invariant — exactly one owner column is populated per row.
alter table conversations
    add constraint ck_conversations_owner_xor
    check ((owner_user_id is not null and owner_client_id is null)
        or (owner_user_id is null and owner_client_id is not null));

-- 6. Per-owner-type indexes for the listByOwner read paths (US-10-006).
--    Partial indexes keep each index tight (no rows of the wrong owner type).
create index idx_conversations_user_created
    on conversations (owner_user_id, created_at desc, id desc)
    where owner_user_id is not null;

create index idx_conversations_client_created
    on conversations (owner_client_id, created_at desc, id desc)
    where owner_client_id is not null;

-- 7. Agent-scoped index for the optional ?agentId= filter on GET /conversations
--    (US-10-006). The optimizer can bitmap-AND this with the per-owner partial
--    index when both are usable.
create index idx_conversations_agent_created
    on conversations (agent_id, created_at desc, id desc);
