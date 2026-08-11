-- =============================================================================
-- V003__seed_rate_limit_config.sql
-- Default global rate-limit configuration (REQ-RL-004): 10 req/min and
-- 50 req/hour. Admins can change these values at runtime via
-- PUT /api/v1/admin/rate-limit (EPIC-13); the row id is fixed at 1 because
-- the table is single-row by design.
-- =============================================================================

insert into rate_limit_config (id, per_minute, per_hour)
values (1, 10, 50);
