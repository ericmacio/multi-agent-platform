-- =============================================================================
-- V002__seed_admin.sql
-- Bootstraps the very first admin account (REQ-USR-007).
--
-- Both placeholders are required and are populated by Spring Boot from the env
-- vars APP_BOOTSTRAP_ADMIN_EMAIL / APP_BOOTSTRAP_ADMIN_PASSWORD_HASH (see the
-- spring.flyway.placeholders.* keys in application.yaml). The application
-- fails fast at startup if either env var is missing or empty in a non-test
-- profile.
--
-- The password hash is expected to be a BCrypt string (^\$2[aby]\$.{56}$). We
-- do not enforce that shape in SQL — Flyway has no knowledge of BCrypt — but
-- any non-conforming value will be rejected at first sign-in by the
-- PasswordHasher port (EPIC-03).
--
-- must_change_password=true forces the password-change flow on first login.
-- =============================================================================

insert into users (email, password_hash, role, disabled, must_change_password)
values ('${app_bootstrap_admin_email}', '${app_bootstrap_admin_password_hash}', 'ADMIN', false, true);
