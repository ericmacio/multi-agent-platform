# EPIC-05-US.md — User stories for EPIC-05 (Agents management — owner-scoped CRUD)

This file lists the user stories that deliver **EPIC-05 — Agents management (owner-scoped
CRUD)** of the frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-05 is the **primary write surface** of the platform: it is the first feature slice
where the user creates persistent business state. After it lands, an end-user can list,
view, create, edit, and delete their own agents, configure team / tool / MCP-server
selection, and see the server-side conflict codes surfaced cleanly in the form. The
sectioned `AgentForm` is the load-bearing artifact; everything else composes it.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-05-<nnn>` — `05` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All seven stories are `MUST` (Agents management
  is a gate on the chat surface in EPIC-06/07).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                    | Priority | Status | Depends on                       |
|------------|------------------------------------------------------------------------------------------|----------|--------|----------------------------------|
| US-05-001  | `agentSchema` Zod schema + Agent query/mutation hooks                                    | MUST     | Done   | EPIC-02                          |
| US-05-002  | `ToolPicker` + `McpServerPicker` (checkbox-list pickers backed by catalog hooks)         | MUST     | Done   | US-05-001, EPIC-04 (US-04-001)   |
| US-05-003  | `TeamPicker` — multi-select with nested-team disabling + tooltip preview                  | MUST     | Done   | US-05-001                        |
| US-05-004  | `AgentForm` — sectioned form (Identity / Behavior / Model / Tools / MCP / Team) + error routing | MUST | Done   | US-05-001, US-05-002, US-05-003  |
| US-05-005  | `AgentList` + `AgentCard` + `DeleteAgentDialog` (grid + per-card actions + cascade-warning delete) | MUST | Done   | US-05-001                       |
| US-05-006  | `AgentsPage` + routes wiring + sidebar entry (list page + empty state + "New agent" CTA) | MUST     | Done   | US-05-005                        |
| US-05-007  | `AgentCreatePage` + `AgentEditPage` + `AgentDetailPage` (page composition + integration tests) | MUST | Done   | US-05-004, US-05-006             |

---

## US-05-001 — `agentSchema` Zod schema + Agent query/mutation hooks

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `agentSchema` Zod schema mirroring `openapi.yaml.AgentRequest`'s
client-visible constraints AND the five typed hooks against the `/agents` collection —
`useAgents` (infinite, cursor-paginated), `useAgent`, `useCreateAgent`, `useUpdateAgent`,
`useDeleteAgent`
**So that** every consumer (AgentForm, AgentList, the three agent pages) reads its
validation rules and its HTTP plumbing from one place, and the post-mutation cache
invalidations happen in one location rather than being re-derived per call site.

### Description

Per SW-DESIGN §9.2, the schema mirrors **only** the openapi-documented constraints; the
cross-field "single-level team" rule is intentionally **not** mirrored on the client (the
server returns `NESTED_TEAM_FORBIDDEN` / `CROSS_OWNER_TEAM_MEMBER` which `AgentForm`
surfaces as form-level errors).

The five hooks follow the standard pattern established by EPIC-03's auth hooks
(US-03-002) and EPIC-04's catalog hooks (US-04-001):

- **`useAgents`** — `useCursorInfiniteQuery` (US-02-005) against `GET /agents`. The
  caller flattens via `flattenPages()`. Default `pageSize` 20 per openapi.
- **`useAgent(agentId)`** — `useQuery` against `GET /agents/{agentId}`. Disabled when
  `agentId` is empty. `enabled: Boolean(agentId)`.
- **`useCreateAgent`** — `useMutation` against `POST /agents`. On success: invalidates
  `qk.agents.all()` so the list refetches. Does **not** push the new agent into the cache
  manually; a clean refetch keeps the cache honest (the list is small and not hot).
- **`useUpdateAgent`** — `useMutation` against `PUT /agents/{agentId}`. On success:
  invalidates `qk.agents.all()` (the list may re-order) AND
  `qk.agents.byId(agentId)` (the detail).
- **`useDeleteAgent`** — `useMutation` against `DELETE /agents/{agentId}`. On success:
  invalidates `qk.agents.all()` AND `qk.conversations.list(null)` /
  `qk.conversations.list(agentId)` (the delete cascades to conversations per
  `REQ-AGT-010`; if EPIC-06 keys exist, invalidate; if they don't yet, the call is a
  no-op against an absent key).

### Acceptance criteria

- `frontend/src/features/agents/schema.ts` exists with the following exports:
  - `import { components } from '@/generated/schema';`
  - `export type AgentRequest = components['schemas']['AgentRequest'];`
  - `export type Agent = components['schemas']['Agent'];`
  - `export const agentSchema = z.object({
       name: z.string().min(1).max(32),
       description: z.string().min(1).max(1024),
       systemPrompt: z.string().min(1).max(1024),
       memorySize: z.number().int().min(1).max(36).default(12),
       llmModel: z.string().max(64).nullable().optional(),
       temperature: z.number().nullable().optional(),
       maxOutputTokens: z.number().int().min(1).nullable().optional(),
       topP: z.number().nullable().optional(),
       tools: z.array(z.string().max(64)).default([]),
       enabledMcpServers: z.array(z.string().max(64)).default([]),
       team: z.array(z.string().uuid()).default([])
     });`
  - `export type AgentValues = z.infer<typeof agentSchema>;`
- The schema is byte-aligned with `openapi.yaml.AgentRequest`. The single-level team rule
  is **not** present (per SW-DESIGN §9.2).
- `frontend/src/features/agents/api.ts` exists with:
  - `export function useAgents(opts?: { pageSize?: number }): UseInfiniteQueryResult<AgentPage, ApiError>`
  - `export function useAgent(agentId: string | undefined): UseQueryResult<Agent, ApiError>`
  - `export function useCreateAgent(): UseMutationResult<Agent, ApiError, AgentRequest>`
  - `export function useUpdateAgent(agentId: string): UseMutationResult<Agent, ApiError, AgentRequest>`
  - `export function useDeleteAgent(): UseMutationResult<void, ApiError, { agentId: string }>`
- Query keys come from `qk.agents.*` (US-02-004); if `qk.agents.all/list/byId` are not
  yet defined, this story adds them — one-line additions per SW-DESIGN §7.4.
- `useAgents` uses the shared `useCursorInfiniteQuery` (US-02-005); `getNextPageParam`
  reads `lastPage.nextCursor`.
- `useCreateAgent.onSuccess`: `queryClient.invalidateQueries({ queryKey: qk.agents.all() })`.
- `useUpdateAgent.onSuccess`: invalidate `qk.agents.all()` AND
  `qk.agents.byId(agentId)`.
- `useDeleteAgent.onSuccess`: invalidate `qk.agents.all()`. If `qk.conversations.list`
  exists, invalidate it too (filter by `agentId` not required — the conversations cache
  is rebuilt cleanly).
- **Unit tests** in `frontend/src/features/agents/schema.test.ts`:
  - `agentSchema.safeParse({ name: '', description: 'x', systemPrompt: 'x' })` fails on
    `name.min`.
  - Submitting with `name.length === 33` fails on `name.max(32)`.
  - `memorySize: 0` and `memorySize: 37` both fail; `memorySize: 1` and `memorySize: 36`
    succeed.
  - `team: ['not-a-uuid']` fails; `team: ['7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1']`
    succeeds.
  - Optional fields (`llmModel`, `temperature`, `maxOutputTokens`, `topP`) accept `null`,
    `undefined`, or omission.
  - Defaults apply: parsing `{ name, description, systemPrompt }` yields `memorySize: 12`,
    `tools: []`, `enabledMcpServers: []`, `team: []`.
- **Unit tests** in `frontend/src/features/agents/api.test.tsx` (MSW + `renderHook`):
  - `useAgents` — 200 with `{ items: [agent], nextCursor: 'c2' }`: data has one page;
    `fetchNextPage` triggers a request with `?cursor=c2`.
  - `useAgent('id')` — 200 happy path returns the spec `Agent` shape.
  - `useAgent(undefined)` — query is `disabled`; no network request fires.
  - `useCreateAgent` — 201 happy path: `mutateAsync` resolves with the spec `Agent`; the
    agents list cache is invalidated (assert via a sibling `useAgents` that re-fetches
    after the mutation settles).
  - `useCreateAgent` — 409 `DUPLICATE_AGENT_NAME`: the mutation `error.code` is
    `DUPLICATE_AGENT_NAME`; the cache is **not** invalidated.
  - `useUpdateAgent('id')` — 200 happy path: detail cache for `id` is invalidated.
  - `useDeleteAgent` — 204 happy path: the agents list is invalidated.

### Out of scope

- A combined `AgentSelect` hook — premature; `TeamPicker` (US-05-003) consumes
  `useAgents` directly via `flattenPages`.
- Server-Sent Events or websocket invalidation — not in v1.
- Optimistic delete. Considered, but the cascade-warning UX (US-05-005) already pauses
  the user explicitly; a server round-trip is cheap and avoids ghosting an agent that
  failed to delete.

### Design references

- `frontend/design/SW-DESIGN.md` §7.4 (`qk` factory), §7.5 (mutation invalidation),
  §7.6 (stale-time table), §9.2 (constraint mirroring).
- `openapi.yaml` `GET /agents`, `POST /agents`, `GET/PUT/DELETE /agents/{agentId}`,
  `AgentRequest`, `Agent`, `AgentPage`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `qk`, `queryClient`, `useCursorInfiniteQuery`,
  `ApiError`).

---

## US-05-002 — `ToolPicker` + `McpServerPicker` (checkbox-list pickers backed by catalog hooks)

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user creating or editing an agent
**I want** two checkbox-list pickers — one for tools (`/tools`), one for MCP servers
(`/mcp-servers`) — each with a search filter, a "selected count" badge, and graceful
loading / error / empty states
**So that** the agent form's Tools and MCP sections render immediately on first paint,
the user can find a specific entry without scrolling, and an empty catalog presents a
useful caption rather than dead space.

### Description

The two pickers are **paired in one story** because they are structurally identical
(checkbox list against a static catalog) and share a single rendering primitive
internally. They differ only in:

- The catalog hook they consume (`useTools` vs `useMcpServers`).
- The shape of each item: tools have `name + description` (description always
  present); MCP servers have `name + description?` (nullable).
- The form field they bind to (`tools[]` vs `enabledMcpServers[]`).
- The empty-state copy.

Both pickers are **controlled** components (props: `value: string[]`, `onChange:
(next: string[]) => void`). They do **not** read from `react-hook-form` directly — the
form (`AgentForm`, US-05-004) uses `Controller` to bridge.

The "selected count" badge in the section heading reads from the `value` prop, not from
the catalog, so it stays correct even while the catalog is still loading.

### Acceptance criteria

- `frontend/src/features/agents/ToolPicker.tsx` exists with the prop shape:
  - `interface ToolPickerProps { value: string[]; onChange: (next: string[]) => void; disabled?: boolean; }`
- `frontend/src/features/agents/McpServerPicker.tsx` exists with the same prop shape.
- Both pickers internally render:
  - A `Card` frame with a header `Tools` / `MCP servers` + a `Badge` showing
    `${value.length} selected`.
  - A search `Input` filtering by case-insensitive substring against `name ||
    (description ?? '')`.
  - A list of `<Checkbox>` rows — one per catalog item — bound to a function
    `toggle(name)` that adds or removes the name from `value` and calls `onChange(next)`.
  - Each row renders `<Checkbox>` + monospace `name` + secondary `description`
    (em-dash `—` when `description` is `null`).
  - When the consumed catalog hook is `isPending`: render 5 skeleton rows.
  - When it `isError`: render an inline error state with the error's title and a "Retry"
    `Button` calling `query.refetch()`. The selected `value` stays intact (does NOT clear).
  - When the catalog is empty (`items.length === 0`): render an inline `EmptyState` with
    title "No tools configured" / "No MCP servers configured" and a caption referencing
    the platform configuration.
  - When the filter matches zero rows: render `No tools match "<query>"` /
    `No MCP servers match "<query>"` with a "Clear filter" button.
- The `disabled` prop, when `true`, disables every checkbox (the form passes this during
  `mutation.isPending`).
- An accessibility check: each row is a `<label>` wrapping the checkbox + text, so a
  click anywhere on the row toggles selection (the test below asserts this).
- **Component tests** in `frontend/src/features/agents/ToolPicker.test.tsx` and
  `McpServerPicker.test.tsx`:
  - **Selecting a row** fires `onChange` with `[...value, name]`.
  - **Deselecting a row** fires `onChange` with `value.filter(v => v !== name)`.
  - **Filter narrows**: typing into the search input reduces the visible rows.
  - **Selected count badge** updates synchronously with `value`.
  - **Loading state**: with the catalog hook returning `isPending`, 5 skeletons render
    and no checkbox is in the DOM.
  - **Error + Retry**: 500 from MSW shows the inline error; clicking Retry re-runs the
    query (verify by switching MSW to 200 and asserting the rows appear).
  - **Empty catalog**: `{ items: [] }` shows the empty state with the correct copy.
  - **Filter empty**: typing a non-match shows the "Clear filter" affordance.
  - **`McpServerPicker` null-description** row renders `—`.
  - **`disabled` prop**: with `disabled: true`, clicking a row does **not** fire
    `onChange`.

### Out of scope

- Group-by tag/category in the tool list — the spec doesn't expose `@ToolGroup`.
- Drag-and-drop reordering — the order in `tools[]` is semantically irrelevant per the
  backend.
- Persisting filter state across remounts — the user's selection is in the form, not in
  the picker's own state.

### Design references

- `frontend/design/SW-DESIGN.md` §11.4 (primitives), §12.4 (`AgentForm` Tools/MCP
  sections), §12.9 (catalog page shape — the picker is the inline equivalent).
- `openapi.yaml` `AgentRequest.tools`, `AgentRequest.enabledMcpServers`,
  `ToolDescriptor`, `McpServerDescriptor`.

### Dependencies

- US-05-001 (`agentSchema` types — for the typed prop shape); EPIC-04 US-04-001
  (`useTools`, `useMcpServers`); EPIC-02 (`Card`, `Badge`, `Checkbox`, `Input`,
  `Button`, `EmptyState`, `Skeleton`).

---

## US-05-003 — `TeamPicker` — multi-select with nested-team disabling + tooltip preview

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user creating or editing an agent
**I want** a `TeamPicker` multi-select that lists my own agents, disables every candidate
whose own `team` is non-empty (with the tooltip "Has a team of its own — cannot be a
delegate"), and excludes the agent currently being edited
**So that** I see the platform's single-level team rule (SW-DESIGN §9.2) as a live
preview, BEFORE submitting, even though the rule is enforced server-side and the form
surfaces `NESTED_TEAM_FORBIDDEN` if I work around the preview.

### Description

Per SW-DESIGN §9.2 / §12.4, the team picker:

- Lists every agent **owned by the caller** (`useAgents()` paginated via
  `flattenPages()`).
- Renders each candidate as a checkbox row showing `name` + `description` (truncated to
  one line).
- **Disables** candidates whose `team.length > 0` with the tooltip "Has a team of its
  own — cannot be a delegate" (the live preview of the single-level rule).
- **Excludes** the agent currently being edited (an `excludeAgentId?: string` prop) —
  passed by `AgentEditPage` so an agent cannot delegate to itself.
- Provides a search filter (case-insensitive against `name || description`).
- Provides a "Show only selected" toggle that filters the visible list to currently
  selected entries — handy when the user has 50 agents and 3 selected.
- Loads paginated until all pages are fetched (it does NOT virtualize) — agent counts
  per owner are expected to stay small per `REQ-NFR-005`. If the count grows past the
  comfortable threshold, the picker degrades gracefully (still functional, just longer
  scrollback).

### Acceptance criteria

- `frontend/src/features/agents/TeamPicker.tsx` exists with the prop shape:
  - `interface TeamPickerProps { value: string[]; onChange: (next: string[]) => void; excludeAgentId?: string; disabled?: boolean; }`
- Internally renders a `Card` frame with a header "Team" + selected-count `Badge`.
- Consumes `useAgents()` and calls `fetchNextPage()` in an effect until `hasNextPage`
  is `false`, so the displayed list is the **complete** set of owned agents. A tiny
  caption "Loading agents… (N loaded)" is shown while pagination is in flight.
- Renders one row per agent (after the `excludeAgentId` filter):
  - `<Checkbox>` + monospace `name` + secondary `description`.
  - When `agent.team.length > 0`, the checkbox is **disabled** and the row is wrapped in
    a `<Tooltip content="Has a team of its own — cannot be a delegate.">`.
  - When the agent is in `value`, the checkbox is `checked`.
- Provides:
  - A search `Input` filtering case-insensitive by `name || description`.
  - A "Show only selected" `Checkbox` toggle.
- States:
  - First-page loading (no items yet): 5 skeleton rows.
  - `useAgents` error: inline error state with `Retry`. Selected `value` stays intact.
  - All-pages-loaded empty (`items.length === 0` after filtering by `excludeAgentId`):
    inline `EmptyState` "No other agents to delegate to" with caption "Create another
    agent first."
- **Component tests** in `frontend/src/features/agents/TeamPicker.test.tsx`:
  - **Excluded self**: `excludeAgentId` is in the catalog but is NOT in the rendered
    list.
  - **Nested-team disabled + tooltip**: an agent with `team: ['otherId']` is rendered
    disabled; hovering shows the tooltip text "Has a team of its own — cannot be a
    delegate."; clicking the row does NOT fire `onChange`.
  - **Toggle on a normal candidate** fires `onChange([...value, candidateId])`.
  - **Filter narrows** and works in combination with **"Show only selected"** (both
    filters compose).
  - **Pagination drain**: with MSW returning two pages, after first paint the picker
    auto-fetches page 2 and ends up displaying the union (verifiable by asserting the
    final count of rendered rows).
  - **All-pages-loaded empty** (only one agent exists, and it's `excludeAgentId`): the
    "No other agents to delegate to" empty state renders.
- The story explicitly **does not** mirror the cross-owner rule
  (`CROSS_OWNER_TEAM_MEMBER`) — the caller can only see their own agents, so the
  client cannot present a cross-owner candidate. If the server somehow returns one
  (impossible by the spec), `AgentForm` surfaces the conflict on submit per US-05-004.

### Out of scope

- A "Suggest a team" affordance using LLM heuristics — not in v1.
- Showing each candidate's last-used timestamp — not exposed on the `Agent` schema.
- Cross-owner candidates — never visible to the user.

### Design references

- `frontend/design/SW-DESIGN.md` §9.2 (single-level rule + live preview), §12.4 (Team
  section of AgentForm).
- `openapi.yaml` `AgentRequest.team`, `Agent.team`.

### Dependencies

- US-05-001 (`useAgents`, `Agent` type); EPIC-02 (`Card`, `Badge`, `Checkbox`, `Input`,
  `Tooltip`, `EmptyState`, `Skeleton`, `flattenPages`).

---

## US-05-004 — `AgentForm` — sectioned form + error routing

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** a single `AgentForm` component composing the six sections from SW-DESIGN §12.4
(Identity / Behavior / Model / Tools / MCP / Team), using `react-hook-form` + `zod` on the
`agentSchema`, with a sticky action bar, scroll-to-first-error behavior, and explicit
routing for every documented server error code
**So that** `AgentCreatePage` and `AgentEditPage` are pure composition (`<AgentForm
mode='create' />` vs `<AgentForm mode='edit' initial={agent} />`) and the form's
error-handling contract is verified once at the form level.

### Description

Per SW-DESIGN §9.1 / §12.4, the form is `react-hook-form` + `@hookform/resolvers/zod`
against the `agentSchema` from US-05-001. It accepts a `mode: 'create' | 'edit'` prop
plus an optional `initial?: Agent` prop for edit mode. On submit:

- **Create** mode: calls `useCreateAgent().mutateAsync(values)`.
- **Edit** mode: calls `useUpdateAgent(initial.id).mutateAsync(values)`.

On success: an `onSuccess(agent: Agent)` prop is called — the page decides where to
navigate (typically to `AgentDetailPage`).

On error, the form maps each documented code to the user-facing UX:

| `ApiError.code`               | Routing                                                                                                                                                  |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `VALIDATION_ERROR`            | `setError(field, { message })` for each entry in `error.fieldErrors`; unmatched fields spill into top-of-form alert.                                     |
| `DUPLICATE_AGENT_NAME`        | `setError('name', { message: errorCopy.DUPLICATE_AGENT_NAME.title })`. Scroll to the `name` field.                                                       |
| `NESTED_TEAM_FORBIDDEN`       | `setError('team', { message: errorCopy.NESTED_TEAM_FORBIDDEN.title })`. Scroll to the Team section.                                                      |
| `CROSS_OWNER_TEAM_MEMBER`     | `setError('team', { message: errorCopy.CROSS_OWNER_TEAM_MEMBER.title })`. Scroll to the Team section.                                                    |
| `RATE_LIMITED`                | Top-of-form alert with countdown (same UX as `LoginForm`).                                                                                               |
| Any other code                | Top-of-form alert using `errorCopy[code].title || errorCopy.fallback.title`.                                                                             |

Scroll-to-first-error: on submit failure, after `setError` fires, the form scrolls the
first invalid field into view (uses `element.scrollIntoView({ block: 'center' })` on
the `data-rhf-field={fieldName}` anchor).

The sticky action bar at the bottom holds "Cancel" (calls `onCancel()` prop — typically
navigates back) and "Save" / "Create" (label depends on `mode`, `loading=isPending`).

### Acceptance criteria

- `frontend/src/features/agents/AgentForm.tsx` exists with the prop shape:
  - `interface AgentFormProps { mode: 'create' | 'edit'; initial?: Agent; onSuccess: (agent: Agent) => void; onCancel: () => void; }`
- Uses `useForm<AgentValues>({ resolver: zodResolver(agentSchema), defaultValues })`.
  - In `create` mode, `defaultValues` come from `agentSchema.parse({ name: '', description: '', systemPrompt: '' })` (so the schema defaults populate `memorySize: 12`, `tools: []`, etc.).
  - In `edit` mode, `defaultValues = initial` (with the optional fields normalized so the
    form controls behave: `temperature: initial.temperature ?? undefined`).
- Renders six sections, each wrapped in a `<section data-rhf-section="<name>">`:
  - **Identity**: `Input` for `name` (with character count `N/32`), `Textarea` for
    `description` (with `N/1024`).
  - **Behavior**: `Textarea` for `systemPrompt` (with `N/1024`); `memorySize` rendered
    as a numeric `Input type="number" min=1 max=36` with a current-value badge ("Memory:
    12 messages").
  - **Model**: An "Use platform default" toggle that, when checked, clears (`null`s)
    `llmModel`, `temperature`, `maxOutputTokens`, `topP`. When unchecked, four optional
    inputs render (string for `llmModel`, number for the others).
  - **Tools**: `<ToolPicker>` bound via `Controller`.
  - **MCP servers**: `<McpServerPicker>` bound via `Controller`.
  - **Team**: `<TeamPicker excludeAgentId={initial?.id}>` bound via `Controller`.
- The sticky action bar lives in a `<div className="sticky bottom-0 …">` and holds:
  - `Cancel` button calling `onCancel()`.
  - `Save` (edit) / `Create agent` (create) button with `type="submit"`,
    `loading={mutation.isPending}`, `disabled={mutation.isPending}`.
- On submit success: calls `onSuccess(agent)` with the returned `Agent`.
- On submit failure, the error-routing table above is honored exactly.
  - For `setError`-mapped codes, the matching anchor is scrolled into view via
    `scrollIntoView({ block: 'center', behavior: 'smooth' })`.
- The form sets every field input's `data-rhf-field={fieldName}` attribute on the
  outermost element of the input so scroll-to-error has a stable anchor.
- **Component tests** in `frontend/src/features/agents/AgentForm.test.tsx`:
  - **Empty-form submit** in create mode: Zod-driven field errors render under `name`,
    `description`, `systemPrompt`.
  - **Create happy path**: filling valid values + clicking Create issues
    `POST /agents` (verify via MSW); `onSuccess` is called with the server's `Agent`.
  - **`409 DUPLICATE_AGENT_NAME`**: `name` field shows the `errorCopy.DUPLICATE_AGENT_NAME.title`;
    no other field has an error; the focused/scrolled section is Identity.
  - **`409 NESTED_TEAM_FORBIDDEN`**: the Team section's form-level error renders the
    correct copy; the Team section is scrolled into view.
  - **`409 CROSS_OWNER_TEAM_MEMBER`**: same as above with the cross-owner copy.
  - **`400 VALIDATION_ERROR` with `errors: [{ field: 'systemPrompt', message: '…' }]`**:
    `systemPrompt` field shows the server-provided message.
  - **`400 VALIDATION_ERROR` with `errors: [{ field: 'unknownField', message: '…' }]`**:
    the top-of-form fallback alert renders.
  - **`429 RATE_LIMITED` with `Retry-After: 5`**: top-of-form countdown alert renders;
    Submit is disabled for 5 seconds.
  - **Edit mode pre-fill**: `<AgentForm mode="edit" initial={agent} />` renders the
    fields with the existing agent's values; the Team picker's `excludeAgentId` is set
    to `agent.id` (verified by the picker's rendered candidate list).
  - **Use-platform-default toggle**: checking it `null`s the four model fields on
    submit; unchecking re-shows the four inputs.
  - **Cancel** calls `onCancel()` (no network call fires).

### Out of scope

- Markdown rendering inside `description` or `systemPrompt` previews — plain text only
  per SW-DESIGN §15.
- Auto-save / draft persistence — explicitly deferred; the user submits manually.
- A confirmation dialog on "Cancel" when the form is dirty — would be a polish item;
  defer to EPIC-11 if asked.

### Design references

- `frontend/design/SW-DESIGN.md` §9.1 (forms), §9.2 (constraint mirroring), §9.4
  (server-side errors), §10.2 (per-code routing), §12.4 (AgentForm shape).
- `openapi.yaml` `AgentRequest`, `Agent`, `POST /agents`, `PUT /agents/{agentId}`,
  conflict codes.

### Dependencies

- US-05-001 (`agentSchema`, `useCreateAgent`, `useUpdateAgent`); US-05-002 (`ToolPicker`,
  `McpServerPicker`); US-05-003 (`TeamPicker`); EPIC-02 (`Input`, `Textarea`, `Button`,
  `Checkbox`, `Card`, alert / toast primitives).

---

## US-05-005 — `AgentList` + `AgentCard` + `DeleteAgentDialog`

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** a `AgentList` that paginates my agents into a CSS grid of `AgentCard`s with a
per-card action menu (View / Edit / Start chat / Delete), and a `DeleteAgentDialog` that
warns about the cascade to conversations before confirming
**So that** I can browse my agents at a glance, jump straight to chat, and not
accidentally delete an agent whose conversations I care about.

### Description

Per SW-DESIGN §12.3, `AgentsPage` (US-05-006) is a thin shell over this trio. The list,
the card, and the delete dialog ship together because each is small and the integration
tests for delete-with-cascade are most natural at the list level.

`AgentCard` renders the metadata listed in SW-DESIGN §12.3:

- `name` (heading, font-mono accent).
- `description` (clamped to 2 lines via `line-clamp-2`).
- Badges row: tool count (e.g., `3 tools`), MCP servers (each name as its own `Badge`),
  model (badge with the model name or `default`), team size (when `team.length > 0`,
  e.g., `Team of 2`).
- Last-updated timestamp using `formatRelative(updatedAt)` (US-02-001).
- A `<Dropdown>` action menu with four entries: **View**, **Edit**, **Start chat**,
  **Delete**.

`AgentList` renders the cards in a CSS grid that responds at the
`sm`/`md`/`lg` Tailwind breakpoints (`1`/`2`/`3` columns), uses `flattenPages()` to
collapse the cursor-paginated `useAgents()` result, and ends with a "Load more" `Button`
when `hasNextPage` is true (`<Button onClick={fetchNextPage} loading=
{isFetchingNextPage}>`).

`DeleteAgentDialog` is a `Modal` wrapping a confirm dialog. The body quotes the cascade
warning:

> *"Deleting this agent will also delete every conversation with it. This cannot be
> undone."*

The user types the agent's `name` into a confirmation `Input` (the Delete button stays
disabled until the typed text exactly matches the agent's name). On confirm: calls
`useDeleteAgent().mutateAsync({ agentId })`; on success: closes the dialog and the
`onDeleted(agent)` prop is called.

### Acceptance criteria

- `frontend/src/features/agents/AgentCard.tsx` exists with the prop shape:
  - `interface AgentCardProps { agent: Agent; onView: () => void; onEdit: () => void; onStartChat: () => void; onDelete: () => void; }`
- Renders:
  - `Card` frame, hover state subtle (per the design tokens).
  - Heading: `agent.name` in `font-mono` with the accent color token.
  - Description: `agent.description` with `line-clamp-2`.
  - Badges row: `${tools.length} tools`, one badge per `enabledMcpServers[i]` (with
    overflow `+N` when > 3), `agent.llmModel ?? 'default'`, `Team of ${agent.team.length}`
    (only when > 0).
  - Footer: `Last updated ${formatRelative(agent.updatedAt)}`.
  - A `<Dropdown>` trigger button (top-right) opening the four-entry action menu. Each
    entry calls the matching prop.
- `frontend/src/features/agents/AgentList.tsx` exists with the prop shape:
  - `interface AgentListProps { onView: (id: string) => void; onEdit: (id: string) => void; onStartChat: (id: string) => void; onDelete: (agent: Agent) => void; }`
- Consumes `useAgents()` + `flattenPages()`. Renders:
  - The CSS grid of `AgentCard`s with the responsive column counts above.
  - A "Load more" button when `hasNextPage`, wired to `fetchNextPage` with the
    `isFetchingNextPage` spinner.
  - First-page loading: 6 skeleton cards.
  - First-page error: inline error state with Retry.
  - Empty: the **page-level** empty state ("You don't have any agents yet") is rendered
    by `AgentsPage` (US-05-006), not the list. So `AgentList` returns `null` when the
    flattened items are empty, leaving the empty state to its parent.
- `frontend/src/features/agents/DeleteAgentDialog.tsx` exists with the prop shape:
  - `interface DeleteAgentDialogProps { agent: Agent | null; open: boolean; onClose: () => void; onDeleted: (agent: Agent) => void; }`
- Renders a `Modal` with:
  - Heading: `Delete ${agent.name}?`
  - Body: the cascade warning copy verbatim above + a confirmation `Input` labelled
    `Type "${agent.name}" to confirm.`
  - Footer: `Cancel` + `Delete` (destructive variant). Delete is disabled until the
    typed text matches; loading while the mutation is in flight.
  - On success: calls `onDeleted(agent)` then `onClose()`.
  - On error: renders an inline error alert inside the dialog (the dialog stays open so
    the user can retry).
- **Component tests** in `frontend/src/features/agents/AgentCard.test.tsx`:
  - All four action-menu entries fire their respective prop callbacks.
  - The MCP-server badges show with the overflow `+N` indicator at counts > 3.
  - The team badge is absent when `team.length === 0`.
- **Component tests** in `frontend/src/features/agents/AgentList.test.tsx`:
  - **Pagination**: MSW returns two pages; first paint shows page 1; clicking "Load
    more" appends page 2.
  - **Skeleton loading** renders 6 skeleton cards on first paint.
  - **Error + Retry** path works.
- **Component tests** in `frontend/src/features/agents/DeleteAgentDialog.test.tsx`:
  - Delete button starts `disabled`; typing the agent's exact name enables it;
    typing a wrong value disables it again.
  - On 204 success: `onDeleted` is called and the dialog closes.
  - On 500 error: an inline error alert renders inside the dialog; the dialog stays
    open; the agents list cache is **not** invalidated (verified by a sibling
    `useAgents` not refetching).
  - On 404 NOT_FOUND (agent already deleted in another tab): the dialog renders the
    error inline AND `onDeleted` is **not** called.

### Out of scope

- Bulk-delete affordance — not in v1.
- Drag-and-drop reordering of cards — `Agent.order` doesn't exist in the spec.
- Optimistic delete — explicitly chosen against in US-05-001's rationale.

### Design references

- `frontend/design/SW-DESIGN.md` §11.4 (primitives), §12.3 (AgentsPage card shape with
  the metadata list), §12.5 (`AgentDetailPage` actions — same "Start chat" /
  "Delete with cascade" semantics).
- `openapi.yaml` `Agent`, `DELETE /agents/{agentId}` (cascade note in the description).

### Dependencies

- US-05-001 (`useAgents`, `useDeleteAgent`, `Agent` type); EPIC-02 (`Card`, `Badge`,
  `Button`, `Dropdown`, `Modal`, `Input`, `Skeleton`, `formatRelative`).

---

## US-05-006 — `AgentsPage` + routes wiring + sidebar entry

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the `/agents` page composing `AgentList` with the page-level header ("Agents"
heading + "New agent" CTA), the dedicated empty state when I own zero agents, the
sidebar navigation entry, and the four-route wiring (`/agents`, `/agents/new`,
`/agents/:agentId`, `/agents/:agentId/edit`) registered under the protected `AppShell`
**So that** I have one obvious entry point into the platform's primary write surface
from the sidebar, and every deep link routes to the right page.

### Description

`AgentsPage` is intentionally thin — it composes `AgentList` (US-05-005), adds the
page-level header + empty-state branch, and owns the `DeleteAgentDialog`'s open state.
It does **not** own the form (the create / edit / detail pages do; this story just
wires the routes for them — the page bodies for create/edit/detail land in US-05-007).

The four routes are all registered under the protected `<AppShell>` layout established
in US-03-007:

- `/agents` → `AgentsPage` (this story).
- `/agents/new` → `AgentCreatePage` (placeholder in this story; body in US-05-007).
- `/agents/:agentId` → `AgentDetailPage` (placeholder in this story; body in US-05-007).
- `/agents/:agentId/edit` → `AgentEditPage` (placeholder in this story; body in
  US-05-007).

All four routes are `React.lazy()`-loaded per SW-DESIGN §16.1.

The sidebar gains an `Agents` link with the `Bot`-or-equivalent lucide icon, visible to
all authenticated principals (no admin gating). It lives at the top of the standard-user
nav group.

### Acceptance criteria

- `frontend/src/pages/agents/AgentsPage.tsx` exists, exporting
  `function AgentsPage(): JSX.Element`. Renders:
  - A page heading `Agents` + a one-line caption "Create and manage your AI agents.".
  - A primary `Button` "New agent" navigating to `/agents/new`.
  - Conditional body:
    - When `useAgents()` is `isPending` (first paint): 6 skeleton cards (delegated to
      `AgentList`).
    - When `useAgents()` is `isError` on first paint: inline error state with Retry.
    - When the flattened agents array is empty: the page-level `EmptyState` with title
      `You don't have any agents yet`, caption short, and a single primary CTA
      `Create your first agent` navigating to `/agents/new` (per SW-DESIGN §12.3).
    - Otherwise: `<AgentList onView={…} onEdit={…} onStartChat={…} onDelete={…} />` with
      the four callbacks navigating respectively to `/agents/:id`, `/agents/:id/edit`,
      `/chat/new?agentId=:id` (a soft-target until EPIC-06 lands), and opening the
      `DeleteAgentDialog`.
  - Owns the `DeleteAgentDialog`'s open state. On `onDeleted`, shows a success toast
    "Agent deleted." (a hard-coded copy is acceptable; if the i18n map gains a key, the
    page reads from it).
- The route table (`frontend/src/pages/routes.tsx`) is updated to register the four
  routes under the protected `<AppShell>` layout with `React.lazy()` imports. For
  US-05-006 the three create/edit/detail routes can resolve to a minimal placeholder
  page (`<>Coming in US-05-007</>`) — US-05-007 replaces the placeholders.
- The `Sidebar` gains an `Agents` link wired to `/agents` (the existing role-gated
  groups remain untouched).
- **Integration tests** in `frontend/src/pages/agents/AgentsPage.test.tsx` under the
  full provider stack:
  - **Empty state**: MSW returns `{ items: [], nextCursor: null }`; the empty state +
    its single CTA render; clicking `Create your first agent` navigates to
    `/agents/new`.
  - **Populated list**: MSW returns 3 agents; all 3 cards render; the header CTA
    `New agent` navigates to `/agents/new`.
  - **Pagination**: with 2 pages of MSW responses, the list renders page 1; clicking
    Load more appends page 2.
  - **Delete-with-cascade**: clicking an agent's `Delete` action opens the dialog with
    the cascade warning; typing the agent's name + clicking Delete fires
    `DELETE /agents/{id}` (MSW asserts the call); after 204, the dialog closes, the
    success toast renders, and the agent is no longer in the list (after the cache
    invalidation refetches).
  - **Delete failure**: 500 on delete leaves the dialog open and the list intact.
- **Integration tests** in `frontend/src/pages/routes.integration.test.tsx` (extending
  the suite seeded in US-03-007):
  - Navigating to `/agents/new` while authenticated lands on the placeholder page
    (proving the route is registered; the real page lands in US-05-007).
  - Navigating to `/agents/abc` and `/agents/abc/edit` likewise land on placeholders.

### Out of scope

- The three create/edit/detail page bodies — US-05-007.
- A "Start chat" shortcut that actually creates a conversation — that requires
  EPIC-06's `POST /conversations`; until then, "Start chat" routes to `/chat/new?agentId=:id`
  and the chat surface decides what to do. If EPIC-06 hasn't landed, that route renders
  the EPIC-02 `HomePlaceholder` and the test for this branch is allowed to just assert
  the URL change (not the page body).

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table — the four `/agents/...` routes),
  §12.3 (`AgentsPage` shape + empty state), §16.1 (lazy loading).
- `openapi.yaml` `GET /agents`, `DELETE /agents/{agentId}`.

### Dependencies

- US-05-005 (`AgentList`, `AgentCard`, `DeleteAgentDialog`); EPIC-02 (`Button`,
  `EmptyState`, `Sidebar`, `AppShell`, `Toast`, route table).

---

## US-05-007 — `AgentCreatePage` + `AgentEditPage` + `AgentDetailPage` + integration tests

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the three remaining agent pages — Create, Edit, Detail — composing the form
(US-05-004) and the read-only detail view (with the "Recent conversations" panel that
stubs out gracefully until EPIC-06 lands)
**So that** my CRUD round-trip is complete end-to-end: I can land on `/agents/new`, fill
the form, hit Create, get redirected to `/agents/:id`, click Edit, save, get redirected
back, and click Delete to remove the agent.

### Description

The three pages are paired in one story because they are pure composition — the heavy
lifting is in `AgentForm` (US-05-004) and `useAgent` (US-05-001). Splitting them would
just multiply boilerplate.

- **`AgentCreatePage`** — `/agents/new`. Renders heading "New agent" +
  `<AgentForm mode="create" onSuccess={(agent) => navigate('/agents/' + agent.id)} onCancel={() => navigate('/agents')} />`.
- **`AgentEditPage`** — `/agents/:agentId/edit`. Reads `agentId` from the URL; calls
  `useAgent(agentId)`. While `isPending`: skeleton. On `isError`: forwards 404 to the
  not-found empty state and any other error to a retryable inline state. On `isSuccess`:
  renders `<AgentForm mode="edit" initial={data} onSuccess={() => navigate('/agents/' + agentId)} onCancel={() => navigate('/agents/' + agentId)} />`.
- **`AgentDetailPage`** — `/agents/:agentId`. Reads `agentId` from the URL; calls
  `useAgent(agentId)`. Renders a read-only summary mirroring the six form sections
  (Identity, Behavior, Model, Tools, MCP, Team) — each section displays the values
  with the same visual hierarchy as the form but no inputs. A CTA bar at the top holds
  three actions: `Start chat` (navigates to `/chat/new?agentId=:id`), `Edit` (navigates
  to `/agents/:id/edit`), `Delete` (opens `DeleteAgentDialog`; on success navigates
  back to `/agents`).
- **"Recent conversations" panel** on `AgentDetailPage`: per SW-DESIGN §12.5, calls
  `listConversations` with `{ agentId, pageSize: 5 }`. Until EPIC-06 lands, this panel
  is **stubbed** — it renders an `<EmptyState>` with title "Conversations coming soon"
  rather than firing a call. The story includes a TODO comment naming the EPIC-06
  story that will wire the real hook. Once EPIC-06 ships `useConversations`, the
  stub is swapped for the live call in a one-line change.

### Acceptance criteria

- `frontend/src/pages/agents/AgentCreatePage.tsx` exists, exporting
  `function AgentCreatePage(): JSX.Element`. Renders heading + `AgentForm` (`mode='create'`).
  - On `onSuccess(agent)`: `navigate('/agents/' + agent.id, { replace: true })` and
    fires a success toast `Agent created`.
  - On `onCancel`: `navigate('/agents')`.
- `frontend/src/pages/agents/AgentEditPage.tsx` exists, exporting
  `function AgentEditPage(): JSX.Element`. Reads `useParams<{ agentId: string }>()`;
  calls `useAgent(agentId)`. Render branches:
  - `isPending`: 6 skeleton sections inside a card frame.
  - `isError` with `error.status === 404`: an `<EmptyState>` `Agent not found` + a
    link `Back to agents` → `/agents`.
  - Other `isError`: inline error state with Retry.
  - `isSuccess`: heading `Edit ${agent.name}` + `<AgentForm mode='edit' initial={data}>`.
    - On `onSuccess`: `navigate('/agents/' + agentId, { replace: true })` and toast
      `Agent updated`.
    - On `onCancel`: `navigate('/agents/' + agentId)`.
- `frontend/src/pages/agents/AgentDetailPage.tsx` exists, exporting
  `function AgentDetailPage(): JSX.Element`. Reads `useParams<{ agentId: string }>()`;
  calls `useAgent(agentId)`. Render branches:
  - `isPending`: skeleton.
  - `isError` 404: same `Agent not found` empty state as Edit.
  - Other `isError`: retryable inline error.
  - `isSuccess`:
    - Heading: `agent.name` + the three-action CTA bar (Start chat / Edit / Delete).
    - Six read-only sections mirroring the form. Empty `tools[]` /
      `enabledMcpServers[]` / `team[]` render a single muted "(none)" line. Model
      section: when all four model fields are null, renders `Using platform default`.
    - The "Recent conversations" panel at the bottom: an `<EmptyState>` titled
      `Conversations coming soon` for the EPIC-05 ship; a `// TODO US-06-...` comment
      names the EPIC-06 story.
  - Owns the `DeleteAgentDialog`'s open state. On `onDeleted`:
    `navigate('/agents', { replace: true })` and toast `Agent deleted`.
- The three route entries in `pages/routes.tsx` (registered as placeholders in
  US-05-006) are swapped for `React.lazy()` imports of the real pages.
- **Integration tests** in `frontend/src/pages/agents/AgentCreatePage.test.tsx`:
  - Filling valid form + Create → MSW responds 201 → location moves to
    `/agents/<id>` → toast appears.
  - `409 DUPLICATE_AGENT_NAME` → the name field shows the conflict copy (already
    verified at the form level, but page-level smoke confirms it surfaces).
- **Integration tests** in `frontend/src/pages/agents/AgentEditPage.test.tsx`:
  - Mount at `/agents/abc/edit` with MSW returning a `200 Agent`; the form is pre-filled.
  - Submitting valid edits → MSW 200 → location returns to `/agents/abc` → toast.
  - `404 NOT_FOUND` (e.g., the agent was deleted in another tab): the not-found empty
    state renders with a Back link.
  - The TeamPicker excludes the current `agent.id` from its candidate list.
- **Integration tests** in `frontend/src/pages/agents/AgentDetailPage.test.tsx`:
  - Mount at `/agents/abc` with a populated `Agent`: all six read-only sections render
    with the correct values; empty arrays render `(none)`; all-null model fields render
    `Using platform default`.
  - Clicking `Start chat` navigates to `/chat/new?agentId=abc` (target page is a
    placeholder under EPIC-06 — assert via URL only).
  - Clicking `Edit` navigates to `/agents/abc/edit`.
  - Clicking `Delete` + completing the dialog navigates to `/agents` + fires the toast.
  - The "Recent conversations" panel renders the stub empty state.

### Out of scope

- Live "Recent conversations" wiring — EPIC-06.
- The chat surface itself — EPIC-06 / EPIC-07.
- A "Duplicate agent" affordance — not in the spec; would be a polish item.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (routes), §12.3–12.5 (Agents pages).
- `openapi.yaml` `GET/PUT/DELETE /agents/{agentId}`.

### Dependencies

- US-05-004 (`AgentForm`); US-05-005 (`DeleteAgentDialog`); US-05-006 (routes + sidebar
  + the AgentsPage that serves as the navigation target on cancel / delete-success);
  EPIC-02 (`Skeleton`, `EmptyState`, `Button`, `Toast`).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-05-001  | `agentSchema` Zod schema + Agent query/mutation hooks                                              | MUST     | Done   |
| US-05-002  | `ToolPicker` + `McpServerPicker` (checkbox-list pickers backed by catalog hooks)                   | MUST     | Done   |
| US-05-003  | `TeamPicker` — multi-select with nested-team disabling + tooltip preview                            | MUST     | Done   |
| US-05-004  | `AgentForm` — sectioned form (Identity / Behavior / Model / Tools / MCP / Team) + error routing    | MUST     | Done   |
| US-05-005  | `AgentList` + `AgentCard` + `DeleteAgentDialog` (grid + per-card actions + cascade-warning delete) | MUST     | Done   |
| US-05-006  | `AgentsPage` + routes wiring + sidebar entry (list page + empty state + "New agent" CTA)           | MUST     | Done   |
| US-05-007  | `AgentCreatePage` + `AgentEditPage` + `AgentDetailPage` (page composition + integration tests)     | MUST     | Done   |

EPIC-05 is **Done** when all seven stories above are `Done`. The next step is then
EPIC-06 (Conversations & messages — non-streaming surface), which lights up the "Recent
conversations" panel stubbed in US-05-007 and unlocks the chat surface.
