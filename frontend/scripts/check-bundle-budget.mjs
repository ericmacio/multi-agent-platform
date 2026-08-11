#!/usr/bin/env node
/**
 * Post-build budget check for the Vite output. Emits a soft warning above
 * 200 KB gzip on the initial chunk (or 120 KB per lazy route) and a hard
 * failure above the SW-DESIGN §16.1 thresholds (250 KB / 150 KB).
 *
 * The "initial chunk" is heuristically defined as the biggest JS asset
 * whose filename starts with `index-` (Vite's default entry-chunk name).
 * Lazy routes are every other emitted JS asset in `dist/assets/`.
 *
 * EPIC-11 (this story) uses only soft warnings + hard fails on the
 * 250 KB / 150 KB caps. EPIC-12 will flip the SOFT thresholds to hard
 * fails once CI-side gating lands.
 */
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIST_DIR = path.resolve(HERE, '..', 'dist');
const ASSETS_DIR = path.join(DIST_DIR, 'assets');

const INITIAL_SOFT_KB = 200;
const INITIAL_HARD_KB = 250;
const LAZY_SOFT_KB = 120;
const LAZY_HARD_KB = 150;

/** @param {number} kb */
function fmtKB(kb) {
  return `${kb.toFixed(1)} KB`;
}

/** @param {string} label @param {number} kb @param {number} soft @param {number} hard */
function report(label, kb, soft, hard) {
  if (kb > hard) {
    console.error(
      `error: ${label} gzip is ${fmtKB(kb)} (hard budget ${hard} KB)`,
    );
    return 'hard';
  }
  if (kb > soft) {
    console.warn(
      `warn:  ${label} gzip is ${fmtKB(kb)} (soft budget ${soft} KB)`,
    );
    return 'soft';
  }
  console.log(`ok:    ${label} gzip is ${fmtKB(kb)}`);
  return 'ok';
}

async function main() {
  try {
    await fs.access(ASSETS_DIR);
  } catch {
    console.error(
      `error: ${ASSETS_DIR} not found — run \`npm run build\` before this script.`,
    );
    process.exit(2);
  }

  const entries = await fs.readdir(ASSETS_DIR);
  const jsFiles = entries.filter((f) => f.endsWith('.js'));

  if (jsFiles.length === 0) {
    console.error('error: no .js assets found in dist/assets — build output looks empty.');
    process.exit(2);
  }

  /** @type {{ file: string; kb: number; kind: 'initial' | 'lazy' }[]} */
  const measurements = [];
  for (const file of jsFiles) {
    const buf = await fs.readFile(path.join(ASSETS_DIR, file));
    const kb = gzipSync(buf).byteLength / 1024;
    const kind = file.startsWith('index-') ? 'initial' : 'lazy';
    measurements.push({ file, kb, kind });
  }

  // Sort so the biggest chunks show first per kind — easier to read.
  measurements.sort((a, b) => b.kb - a.kb);

  let hardBreach = false;
  console.log('Bundle budget report (gzip)');
  console.log('---------------------------');
  for (const m of measurements) {
    const label = `${m.kind === 'initial' ? '[initial]' : '[lazy]   '} ${m.file}`;
    const soft = m.kind === 'initial' ? INITIAL_SOFT_KB : LAZY_SOFT_KB;
    const hard = m.kind === 'initial' ? INITIAL_HARD_KB : LAZY_HARD_KB;
    const outcome = report(label, m.kb, soft, hard);
    if (outcome === 'hard') hardBreach = true;
  }

  if (hardBreach) {
    console.error(
      '\nOne or more chunks exceed the hard budget from SW-DESIGN §16.1. ' +
        'Fix them before shipping.',
    );
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(2);
});
