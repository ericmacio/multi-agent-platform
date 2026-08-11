# US-STATUS.md — Frontend user-story status tracker

This document is the **single source of truth** for the implementation status of every frontend
user story. It complements `EPICS.md` (EPIC-level breakdown) and the per-EPIC story files
`EPIC-<ref>-US.md` (detailed acceptance criteria).

## Maintenance rules

This file MUST be kept up to date whenever:

1. A new EPIC's user stories are created → append a new section with the EPIC's stories,
   each row in `Draft` status.
2. A user story changes status → update its `Status` column.
3. A user story is added, removed, split, or merged within an existing EPIC → reflect the
   change in this file at the same time as the change to the corresponding `EPIC-<ref>-US.md`.

The four allowed status values are:

| Status        | Meaning                                                                    |
|---------------|----------------------------------------------------------------------------|
| `Draft`       | Story exists in `EPIC-<ref>-US.md`; not yet ready for implementation.      |
| `Ready`       | Acceptance criteria reviewed; story is ready to be picked up.              |
| `In progress` | Implementation has started.                                                |
| `Done`        | All acceptance criteria met; tests green; merged into the working branch.  |

A story moves to `Done` only when the acceptance criteria from its `EPIC-<ref>-US.md` entry
are fully satisfied, including any explicit "verified by …" test artifacts.

## Conventions

- **ID format**: `US-<epic>-<nnn>` (matches `EPIC-<epic>-US.md`).
- **Purpose**: a one-line summary; the authoritative description lives in `EPIC-<epic>-US.md`.
- **Priority**: `MUST`, `SHOULD`, `COULD` — copied from the story file.
- One section per EPIC.
- Stories appear in build order within their EPIC.

---

## EPIC-01 — Project foundation & tooling

| ID         | Purpose                                                                       | Priority | Status |
|------------|-------------------------------------------------------------------------------|----------|--------|
| US-01-001  | npm project scaffold (`package.json`, `.nvmrc`, `.gitignore`, lockfile)        | MUST     | Done   |
| US-01-002  | TypeScript strict configuration (`tsconfig.json` + `tsconfig.node.json`)       | MUST     | Done   |
| US-01-003  | Vite project with `@` path alias, dev server & `/api` proxy                    | MUST     | Done   |
| US-01-004  | Tailwind CSS + design tokens + Geist fonts                                     | MUST     | Done   |
| US-01-005  | Application entry point: `index.html`, `main.tsx`, `App.tsx`, router shell     | MUST     | Done   |
| US-01-006  | Environment-variable module (`src/env.ts`, `.env.example`, Zod validation)     | MUST     | Done   |
| US-01-007  | OpenAPI type-generation pipeline + CI freshness check                          | MUST     | Done   |
| US-01-008  | ESLint + Prettier with layering rule                                           | MUST     | Done   |
| US-01-009  | Vitest + RTL + MSW v2 test infrastructure + smoke test                         | MUST     | Done   |
| US-01-010  | `npm run verify` aggregator script                                             | MUST     | Done   |

---

## EPIC-02 — Shared layer (API client, auth, SSE, design system, layouts)

| ID         | Purpose                                                                            | Priority | Status |
|------------|------------------------------------------------------------------------------------|----------|--------|
| US-02-001  | Shared lib utilities (`cn`, `date`, `result`) + `i18n/en.ts` copy map skeleton     | MUST     | Done   |
| US-02-002  | `ApiError` class + RFC 7807 normalizer (`shared/api/errors.ts`)                    | MUST     | Done   |
| US-02-003  | `openapi-fetch` typed client + three HTTP middlewares (`shared/api/client.ts`)     | MUST     | Done   |
| US-02-004  | TanStack Query client + typed `qk` key factory                                     | MUST     | Done   |
| US-02-005  | Cursor-pagination helpers (`flattenPages` + `useCursorInfiniteQuery`)              | MUST     | Done   |
| US-02-006  | JWT token storage (`tokenStorage.ts`) + Zod-validated decode (`jwt.ts`)            | MUST     | Done   |
| US-02-007  | `AuthContext` + proactive 30 s pre-`exp` banner + `auth:logout` event wiring       | MUST     | Done   |
| US-02-008  | Route guards (`RequireAuth`, `RequireRole`, `RequireFreshPassword`, `RequireGuest`)| MUST     | Done   |
| US-02-009  | Design-system atoms (Button, Input, Textarea, Select, Checkbox, Badge, Card, Spinner, Skeleton, EmptyState, icons) | MUST | Done |
| US-02-010  | Design-system overlays (Modal, Dropdown, Tooltip, Tabs, Toast)                     | MUST     | Done   |
| US-02-011  | Layout shells (`AppShell` + `Sidebar` + `Topbar` + `AuthShell`)                    | MUST     | Done   |
| US-02-012  | SSE primitives (`sseFrames.ts` + `chatStream.ts`)                                  | MUST     | Done   |

---

## EPIC-03 — Authentication flows (login, change password, sign out, expiry)

| ID         | Purpose                                                                                  | Priority | Status |
|------------|------------------------------------------------------------------------------------------|----------|--------|
| US-03-001  | `passwordPolicy` Zod schema + per-rule live evaluator (`features/auth/password.ts`)      | MUST     | Done   |
| US-03-002  | Auth mutation hooks: `useLogin`, `useLogout`, `useChangeOwnPassword`                     | MUST     | Done   |
| US-03-003  | `LoginForm` — email + password + `?next=` validation + 429 countdown                     | MUST     | Done   |
| US-03-004  | `ChangePasswordForm` — three fields + live policy checklist + confirm-match              | MUST     | Done   |
| US-03-005  | `LoginPage` — `AuthShell` composition + integration tests                                | MUST     | Done   |
| US-03-006  | `ChangePasswordPage` — `AuthShell` composition + forced-visit banner + integration tests | MUST     | Done   |
| US-03-007  | Routes wiring (`RequireGuest` / `RequireFreshPassword`) + session-expired toast          | MUST     | Done   |

---

## EPIC-04 — Catalog pages (Tools & MCP servers — read-only)

| ID         | Purpose                                                                                    | Priority | Status |
|------------|--------------------------------------------------------------------------------------------|----------|--------|
| US-04-001  | Catalog hooks: `useTools` + `useMcpServers` (static `staleTime: Infinity`)                 | MUST     | Done   |
| US-04-002  | `ToolsPage` end-to-end (list + filter + route + sidebar + integration tests)               | MUST     | Done   |
| US-04-003  | `McpServersPage` end-to-end (list + filter + route + sidebar + integration tests)          | MUST     | Done   |

---

## EPIC-05 — Agents management (owner-scoped CRUD)

| ID         | Purpose                                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-05-001  | `agentSchema` Zod schema + Agent query/mutation hooks                                                | MUST     | Done   |
| US-05-002  | `ToolPicker` + `McpServerPicker` (checkbox-list pickers backed by catalog hooks)                     | MUST     | Done   |
| US-05-003  | `TeamPicker` — multi-select with nested-team disabling + tooltip preview                              | MUST     | Done   |
| US-05-004  | `AgentForm` — sectioned form (Identity / Behavior / Model / Tools / MCP / Team) + error routing      | MUST     | Done   |
| US-05-005  | `AgentList` + `AgentCard` + `DeleteAgentDialog` (grid + per-card actions + cascade-warning delete)   | MUST     | Done   |
| US-05-006  | `AgentsPage` + routes wiring + sidebar entry (list page + empty state + "New agent" CTA)             | MUST     | Done   |
| US-05-007  | `AgentCreatePage` + `AgentEditPage` + `AgentDetailPage` (page composition + integration tests)       | MUST     | Done   |

---

## EPIC-06 — Conversations & messages (non-streaming surface)

| ID         | Purpose                                                                                                  | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------------|----------|--------|
| US-06-001  | Conversation / Message types + Zod schemas + six conversation hooks (incl. optimistic updates)            | MUST     | Done   |
| US-06-002  | `MessageBubble` + virtualized `MessageList` (USER / ASSISTANT styling + 64-row scroll)                    | MUST     | Done   |
| US-06-003  | `EditTitleDialog` + `DeleteConversationDialog` (modal-based edits with optimistic flow)                   | MUST     | Done   |
| US-06-004  | `ConversationList` + `ConversationListItem` (left-pane list with active highlighting)                     | MUST     | Done   |
| US-06-005  | `ConversationView` — topbar (title / agent link / count / overflow) + message list + composer placeholder | MUST     | Done   |
| US-06-006  | `ChatPage` layout (two-pane) + `ChatNewPage` (agent picker) + routes wiring + sidebar entry               | MUST     | Done   |
| US-06-007  | `ConversationPage` composition + `AgentDetailPage` "Recent conversations" wire-up + integration tests     | MUST     | Done   |

---

## EPIC-07 — SSE streaming chat

| ID         | Purpose                                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-07-001  | `Composer` — textarea + N/1024 counter + Cmd/Ctrl+Enter + send / stop buttons                         | MUST     | Done   |
| US-07-002  | `useChatStream` — state machine, frame handling, first-turn title cache patch, `startTransition`      | MUST     | Done   |
| US-07-003  | `ConversationView` SSE wiring — replace placeholder, `aria-live` log, conversation-full banner        | MUST     | Done   |
| US-07-004  | Cancellation — stop button + page-navigation cleanup + partial-bubble greying                         | MUST     | Done   |
| US-07-005  | End-to-end SSE integration tests — golden path, all error paths, 64-cap, 406                          | MUST     | Done   |

---

## EPIC-08 — Admin: Users

| ID         | Purpose                                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-08-001  | `createUserSchema` Zod schema + Admin-user query/mutation hooks                                       | MUST     | Done   |
| US-08-002  | `UserForm` — email + password (with policy checklist) + role + error routing                          | MUST     | Done   |
| US-08-003  | `UserList` (table) + `DisableUserDialog` + `DeleteUserDialog` (optimistic + cascade warn)             | MUST     | Done   |
| US-08-004  | `AdminUsersPage` + admin routes wiring + role-gated sidebar group                                     | MUST     | Done   |
| US-08-005  | `AdminUserCreatePage` + `AdminUserDetailPage` (page composition + integration tests)                  | MUST     | Done   |

---

## EPIC-09 — Admin: API keys

| ID         | Purpose                                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-09-001  | `createApiKeySchema` Zod schema + Admin API-key query/mutation hooks                                  | MUST     | Done   |
| US-09-002  | `RevealOnceBanner` — cleartext-in-mono + Copy button + copy-once-then-Done gate                       | MUST     | Done   |
| US-09-003  | `CreateApiKeyDialog` — optional-label input → mutate → pivot to `RevealOnceBanner`                    | MUST     | Done   |
| US-09-004  | `ApiKeyList` (table) + row revoke / re-enable action (optimistic)                                     | MUST     | Done   |
| US-09-005  | `AdminApiKeysPage` + admin route wiring + integration tests                                           | MUST     | Done   |

---

## EPIC-10 — Admin: Rate limit

| ID         | Purpose                                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-10-001  | `rateLimitConfigSchema` Zod schema + Admin rate-limit query/mutation hooks                            | MUST     | Done   |
| US-10-002  | `RateLimitForm` — two numeric inputs + updated-at/by caption + save button + per-code error routing   | MUST     | Done   |
| US-10-003  | `AdminRateLimitPage` + admin route wiring + integration tests                                         | MUST     | Done   |

---

## EPIC-11 — Cross-cutting UX polish (toasts, error boundary, empty/loading/offline, a11y)

| ID         | Purpose                                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-11-001  | Mount `ErrorBoundary` at the true app root (`main.tsx`) + integration test                            | MUST     | Done   |
| US-11-002  | Toast dedup for rate-limit bursts — last-write-wins single toast with live countdown                  | MUST     | Done   |
| US-11-003  | `<OfflineBanner>` — `navigator.onLine` listener wired into `AppShell` / `AuthShell`                   | MUST     | Done   |
| US-11-004  | In-content state primitives (`ForbiddenState`, `NotFoundState`, `LoadingList`)                        | MUST     | Done   |
| US-11-005  | Empty / loading / error sweep across every list & detail page (EPIC-04..EPIC-10)                      | MUST     | Done   |
| US-11-006  | Accessibility audit + `vitest-axe` automated check on shared UI + key pages                           | MUST     | Done   |
| US-11-007  | Bundle-budget visualization (`vite-plugin-bundle-visualizer` + `npm run build:analyze` + 200 KB warn) | SHOULD   | Done   |

---

## Aggregate progress

| EPIC     | Title                                              | Total | Draft | Ready | In progress | Done |
|----------|----------------------------------------------------|------:|------:|------:|------------:|-----:|
| EPIC-01  | Project foundation & tooling                       |    10 |     0 |     0 |           0 |   10 |
| EPIC-02  | Shared layer (API client, auth, SSE, DS, layouts)  |    12 |     0 |     0 |           0 |   12 |
| EPIC-03  | Authentication flows                               |     7 |     0 |     0 |           0 |    7 |
| EPIC-04  | Catalog pages (Tools & MCP servers)                |     3 |     0 |     0 |           0 |    3 |
| EPIC-05  | Agents management (owner-scoped CRUD)              |     7 |     0 |     0 |           0 |    7 |
| EPIC-06  | Conversations & messages (non-streaming surface)   |     7 |     0 |     0 |           0 |    7 |
| EPIC-07  | SSE streaming chat                                 |     5 |     0 |     0 |           0 |    5 |
| EPIC-08  | Admin — Users                                      |     5 |     0 |     0 |           0 |    5 |
| EPIC-09  | Admin — API keys                                   |     5 |     0 |     0 |           0 |    5 |
| EPIC-10  | Admin — Rate limit                                 |     3 |     0 |     0 |           0 |    3 |
| EPIC-11  | Cross-cutting UX polish                            |     7 |     0 |     0 |           0 |    7 |
| **All**  |                                                    | **71** |  **0** |  **0** |     **0** | **71** |

> Stories for EPIC-12 will be appended to this file as its `EPIC-12-US.md` file is
> produced.

---

## Notable implementation choices flagged for the next EPIC

The following deviations from the as-written story acceptance criteria were made during
EPIC-01 implementation. Each was harmless to acceptance and the rationale is recorded here
so EPIC-02 doesn't have to rediscover it.

- **`@fontsource-variable/geist` + `@fontsource-variable/geist-mono`** were used **instead of**
  the `geist` npm package. The `geist` package on npm is Next.js-only — it ships JavaScript
  modules that integrate with Next.js's font system, not plain CSS `@import` declarations.
  In a Vite project, attempting `@import 'geist/font/sans'` produces a PostCSS parser error.
  The `@fontsource-variable/*` ecosystem provides the same Vercel-released Geist fonts as
  self-hosted variable woff2 files exposed through CSS imports, which is what Tailwind +
  Vite need. Visual identity is unchanged.

- **`scripts/check-api-generated.mjs` uses `execSync` with a quoted command string** rather
  than `execFileSync`. The repository path contains spaces and a hyphen
  (`OneDrive - Cognizant`); `execFileSync` with `shell: true` would re-split args on
  whitespace inside the shell. `execSync` with explicit `"…"` quoting around `SPEC_PATH` and
  `TMP` is the cross-platform safe form.

- **`tsc -b` (composite project) emits sibling artifacts** next to the Node `tsconfig`'s
  included files (`vite.config.d.ts`, `vite.config.js`, `tailwind.config.d.ts`,
  `tailwind.config.js`, `*.tsbuildinfo`). All are added to `.gitignore`, `.eslintrc.cjs`
  `ignorePatterns`, and `.prettierignore`. They are build artifacts, not authored sources.

- **`engines.node` is `>=20.0.0 <21.0.0`** as the story specified, but **no `.npmrc`
  `engine-strict=true`** was set. Without `engine-strict`, npm emits a loud `EBADENGINE`
  warning on the wrong Node version but completes the install — which is the practical
  middle ground for a team that does not want a hard install fail. EPIC-02 may revisit this
  if Node-version drift becomes a real problem.

- **The ESLint layering rule is enforced via `eslint-plugin-import`'s `no-restricted-paths`**
  with explicit zones. The sibling-feature constraint ("features may import other features
  only via `index.ts`") is **not** enforced yet because `src/features/` does not exist. The
  rule will be tightened — likely by switching to `eslint-plugin-boundaries` — in EPIC-02
  when the first feature slice lands.
