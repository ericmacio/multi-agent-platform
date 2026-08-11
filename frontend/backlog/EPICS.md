# EPICS.md — Frontend EPICs

This document lists the EPICs of the **frontend** module of the multi-agent platform. Each EPIC
groups a coherent slice of capabilities and translates a portion of `frontend/design/SW-DESIGN.md`
and `openapi.yaml` into deliverable work. The detailed user stories of each EPIC will live in a
companion file `EPIC-<ref>-US.md` (to be created in a subsequent step).

## Conventions

- **ID format**: `EPIC-<nn>` — two-digit zero-padded sequence reflecting recommended build order.
- **Status**: one of `Draft`, `Ready`, `In progress`, `Done`. All EPICs start as `Draft`.
- **Priority**: `MUST` (v1 must-have), `SHOULD` (strongly desired for v1), `COULD` (nice-to-have).
- **Design references**: section(s) of `frontend/design/SW-DESIGN.md` that scope the work.
- **API surface (consumed)**: `operationId`s from `openapi.yaml` the EPIC depends on. EPICs that
  do not call any backend endpoint declare `none`.
- **Backend-requirement anchors**: requirement IDs from `backend/requirements/REQS.md` whose
  user-facing behavior is realized by this EPIC. The frontend is not the authority on these
  requirements — it surfaces them.
- **Dependencies**: other frontend EPICs that must reach a workable state first.

## Build order rationale

EPICs are numbered by recommended build order, not by importance. **Foundations first**
(project scaffold, shared layer with API client, design system, auth machinery), then **read-only
catalogs** which unlock the agent form, then **agents** (the platform's primary write surface),
then **conversations** non-streaming, then **SSE streaming chat** — the product's value moment.
**Admin features** layer on once the standard-user paths are stable. **Cross-cutting polish** and
**build & deployment** finalize the release.

```
EPIC-01 ──→ EPIC-02 ──→ EPIC-03 ──→ EPIC-04 ──→ EPIC-05 ──→ EPIC-06 ──→ EPIC-07
                                       │            │           │           │
                                       │            ▼           │           │
                                       │         (agents form   │           │
                                       │          pickers       │           │
                                       │          consume       │           │
                                       │          catalogs)     │           │
                                       │                        │           │
                                       ├────────→ EPIC-08 ──────┤           │
                                       ├────────→ EPIC-09       │           │
                                       └────────→ EPIC-10       │           │
                                                                ▼           ▼
                                                            EPIC-11 ────→ EPIC-12
                                                       (cross-cutting    (build &
                                                        polish runs       deployment
                                                        in parallel       — last)
                                                        with features)
```

---

## EPIC list

| ID       | Title                                              | Priority | Status |
|----------|----------------------------------------------------|----------|--------|
| EPIC-01  | Project foundation & tooling                       | MUST     | Done   |
| EPIC-02  | Shared layer — API client, auth, design system     | MUST     | Done   |
| EPIC-03  | Authentication flows (login, change password, sign out) | MUST | Done   |
| EPIC-04  | Catalog pages — Tools & MCP servers (read-only)    | MUST     | Done  |
| EPIC-05  | Agents management (owner-scoped CRUD)              | MUST     | Done  |
| EPIC-06  | Conversations & messages (non-streaming surface)   | MUST     | Ready  |
| EPIC-07  | SSE streaming chat                                 | MUST     | Ready  |
| EPIC-08  | Admin — Users                                      | MUST     | Done   |
| EPIC-09  | Admin — API keys                                   | MUST     | Ready  |
| EPIC-10  | Admin — Rate limit                                 | MUST     | Draft  |
| EPIC-11  | Cross-cutting UX polish (toasts, error boundary, a11y, empty/loading) | MUST | Done  |
| EPIC-12  | Build, bundle budgets & static deployment          | MUST     | Draft  |

---

## EPIC-01 — Project foundation & tooling

- **Goal**: Stand up an empty but runnable Vite + React + TypeScript project with the layering
  rule enforced, the design tokens wired into Tailwind, the API type-generation pipeline in
  place, and the testing / lint / format toolchain ready. This EPIC delivers no business
  endpoint — it delivers the scaffolding every other EPIC builds on.
- **Scope**:
  - `package.json`, `.nvmrc` (Node 20), `tsconfig.json` (strict, `noUncheckedIndexedAccess`),
    `tsconfig.node.json` for Vite config.
  - Vite 5 project (`vite.config.ts`) with the `@` → `src/` path alias and the `/api` dev
    proxy to `http://localhost:8080`.
  - Tailwind CSS configured to read CSS variables from `src/styles/tokens.css` (tokens copied
    verbatim from `frontend/CLAUDE.md`).
  - `src/styles/globals.css` and `src/styles/tokens.css`; `Geist` + `Geist Mono` loaded via
    the `geist` npm package.
  - `src/main.tsx`, `src/App.tsx`, a placeholder home route, the `<RouterProvider>` shell.
  - `src/env.ts` Zod-validated `import.meta.env` parser; fails loudly on missing required vars.
  - **API codegen pipeline**: `npm run gen:api` runs `openapi-typescript ../openapi.yaml -o
    src/generated/schema.d.ts`. CI fails if the committed `schema.d.ts` diverges from the
    spec.
  - **ESLint boundaries rule**: `pages → features → shared → generated`; sibling features may
    only import each other's `index.ts`.
  - Prettier configuration; `npm run format` / `format:fix`.
  - Vitest configured with `jsdom`, `@testing-library/jest-dom`, MSW v2 server harness in
    `src/test/setup.ts` (handlers added by feature EPICs; an empty handler set is fine here).
  - `npm run verify` script chaining `gen:api → lint → test → build`.
  - One smoke test: the root component renders without crashing.
- **Out of scope**: any business endpoint or UI flow.
- **Design references**: §2 (architecture), §3 (tech stack), §4 (project structure), §11
  (design tokens), §13 (testing strategy), §14 (build & dev tooling).
- **API surface (consumed)**: none.
- **Backend-requirement anchors**: `REQ-NFR-001` (maintainability), `REQ-NFR-003`
  (configurability), `REQ-NFR-004` (frontend compatibility).
- **Dependencies**: none.

---

## EPIC-02 — Shared layer (API client, auth, SSE, design system, layouts)

- **Goal**: Deliver every cross-cutting primitive feature slices will consume: the typed
  `openapi-fetch` client + interceptors, the TanStack Query client + key factory, the JWT
  storage + `AuthContext` + route guards, the SSE client wrapper, and the headless design-system
  primitives + app/auth shell layouts. No business page is shipped — feature EPICs compose
  these primitives.
- **Scope**:
  - `src/shared/api/client.ts` — `openapi-fetch` client + the three middlewares from
    SW-DESIGN §7.2 (auth header, error normalizer, auth-failure event).
  - `src/shared/api/errors.ts` — `ApiError` class + RFC 7807 normalizer covering every code
    in `ProblemDetails.code`; `fieldErrors` map for form integration.
  - `src/shared/api/queryClient.ts` — TanStack `QueryClient` with the stale-time defaults
    from SW-DESIGN §7.6.
  - `src/shared/api/queryKeys.ts` — typed `qk` factory (single source of cache keys).
  - `src/shared/lib/pagination.ts` — `flattenPages()` + a `useCursorInfiniteQuery` helper
    around `useInfiniteQuery` for `PageEnvelope` responses.
  - `src/shared/sse/sseFrames.ts` — discriminated union `started | delta | completed | error`.
  - `src/shared/sse/chatStream.ts` — `streamChat()` wrapping `@microsoft/fetch-event-source`
    with the contract from SW-DESIGN §8.2 (Accept negotiation, AbortSignal, frame parsing,
    error rejection).
  - `src/shared/auth/tokenStorage.ts` — in-memory primary + sessionStorage hand-off per
    SW-DESIGN §6.4.
  - `src/shared/auth/jwt.ts` — Zod-validated client-side decode of `{ sub, role, exp, iat,
    jti }` (no signature verify).
  - `src/shared/auth/AuthContext.tsx` — `{ token, expiresAt, principal, mustChangePassword,
    signIn, signOut }`; the proactive "30 s before exp" banner trigger; the `auth:logout`
    event listener.
  - `src/shared/auth/guards.tsx` — `<RequireAuth>`, `<RequireRole>`, `<RequireFreshPassword>`.
  - `src/shared/ui/` design-system primitives: `Button`, `Input`, `Textarea`, `Select`,
    `Checkbox`, `Modal`, `Dropdown`, `Tooltip`, `Badge`, `Card`, `Tabs`, `EmptyState`,
    `Skeleton`, `Spinner`, `Toast`, and `icons.ts` (lucide re-exports).
  - `src/shared/layout/AppShell.tsx` (sidebar + topbar + outlet, with the role-gated nav
    from SW-DESIGN §6.5) and `AuthShell.tsx` (centered card for login / change password).
  - `src/shared/lib/cn.ts` (clsx + tailwind-merge), `date.ts` (Intl helpers), `result.ts`.
  - `src/shared/i18n/en.ts` copy map skeleton with the per-code error-copy entries from
    SW-DESIGN §10.2.
  - Unit tests for: `ApiError` normalizer (every `code`), token storage hand-off, JWT decode,
    guard redirects, pagination flattening, SSE frame parsing (all four frame types + unknown
    frame tolerance).
- **Out of scope**: any feature slice (auth flows, agents, chat, admin, catalogs).
- **Design references**: §6 (auth), §7 (API client, server-state, pagination), §8 (SSE), §10
  (error handling), §11 (design system), §13 (testing strategy).
- **API surface (consumed)**: none directly. Provides the plumbing every feature EPIC uses.
- **Backend-requirement anchors**: `REQ-AUTH-001..006`, `REQ-AUTH-009`, `REQ-API-004` (error
  format), `REQ-API-005` (cursor pagination), `REQ-STR-001`, `REQ-STR-003`, `REQ-STR-004`.
- **Dependencies**: EPIC-01.

---

## EPIC-03 — Authentication flows (login, change password, sign out, expiry)

- **Goal**: Deliver the end-user authentication surface: login, the forced and self-initiated
  password change, sign-out, and the token-expiry → re-login routing. After this EPIC, an
  end-user can sign in and stay signed in long enough to use the rest of the app.
- **Scope**:
  - `features/auth/api.ts` hooks: `useLogin`, `useLogout`, `useChangeOwnPassword`.
  - `features/auth/LoginForm.tsx` — email + password, `react-hook-form` + Zod
    (`LoginRequest` constraints); maps `INVALID_CREDENTIALS` to a generic form alert per
    `REQ-AUTH-009`; honors `?next=` (validated as relative path; absolute URLs dropped per
    SW-DESIGN §15).
  - `features/auth/ChangePasswordForm.tsx` — three fields with the live policy checklist
    from SW-DESIGN §9.3; submit disabled until policy + confirm-match satisfied.
  - `features/auth/password.ts` — Zod `passwordPolicy` (≥10, ≥1 uppercase, ≥1 special).
  - `pages/LoginPage.tsx` (wraps `LoginForm` in `AuthShell`), `pages/ChangePasswordPage.tsx`
    (with the forced-visit `?reason=forced` banner).
  - Routing wiring: `<RequireFreshPassword>` redirects every protected page to
    `/change-password` while `mustChangePassword === true`; `/login` is the only public route.
  - Profile menu "Sign out" action — calls `POST /auth/logout` best-effort, clears local
    state, routes to `/login` (per SW-DESIGN §6.3).
  - Token-expiry banner: a 30-second pre-`exp` warning visible app-wide; on actual 401
    interception, a toast and a redirect to `/login?next=...` (per SW-DESIGN §5.3.3).
  - Integration tests (MSW + RTL): standard-login happy path, first-time-admin forced
    redirect, change-password success keeps existing token valid, sign-out clears state,
    rate-limit `429` shown with countdown, generic `INVALID_CREDENTIALS` shown on `/auth/login`
    without leaking which field was wrong.
- **Out of scope**: API-key admin management (EPIC-09), admin user-management endpoints
  (EPIC-08), self password reset by email (not a backend capability).
- **Design references**: §5.3.1–5.3.4 (auth flows), §6 (auth model + storage), §9.3 (password
  policy), §10.2 (per-code routing for auth errors).
- **API surface (consumed)**: `login`, `logout`, `changeOwnPassword`.
- **Backend-requirement anchors**: `REQ-AUTH-001..006`, `REQ-AUTH-009`, `REQ-AUTH-011`,
  `REQ-USR-004`, `REQ-USR-007`, `REQ-SEC-001`.
- **Dependencies**: EPIC-01, EPIC-02.

---

## EPIC-04 — Catalog pages: Tools & MCP servers (read-only)

- **Goal**: Ship the two read-only catalog pages and — critically — the catalog hooks the
  `AgentForm` pickers will consume. Delivered ahead of EPIC-05 so the agent form can render
  its tool / MCP pickers immediately.
- **Scope**:
  - `features/catalog/api.ts`: `useTools()`, `useMcpServers()`. TanStack Query with
    `staleTime: Infinity` per SW-DESIGN §7.6 (catalogs are static at startup —
    `REQ-TOOL-001`, `REQ-MCP-001`).
  - `features/catalog/ToolList.tsx` and `McpServerList.tsx` — tables with a name+description
    filter input; "no results" empty state when the filter matches nothing.
  - `pages/catalog/ToolsPage.tsx` and `pages/catalog/McpServersPage.tsx` — routed at
    `/tools` and `/mcp-servers` per the route table in SW-DESIGN §5.1.
  - Sidebar entries added under the standard-user group.
  - Integration tests: list renders, filter narrows, empty-state appears when empty, an
    `INTERNAL_ERROR` from the server surfaces as a retryable error state.
- **Out of scope**: any catalog mutation (the spec doesn't expose any). Per-agent tool/MCP
  selection — that's EPIC-05.
- **Design references**: §7.6 (stale-time policy), §10.2 (read-only error handling), §12.9
  (page shapes).
- **API surface (consumed)**: `listTools`, `listMcpServers`.
- **Backend-requirement anchors**: `REQ-TOOL-001`, `REQ-TOOL-003`, `REQ-TOOL-005`,
  `REQ-MCP-001`, `REQ-MCP-002`, `REQ-MCP-006`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03.

---

## EPIC-05 — Agents management (owner-scoped CRUD)

- **Goal**: Deliver the primary write surface of the application: an end-user can list, view,
  create, edit, and delete their own agents, including team / tool / MCP-server selection,
  with all client-mirrored validation caps and all server-side conflict codes surfaced
  cleanly in the form.
- **Scope**:
  - `features/agents/api.ts`: `useAgents` (`useCursorInfiniteQuery`), `useAgent`,
    `useCreateAgent`, `useUpdateAgent`, `useDeleteAgent`.
  - `features/agents/schema.ts` — Zod schema mirroring `AgentRequest`: `name` ≤32,
    `description` ≤1024, `systemPrompt` ≤1024, `memorySize` ∈ [1,36], optional model +
    sampling parameters, `tools[]`, `enabledMcpServers[]`, `team[]`. Cross-field "single
    level" rule is intentionally **not** mirrored client-side (see SW-DESIGN §9.2).
  - `features/agents/AgentForm.tsx` — sectioned form (Identity / Behavior / Model / Tools /
    MCP / Team) per SW-DESIGN §12.4. Sticky action bar; on submit error, scroll to the first
    invalid field; on `409 DUPLICATE_AGENT_NAME` / `NESTED_TEAM_FORBIDDEN` /
    `CROSS_OWNER_TEAM_MEMBER`, set the corresponding form-level error with the precise copy
    from `errorCopy`.
  - `features/agents/ToolPicker.tsx` — checkbox list consuming `useTools()`; selected count
    badge.
  - `features/agents/McpServerPicker.tsx` — checkbox list consuming `useMcpServers()`.
  - `features/agents/TeamPicker.tsx` — multi-select over `useAgents()`; candidates whose
    own `team` is non-empty are rendered disabled with the "Has a team of its own" tooltip
    from SW-DESIGN §9.2 (a live preview of the single-level rule, even though the rule is
    enforced server-side).
  - `features/agents/AgentList.tsx` + `AgentCard.tsx` — grid layout per SW-DESIGN §12.3;
    card shows the metadata listed there; per-card action menu (View, Edit, Start chat,
    Delete with confirm + cascade warning quoting `REQ-AGT-010`).
  - `pages/agents/AgentsPage.tsx`, `AgentCreatePage.tsx`, `AgentDetailPage.tsx`,
    `AgentEditPage.tsx` — composition only; routing as per SW-DESIGN §5.1.
  - `AgentDetailPage` includes the "Recent conversations with this agent" panel calling
    `listConversations` with `agentId` and `pageSize=5`.
  - Integration tests: list pagination, create happy path, create with `DUPLICATE_AGENT_NAME`,
    edit propagates to detail cache, team picker disables nested-team candidates with
    tooltip, delete confirmation triggers cascade-warning text, server `VALIDATION_ERROR`
    with `errors[]` maps to per-field errors.
- **Out of scope**: chat with the agent (EPIC-06/07), team delegation runtime behavior
  (driven by the backend during chat; the frontend just configures `team`).
- **Design references**: §9.2 (form constraint mirroring), §10.2 (per-code error routing),
  §12.3–12.5 (agents pages).
- **API surface (consumed)**: `listAgents`, `getAgent`, `createAgent`, `updateAgent`,
  `deleteAgent`. Also `listTools`, `listMcpServers`, `listConversations` (via EPIC-04 /
  EPIC-06 hooks).
- **Backend-requirement anchors**: `REQ-AGT-001..010`, `REQ-AGT-012`, `REQ-AGT-013`,
  `REQ-AGT-014`, `REQ-API-005`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03, EPIC-04. Soft dependency on EPIC-06 for the
  "Recent conversations" panel — if EPIC-06 has not landed yet, the panel can be stubbed
  (`<EmptyState>`) and wired in afterward.

---

## EPIC-06 — Conversations & messages (non-streaming surface)

- **Goal**: Deliver the non-streaming half of the chat surface: an end-user can start a
  conversation with one of their agents, browse the conversation list, open a past
  conversation and view its persisted messages, rename it, and delete it. The send-message
  flow itself is deferred to EPIC-07.
- **Scope**:
  - `features/conversations/api.ts`: `useConversations` (infinite, optional `agentId`
    filter), `useConversation`, `useMessages` (single page sized at 64 to fit the cap from
    `REQ-CHAT-010`), `useStartConversation`, `useUpdateConversationTitle` (optimistic per
    SW-DESIGN §7.5), `useDeleteConversation` (optimistic).
  - `features/conversations/ConversationList.tsx` + `ConversationListItem.tsx` — left-pane
    list per SW-DESIGN §12.6.
  - `features/conversations/ConversationView.tsx` — topbar (inline-editable title via
    `EditTitleDialog`, agent link, message-count "X / 64", overflow menu), message list
    (virtualized with `@tanstack/react-virtual` per SW-DESIGN §12.8), composer placeholder
    (the real composer ships in EPIC-07).
  - `features/conversations/MessageBubble.tsx` — USER vs ASSISTANT styling; plain text
    rendering per SW-DESIGN §15 (no Markdown in v1).
  - `features/conversations/EditTitleDialog.tsx` — Zod-validated 1–32 chars (mirrors
    `UpdateConversationRequest`).
  - `features/conversations/DeleteConversationDialog.tsx` — confirm dialog.
  - `pages/chat/ChatPage.tsx` (two-pane layout with outlet), `pages/chat/ChatNewPage.tsx`
    (agent picker → `createConversation` → `<Navigate>` to the new conversation),
    `pages/chat/ConversationPage.tsx`.
  - Integration tests: list pagination, start-new-conversation happy path, message list
    renders cap (64) without scroll jank, title edit is optimistic and rolls back on error,
    delete is optimistic and rolls back on error, `404 NOT_FOUND` on a deleted conversation
    surfaces as an empty state with a link back to the list.
- **Out of scope**: the SSE send-message flow, composer, streaming bubble rendering, stop
  button — all delivered in EPIC-07. Until then, the composer is a disabled placeholder
  with a "Streaming chat coming next" caption (or hidden behind a feature toggle).
- **Design references**: §7.5 (optimistic updates), §12.6–12.8 (chat pages).
- **API surface (consumed)**: `listConversations`, `createConversation`, `getConversation`,
  `updateConversation`, `deleteConversation`, `listMessages`.
- **Backend-requirement anchors**: `REQ-CHAT-001..005`, `REQ-CHAT-007`, `REQ-CHAT-008`,
  `REQ-CHAT-009`, `REQ-CHAT-010`, `REQ-CHAT-011`, `REQ-API-005`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03, EPIC-05.

---

## EPIC-07 — SSE streaming chat

- **Goal**: Deliver the value moment of the product: the user types a message, sees the
  assistant response stream in token-by-token, and can stop a runaway stream. This EPIC
  wires the SSE bridge (built in EPIC-02) into the conversation view, including all error,
  cap, and cancellation paths.
- **Scope**:
  - `features/conversations/Composer.tsx` — `<textarea>` with character counter `N / 1024`,
    `Cmd/Ctrl+Enter` to send, `Esc` to stop while streaming, send button + stop button
    (the latter visible only while `phase === 'streaming'`).
  - `features/conversations/useChatStream.ts` — the React-facing wrapper from SW-DESIGN §8.3:
    optimistic USER bubble, `started`/`delta`/`completed`/`error` handling, first-turn title
    patching of the conversations-list cache, partial-bubble preservation on `error`,
    `React.startTransition` to keep delta rendering off the input path.
  - Integration with `ConversationView`: composer state replaces the EPIC-06 placeholder;
    "Conversation full" banner replaces the composer when `messageCount === 64`
    (`REQ-CHAT-010`).
  - Cancellation wiring: stop button + page-navigation `useEffect` cleanup both abort the
    `AbortController` so the backend disposes the upstream LLM call per `REQ-STR-003`.
  - Error surfacing per the table in SW-DESIGN §8.5 — including the special preservation of
    a greyed-out partial bubble on mid-stream `LLM_UNAVAILABLE` / `MCP_SERVER_ERROR`.
  - Accessibility: the message list is `role="log" aria-live="polite"`; delta text is **not**
    announced; the complete assistant message is announced on `completed` per SW-DESIGN §11.6.
  - Integration tests (MSW emits a hand-rolled `text/event-stream` body): user bubble appears
    immediately, caret appears after `started`, assistant text grows monotonically across
    deltas, `completed` patches title on first turn, stop aborts and preserves partial
    bubble, `error` frame surfaces toast + greys partial bubble, `409 CONVERSATION_FULL`
    swaps composer for the "Start a new conversation" banner, `406` is logged (engineering
    bug — should not occur).
- **Out of scope**: agent-team delegation UX (the backend handles delegation internally per
  `REQ-AGT-015`; the user only sees the aggregated answer).
- **Design references**: §8 (SSE streaming), §10.2 (error handling), §11.6 (a11y), §12.8
  (`ConversationPage`).
- **API surface (consumed)**: `sendMessage` (SSE).
- **Backend-requirement anchors**: `REQ-AGT-014`, `REQ-CHAT-006`, `REQ-CHAT-009`,
  `REQ-CHAT-010`, `REQ-CHAT-012`, `REQ-STR-001`, `REQ-STR-002`, `REQ-STR-003`, `REQ-STR-004`.
- **Dependencies**: EPIC-02 (SSE primitive), EPIC-06.

---

## EPIC-08 — Admin: Users

- **Goal**: Allow admin principals to list users, create new accounts, enable/disable, and
  delete (with an explicit cascade warning). The admin section is gated by
  `<RequireRole role="ADMIN">`; standard users never see it.
- **Scope**:
  - `features/admin-users/api.ts`: `useUsers` (infinite), `useUser`, `useCreateUser`,
    `useUpdateUser` (optimistic per SW-DESIGN §7.5 for the disable/enable toggle),
    `useDeleteUser`.
  - `features/admin-users/UserList.tsx` — table with email, role badge, disabled badge,
    created-at; row click → detail; "Create user" CTA per SW-DESIGN §12.10.
  - `features/admin-users/UserForm.tsx` — Zod schema mirroring `CreateUserRequest` (email,
    password with the policy checklist from §9.3, role enum).
  - `features/admin-users/DisableUserDialog.tsx` — confirm dialog.
  - `pages/admin/AdminUsersPage.tsx`, `AdminUserCreatePage.tsx`, `AdminUserDetailPage.tsx`.
  - Sidebar wiring: under the role-gated "Admin" group.
  - Delete confirm dialog renders an explicit cascade warning quoting the user-facing
    consequence ("This will permanently delete the user's agents and conversations.") —
    mirrors `REQ-USR-006`.
  - Integration tests: list, create happy path, create with `409 CONFLICT` (duplicate email),
    disable/enable toggle is optimistic and rolls back on error, delete confirms then
    triggers cascade-warning text and removes from list.
- **Out of scope**: API-key admin (EPIC-09), rate-limit admin (EPIC-10), self password change
  (EPIC-03).
- **Design references**: §6.5 (role gating), §7.5 (optimistic updates), §12.10 (admin pages).
- **API surface (consumed)**: `listUsers`, `createUser`, `getUser`, `updateUser`,
  `deleteUser`.
- **Backend-requirement anchors**: `REQ-USR-001..003`, `REQ-USR-005`, `REQ-USR-006`,
  `REQ-AUTH-008`, `REQ-SEC-001`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03.

---

## EPIC-09 — Admin: API keys

- **Goal**: Allow admin principals to mint machine-to-machine API keys, see their cleartext
  value **exactly once** at creation time, list metadata, and soft-revoke (disable) /
  re-enable a key.
- **Scope**:
  - `features/admin-api-keys/api.ts`: `useApiKeys` (infinite), `useCreateApiKey`,
    `useUpdateApiKey`.
  - `features/admin-api-keys/ApiKeyList.tsx` — table with client-id, label, created-at,
    disabled badge; row action: revoke / re-enable (optimistic toggle).
  - `features/admin-api-keys/CreateApiKeyDialog.tsx` — optional label input → on success,
    pivots to the `RevealOnceBanner` view (see below).
  - `features/admin-api-keys/RevealOnceBanner.tsx` — the "shown once" UX from SW-DESIGN
    §5.3.5: cleartext in a `Geist Mono` field with a Copy button; persistent warning copy;
    "Done" button disabled until Copy has been clicked at least once; on close, the
    cleartext is wiped from React state and the list refetches.
  - `pages/admin/AdminApiKeysPage.tsx`.
  - Integration tests: list, create + reveal-once shows the cleartext exactly once, "Done"
    is disabled until Copy is clicked, after close the cleartext is no longer in the DOM,
    revoke / re-enable toggles are optimistic.
- **Out of scope**: API-key callers' UX (the frontend never authenticates with API keys —
  they are M2M only, per SW-DESIGN §6.1).
- **Design references**: §5.3.5 (reveal-once UX), §6.5 (role gating), §12.10 (admin pages).
- **API surface (consumed)**: `listApiKeys`, `createApiKey`, `updateApiKey`.
- **Backend-requirement anchors**: `REQ-AUTH-007`, `REQ-AUTH-012`, `REQ-SEC-002`,
  `REQ-SEC-003`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03.

---

## EPIC-10 — Admin: Rate limit

- **Goal**: Allow admins to view and update the live rate-limit configuration. A small EPIC,
  but it closes the admin section.
- **Scope**:
  - `features/admin-rate-limit/api.ts`: `useRateLimitConfig`, `useUpdateRateLimitConfig`.
  - `features/admin-rate-limit/RateLimitForm.tsx` — two numeric fields (per-minute,
    per-hour), Zod schema mirroring `RateLimitConfigRequest` (both `minimum: 1`); "Last
    updated at" + "Last updated by" caption; save button.
  - `pages/admin/AdminRateLimitPage.tsx`.
  - On save success: re-fetch to confirm the new live config (no optimistic update — a
    rate-limit change is rare and the user expects visual confirmation).
  - Integration tests: load, edit, save success refetches, validation rejects values < 1,
    `429 RATE_LIMITED` surfaces as a toast with countdown like everywhere else.
- **Out of scope**: per-IP or per-user limits (explicitly excluded by `REQ-RL-003`).
- **Design references**: §6.5 (role gating), §10.2 (error routing), §12.10 (admin pages).
- **API surface (consumed)**: `getRateLimitConfig`, `updateRateLimitConfig`.
- **Backend-requirement anchors**: `REQ-RL-001..005`.
- **Dependencies**: EPIC-01, EPIC-02, EPIC-03.

---

## EPIC-11 — Cross-cutting UX polish (toasts, error boundary, empty/loading/offline states, a11y)

- **Goal**: Ship the global polish that turns the feature EPICs into a cohesive product:
  the toast queue, the root error boundary, the standard empty / loading / offline /
  forbidden / not-found states, and an accessibility audit pass. Most pieces are
  incrementally landed alongside earlier EPICs — this EPIC is the **explicit, dedicated**
  sweep that closes the gaps.
- **Scope**:
  - `shared/ui/Toast.tsx` — toast queue with `aria-live="polite"`, dedup of rate-limit
    bursts per TBD-F3 (last-write-wins single toast with countdown).
  - `shared/ui/RootErrorBoundary.tsx` — catches render-time exceptions; renders the
    minimalist "We hit an unexpected error" screen with a "Reload" button; logs to
    `console.error`.
  - Standardized state primitives: `<EmptyState>`, `<LoadingSkeleton>`, `<ForbiddenState>`,
    `<NotFoundState>`, `<OfflineBanner>` (`navigator.onLine` listener).
  - Sweep across every list/detail page from EPIC-04 through EPIC-10: confirm every page
    has empty / loading / error / forbidden states wired in.
  - **Accessibility audit** pass: keyboard reachability on every interactive element, focus
    order verification, color-contrast verification of every text-on-background pair against
    WCAG AA, focus-trap on every modal, `aria-modal="true"` on dialogs, `aria-live` regions
    only where intended, axe-core automated check integrated in `npm run test`.
  - Bundle-budget instrumentation via `vite-plugin-bundle-visualizer`; `npm run
    build:analyze` script; soft warning when initial chunk > 200 KB gzip (hard fail in
    EPIC-12).
- **Out of scope**: any new business endpoint or feature; client-side telemetry / Sentry
  (deferred per SW-DESIGN §16.3).
- **Design references**: §10.3 (root error boundary), §11.5–11.6 (animation budget, a11y
  baseline), §16.1 (performance), §17 TBD-F3 (toast dedup).
- **API surface (consumed)**: none new.
- **Backend-requirement anchors**: `REQ-API-004` (error format consistency), `REQ-NFR-001`
  (maintainability).
- **Dependencies**: can run in parallel with EPIC-04 through EPIC-10; the closing sweep
  depends on those landing.

---

## EPIC-12 — Build, bundle budgets & static deployment

- **Goal**: Produce a deployable static bundle, enforce bundle budgets in CI, and document
  the static-host configuration so any nginx / S3+CloudFront / EC2 nginx host can serve the
  app with deep-link routing.
- **Scope**:
  - `npm run build` produces a clean `dist/` (verified by a CI smoke that runs `npm run
    preview` and hits `/` + `/login`).
  - Bundle-budget enforcement (hard fail): initial chunk ≤ 250 KB gzip; lazy route chunks
    ≤ 150 KB gzip (per SW-DESIGN §16.1).
  - `React.lazy()` splits for every page outside `/login`, `/change-password`, and the
    chat surface (which are eagerly loaded).
  - `frontend/docs/nginx.conf.example` — minimal config with `try_files $uri $uri/
    /index.html;` for SPA routing, gzip for `.js` / `.css` / `.html`, long-cache headers
    for hashed assets, no-cache for `index.html`.
  - `frontend/docs/DEPLOY.md` — local-run instructions (Vite dev), production-bundle
    instructions, environment-variable matrix (SW-DESIGN §14.2), CORS expectations
    (backend allow-list must include the deployed origin per `REQ-API-003`).
  - CI pipeline definition: `npm ci && npm run verify` on every PR. CI also enforces that
    `src/generated/schema.d.ts` is up-to-date relative to `../openapi.yaml`.
  - Browser-support check: a `browserslist` entry pinning Chromium ≥ 117, Firefox ≥ 117,
    Safari ≥ 16.4 per SW-DESIGN §16.2.
- **Out of scope**: hosting infrastructure (the artifact is a static directory; the host is
  chosen by ops). Sentry / RUM wiring (deferred per SW-DESIGN §16.3).
- **Design references**: §14 (build & dev tooling), §15 (security), §16.1 (performance),
  §16.2 (browser support).
- **API surface (consumed)**: none.
- **Backend-requirement anchors**: `REQ-API-003` (CORS allow-list), `REQ-NFR-003`
  (configurability), `REQ-NFR-004` (frontend compatibility).
- **Dependencies**: every other EPIC reaches a runnable state first.

---

## Notes

- **Open design items** TBD-F1 (light theme), TBD-F2 (Markdown rendering), TBD-F3 (toast
  dedup), TBD-F4 (conversation export), TBD-F5 (virtualization swap if cap is raised),
  TBD-F6 (real-time conv-list updates), TBD-F7 (Playwright E2E) live in SW-DESIGN §17. They
  do **not** block any v1 EPIC — each has an explicit v1 default. They will be picked up in
  later EPICs as product priorities firm up.
- **EPIC-04 (catalogs) is intentionally tiny and lands before EPIC-05 (agents)** so the
  agent form's `ToolPicker` / `McpServerPicker` have a real catalog to consume on day one.
- **EPIC-06 → EPIC-07 split** keeps the non-streaming surface (list / view / rename /
  delete) reviewable independently of the SSE bridge. The composer in EPIC-06 is a
  disabled placeholder until EPIC-07 wires it up.
- **EPIC-11 (cross-cutting polish) runs in parallel** with EPICs 04–10. Most polish lands
  incrementally inside those EPICs; this EPIC is the dedicated closing sweep that
  guarantees no page ships without its empty / loading / error / forbidden states and that
  the a11y baseline holds end-to-end.
- **Testing** is part of every EPIC, not a separate one. Each EPIC includes Vitest unit
  tests for pure logic, RTL component tests for forms / pickers / dialogs, MSW-backed page
  integration tests for happy + sad paths. The E2E (Playwright) layer is deferred per
  SW-DESIGN §17 TBD-F7 and will be its own future EPIC.
- A companion `frontend/backlog/US-STATUS.md` file SHOULD be created and maintained the
  moment the first per-EPIC user-story file lands (mirroring the backend's `US-STATUS.md`
  workflow). It is **not** created by this EPICs-only document.
