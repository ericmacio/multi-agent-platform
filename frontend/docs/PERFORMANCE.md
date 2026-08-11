# Performance — bundle budgets

The frontend v1 bundle budgets are set by SW-DESIGN §16.1:

| Metric                        | Hard cap | Soft warning |
|-------------------------------|----------|--------------|
| Initial chunk (gzip)          | 250 KB   | 200 KB       |
| Per-lazy-route chunk (gzip)   | 150 KB   | 120 KB       |

The **hard cap** exits the check with code `1` — even the developer-facing
`build:analyze` run refuses to succeed if a chunk busts the SW-DESIGN limit.
The **soft warning** is developer-facing only; EPIC-12 will convert it into
a CI gate.

## How to run

```
npm run build:analyze
```

That command:

1. Sets `ANALYZE=true` and calls `vite build`, which mounts
   `rollup-plugin-visualizer` and writes `dist/stats.html` — an interactive
   treemap of every module in the bundle.
2. Runs `scripts/check-bundle-budget.mjs`, which reads every emitted
   `dist/assets/*.js` file, computes its gzip size, and prints a per-chunk
   report to stdout.

To re-check without rebuilding:

```
npm run check:bundle
```

## Reading the treemap

Open `dist/stats.html` in a browser. The largest rectangles are the biggest
modules — usually the framework runtimes (`react-dom`, `@tanstack/react-query`)
and third-party libraries. Focus on:

- **Unexpectedly large chunks** — a page chunk over 100 KB gzip almost
  always pulls in a heavy dep that wasn't intended (a chart library, a full
  ICU polyfill, a barrel import that dragged the whole feature).
- **Duplicate copies** — two versions of the same module in different
  chunks indicate a code-splitting boundary problem.
- **Regressions vs. a previous run** — commit the `dist/stats.html` output
  into a scratch branch and compare treemaps by eye.

## Chunk classification

The budget script treats a JS asset as **initial** iff its filename starts
with `index-` (Vite's default entry-chunk prefix). Everything else in
`dist/assets/` counts as **lazy**. The `React.lazy()` splits in
`src/pages/routes.tsx` produce one lazy chunk per top-level route.

## Escalation

- **Soft warning (200 / 120 KB)** — investigate the treemap, decide whether
  the growth is warranted, land a fix if not. No CI fail.
- **Hard fail (250 / 150 KB)** — the change MUST be reverted or the chunk
  MUST be split before merge, per SW-DESIGN §16.1. EPIC-12's CI gate will
  enforce this automatically.

## What is NOT in scope for this story

- Uploading `dist/stats.html` to a shared dashboard. Local artifact only.
- Sentry / RUM. Deferred per SW-DESIGN §16.3.
- Bundling the browserslist check (Chromium ≥ 117, Firefox ≥ 117, Safari
  ≥ 16.4). That lives in EPIC-12.
