#!/usr/bin/env node
/**
 * Cross-platform freshness check for `src/generated/schema.d.ts`.
 *
 * Regenerates the file to a temporary location, compares it byte-for-byte against
 * the committed version, and exits non-zero on drift. Wired into `npm run verify`
 * so CI fails when the committed types fall behind `../openapi.yaml`.
 *
 * Implementation note (US-01-007): uses `execSync` with a shell-quoted command
 * string so the Windows path containing spaces (and a hyphen-with-spaces folder
 * name) survives spawn-time argument splitting.
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync, rmSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import process from 'node:process';

const __dirname = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(__dirname, '..');
const SPEC_PATH = resolve(FRONTEND_ROOT, '..', 'openapi.yaml');
const TARGET = resolve(FRONTEND_ROOT, 'src/generated/schema.d.ts');
const TMP = `${TARGET}.tmp`;

if (!existsSync(SPEC_PATH)) {
  console.error(`[gen:api:check] spec not found at ${SPEC_PATH}`);
  process.exit(2);
}
if (!existsSync(TARGET)) {
  console.error(
    `[gen:api:check] committed types not found at ${TARGET}.\n` +
      'Run `npm run gen:api` and commit the result.',
  );
  process.exit(2);
}

try {
  // Single quoted command string. Paths are wrapped in double quotes so
  // spaces/hyphens in the cwd path are preserved when the shell re-parses.
  const cmd = `npx openapi-typescript "${SPEC_PATH}" -o "${TMP}"`;
  execSync(cmd, { stdio: 'inherit', cwd: FRONTEND_ROOT });

  const committed = readFileSync(TARGET, 'utf8');
  const fresh = readFileSync(TMP, 'utf8');

  if (committed !== fresh) {
    console.error(
      '\n[gen:api:check] src/generated/schema.d.ts is OUT OF DATE relative to ../openapi.yaml.\n' +
        'Run `npm run gen:api` and commit the regenerated file.',
    );
    process.exit(1);
  }

  console.log('[gen:api:check] src/generated/schema.d.ts is up to date.');
} finally {
  if (existsSync(TMP)) {
    try {
      rmSync(TMP);
    } catch {
      /* best-effort cleanup */
    }
  }
}
