-- =============================================================================
-- V004__email_case_insensitive.sql
-- Make user email lookups and uniqueness case-insensitive
-- (Code Review #1 — HIGH finding US-CR1-001).
--
-- The domain Email value object canonicalizes to lowercase at construction
-- (Locale.ROOT). This migration enforces the same invariant at the database
-- boundary so that no caller can bypass it via direct JDBC: any existing row is
-- backfilled to lowercase, the plain unique constraint on users.email is
-- replaced by a functional unique index on lower(email).
--
-- citext was considered and rejected: it would require `create extension`
-- privileges that are not guaranteed on every target Postgres, and a functional
-- unique index gives the same guarantee without an extension dependency.
--
-- This migration is forward-only; no down script is shipped.
-- =============================================================================

-- Backfill any non-lowercase row to its canonical form. With a fresh DB this is
-- a no-op; on a long-lived DB it guarantees the new index can be built.
update users set email = lower(email) where email <> lower(email);

-- Drop the implicit unique constraint created by `email varchar(254) unique` in
-- V001. PostgreSQL names that constraint `users_email_key`.
alter table users drop constraint users_email_key;

-- Case-insensitive uniqueness: at most one row per lower(email).
create unique index ux_users_email_lower on users (lower(email));
