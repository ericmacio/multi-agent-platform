-- =============================================================================
-- V900__bump_rate_limit_for_tests.sql
--
-- Test-only override: bumps the seeded rate-limit row to non-throttling values
-- so the broader integration suite (admin, agent, conversation, auth tests) is
-- not denied by the global Bucket4j filter introduced by EPIC-13.
--
-- The 10 req/min production default (V003) would deny most test classes mid-run
-- because Spring Test caches contexts and the in-JVM bucket is shared across
-- requests in the same context.
--
-- This file lives under classpath:db/migration-test/, NOT classpath:db/migration/,
-- so it is loaded ONLY by tests whose spring.flyway.locations includes the
-- migration-test path (the default in src/test/resources/application.yaml).
-- Tests that need to assert the production seed values explicitly override
-- spring.flyway.locations to drop this path.
-- =============================================================================

update rate_limit_config set per_minute = 100000, per_hour = 1000000 where id = 1;
