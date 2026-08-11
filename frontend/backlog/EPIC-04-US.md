# EPIC-04-US.md — User stories for EPIC-04 (Catalog pages — Tools & MCP servers)

This file lists the user stories that deliver **EPIC-04 — Catalog pages: Tools & MCP servers
(read-only)** of the frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-04 is the first read-only authenticated surface. Beyond shipping the two `/tools` and
`/mcp-servers` pages themselves, its load-bearing deliverable is the **two catalog hooks**
(`useTools`, `useMcpServers`) that EPIC-05's `AgentForm` pickers (`ToolPicker`,
`McpServerPicker`) will consume on day one. The two catalogs are static at backend startup
(`REQ-TOOL-001`, `REQ-MCP-001`); the hooks therefore use `staleTime: Infinity` and are only
invalidated on full sign-out.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-04-<nnn>` — `04` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All three stories are `MUST` (EPIC-05 hard-depends
  on US-04-001; the two catalog pages are the smallest viable authenticated surface).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                    | Priority | Status | Depends on            |
|------------|--------------------------------------------------------------------------|----------|--------|-----------------------|
| US-04-001  | Catalog hooks: `useTools` + `useMcpServers` (static `staleTime: Infinity`) | MUST   | Done   | EPIC-02               |
| US-04-002  | `ToolsPage` end-to-end (list + filter + route + sidebar + integration tests) | MUST | Done   | US-04-001             |
| US-04-003  | `McpServersPage` end-to-end (list + filter + route + sidebar + integration tests) | MUST | Done  | US-04-001         |

---

## US-04-001 — Catalog hooks: `useTools` + `useMcpServers` (static `staleTime: Infinity`)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** two paired TanStack Query hooks — `useTools()` and `useMcpServers()` — that
fetch the static tool catalog (`GET /tools`) and the static MCP-server catalog
(`GET /mcp-servers`) with `staleTime: Infinity` and a typed return value
**So that** every consumer (the catalog pages in US-04-002 / US-04-003, the `ToolPicker`
and `McpServerPicker` in EPIC-05) shares a single cached fetch per session, and the
agent-form pickers can render synchronously after the first authenticated page load.

### Description

Per `REQ-TOOL-001` / `REQ-MCP-001`, the two catalogs are discovered by the backend at
application startup and never change at runtime. Per SW-DESIGN §7.6, the client-side cache
policy for catalogs is `staleTime: Infinity`, `refetchOnWindowFocus: false`, invalidated
only on full sign-out.

The two hooks are intentionally **paired in one story**: they have identical shape (single
GET, no parameters, no pagination, identical caching policy), share the same `qk.catalog.*`
key-factory entries (already defined by US-02-004), and the same MSW handler conventions in
tests. Splitting them would only duplicate boilerplate.

The hooks unwrap the `openapi-fetch` envelope and return the spec's `ToolList` /
`McpServerList` objects' `items[]` directly — consumers want the array, not the wrapping
object.

### Acceptance criteria

- `frontend/src/features/catalog/api.ts` exists with the following exports:
  - `export function useTools(): UseQueryResult<ToolDescriptor[], ApiError>`
  - `export function useMcpServers(): UseQueryResult<McpServerDescriptor[], ApiError>`
  - Where `ToolDescriptor` and `McpServerDescriptor` are type aliases for the generated
    openapi schemas (`components['schemas']['ToolDescriptor']` etc.) re-exported from the
    module for convenience.
- Each hook is built with `useQuery({ queryKey, queryFn, staleTime: Infinity })`:
  - `useTools` → `queryKey: qk.catalog.tools()`, `queryFn: () => unwrap(await api.GET('/tools'))`,
    selects `.items`.
  - `useMcpServers` → `queryKey: qk.catalog.mcpServers()`,
    `queryFn: () => unwrap(await api.GET('/mcp-servers'))`, selects `.items`.
- `staleTime: Infinity` is applied **per hook** (not at the QueryClient defaults level) so
  the rest of the app's cache policies are untouched.
- `refetchOnWindowFocus: false` and `refetchOnReconnect: false` are set on both hooks
  (catalogs don't change at runtime).
- A new helper `invalidateCatalogs(queryClient)` is exported from the module and is wired
  into the sign-out path (US-02-007 `signOut` already clears in-memory token state — this
  helper is called from `signOut` or from the `AuthContext` effect that watches for
  `token === null`; implementer's choice as long as catalogs are evicted on sign-out).
- `qk.catalog.tools()` and `qk.catalog.mcpServers()` already exist in the key factory
  (US-02-004). If they don't, this story adds them — a one-line addition.
- **Unit tests** in `frontend/src/features/catalog/api.test.tsx` (MSW + `renderHook` under
  the standard provider stack):
  - `useTools` — 200 happy path: returns the `items` array; `data` shape is `ToolDescriptor[]`.
  - `useTools` — `INTERNAL_ERROR` 500: the query enters error state with
    `error.code === 'INTERNAL_ERROR'`; `data` is `undefined`.
  - `useTools` — calling the hook twice in the same test issues **one** network request
    (verifies the `staleTime: Infinity` cache).
  - `useMcpServers` — 200 happy path: returns the `items` array; `data` shape is
    `McpServerDescriptor[]`.
  - `useMcpServers` — empty catalog (server returns `{ items: [] }`): `data` is `[]`, no
    error; consumers can render an empty state.
  - `useMcpServers` — calling the hook twice issues one network request.
  - After `invalidateCatalogs(queryClient)`, a subsequent `useTools()` call re-fetches
    (verifies sign-out path).

### Out of scope

- The catalog pages themselves (US-04-002 / US-04-003).
- A combined `useCatalogs()` hook returning both arrays — premature; the two consumers in
  EPIC-05 (`ToolPicker`, `McpServerPicker`) each consume one catalog, and the page surfaces
  consume one each.
- Refresh-on-401: not applicable — the global auth middleware already redirects on auth
  failures.

### Design references

- `frontend/design/SW-DESIGN.md` §7.6 (stale-time policy table), §12.9 (catalog page shape).
- `openapi.yaml` `GET /tools`, `GET /mcp-servers`, `ToolList`, `McpServerList`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `qk` key factory, `queryClient`, `ApiError`).

---

## US-04-002 — `ToolsPage` end-to-end (list + filter + route + sidebar + integration tests)

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** a `/tools` page that lists the tools available for assignment to an agent, with
a name+description filter and a clear empty state when the filter matches nothing
**So that** I can see what's on the platform before configuring an agent in EPIC-05, and so
that I have a route to bookmark when asking a colleague "is tool X available?".

### Description

The page is the standard read-only catalog shape from SW-DESIGN §12.9: a header, a filter
input, a table of `name` + `description` rows, and an empty state. The filter is a pure
client-side substring match (case-insensitive) against both `name` and `description` — no
server-side filter exists per `openapi.yaml`.

The page consumes `useTools()` (US-04-001) and renders three states:

1. **Loading** — `<Skeleton>` rows (3–5 placeholder rows from the design-system primitive
   set in US-02-009).
2. **Loaded** — table with rows; if the filter is non-empty and matches nothing, render the
   filtered empty state ("No tools match '<query>'") rather than the "no tools at all"
   one.
3. **Error** — `INTERNAL_ERROR` (or any other `ApiError`) surfaces via a retryable error
   state with a "Retry" button that re-runs the query.

The route is registered under the existing protected `<AppShell>` layout (per SW-DESIGN
§5.1). A new sidebar entry `Tools` is added under the standard-user group with the
`Wrench`-or-equivalent lucide icon (the existing nav copy map in US-02-001 reserves the
key; this story populates it).

### Acceptance criteria

- `frontend/src/features/catalog/ToolList.tsx` exists and accepts a single prop
  `{ items: ToolDescriptor[] }`. It renders:
  - A controlled text `Input` labelled "Filter tools" with placeholder
    "Filter by name or description…" and the `Search` lucide icon.
  - A table with two columns (`Name`, `Description`). `Name` uses the `font-mono` token;
    `Description` uses the secondary text token.
  - When the filter is non-empty, rows are filtered by case-insensitive substring against
    `name || description`.
  - When `items.length === 0` (server-side empty), renders an `EmptyState` with title
    "No tools configured" and a short caption.
  - When the filter is non-empty and produces 0 rows, renders an `EmptyState` with title
    `No tools match "<query>"` and a "Clear filter" `Button` that resets the filter.
- `frontend/src/pages/catalog/ToolsPage.tsx` exists, exporting
  `function ToolsPage(): JSX.Element`. It composes:
  - A page heading "Tools" + a one-line caption "Catalog of tools available for assignment
    to an agent."
  - `useTools()` consumption:
    - `isPending` → render 4 `<Skeleton>` rows inside a card frame.
    - `isError` → render an inline error state with `error.detail || errorCopy[error.code].title`
      and a "Retry" `Button` calling `query.refetch()`.
    - `isSuccess` → `<ToolList items={data} />`.
- The route table in `frontend/src/pages/routes.tsx` (set up in US-03-007) registers
  `/tools` under the protected `<AppShell>` layout with `<RequireAuth>` +
  `<RequireFreshPassword>` inherited from the layout-route element. The page is
  **`React.lazy()`-loaded** per SW-DESIGN §16.1 (everything outside auth + chat is lazy).
- The `Sidebar` (US-02-011) gains a `Tools` link wired to `/tools`, visible to all
  authenticated principals (no admin gating).
- **Integration tests** in `frontend/src/pages/catalog/ToolsPage.test.tsx` (MSW + RTL under
  the full provider stack):
  - **List renders**: MSW returns a 200 with 3 tools; after the initial render settles,
    all 3 rows are visible.
  - **Filter narrows**: typing into the filter input reduces the visible rows; the
    matching is case-insensitive and matches on either column.
  - **Empty-state when no match**: typing a query that matches nothing renders the
    `No tools match "…"` empty state with the "Clear filter" button; clicking the button
    restores the full list.
  - **Server-side empty**: MSW returns `{ items: [] }`; the `No tools configured` empty
    state is rendered (and the filter input is not).
  - **`INTERNAL_ERROR` surfaces as retryable**: MSW returns 500; the inline error state
    renders; the user clicks Retry; MSW (now returning 200) feeds the success state.
- The page passes the project's `cn`-class snapshot conventions (no hex colors, all colors
  via tokens — verified by lint).

### Out of scope

- A "Used by N agents" column — not exposed by the API and not in the v1 design.
- Server-side filtering or pagination — `GET /tools` returns the full catalog in one
  response per the spec.
- Inline tool documentation links — the spec only ships `name` + `description`.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table), §7.6 (stale-time), §10.2 (error
  routing for read-only pages), §12.9 (catalog page shape), §16.1 (lazy loading).
- `openapi.yaml` `GET /tools`, `ToolDescriptor`.

### Dependencies

- US-04-001 (`useTools`); EPIC-02 (`Input`, `Button`, `Card`, `EmptyState`, `Skeleton`,
  `Sidebar`, route table, `AppShell`).

---

## US-04-003 — `McpServersPage` end-to-end (list + filter + route + sidebar + integration tests)

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** an `/mcp-servers` page that lists the MCP servers configured on the platform,
with a name+description filter and a clear empty state when nothing matches
**So that** I can see which MCP integrations are available before enabling them on an
agent in EPIC-05.

### Description

Structurally identical to `ToolsPage` (US-04-002). The differences are:

- Schema: `McpServerDescriptor` has `name` (required) and `description` (**nullable**) —
  the table cell must render `—` (em-dash) when `description == null`, and the filter
  must treat a `null` description as a non-matching empty string.
- Hook: `useMcpServers()`.
- Route: `/mcp-servers`.
- Sidebar entry: `MCP servers`.

The acceptance criteria below intentionally mirror US-04-002's structure so the diff is
small and the reviewer can verify each case point-by-point.

### Acceptance criteria

- `frontend/src/features/catalog/McpServerList.tsx` exists and accepts a single prop
  `{ items: McpServerDescriptor[] }`. It renders:
  - A controlled text `Input` labelled "Filter MCP servers" with placeholder
    "Filter by name or description…".
  - A table with two columns (`Name`, `Description`). `Name` uses the `font-mono` token;
    `Description` renders the literal em-dash `—` when `description == null`.
  - Filtering is case-insensitive against `name || (description ?? '')`.
  - When `items.length === 0`, renders an `EmptyState` with title
    "No MCP servers configured" and a short caption.
  - When filter produces 0 rows, renders `No MCP servers match "<query>"` + a "Clear
    filter" `Button`.
- `frontend/src/pages/catalog/McpServersPage.tsx` exists, exporting
  `function McpServersPage(): JSX.Element`. It composes:
  - Heading "MCP servers" + caption "MCP integrations available for enabling on an agent."
  - `useMcpServers()` consumption: `isPending` → 4 skeleton rows; `isError` → inline error
    state with Retry; `isSuccess` → `<McpServerList items={data} />`.
- The route `/mcp-servers` is registered under the protected `<AppShell>` layout via
  `React.lazy()`.
- The `Sidebar` gains an `MCP servers` link wired to `/mcp-servers`, visible to all
  authenticated principals.
- **Integration tests** in `frontend/src/pages/catalog/McpServersPage.test.tsx`:
  - **List renders**: MSW returns 3 servers (one with `description: null`); all 3 rows
    appear; the null-description row renders `—`.
  - **Filter narrows**: typing into the filter narrows; the null-description row only
    appears when the query matches its `name`.
  - **Empty-state when no match**: typing a non-matching query renders the
    `No MCP servers match "…"` empty state; clicking "Clear filter" restores the list.
  - **Server-side empty**: `{ items: [] }` renders the `No MCP servers configured` empty
    state.
  - **`INTERNAL_ERROR` surfaces as retryable**: 500 then 200 after a Retry click.

### Out of scope

- A "Connection status" column — the spec doesn't expose runtime MCP health.
- Per-server documentation links — not in the spec.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1, §7.6, §10.2, §12.9, §16.1.
- `openapi.yaml` `GET /mcp-servers`, `McpServerDescriptor` (note: `description` is
  `nullable: true`).

### Dependencies

- US-04-001 (`useMcpServers`); EPIC-02 (same primitives as US-04-002). Soft sibling
  dependency on US-04-002 — landing US-04-002 first means the `ToolsPage` integration
  patterns are already proven, so US-04-003 is mechanically a copy with the schema-null
  difference.

---

## Summary

| ID         | Title                                                                              | Priority | Status |
|------------|------------------------------------------------------------------------------------|----------|--------|
| US-04-001  | Catalog hooks: `useTools` + `useMcpServers` (static `staleTime: Infinity`)         | MUST     | Done   |
| US-04-002  | `ToolsPage` end-to-end (list + filter + route + sidebar + integration tests)       | MUST     | Done   |
| US-04-003  | `McpServersPage` end-to-end (list + filter + route + sidebar + integration tests)  | MUST     | Done   |

EPIC-04 is **Done** when all three stories are `Done`. EPIC-05 (Agents management) can
then consume `useTools` and `useMcpServers` in its `ToolPicker` / `McpServerPicker`
pickers from day one.
