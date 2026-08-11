# EPIC-01-US.md — User stories for EPIC-01 (Project foundation & tooling)

This file lists the user stories that deliver **EPIC-01 — Project foundation & tooling** of
the frontend, as defined in `frontend/backlog/EPICS.md`. The EPIC delivers no business
endpoint; it produces the scaffold every other EPIC builds on: a runnable Vite + React +
TypeScript project with the layering rule enforced, design tokens wired into Tailwind, the
API type-generation pipeline in place, and the lint / test / format toolchain ready.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-01-<nnn>` — `01` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All ten stories are `MUST` (they implement the
  foundation EPIC).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design references,
  and its dependencies.

## Story list

| ID         | Title                                                                       | Priority | Status | Depends on              |
|------------|-----------------------------------------------------------------------------|----------|--------|-------------------------|
| US-01-001  | npm project scaffold (package.json, .nvmrc, .gitignore, lockfile)           | MUST     | Done   | —                       |
| US-01-002  | TypeScript strict configuration (`tsconfig.json` + `tsconfig.node.json`)    | MUST     | Done   | US-01-001               |
| US-01-003  | Vite project with `@` path alias, dev server & `/api` proxy                 | MUST     | Done   | US-01-001, US-01-002    |
| US-01-004  | Tailwind CSS + design tokens + Geist fonts                                  | MUST     | Done   | US-01-001, US-01-003    |
| US-01-005  | Application entry point: `index.html`, `main.tsx`, `App.tsx`, router shell  | MUST     | Done   | US-01-001..004          |
| US-01-006  | Environment-variable module (`src/env.ts`, `.env.example`, Zod validation)  | MUST     | Done   | US-01-001, US-01-002    |
| US-01-007  | OpenAPI type-generation pipeline (`npm run gen:api`) + CI freshness check   | MUST     | Done   | US-01-001, US-01-002    |
| US-01-008  | ESLint + Prettier with layering rule (`pages → features → shared → generated`) | MUST  | Draft  | US-01-001, US-01-002    |
| US-01-009  | Vitest + RTL + MSW v2 test infrastructure + smoke test                      | MUST     | Done   | US-01-001..005          |
| US-01-010  | `npm run verify` aggregator script                                          | MUST     | Done   | US-01-001, US-01-007..009 |

---

## US-01-001 — npm project scaffold

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** an initialized npm project at `frontend/` with `package.json`, `.nvmrc`,
`.gitignore`, and a committed lockfile
**So that** every subsequent story has a stable place to declare its dependencies and a
shared Node version contract.

### Description

This is the very first slice: nothing else can be added until `package.json` exists.
The story also locks the **Node version** (20 LTS) via `.nvmrc` and `engines` so a
developer on Node 18 or 22 fails loudly instead of silently producing a different
dependency tree.

The package manager is **npm** with the lockfile checked in, per `frontend/CLAUDE.md`.
Yarn and pnpm are not introduced.

### Acceptance criteria

- `frontend/package.json` exists with:
  - `"name": "multi-agent-platform-frontend"`,
  - `"private": true`,
  - `"type": "module"`,
  - `"version": "0.1.0"`,
  - `"engines": { "node": ">=20.0.0 <21.0.0" }`,
  - empty `"dependencies"` and `"devDependencies"` objects (populated by later stories),
  - a placeholder `"scripts": { "verify": "echo 'pending US-01-010'" }` that will be
    overwritten by `US-01-010` — present so contributors discover the canonical entry
    point immediately.
- `frontend/.nvmrc` contains exactly `20` on a single line.
- `frontend/.gitignore` contains at least: `node_modules/`, `dist/`, `coverage/`,
  `.env`, `.env.local`, `.vite/`, `*.local`, `.DS_Store`, `src/generated/` is **not**
  ignored (generated code is checked in — see US-01-007).
- `frontend/package-lock.json` exists and is committed (produced by an `npm install` run
  even on the empty dependency set; this baseline lockfile is what later stories will
  amend, never delete).
- Running `npm install` in `frontend/` completes successfully with no warnings about
  Node-version mismatch on a Node 20 machine.
- `npm install` on Node 18 or 22 fails with the engine-mismatch error from `engines`
  (verified manually; no automated CI gate for this story).

### Out of scope

- Any source files under `src/` — those come in US-01-003 and later.
- TypeScript configuration — US-01-002.
- Any runtime dependency (React, Vite, Tailwind, …) — added by the stories that need them.

### Design references

- `frontend/design/SW-DESIGN.md` §3 (tech stack — npm, Node 20), §4 (project structure —
  root-level files).
- `frontend/CLAUDE.md` ("Package manager — npm (lockfile checked in)").

### Dependencies

- None.

---

## US-01-002 — TypeScript strict configuration

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** TypeScript installed at strict mode with `noUncheckedIndexedAccess` enabled and
a `@` path alias mapping to `src/`
**So that** every later story writes type-safe code under a consistent compiler contract,
and so that imports stay stable as the tree grows.

### Description

Two `tsconfig` files are needed because the Vite config (`vite.config.ts`) runs under Node
(types from `vite/client`, `node`, no DOM lib), while the application code runs under the
browser. This is the canonical Vite split: a project-references setup where the root
`tsconfig.json` references `tsconfig.node.json` for build-tool files.

`noUncheckedIndexedAccess` is included because the spec uses cursor pagination and
discriminated unions extensively; the option catches a whole class of `string | undefined`
bugs at compile time. This is a non-default but deliberate choice fixed in SW-DESIGN §3.

### Acceptance criteria

- `frontend/package.json` adds `typescript` as a `devDependency` (version `^5.4.0` or
  later within `5.x`).
- `frontend/tsconfig.json` exists with:
  - `"strict": true`,
  - `"noUncheckedIndexedAccess": true`,
  - `"target": "ES2022"`,
  - `"module": "ESNext"`, `"moduleResolution": "Bundler"`,
  - `"jsx": "react-jsx"`,
  - `"lib": ["ES2022", "DOM", "DOM.Iterable"]`,
  - `"esModuleInterop": true`, `"isolatedModules": true`,
  - `"skipLibCheck": true`, `"forceConsistentCasingInFileNames": true`,
  - `"resolveJsonModule": true`,
  - `"baseUrl": "."`, `"paths": { "@/*": ["src/*"] }`,
  - `"include": ["src", "vite-env.d.ts"]`,
  - `"references": [{ "path": "./tsconfig.node.json" }]`,
  - `"noEmit": true` (Vite handles emit).
- `frontend/tsconfig.node.json` exists with:
  - `"composite": true`, `"skipLibCheck": true`,
  - `"module": "ESNext"`, `"moduleResolution": "Bundler"`,
  - `"allowSyntheticDefaultImports": true`,
  - `"include": ["vite.config.ts"]`.
- `frontend/src/vite-env.d.ts` exists with `/// <reference types="vite/client" />`.
- Running `npx tsc -b` from `frontend/` succeeds (no source files yet → zero errors).

### Out of scope

- Vite configuration itself — US-01-003.
- Path alias resolution at runtime — added in US-01-003 (Vite's `resolve.alias`).
- `@types/react` / `@types/react-dom` — added with React in US-01-005.

### Design references

- `frontend/design/SW-DESIGN.md` §3 (TypeScript 5.x strict, `noUncheckedIndexedAccess`),
  §4 (`@` path alias).
- `frontend/CLAUDE.md` ("Language — TypeScript (strict mode)").

### Dependencies

- US-01-001.

---

## US-01-003 — Vite project with `@` alias, dev server & `/api` proxy

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** Vite 5 installed and configured with the `@` alias resolved at runtime, the dev
server bound to port `5173`, and a `/api` proxy forwarding to `http://localhost:8080`
**So that** I can `npm run dev` against the local backend without CORS friction, and so
that future imports like `import x from '@/shared/api/client'` resolve correctly both at
build time (US-01-002) and at runtime.

### Description

The CLAUDE.md and SW-DESIGN both pin Vite as the build tool. The dev-proxy is a fallback
for the case where the backend's CORS allow-list does not include `http://localhost:5173`
(per `REQ-API-003`, CORS is configurable; the proxy makes local dev work regardless of
that configuration). The `VITE_API_BASE_URL` env var defaults to `/api/v1` in development
when the proxy is in use; production builds use the absolute backend URL — see US-01-006.

### Acceptance criteria

- `frontend/package.json` adds the following `devDependencies`:
  - `vite ^5.0.0`,
  - `@vitejs/plugin-react ^4.0.0`.
- `frontend/vite.config.ts` exists with:
  - `defineConfig({ plugins: [react()], ... })`,
  - `resolve.alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }`,
  - `server: { port: 5173, strictPort: true, proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }`,
  - `build: { outDir: 'dist', sourcemap: true }`,
  - `define: { __APP_VERSION__: JSON.stringify(process.env.VITE_BUILD_VERSION ?? 'dev') }`.
- A `"dev": "vite"` script is added to `package.json` (alongside the existing placeholder
  `verify` from US-01-001).
- A `"preview": "vite preview --port 4173 --strictPort"` script is added.
- A `"build": "tsc -b && vite build"` script is added.
- Running `npm run dev` in `frontend/` starts the dev server on `http://localhost:5173`
  without errors. (The page may 404 since `index.html` is added in US-01-005 — that's
  acceptable for this story; the server starting cleanly is the gate.)
- The `tsconfig.node.json` from US-01-002 already includes `vite.config.ts`; `npx tsc -b`
  still succeeds.

### Out of scope

- `index.html` and any React entry — US-01-005.
- Tailwind plugin / PostCSS — US-01-004.
- Bundle-budget plugin (`vite-plugin-bundle-visualizer`) — EPIC-11 / EPIC-12.

### Design references

- `frontend/design/SW-DESIGN.md` §3 (Vite 5+), §4 (project structure root), §14.3 (Vite
  configuration — alias, proxy, `define`).

### Dependencies

- US-01-001, US-01-002.

---

## US-01-004 — Tailwind CSS + design tokens + Geist fonts

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** Tailwind CSS configured to read color tokens from CSS variables defined in
`src/styles/tokens.css`, and the Geist + Geist Mono fonts loaded via the `geist` npm
package
**So that** later stories can write `bg-surface text-primary` instead of hardcoded hex
values, and so that the "Dark Professional" visual identity from `frontend/CLAUDE.md` is
live on the first render.

### Description

The CSS tokens are **copied verbatim from `frontend/CLAUDE.md`** (the single source of
truth for the visual identity). Tailwind's `theme.extend.colors` reads them via
`var(--color-...)`, so a token change in the CSS file flows through every Tailwind class
without a config edit.

The `geist` npm package ships both the sans and mono families as self-hosted woff2 files
with side-effect CSS, which means no Google Fonts request at runtime (better for privacy
and offline development).

### Acceptance criteria

- `frontend/package.json` adds the following `devDependencies`:
  - `tailwindcss ^3.4.0`,
  - `postcss ^8.4.0`,
  - `autoprefixer ^10.4.0`.
- And the following `dependencies`:
  - `geist ^1.3.0`.
- `frontend/tailwind.config.ts` exists with:
  - `content: ['./index.html', './src/**/*.{ts,tsx}']`,
  - `theme.extend.colors` mapping every token from CLAUDE.md to `var(--color-<name>)`
    (bg-{base,surface,elevated,subtle}, border-{default,accent,focus},
    text-{primary,secondary,muted,disabled}, accent, accent-bg, accent-dim,
    success{-bg}, info{-bg}, warning{-bg}, danger{-bg}),
  - `theme.extend.fontFamily: { sans: ['var(--font-sans)', ...defaultTheme.fontFamily.sans], mono: ['var(--font-mono)', ...defaultTheme.fontFamily.mono] }`,
  - `theme.extend.borderRadius: { sm: '6px', md: '8px', lg: '12px' }` (per SW-DESIGN §11.3).
- `frontend/postcss.config.js` exists with `{ plugins: { tailwindcss: {}, autoprefixer: {} } }`.
- `frontend/src/styles/tokens.css` exists with a single `:root { ... }` block containing
  **every** CSS variable from the "Color palette" section of `frontend/CLAUDE.md`,
  byte-identical to the values listed there. Plus:
  - `--font-sans: 'Geist', system-ui, sans-serif;`,
  - `--font-mono: 'Geist Mono', 'Fira Code', monospace;`.
- `frontend/src/styles/globals.css` exists with:
  - `@import './tokens.css';`,
  - `@import 'geist/font/sans';`, `@import 'geist/font/mono';`,
  - the Tailwind directives `@tailwind base; @tailwind components; @tailwind utilities;`,
  - a `body { @apply bg-bg-base text-text-primary font-sans; }` rule so the dark
    background is visible from the first paint.
- A unit assertion (manual, since there is no test runner yet): after US-01-005 lands,
  `npm run dev` shows the dark background and Geist font on the placeholder page.

### Out of scope

- Design-system primitives (`Button`, `Input`, …) — EPIC-02.
- A light theme — TBD-F1 in SW-DESIGN §17.
- Loading icons / `lucide-react` — EPIC-02.

### Design references

- `frontend/design/SW-DESIGN.md` §11.1 (tokens), §11.2 (typography), §11.3 (spacing/radii).
- `frontend/CLAUDE.md` (full color palette, font choices).

### Dependencies

- US-01-001, US-01-003.

---

## US-01-005 — Application entry point: `index.html`, `main.tsx`, `App.tsx`, router shell

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a minimal runnable React app — `index.html`, `main.tsx` with `createRoot`,
`App.tsx` mounted under a `<RouterProvider>`, and a single placeholder home route
**So that** `npm run dev` renders an actual page (dark-themed, Geist-typeset) and so that
later stories can register routes by editing `pages/routes.tsx` instead of having to
introduce React Router as a separate refactor.

### Description

This is the smallest possible "hello world" that proves the toolchain end-to-end: TS
strict mode + Vite + Tailwind + tokens + fonts + React + Router. Nothing in this story is
business logic — the placeholder page is a single centered heading. The router is wired
**with the data-router API** (`createBrowserRouter` + `<RouterProvider>`), not the
legacy `<BrowserRouter>` element API, because the route map in SW-DESIGN §5 uses
loaders/`<Outlet>` patterns that the data-router supports cleanly.

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `react ^18.3.0`,
  - `react-dom ^18.3.0`,
  - `react-router-dom ^6.22.0`.
- And the following `devDependencies`:
  - `@types/react ^18.3.0`,
  - `@types/react-dom ^18.3.0`.
- `frontend/index.html` exists at the project root with:
  - `<!doctype html>`,
  - `<html lang="en">`,
  - `<title>Multi-Agent Platform</title>`,
  - a single `<div id="root"></div>`,
  - a `<script type="module" src="/src/main.tsx"></script>` tag,
  - the meta viewport tag for responsive scaling.
- `frontend/src/main.tsx` exists with:
  - `import './styles/globals.css';`,
  - `import React from 'react';`, `import ReactDOM from 'react-dom/client';`,
  - `ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);`.
- `frontend/src/App.tsx` exists with `<RouterProvider router={router} />` where `router`
  comes from `pages/routes.tsx`.
- `frontend/src/pages/routes.tsx` exists with:
  - `export const router = createBrowserRouter([{ path: '/', element: <HomePlaceholder /> }, { path: '*', element: <NotFoundPlaceholder /> }]);`.
- `frontend/src/pages/HomePlaceholder.tsx` exists rendering a centered heading
  `Multi-Agent Platform` and a caption `Frontend scaffold — EPIC-01`.
- `frontend/src/pages/NotFoundPlaceholder.tsx` exists rendering "404 — Not found".
- Running `npm run dev` and opening `http://localhost:5173` shows:
  - the dark `--color-bg-base` background,
  - the heading rendered in the Geist sans font,
  - no console errors / warnings (Strict Mode included).
- Running `npm run build` produces a `dist/` directory containing `index.html` and a
  hashed JS chunk; `npm run preview` serves it cleanly.

### Out of scope

- All the real pages (`LoginPage`, `AgentsPage`, …) — their EPICs.
- The `AuthShell` / `AppShell` layouts and route guards — EPIC-02.
- The proactive 30 s-before-expiry banner and `AuthContext` — EPIC-02.

### Design references

- `frontend/design/SW-DESIGN.md` §4 (project structure — `main.tsx`, `App.tsx`,
  `pages/routes.tsx`), §5.1 (route map — the structure that this placeholder seeds).

### Dependencies

- US-01-001, US-01-002, US-01-003, US-01-004.

---

## US-01-006 — Environment-variable module (`src/env.ts`, `.env.example`, Zod validation)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `src/env.ts` module that parses `import.meta.env` against a Zod schema
at app boot and exposes the validated values as a typed `env` object
**So that** a missing or malformed `VITE_API_BASE_URL` produces a loud, actionable error
the first time the bundle runs instead of a silent `undefined` reaching the typed API
client.

### Description

Vite only exposes env vars prefixed `VITE_`. The schema mirrors the matrix in
SW-DESIGN §14.2: `VITE_API_BASE_URL` (default `http://localhost:8080/api/v1`),
`VITE_APP_NAME` (default `Multi-Agent Platform`), `VITE_BUILD_VERSION` (default `dev`).

The module is the **only** place in the codebase that reads `import.meta.env`. An ESLint
restriction (added in US-01-008) forbids any other file from doing so, to prevent a
future story from sneaking in an unchecked env var.

### Acceptance criteria

- `frontend/package.json` adds `zod ^3.23.0` as a `dependency`.
- `frontend/src/env.ts` exists with:
  - a Zod schema describing the three vars above, with defaults applied via
    `z.string().default(...)`,
  - parsing performed at module load: `const parsed = envSchema.safeParse(import.meta.env);`
    on failure, `throw new Error('Invalid frontend environment: ' + JSON.stringify(parsed.error.format(), null, 2));`,
  - on success, `export const env = parsed.data;` (typed as the Zod inferred type).
- `frontend/.env.example` exists at the project root with one entry per recognized var
  and a comment explaining each:
  ```
  # Base URL of the backend REST API. In dev, the Vite proxy on /api lets you point this
  # at /api/v1 to avoid CORS friction.
  VITE_API_BASE_URL=http://localhost:8080/api/v1

  # Shown in the document title and on the login card.
  VITE_APP_NAME=Multi-Agent Platform

  # Footer label. CI overrides this with the git short SHA.
  VITE_BUILD_VERSION=dev
  ```
- `frontend/.env.example` is **committed**; `frontend/.env.local` is **gitignored**
  (already covered by US-01-001's `.gitignore`).
- US-01-005's `HomePlaceholder` renders `env.VITE_APP_NAME` somewhere visible (e.g., as
  the heading) so that a wiring break is caught at first paint — the placeholder is the
  smoke test for this story.
- Running `npm run dev` with a deliberately malformed `.env.local` (e.g.,
  `VITE_API_BASE_URL=not-a-string-but-an-array` is hard to produce; instead, omit nothing
  and verify defaults apply; the schema's `safeParse` failure path is covered by a unit
  test in US-01-009).

### Out of scope

- Runtime environment hot-reloading — Vite restart required after a `.env` change.
- Reading secrets — there are none on the frontend by design (SW-DESIGN §15).
- The proxy itself — US-01-003.

### Design references

- `frontend/design/SW-DESIGN.md` §14.2 (environment variables table), §15 ("No secrets in
  the bundle").

### Dependencies

- US-01-001, US-01-002.

---

## US-01-007 — OpenAPI type-generation pipeline + CI freshness check

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** an `npm run gen:api` script that regenerates `src/generated/schema.d.ts` from
`../openapi.yaml` using `openapi-typescript`, plus a CI gate that fails the build if the
committed file is out of date
**So that** every backend contract change surfaces immediately as a TypeScript compile
error in the consuming slice — never as a silent runtime mismatch.

### Description

`openapi-typescript` produces a single `paths` + `components` type map with **no runtime
output**. It is the lighter half of the codegen pair described in SW-DESIGN §2.3; the
matching runtime wrapper (`openapi-fetch`) is added in EPIC-02 alongside the typed
client. This story stops at "the generator works, the file is committed, CI verifies
freshness". Consumption is EPIC-02's job.

The CI check is implemented as a script that re-runs the generator and `git diff
--exit-code src/generated/schema.d.ts`; a non-zero diff fails the script. It is wired
into `npm run verify` in US-01-010.

### Acceptance criteria

- `frontend/package.json` adds `openapi-typescript ^7.0.0` as a `devDependency`.
- `frontend/package.json` `scripts` section gains:
  - `"gen:api": "openapi-typescript ../openapi.yaml -o src/generated/schema.d.ts"`,
  - `"gen:api:check": "openapi-typescript ../openapi.yaml -o src/generated/schema.d.ts.tmp && diff -q src/generated/schema.d.ts src/generated/schema.d.ts.tmp && rm src/generated/schema.d.ts.tmp"` — fails non-zero if the committed file diverges from a fresh regeneration.
  - (Windows-portable alternative: a tiny Node script `scripts/check-api-generated.mjs`
    that does the same thing using `node:fs` and `node:child_process`. Either approach
    is acceptable; choose the portable one if the team runs Windows shells.)
- Running `npm run gen:api` produces `frontend/src/generated/schema.d.ts` containing the
  TypeScript `paths` interface with at least the `operationId`s from `openapi.yaml`
  (spot-check: `login`, `listAgents`, `sendMessage`, `listMessages`).
- `frontend/src/generated/schema.d.ts` is **committed** (already explicitly excluded from
  the US-01-001 `.gitignore`'s `src/generated/` carve-out).
- `frontend/src/generated/.eslintrc.cjs` (or an equivalent `overrides` entry in the
  root ESLint config in US-01-008) exempts `src/generated/**` from formatting and
  layering rules — the file is not human-edited.
- A one-line `README.md` at `frontend/src/generated/README.md` reading
  `Generated from ../../../openapi.yaml — do not edit by hand. Run \`npm run gen:api\` to refresh.`
- Running `npm run gen:api:check` succeeds on a clean tree; it fails after a manual edit
  to either the spec or the generated file (verified manually in the story's PR review).

### Out of scope

- `openapi-fetch` runtime wrapper and the typed `client` module — EPIC-02.
- TanStack Query, query keys — EPIC-02.
- Any hook that consumes the types — EPIC-02 onwards.

### Design references

- `frontend/design/SW-DESIGN.md` §2.3 (generator choice — `openapi-typescript` +
  `openapi-fetch`), §7.1 (generated typed client), §14.1 (CI verify script).

### Dependencies

- US-01-001, US-01-002.

---

## US-01-008 — ESLint + Prettier with layering rule

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** ESLint configured with the layering rule from SW-DESIGN §2.4
(`pages → features → shared → generated`, sibling features only via `index.ts`), TypeScript
+ React + a11y plugins, and Prettier as the formatter
**So that** an architectural drift (a `shared/` file importing from `features/`, or
`features/agents/AgentCard.tsx` reaching into `features/conversations/MessageBubble.tsx`)
is caught at lint time, not at code review.

### Description

The layering rule is the single most important guarantee EPIC-01 ships. Without it, every
EPIC after EPIC-02 will silently drift toward circular imports. Two implementations are
acceptable:

- `eslint-plugin-boundaries` — purpose-built, declarative.
- `eslint-plugin-import` with `no-restricted-paths` zones — built-in to a plugin already
  needed for other reasons; less specialized but adequate.

The PR may pick either; the acceptance criteria below specify the **behavior** that must
hold, not the plugin.

Prettier is run as a separate step (no `eslint-plugin-prettier` integration — that pattern
is no longer recommended). `eslint-config-prettier` is included to disable ESLint rules
that would conflict with Prettier formatting.

### Acceptance criteria

- `frontend/package.json` adds the following `devDependencies`:
  - `eslint ^8.57.0`,
  - `@typescript-eslint/parser ^7.0.0`, `@typescript-eslint/eslint-plugin ^7.0.0`,
  - `eslint-plugin-react ^7.34.0`, `eslint-plugin-react-hooks ^4.6.0`,
  - `eslint-plugin-jsx-a11y ^6.8.0`,
  - either `eslint-plugin-boundaries ^4.0.0` **or** the layering rule expressed via
    `eslint-plugin-import` + `no-restricted-imports` / `no-restricted-paths`,
  - `eslint-config-prettier ^9.1.0`,
  - `prettier ^3.2.0`.
- `frontend/.eslintrc.cjs` exists with:
  - `parser: '@typescript-eslint/parser'`,
  - `extends: ['eslint:recommended', 'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended', 'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended', 'prettier']`,
  - `settings.react.version: 'detect'`,
  - rules that **forbid** the following import patterns (the layering rule):
    1. `src/shared/**` → `src/features/**` (any direction): error.
    2. `src/shared/**` → `src/pages/**`: error.
    3. `src/features/<a>/**` → `src/features/<b>/<anything-but-index.ts>`: error.
    4. `src/pages/**` → `src/shared/api/**` direct calls to `useQuery` / `useMutation`:
       error (pages must consume feature hooks; this rule may be relaxed if it produces
       false positives — the design constraint is documented in SW-DESIGN §2.4).
  - `no-restricted-syntax` rule forbidding `import.meta.env` access **outside** of
    `src/env.ts` (see US-01-006).
  - An `overrides` block exempting `src/generated/**` from every project-specific rule.
- `frontend/.prettierrc` exists with:
  - `{ "semi": true, "singleQuote": true, "trailingComma": "all", "printWidth": 100,
    "arrowParens": "always" }`.
- `frontend/.prettierignore` exists ignoring `dist/`, `coverage/`, `src/generated/`,
  `package-lock.json`.
- `frontend/package.json` `scripts` section gains:
  - `"lint": "eslint . --ext .ts,.tsx --max-warnings 0"`,
  - `"format": "prettier --check ."`,
  - `"format:fix": "prettier --write ."`.
- Running `npm run lint` on the tree from US-01-005 succeeds with zero warnings.
- A **negative test** is added (a deliberate violation file under
  `frontend/src/__lint_fixtures__/` that imports from a forbidden path) — `npm run lint`
  fails when it is present. The fixture is then removed before the PR merges; the story's
  PR description records the manual verification.

### Out of scope

- Husky / lint-staged pre-commit hooks — out of v1 scope.
- ArchUnit-equivalent fitness functions beyond the import rule — not necessary in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §2.4 (module boundaries — the layering rule),
  §3 (lint / format toolchain), §4.1 (naming conventions enforced by the lint config),
  §14.1 (the `lint` and `format` scripts).

### Dependencies

- US-01-001, US-01-002.

---

## US-01-009 — Vitest + RTL + MSW v2 test infrastructure + smoke test

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** Vitest configured with `jsdom`, React Testing Library, `@testing-library/jest-dom`
matchers, and an MSW v2 server harness wired into a `src/test/setup.ts` file
**So that** every later EPIC can drop in its tests without reconfiguring the runner, and
so that the foundation EPIC itself ships with a smoke test proving the toolchain works.

### Description

MSW handlers are added per feature EPIC; this story ships an **empty handler set** plus
the lifecycle wiring (`listen → resetHandlers per test → close`). The shared `renderWithProviders`
helper is added here as a stub so feature tests can extend it later without inventing a
new entry point.

The smoke test verifies that the placeholder `HomePlaceholder` from US-01-005 renders the
`VITE_APP_NAME` value, exercising: Vite alias resolution, the env module from US-01-006,
the React 18 root, and React Router. If any of these break, this single test breaks.

### Acceptance criteria

- `frontend/package.json` adds the following `devDependencies`:
  - `vitest ^1.6.0`,
  - `@vitest/ui ^1.6.0` (optional but useful — required by the `test:ui` script below),
  - `jsdom ^24.0.0`,
  - `@testing-library/react ^16.0.0`,
  - `@testing-library/jest-dom ^6.4.0`,
  - `@testing-library/user-event ^14.5.0`,
  - `msw ^2.3.0`.
- `frontend/vite.config.ts` is extended (or a sibling `vitest.config.ts` is added —
  pick one) with:
  - `test: { globals: true, environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], css: true }`.
- `frontend/src/test/setup.ts` exists with:
  - `import '@testing-library/jest-dom/vitest';`,
  - `import { afterAll, afterEach, beforeAll } from 'vitest';`,
  - `import { server } from './server';`,
  - `beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));`,
  - `afterEach(() => server.resetHandlers());`,
  - `afterAll(() => server.close());`.
- `frontend/src/test/server.ts` exists with:
  - `import { setupServer } from 'msw/node';`,
  - `export const server = setupServer();` (empty handler set — features add their own
    via `server.use(...)`).
- `frontend/src/test/render.tsx` exists as a stub `renderWithProviders` helper:
  - Today it just wraps `render()` from RTL. EPIC-02 will extend it with the
    `QueryClientProvider`, `AuthContext`, and `<RouterProvider>` (or `MemoryRouter`).
- `frontend/src/test/factories.ts` exists empty (with a header comment reserving it for
  feature-test factories matching openapi schemas).
- `frontend/package.json` `scripts` section gains:
  - `"test": "vitest"`,
  - `"test:run": "vitest run"`,
  - `"test:ui": "vitest --ui"`,
  - `"test:coverage": "vitest run --coverage"` (coverage tool is `v8` — the Vitest
    default — no extra dependency needed unless detailed reports are required).
- **Smoke test**: `frontend/src/pages/HomePlaceholder.test.tsx` exists and asserts that
  rendering `<HomePlaceholder />` with the test render helper shows the text matching
  `env.VITE_APP_NAME` (default: `Multi-Agent Platform`).
- **Env-validation negative test**: `frontend/src/env.test.ts` exists and asserts that
  the Zod schema rejects malformed input (e.g., a string with embedded whitespace where a
  URL is expected — exact assertion depends on the schema in US-01-006). This closes the
  loop on US-01-006's deferred validation test.
- Running `npm run test:run` succeeds (exit 0, all tests pass).

### Out of scope

- Feature handlers and feature factories — added by each feature EPIC.
- Playwright / E2E — deferred (TBD-F7 in SW-DESIGN §17).
- `axe-core` automated a11y check — EPIC-11.

### Design references

- `frontend/design/SW-DESIGN.md` §13 (testing strategy — the pyramid, MSW harness,
  `renderWithProviders`), §14.1 (test scripts).

### Dependencies

- US-01-001, US-01-002, US-01-003, US-01-004, US-01-005 (the smoke test renders the
  placeholder from US-01-005), US-01-006 (the smoke test asserts on `env.VITE_APP_NAME`).

---

## US-01-010 — `npm run verify` aggregator script

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `npm run verify` script that runs `gen:api → lint → format check →
test → build` in order and fails on the first non-zero exit
**So that** CI (and every contributor before a commit) has exactly one command to run, and
so that all six toolchain pieces from EPIC-01 are exercised together on every change.

### Description

This story is the **closing seam** of EPIC-01: it removes the placeholder `verify` script
from US-01-001 and replaces it with the real aggregator. After it merges, EPIC-01 is
complete and the foundation is ready for EPIC-02 to start consuming.

The script chain uses `&&` (POSIX) so it short-circuits on the first failure. A
Windows-friendly chain via `npm-run-all2` is an acceptable alternative if the team
includes Windows shells — flag the choice in the PR.

### Acceptance criteria

- The placeholder `"verify": "echo 'pending US-01-010'"` from US-01-001 is **replaced**
  with the real chain.
- `frontend/package.json` `scripts.verify` runs, in order:
  1. `npm run gen:api:check` (from US-01-007),
  2. `npm run lint` (from US-01-008),
  3. `npm run format` (from US-01-008 — non-mutating check),
  4. `npm run test:run` (from US-01-009),
  5. `npm run build` (from US-01-003).
  The composite invocation is, on POSIX: `"verify": "npm run gen:api:check && npm run
  lint && npm run format && npm run test:run && npm run build"`.
- Running `npm run verify` on a clean tree (US-01-001..009 all merged) succeeds end-to-end
  with exit 0.
- A deliberate violation of each step is verified to fail the aggregator at the right
  position (manual verification recorded in the PR description):
  - Mutating `openapi.yaml` and not regenerating → fails at step 1.
  - Introducing a forbidden import → fails at step 2.
  - A Prettier-divergent file → fails at step 3.
  - A failing assertion in the smoke test → fails at step 4.
  - A TypeScript error in `src/` → fails at step 5 (`tsc -b` runs as part of `build`).
- `frontend/README.md` is created (or updated, if a stub exists) with a single
  "Getting started" section telling new contributors:
  1. `nvm use` (reads `.nvmrc`),
  2. `cp .env.example .env.local && edit`,
  3. `npm install`,
  4. `npm run gen:api`,
  5. `npm run dev` (or `npm run verify` for the full check).

### Out of scope

- Husky pre-commit / pre-push hooks — out of v1 scope (the script is the contract, not
  the enforcement mechanism).
- CI YAML — that is EPIC-12's deliverable (this story produces the script CI calls).
- Bundle-budget gate — EPIC-12.

### Design references

- `frontend/design/SW-DESIGN.md` §14.1 (the `verify` script as the single contract for
  CI and contributors).

### Dependencies

- US-01-001 (placeholder script to overwrite), US-01-007 (`gen:api:check`),
  US-01-008 (`lint`, `format`), US-01-009 (`test:run`), and US-01-003 (`build`).

---

## Summary

| ID         | Title                                                                       | Priority | Status |
|------------|-----------------------------------------------------------------------------|----------|--------|
| US-01-001  | npm project scaffold                                                        | MUST     | Done   |
| US-01-002  | TypeScript strict configuration                                              | MUST     | Done   |
| US-01-003  | Vite project with `@` alias, dev server & `/api` proxy                      | MUST     | Done   |
| US-01-004  | Tailwind CSS + design tokens + Geist fonts                                  | MUST     | Done   |
| US-01-005  | Application entry point: `index.html`, `main.tsx`, `App.tsx`, router shell  | MUST     | Done   |
| US-01-006  | Environment-variable module (`src/env.ts`, `.env.example`, Zod validation)  | MUST     | Done   |
| US-01-007  | OpenAPI type-generation pipeline + CI freshness check                       | MUST     | Done   |
| US-01-008  | ESLint + Prettier with layering rule                                        | MUST     | Done   |
| US-01-009  | Vitest + RTL + MSW v2 test infrastructure + smoke test                      | MUST     | Done   |
| US-01-010  | `npm run verify` aggregator script                                          | MUST     | Done   |

EPIC-01 is **Done** when all ten stories above are `Done`. The next step is then
EPIC-02 (Shared layer — API client, auth, SSE, design system, layouts), which consumes
every piece of the foundation laid here.
