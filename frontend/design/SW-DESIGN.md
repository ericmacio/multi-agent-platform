# SW-DESIGN.md — Frontend Software Design

This document is the authoritative software design for the **frontend** of the multi-agent
platform. It is derived from:

- `docs/SPECS.md` — global project specifications.
- `frontend/CLAUDE.md` — frontend conventions (stack, visual identity, design tokens).
- `openapi.yaml` (project root) — **the single source of truth for the API contract**. Every
  HTTP call, payload, error shape, and SSE frame referenced below resolves to a definition in
  that file.
- `backend/design/SW-DESIGN.md` and `backend/requirements/REQS.md` — used as cross-reference for
  the behavior the frontend must accommodate (auth model, pagination, streaming, error codes,
  business caps).

Its purpose is to define everything needed for the next steps: producing the frontend EPIC
breakdown (`frontend/backlog/EPICS.md`) and the per-EPIC user stories.

> When the openapi spec and this document disagree, **the openapi spec wins**. This document
> never re-declares request/response shapes — it references operationIds and component schema
> names from the contract.

---

## 1. Scope and references

### In scope

- Frontend application architecture (project layout, modules, layering).
- Tech-stack pinning and rationale.
- Routing, navigation, guards.
- State management and server-state caching.
- Generated, type-safe API client and HTTP/SSE plumbing.
- Authentication flow (JWT only — see §6).
- Authorization at the UI level (role gating, owner scoping at the route level).
- Error handling (HTTP + SSE + RFC 7807 mapping, user-facing copy strategy).
- Forms, validation, schema-derivation rules.
- Design system (tokens, primitives, accessibility).
- Pages and user flows (informational catalog — the page-by-page acceptance criteria belong in
  the EPIC user stories).
- Testing strategy (unit, integration, mock-server tests).
- Build, dev tooling, environment configuration.

### Out of scope

- The **public marketing site**. There is none — the frontend is the application itself, served
  behind the login screen.
- **Self-signup** flows. Per `REQ-USR-003`, accounts are created by admins only; the frontend
  has no signup screen.
- **Password reset by email**. Not a backend capability today (no `POST /auth/password-reset`
  exists in `openapi.yaml`); password recovery is operationally an admin re-creation. The UI
  surfaces a "contact your administrator" message instead.
- **Refresh tokens / silent re-login**. Per `REQ-AUTH-005`, JWTs are short-lived (30 min) and
  no refresh endpoint exists. The frontend handles expiry by routing to the login screen.
- **API-key callers' UX**. API-key principals are machine-to-machine (`REQ-AUTH-007`) and do not
  use this frontend. The frontend authenticates exclusively with JWTs.
- **Server-side rendering / SEO**. The app is a private, authenticated SPA.
- **Internationalization**. The v1 UI is English-only; copy lives in a single
  `frontend/src/i18n/en.ts` map so a later i18n pass is mechanical, not architectural.
- **Mobile-first / native apps**. The application is responsive down to ~1024px (laptop). Phone
  layouts are best-effort but not a v1 acceptance target.

### References

- `openapi.yaml` — REST + SSE contract (operationIds, schemas, error codes, pagination).
- `frontend/CLAUDE.md` — visual identity, design tokens, stack pinning.
- `docs/SPECS.md` — overall product specification.
- `backend/design/SW-DESIGN.md` §6 (endpoints), §7 (streaming), §8 (security), §9 (errors),
  §10 (pagination) — informs the frontend's matching client-side behavior.
- `backend/requirements/REQS.md` `REQ-AUTH-*`, `REQ-AGT-*`, `REQ-CHAT-*`, `REQ-STR-*`,
  `REQ-API-*`.

---

## 2. High-level architecture

### 2.1 Style — feature-sliced SPA on top of a typed API client

The frontend is a **single-page application** organized in **feature slices** (a lightweight
take on the "Feature-Sliced Design" pattern, without its full nomenclature), sitting on top
of a generated, typed API client. There are four conceptual layers:

```
                  ┌────────────────────────────────────────────────────┐
                  │                    Pages (routes)                  │
                  │   Auth / Agents / Chat / Admin / Profile           │
                  └──────────────────────────▲─────────────────────────┘
                                             │ feature hooks, providers
                  ┌──────────────────────────┴─────────────────────────┐
                  │                  Features (slices)                 │
                  │  auth · agents · conversations · admin · catalog   │
                  │   each slice exposes hooks + components            │
                  └──────────────────────────▲─────────────────────────┘
                                             │ API client, SSE client, query keys
                  ┌──────────────────────────┴─────────────────────────┐
                  │                Shared (cross-cutting)              │
                  │  api/, sse/, auth/, ui/ (design system), lib/      │
                  └──────────────────────────▲─────────────────────────┘
                                             │
                  ┌──────────────────────────┴─────────────────────────┐
                  │           Generated layer (do not edit)            │
                  │     types & operations from openapi.yaml           │
                  └────────────────────────────────────────────────────┘
```

- **Generated layer**: produced from `openapi.yaml` at build time. Never edited by hand. Any
  contract change re-generates this layer and the type-checker surfaces every affected call.
- **Shared layer**: framework-agnostic primitives — typed fetch wrapper, SSE client, JWT
  storage, error normalizer, design-system components, formatting helpers. No business logic.
- **Features layer**: one folder per bounded business area. Each slice owns its server-state
  query hooks, its forms, its slice-local components, and its types (when not directly
  expressible via generated schemas). Cross-slice imports go **through the public `index.ts`**
  of the target slice — never reach into another slice's internals.
- **Pages layer**: React Router routes. A page composes feature hooks and feature components
  into a layout. Pages contain no business logic of their own.

### 2.2 Why not Redux / Zustand / a single global store?

The application's state is overwhelmingly **server state** (agents, conversations, messages,
catalogs). The very small amount of **client state** (login form, modal open/closed, theme,
ephemeral SSE buffer) does not justify a global store. We use:

- **TanStack Query (React Query) v5** for all server state — caching, deduplication,
  invalidation after mutations, cursor pagination via `useInfiniteQuery`, optimistic updates
  where appropriate.
- **React `useState` / `useReducer`** for local UI state.
- **React Context** for genuinely cross-cutting client state: the current JWT/principal
  (`AuthContext`), the theme (single dark theme today; the context is the seam for a future
  light theme), and the toast queue.

Why TanStack Query: it removes hundreds of lines of hand-written cache-invalidation code, it
matches the openapi-typed client cleanly (each `operationId` becomes one query/mutation hook),
and it gives us the `staleTime` / `gcTime` knobs we need for chat UX without inventing a
custom store. It is **not** mentioned in `frontend/CLAUDE.md` — adding it here is a deliberate
design choice; the alternative (raw `useEffect` + manual cache) was rejected on
maintainability grounds for a chat-heavy product with cursor pagination on every list endpoint.

### 2.3 Why a generated API client?

Per `frontend/CLAUDE.md`, the HTTP client is "fetch (wrapped) — generated typed client from
`openapi.yaml`". The generator we choose is **`openapi-typescript`** (for static types) +
**`openapi-fetch`** (for the runtime wrapper):

- `openapi-typescript` produces a single `src/api/schema.d.ts` file with `paths` and
  `components` type maps. **No runtime code, no axios, no dependencies leaked into the
  bundle.**
- `openapi-fetch` is a 6 KB wrapper around `fetch` that consumes those types. Every call —
  `client.GET('/agents', { params: { query: { cursor } } })` — is statically typed against
  the spec; a backend rename surfaces as a compile error in the slice that consumes the
  endpoint.

Alternatives considered and rejected:

| Generator                        | Why rejected                                                                 |
|----------------------------------|-------------------------------------------------------------------------------|
| `openapi-generator-cli` (axios)  | Heavy generated code (one class per tag), pulls in axios, harder to tree-shake. |
| `orval`                          | Couples codegen with React Query hook generation — we want the codegen and the data-layer choices decoupled so the codegen layer stays trivially regeneratable. |
| Hand-written `fetch` + Zod       | Duplicates the contract in TypeScript-by-hand; every backend rename is a diff in two places. |

### 2.4 Module boundaries

The dependency direction is strict, enforced by an ESLint rule
(`eslint-plugin-boundaries` or `eslint-plugin-import` with custom zone rules):

```
pages  →  features  →  shared  →  generated
       (never backwards, never sideways between sibling slices)
```

- Features may import from `shared` and from `generated`. They MUST NOT import from another
  feature's internal files. They MAY import from another feature's `index.ts` (its public
  surface).
- Shared MUST NOT import from features or pages.
- Pages MUST NOT contain `useQuery` calls directly — they consume feature hooks. This keeps
  pages thin and testable, and keeps the cache-key convention (see §7.3) inside the feature
  slice that owns the resource.

---

## 3. Tech stack

| Concern                  | Choice                                            | Pinned by              |
|--------------------------|---------------------------------------------------|------------------------|
| Language                 | **TypeScript 5.x** (strict mode, `noUncheckedIndexedAccess`) | CLAUDE.md     |
| UI framework             | **React 18+**                                     | CLAUDE.md              |
| Build tool               | **Vite 5+**                                       | CLAUDE.md              |
| Router                   | **React Router v6** (data-router APIs)            | CLAUDE.md              |
| Server-state             | **TanStack Query v5**                             | This doc (§2.2)        |
| HTTP client              | **`openapi-fetch`** (typed) over `fetch`          | CLAUDE.md (typed fetch)|
| Type generator           | **`openapi-typescript`**                          | This doc (§2.3)        |
| SSE client               | **`@microsoft/fetch-event-source`**               | CLAUDE.md              |
| Forms                    | **`react-hook-form`** + **`zod`** resolver         | This doc (§9)          |
| Styling                  | **Tailwind CSS** with CSS-variable tokens         | CLAUDE.md              |
| Animations               | **Framer Motion**                                 | CLAUDE.md              |
| Icons                    | **`lucide-react`** (consistent stroke-icon set)   | This doc (§11)         |
| Fonts                    | **Geist** + **Geist Mono** (npm `geist`)          | CLAUDE.md              |
| Test runner              | **Vitest**                                        | CLAUDE.md              |
| Component testing        | **React Testing Library** + `@testing-library/user-event` | CLAUDE.md      |
| API mocking in tests     | **MSW (Mock Service Worker)** v2                  | This doc (§13.2)       |
| Lint / format            | **ESLint** + **Prettier**                         | CLAUDE.md              |
| Package manager          | **npm** (lockfile checked in)                     | CLAUDE.md              |
| Node version             | **20 LTS** (pinned via `.nvmrc` and `engines`)    | This doc (§14)         |

### Rationale for additions beyond CLAUDE.md

- **TanStack Query v5** — justified in §2.2. The alternative (raw effects) does not scale to
  the cursor-paginated list endpoints + chat UX.
- **`openapi-typescript` + `openapi-fetch`** — justified in §2.3. The lightest possible
  "typed fetch from spec" combination.
- **`react-hook-form` + `zod`** — chosen because the contract has small, well-bounded request
  payloads (login, change-password, agent, conversation title). Zod schemas can mirror the
  generated TypeScript shapes for the request bodies and adds runtime validation that the
  client-side form respects the same caps as the backend (e.g. `name` ≤ 32, `description`
  ≤ 1024, message `content` ≤ 1024). Alternative considered: Formik — rejected as heavier
  and less ergonomic with TypeScript.
- **MSW v2** — service-worker mocking lets us test pages end-to-end against the openapi
  schemas in the browser test environment, without spinning the backend. Aligns with
  `REQ-NFR-002` testability for the frontend equivalent.
- **`lucide-react`** — a single, tree-shakable icon set that matches the "Dark Professional"
  visual identity (clean, technical, 1.5-px stroke). Avoids picking icons piecemeal from
  multiple libraries.

---

## 4. Project structure

Root folder: `frontend/`. Vite project layout, with the source tree organized by the layering
defined in §2.

```
frontend/
├── .nvmrc                          # 20
├── package.json
├── package-lock.json
├── tsconfig.json                   # strict; project references to tsconfig.node.json
├── tsconfig.node.json              # config for vite.config.ts only
├── vite.config.ts
├── tailwind.config.ts
├── postcss.config.js
├── .eslintrc.cjs                   # boundaries rule (pages → features → shared)
├── .prettierrc
├── index.html
├── public/
│   └── favicon.svg
├── design/
│   └── SW-DESIGN.md                # this document
├── CLAUDE.md
└── src/
    ├── main.tsx                    # ReactDOM.createRoot, providers
    ├── App.tsx                     # router shell
    ├── env.ts                      # zod-validated import.meta.env
    │
    ├── generated/                  # ⚠ do not edit — produced by `npm run gen:api`
    │   └── schema.d.ts             # openapi-typescript output
    │
    ├── shared/                     # cross-cutting primitives, no business logic
    │   ├── api/
    │   │   ├── client.ts           # openapi-fetch client + interceptors (auth, errors)
    │   │   ├── errors.ts           # ProblemDetails normalizer + ApiError class
    │   │   ├── queryKeys.ts        # central enum of cache-key roots
    │   │   └── queryClient.ts      # TanStack QueryClient + default options
    │   ├── sse/
    │   │   ├── chatStream.ts       # fetchEventSource wrapper for POST /conversations/{id}/messages
    │   │   └── sseFrames.ts        # discriminated-union types: started|delta|completed|error
    │   ├── auth/
    │   │   ├── AuthContext.tsx
    │   │   ├── tokenStorage.ts     # in-memory + sessionStorage hand-off (§6.4)
    │   │   ├── jwt.ts              # decode `sub`, `role`, `exp` (no signature verify)
    │   │   └── guards.tsx          # <RequireAuth>, <RequireRole>, <RequireFreshPassword>
    │   ├── ui/                     # design-system primitives (§11)
    │   │   ├── Button.tsx
    │   │   ├── Input.tsx
    │   │   ├── Textarea.tsx
    │   │   ├── Select.tsx
    │   │   ├── Checkbox.tsx
    │   │   ├── Modal.tsx
    │   │   ├── Dropdown.tsx
    │   │   ├── Tooltip.tsx
    │   │   ├── Badge.tsx
    │   │   ├── Card.tsx
    │   │   ├── Tabs.tsx
    │   │   ├── EmptyState.tsx
    │   │   ├── Skeleton.tsx
    │   │   ├── Spinner.tsx
    │   │   ├── Toast.tsx
    │   │   └── icons.ts            # re-export of lucide icons we actually use
    │   ├── layout/
    │   │   ├── AppShell.tsx        # sidebar + topbar + outlet
    │   │   ├── Sidebar.tsx
    │   │   ├── Topbar.tsx
    │   │   └── AuthShell.tsx       # centered card for login / change-password
    │   ├── lib/
    │   │   ├── cn.ts               # clsx + tailwind-merge
    │   │   ├── date.ts             # Intl.DateTimeFormat helpers (no moment, no dayjs)
    │   │   ├── pagination.ts       # cursor → useInfiniteQuery helpers
    │   │   └── result.ts           # small Result<T,E> for non-throwing flows
    │   └── i18n/
    │       └── en.ts               # single English copy map (§1)
    │
    ├── features/                   # one folder per bounded business area
    │   ├── auth/
    │   │   ├── index.ts            # public surface (hooks + components)
    │   │   ├── api.ts              # useLogin, useLogout, useChangeOwnPassword
    │   │   ├── LoginForm.tsx
    │   │   ├── ChangePasswordForm.tsx
    │   │   └── password.ts         # zod schema for the policy (§9.3)
    │   ├── agents/
    │   │   ├── index.ts
    │   │   ├── api.ts              # useAgents (infinite), useAgent, useCreateAgent, useUpdateAgent, useDeleteAgent
    │   │   ├── AgentList.tsx
    │   │   ├── AgentCard.tsx
    │   │   ├── AgentForm.tsx       # shared by create + edit
    │   │   ├── TeamPicker.tsx      # multi-select bounded by single-level rule (live preview)
    │   │   ├── ToolPicker.tsx
    │   │   ├── McpServerPicker.tsx
    │   │   └── schema.ts           # zod schema derived from AgentRequest
    │   ├── conversations/
    │   │   ├── index.ts
    │   │   ├── api.ts              # useConversations, useConversation, useMessages, useStartConversation, ...
    │   │   ├── ConversationList.tsx
    │   │   ├── ConversationListItem.tsx
    │   │   ├── ConversationView.tsx
    │   │   ├── MessageBubble.tsx
    │   │   ├── Composer.tsx
    │   │   ├── useChatStream.ts    # bridges shared/sse/chatStream → React state
    │   │   ├── EditTitleDialog.tsx
    │   │   └── DeleteConversationDialog.tsx
    │   ├── catalog/
    │   │   ├── index.ts
    │   │   ├── api.ts              # useTools, useMcpServers
    │   │   ├── ToolList.tsx
    │   │   └── McpServerList.tsx
    │   ├── admin-users/
    │   │   ├── index.ts
    │   │   ├── api.ts              # useUsers, useCreateUser, useUpdateUser, useDeleteUser, useUser
    │   │   ├── UserList.tsx
    │   │   ├── UserForm.tsx
    │   │   └── DisableUserDialog.tsx
    │   ├── admin-api-keys/
    │   │   ├── index.ts
    │   │   ├── api.ts              # useApiKeys, useCreateApiKey, useUpdateApiKey
    │   │   ├── ApiKeyList.tsx
    │   │   ├── CreateApiKeyDialog.tsx
    │   │   └── RevealOnceBanner.tsx  # cleartext API key shown once at creation (§5.3.5)
    │   ├── admin-rate-limit/
    │   │   ├── index.ts
    │   │   ├── api.ts              # useRateLimitConfig, useUpdateRateLimitConfig
    │   │   └── RateLimitForm.tsx
    │   └── profile/
    │       ├── index.ts
    │       └── ProfileMenu.tsx     # avatar/initials + "Sign out" + "Change password"
    │
    ├── pages/                      # React Router route components — composition only
    │   ├── routes.tsx              # createBrowserRouter() — single source of routes
    │   ├── LoginPage.tsx
    │   ├── ChangePasswordPage.tsx
    │   ├── NotFoundPage.tsx
    │   ├── ForbiddenPage.tsx
    │   ├── agents/
    │   │   ├── AgentsPage.tsx
    │   │   ├── AgentCreatePage.tsx
    │   │   ├── AgentDetailPage.tsx
    │   │   └── AgentEditPage.tsx
    │   ├── chat/
    │   │   ├── ChatPage.tsx                  # left: conversation list, right: outlet
    │   │   ├── ChatNewPage.tsx               # "start new chat" — agent picker
    │   │   └── ConversationPage.tsx          # specific conversation view
    │   ├── catalog/
    │   │   ├── ToolsPage.tsx
    │   │   └── McpServersPage.tsx
    │   └── admin/
    │       ├── AdminUsersPage.tsx
    │       ├── AdminUserCreatePage.tsx
    │       ├── AdminUserDetailPage.tsx
    │       ├── AdminApiKeysPage.tsx
    │       └── AdminRateLimitPage.tsx
    │
    ├── styles/
    │   ├── tokens.css              # CSS custom properties from CLAUDE.md
    │   └── globals.css             # base resets, font-face, tailwind directives
    │
    └── test/
        ├── setup.ts                # vitest setup: MSW server, jest-dom, RTL cleanup
        ├── server.ts               # MSW handlers derived from openapi-fetch types
        ├── factories.ts            # test data factories matching openapi schemas
        └── render.tsx              # `renderWithProviders` for component tests
```

### 4.1 Naming conventions

- Files: `PascalCase.tsx` for components, `camelCase.ts` for non-component modules,
  `kebab-case` for directory names that contain multiple words (`admin-users/`).
- Hooks: `useXxx` (TanStack Query hooks live in each slice's `api.ts`).
- Types: `PascalCase`. Prefer **type aliases** (`type X = ...`) over interfaces; use interfaces
  only when declaration merging is required (very rare here).
- Zod schemas: `xxxSchema` (e.g., `loginSchema`); their inferred type uses the same name without
  the `Schema` suffix (`type Login = z.infer<typeof loginSchema>`).
- Query keys: see §7.3 — produced from a single typed enum, never assembled as ad-hoc string
  arrays in components.

---

## 5. Routing

### 5.1 Route map

Routes are declared once in `pages/routes.tsx` using `createBrowserRouter` (data-router API).
Every authenticated route nests under a single `<AppShell>` layout route; auth-related routes
nest under `<AuthShell>`. Guards (§5.2) are declared on each protected segment.

| Path                              | Element                  | Guards                                  | Notes                                                              |
|-----------------------------------|--------------------------|-----------------------------------------|--------------------------------------------------------------------|
| `/login`                          | `LoginPage`              | redirect to `/agents` if already authed | Public.                                                            |
| `/change-password`                | `ChangePasswordPage`     | `RequireAuth`                           | Forced when `mustChangePassword=true`; also reachable from menu.  |
| `/`                               | redirect → `/agents`     | `RequireAuth`                           |                                                                    |
| `/agents`                         | `AgentsPage`             | `RequireAuth` + `RequireFreshPassword`  | Lists own agents.                                                  |
| `/agents/new`                     | `AgentCreatePage`        | ↑                                       |                                                                    |
| `/agents/:agentId`                | `AgentDetailPage`        | ↑                                       | Read-only summary + actions.                                       |
| `/agents/:agentId/edit`           | `AgentEditPage`          | ↑                                       |                                                                    |
| `/chat`                           | `ChatPage` (layout)      | ↑                                       | Two-pane layout: conversation list + outlet.                       |
| `/chat/new`                       | `ChatNewPage`            | ↑                                       | Agent picker → POST `/conversations` → redirect to new one.        |
| `/chat/:conversationId`           | `ConversationPage`       | ↑                                       | Loads messages, opens SSE on send.                                 |
| `/tools`                          | `ToolsPage`              | ↑                                       | Read-only catalog.                                                 |
| `/mcp-servers`                    | `McpServersPage`         | ↑                                       | Read-only catalog.                                                 |
| `/admin/users`                    | `AdminUsersPage`         | ↑ + `RequireRole(ADMIN)`                |                                                                    |
| `/admin/users/new`                | `AdminUserCreatePage`    | ↑                                       |                                                                    |
| `/admin/users/:userId`            | `AdminUserDetailPage`    | ↑                                       | Enable/disable, delete.                                            |
| `/admin/api-keys`                 | `AdminApiKeysPage`       | ↑                                       | List + create + revoke.                                            |
| `/admin/rate-limit`               | `AdminRateLimitPage`     | ↑                                       |                                                                    |
| `/403`                            | `ForbiddenPage`          | none                                    | Shown when a route guard rejects access (role mismatch).           |
| `*`                               | `NotFoundPage`           | none                                    | Unknown route.                                                     |

The base path is `/` in dev (Vite dev server on `:5173`). The API base URL is taken from
`VITE_API_BASE_URL` (default `http://localhost:8080/api/v1` — see §14.2).

### 5.2 Guards

Guards are React components, not loaders, because they need access to the `AuthContext`. Each
guard returns either its children, a `<Navigate>`, or a stateful render (e.g. a "verifying…"
skeleton during a token bootstrap on cold start).

| Guard                       | Logic                                                                                                                       |
|-----------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `<RequireAuth>`             | If no token or token expired (`exp < now()`), `<Navigate to="/login?next={location}" replace />`.                            |
| `<RequireRole role={X}>`    | If `principal.role !== X`, `<Navigate to="/403" replace />`. Pairs with `RequireAuth`.                                       |
| `<RequireFreshPassword>`    | If `mustChangePassword === true`, `<Navigate to="/change-password?reason=forced" replace />`. Applied to every protected page **except** `/change-password` itself and the logout action. |

The "next" query parameter on `/login` is honored after successful login (validated as
relative path; absolute URLs ignored to prevent open redirects).

### 5.3 Notable user flows

#### 5.3.1 First-time admin login

1. `POST /auth/login` (`operationId: login`) returns `mustChangePassword: true`.
2. Token stored in memory (§6.4); `AuthContext` updated.
3. `<RequireFreshPassword>` redirects every subsequent navigation to `/change-password`.
4. On success of `PUT /auth/password` (`changeOwnPassword`), the local `mustChangePassword`
   flag is set to `false` and the user is routed to `/agents`.
5. The existing JWT remains valid (per the openapi note on `PUT /auth/password`); we do **not**
   force a re-login.

#### 5.3.2 Standard login

1. `POST /auth/login`. If `mustChangePassword === false`, redirect to `?next=` or `/agents`.
2. Auth context exposes `{ token, expiresAt, principal: { sub: email, role, mustChangePassword } }`.

#### 5.3.3 Token expiry mid-session

- The HTTP interceptor (§7.2) catches `401` with `code: INVALID_CREDENTIALS`. It clears the
  token, posts a toast `"Your session expired — sign in again."`, and routes to
  `/login?next={current}`.
- A **proactive timer** in `AuthContext` also schedules a redirect 30 seconds **before** `exp`
  to avoid losing an in-flight chat to a silent 401. The 30-second guard is conservative; users
  see a banner `"Session ends in 30s — finish typing and re-sign in."` rather than an instant
  redirect, so any open composer can be copy-pasted.

#### 5.3.4 Logout

- "Sign out" in the profile menu calls `POST /auth/logout` (best-effort; ignore failures), then
  unconditionally clears the in-memory token and routes to `/login`. This matches the openapi
  note: "the client must also discard the JWT locally".

#### 5.3.5 API key creation — "shown once" UX

- `POST /admin/api-keys` returns `ApiKeyCreated` with a cleartext `apiKey`. The dialog renders
  a `RevealOnceBanner`:
  - The cleartext is displayed in a `Geist Mono` field with a **Copy** button.
  - A persistent inline warning: *"This is the only time this key will be shown. Copy and store
    it securely now."*
  - The "Done" button is **disabled until the user has clicked Copy at least once** (UX guard,
    not a security control).
  - After the dialog is closed, the cleartext is wiped from memory; the list re-fetches and
    shows metadata only.
- This implements the openapi note "Returned only once at creation time. Persist it
  client-side immediately".

---

## 6. Authentication & token handling

### 6.1 Modes supported by the frontend

The frontend authenticates **only via JWT** (per `frontend/CLAUDE.md` purpose: "view ‘me’,
manage personal API keys, …" — these are end-user activities, which the API contract scopes to
JWT). The `X-Api-Key` / `X-Client-Id` mode exists in `openapi.yaml` for machine-to-machine
callers and is never used by this UI.

### 6.2 Login

- Endpoint: `POST /auth/login` (`LoginRequest` → `LoginResponse`).
- On success, the frontend extracts `token`, `expiresAt`, `mustChangePassword`, and decodes the
  JWT payload **client-side without signature verification** to read `sub` (email) and `role`.
  The decoding is for UI gating only — every protected request is still authenticated by the
  backend.
- A small Zod schema validates the JWT payload shape (`{ sub: string, role: 'ADMIN' | 'STANDARD',
  exp: number, iat: number, jti: string }`). If the payload doesn't match, the frontend treats
  it as a failed login (toast: "Unexpected response from the server"), to fail loud rather than
  rendering an undefined role badge.

### 6.3 Sign-out

- "Sign out" calls `POST /auth/logout` and clears local state regardless of the response.
- Treats `429` and `5xx` as best-effort failures: the local state is still cleared and the
  user routed to `/login`. The server-side denylist may retain the token for its TTL, but the
  user-facing intent is honored.

### 6.4 Token storage — in-memory primary, sessionStorage hand-off

JWT lifetime is short (30 min, `REQ-AUTH-004`), there is no refresh token, and the threat we
care most about is **token exfiltration via XSS**. The chosen storage policy is:

- **Primary storage: in-memory** (module-level closure in `shared/auth/tokenStorage.ts`).
  Survives navigation, lost on full reload. XSS attackers cannot read the closure without
  achieving JS execution in our origin (which would be game-over for any storage choice anyway,
  but in-memory minimizes the size of the script the attacker has to inject).
- **Hand-off across full reloads: `sessionStorage`**, with the following discipline:
  - Token is written to `sessionStorage['mam.token']` only on successful login.
  - On app boot, the bootstrapper attempts to **hydrate from `sessionStorage`** into memory,
    then **immediately deletes the `sessionStorage` entry**. The token then lives in memory
    only. If the user reloads again before the next save, they will be re-prompted to sign in.
  - On logout, both stores are cleared.
- We **do not** use `localStorage` (persists across browser sessions; out of scope for short
  TTLs) and **do not** use `httpOnly` cookies (would require CSRF tokens and a same-origin
  reverse proxy; the backend chose `Authorization` header per openapi).

Rationale: the in-memory + sessionStorage hand-off keeps the token unavailable to long-lived
XSS payloads while preserving a familiar "I refreshed the tab and I am still logged in"
experience for the same browser-tab session.

### 6.5 Authorization on the UI

Per `REQ-AUTH-008`, the backend is the authority. The UI hides controls the current principal
cannot use, **and additionally renders an error if a server call is rejected with `FORBIDDEN`**
(belt-and-suspenders).

| Principal role | Sidebar items                                                                       |
|----------------|-------------------------------------------------------------------------------------|
| `STANDARD`     | Agents, Chat, Tools, MCP Servers, Profile.                                          |
| `ADMIN`        | All of the above + **Admin** section (Users, API Keys, Rate Limit).                 |

The admin section is collapsed into one `Admin ▾` group in the sidebar so the navigation stays
quiet for standard users (who simply never see it).

---

## 7. API client, server-state, and pagination

### 7.1 Generated typed client

- `npm run gen:api` runs `openapi-typescript ../openapi.yaml -o src/generated/schema.d.ts`.
- The CI build runs the generator and fails the job if `git diff --exit-code
  src/generated/schema.d.ts` is non-zero. This guarantees the committed types match the
  current contract.
- `src/shared/api/client.ts` creates the `openapi-fetch` client:

  ```ts
  import createClient from 'openapi-fetch';
  import type { paths } from '@/generated/schema';
  export const api = createClient<paths>({
    baseUrl: env.API_BASE_URL,
    headers: { Accept: 'application/json' },
  });
  ```

  All slice `api.ts` modules import `api` from here; no slice constructs its own client.

### 7.2 Interceptors (HTTP middleware)

`openapi-fetch` exposes `client.use({...middleware})`. We register three middlewares, in order:

1. **Auth middleware** — injects `Authorization: Bearer <token>` if a token is present and not
   expired. If the token is expired, it short-circuits with a synthesized `401` response of the
   shape `ProblemDetails(code=INVALID_CREDENTIALS)` so the rest of the stack handles it
   uniformly.
2. **Error middleware** — for every `response.ok === false`, parses the body as
   `application/problem+json`, builds an `ApiError` with the discriminated code, and throws.
   `ApiError` is the **only** error type that reaches feature code; raw `Response`s never do.
3. **Auth-failure middleware** — listens for `ApiError` with `status === 401`, clears the local
   token, and emits an `auth:logout` event consumed by `AuthContext`. The page-level redirect
   to `/login` happens inside the context, not the middleware (the middleware has no router
   handle).

### 7.3 Query keys

A single typed key factory lives at `src/shared/api/queryKeys.ts`:

```ts
export const qk = {
  me: () => ['me'] as const,
  agents: { all: () => ['agents'] as const,
            list: (cursor?: string) => ['agents', 'list', cursor ?? null] as const,
            byId: (id: string) => ['agents', 'byId', id] as const },
  conversations: { all: () => ['conversations'] as const,
                   list: (agentId?: string) => ['conversations', 'list', agentId ?? null] as const,
                   byId: (id: string) => ['conversations', 'byId', id] as const,
                   messages: (id: string) => ['conversations', 'messages', id] as const },
  catalog: { tools: () => ['catalog', 'tools'] as const,
             mcpServers: () => ['catalog', 'mcpServers'] as const },
  admin: { users: { list: () => ['admin', 'users', 'list'] as const,
                    byId: (id: string) => ['admin', 'users', 'byId', id] as const },
           apiKeys: () => ['admin', 'apiKeys'] as const,
           rateLimit: () => ['admin', 'rateLimit'] as const },
} as const;
```

Mutations invalidate the smallest possible subtree (`queryClient.invalidateQueries({ queryKey:
qk.agents.all() })` after a delete; `qk.agents.byId(id)` after an edit).

### 7.4 Cursor pagination

All list endpoints in `openapi.yaml` use the `PageEnvelope` shape (`items[]`, `nextCursor`,
`pageSize`). We wrap them with `useInfiniteQuery`:

```ts
useInfiniteQuery({
  queryKey: qk.agents.list(),
  initialPageParam: undefined as string | undefined,
  queryFn: ({ pageParam }) => api.GET('/agents', { params: { query: { cursor: pageParam } } }),
  getNextPageParam: (last) => last.data?.nextCursor ?? undefined,
});
```

The shared helper `lib/pagination.ts` exposes `flattenPages()` to give components a single
flat array. `pageSize` is left to the server default (20) except in the conversation message
list, where we request `pageSize=64` so a single round-trip fits the entire conversation
(cap is 64 messages — `REQ-CHAT-010`).

### 7.5 Optimistic updates

Used sparingly, only where the round-trip latency is user-visible:

| Mutation                          | Optimistic? | Rationale                                                   |
|-----------------------------------|-------------|--------------------------------------------------------------|
| Edit conversation title            | yes         | Single field; rollback on error.                            |
| Delete conversation                | yes         | Remove from list; restore on error.                         |
| Disable / enable user (admin)      | yes         | Toggle is binary; rollback on error.                        |
| Create agent / update agent        | **no**      | Server runs cross-owner team checks (`CROSS_OWNER_TEAM_MEMBER`) that the client cannot replicate cheaply. Wait for the round-trip. |
| Create API key                     | **no**      | Server returns the cleartext we must show.                  |
| Send chat message                  | yes (USER bubble only) | The user bubble appears immediately; the assistant bubble streams in via SSE (see §8). |

### 7.6 Stale-time and refetch policy

| Resource class           | `staleTime` | `refetchOnWindowFocus` | Notes                                                 |
|--------------------------|-------------|------------------------|--------------------------------------------------------|
| Catalogs (tools, MCP)    | `Infinity`  | false                  | Static — only invalidated on full sign-out.            |
| Agents list/detail       | 30 s        | true                   |                                                        |
| Conversations list       | 0 (always refetch) | true            | List can change from message activity.                 |
| Messages of a conversation | 0         | false                  | Updated explicitly after SSE `completed`.              |
| Admin users / api keys    | 30 s        | true                  |                                                        |
| Rate-limit config        | 60 s        | true                   |                                                        |

---

## 8. SSE streaming for chat

### 8.1 Why `@microsoft/fetch-event-source` instead of native `EventSource`

The native `EventSource` API:

- Issues a `GET`, not a `POST`. The contract requires `POST /conversations/{id}/messages`.
- Cannot carry an `Authorization` header.
- Cannot carry a JSON request body.

`@microsoft/fetch-event-source` solves all three by re-implementing the SSE parser on top of
`fetch`, while preserving the event-stream framing rules.

### 8.2 `shared/sse/chatStream.ts`

A single function exposes the contract:

```ts
type SseFrame =
  | { type: 'started';   userMessageId: string; conversationId: string }
  | { type: 'delta';     text: string }
  | { type: 'completed'; assistantMessageId: string; title: string | null; messageCount: number }
  | { type: 'error';     problem: ProblemDetails };

export function streamChat(conversationId: string, content: string, opts: {
  signal: AbortSignal;
  onFrame: (f: SseFrame) => void;
}): Promise<void>;
```

Implementation details:

- Sets `Accept: text/event-stream`, `Authorization: Bearer ...`, `Content-Type: application/json`,
  body `{ content }`.
- Validates each `event:` against the union of frame names; unknown frames are logged and
  dropped (defensive — the contract pins the four names but tolerates future additions).
- For each named event, `JSON.parse(data)` and call `onFrame` with the typed frame.
- On `error` frame, `onFrame` is called and the function rejects with an `ApiError` built from
  the `ProblemDetails`.
- On HTTP 4xx/5xx **before** the stream opens (e.g., `406`, `409 CONVERSATION_FULL`, `429`),
  the function rejects with the corresponding `ApiError` — no frames will have been delivered.
- On `AbortSignal` abort, the underlying fetch is cancelled. Per `REQ-STR-003`, this is the
  signal the backend uses to dispose the in-flight LLM call.

### 8.3 `features/conversations/useChatStream.ts`

The React-facing wrapper holds the per-conversation streaming state and bridges to TanStack
Query:

- Local state: `{ phase: 'idle' | 'sending' | 'streaming' | 'completed' | 'error',
  pendingUserMessage?: Message, pendingAssistantText: string, error?: ApiError }`.
- On send:
  1. Insert the user message bubble optimistically (with a temp UUID).
  2. Open the stream with `streamChat`.
  3. On `started`, replace the temp user UUID with `userMessageId`.
  4. On every `delta`, append `text` to `pendingAssistantText`. `React.startTransition` keeps
     the typing-render off the critical input path.
  5. On `completed`, commit the assistant bubble with `assistantMessageId`, update
     `messageCount`, and **if `title` is non-null**, update the conversation in the
     conversations-list cache (first-turn title rule).
  6. On `error`, mark the conversation as errored and surface the `ApiError` to a toast.
- After `completed`, `invalidateQueries(qk.conversations.byId(id))` so any out-of-band state
  (e.g. an admin viewing the conversation in another tab) resyncs. The message list itself is
  patched in place — no refetch — so the scroll position is preserved.

### 8.4 Cancellation

- A "Stop" button next to the composer is visible while `phase === 'streaming'`. Clicking it
  aborts the `AbortController`, which:
  - Closes the SSE stream on the wire (the backend disposes the upstream LLM call).
  - Drops the partial assistant bubble from the UI **or** keeps it as a greyed-out "(stopped)"
    record (design choice — we keep it for transparency, but mark it `assistantMessageId: null,
    stopped: true` and **do not** persist it — the backend won't have written it either).
- A page navigation away from the conversation also aborts (the `useChatStream` hook cleans up
  in its `useEffect` return).

### 8.5 Error frames vs HTTP errors

| Trigger                                          | Surface                       | Frame / HTTP                        |
|--------------------------------------------------|-------------------------------|--------------------------------------|
| Empty `content` / >1024 chars                    | inline form error             | HTTP 400 `VALIDATION_ERROR`         |
| Conversation full (64 messages)                  | inline banner + composer disabled | HTTP 409 `CONVERSATION_FULL`     |
| Wrong `Accept` (engineering bug)                 | toast                         | HTTP 406                            |
| Rate-limited                                     | toast + `Retry-After`         | HTTP 429                            |
| LLM upstream failure mid-stream                  | toast + keep partial bubble (greyed) | SSE `error` frame (502)      |
| MCP server failure mid-stream                    | toast                         | SSE `error` frame (502)             |
| Auth expired                                     | global redirect to `/login`   | HTTP 401 via interceptor            |

---

## 9. Forms and validation

### 9.1 Library choice

`react-hook-form` for form state + `zod` for schemas + `@hookform/resolvers/zod` for the bridge.
Each form has a single `xxxSchema` that:

- Mirrors the **client-visible constraints** from `openapi.yaml`.
- Is the source of truth for client-side validation messages.
- Is exported alongside the form so unit tests can assert on it directly.

### 9.2 Constraint mirroring (not duplicated)

The Zod schemas express **only the constraints the openapi spec already documents**
(`maxLength`, `minimum`, `enum`, required fields, regex on email). When the backend cap moves,
both layers move together via the openapi regeneration cycle:

- `LoginRequest`: `email` (email format, ≤254), `password` (≥1, ≤256).
- `ChangePasswordRequest`: `currentPassword`, `newPassword` (≥10, ≤256). The policy regex
  ("≥1 uppercase + ≥1 special") is mirrored from the `description` in the spec; see §9.3.
- `AgentRequest`: every field with its cap; `memorySize` ∈ [1,36]; `team` array of UUIDs.
  Cross-field rule on `team` (single-level) is intentionally **not** mirrored on the client —
  the server returns `409 NESTED_TEAM_FORBIDDEN` / `409 CROSS_OWNER_TEAM_MEMBER`, and the
  client renders that error. The `TeamPicker` component exposes a live, server-driven preview:
  any candidate whose own team is non-empty is shown in the picker with a disabled tooltip
  ("Has a team of its own — cannot be a delegate") so the user sees the constraint before
  submitting.
- `UpdateConversationRequest`: `title` (≥1, ≤32).
- `SendMessageRequest`: `content` (≥1, ≤1024).
- `CreateUserRequest`, `UpdateUserRequest`, `CreateApiKeyRequest`, `UpdateApiKeyRequest`,
  `RateLimitConfigRequest`: as per the spec.

### 9.3 Password policy

The policy in the openapi `ChangePasswordRequest.newPassword.description` is canonical:
"≥10 characters, ≥1 uppercase letter, ≥1 special character". The client encodes it as:

```ts
const passwordPolicy = z.string()
  .min(10, 'At least 10 characters')
  .max(256)
  .regex(/[A-Z]/, 'At least one uppercase letter')
  .regex(/[^A-Za-z0-9]/, 'At least one special character');
```

A live "policy checklist" appears under the field while focused: each rule renders with a
filled or empty check icon. The submit button stays disabled until all three rules pass and the
"confirm new password" field matches.

### 9.4 Server-side validation errors → form fields

`ProblemDetails.errors[]` carries `{ field, message }` entries. The shared error-normalizer
exposes `apiError.fieldErrors: Record<string, string>`; `react-hook-form` consumes them via
`form.setError(field, { message })`. Fields that the server names but the client doesn't show
fall back to a top-of-form alert ("Some fields couldn't be saved — please contact support").

---

## 10. Error handling

### 10.1 Error normalization

Every non-2xx response is normalized to a single `ApiError` class:

```ts
class ApiError extends Error {
  status: number;
  code: ProblemCode;           // discriminated union from the openapi enum
  detail?: string;             // human-readable
  fieldErrors: Record<string,string>;
  retryAfterSeconds?: number;  // from Retry-After header
}
```

The `code` discriminator drives the user-facing copy via a single map in
`shared/i18n/en.ts` (`errorCopy[ApiError.code]`). The map maps every code documented in
`ProblemDetails.code` (`VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `MUST_CHANGE_PASSWORD`,
`FORBIDDEN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `CONFLICT`, `DUPLICATE_AGENT_NAME`,
`NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER`, `CONVERSATION_FULL`, `RATE_LIMITED`,
`LLM_UNAVAILABLE`, `MCP_SERVER_ERROR`, `NOT_ACCEPTABLE`, `INTERNAL_ERROR`) plus a fallback for
unknown codes.

### 10.2 Per-code routing

| Code                          | Default UX                                                                                                  |
|-------------------------------|-------------------------------------------------------------------------------------------------------------|
| `VALIDATION_ERROR`            | Field-level errors via `setError`; top alert if no field maps.                                              |
| `INVALID_CREDENTIALS`         | On `/auth/login`: form-level error. Elsewhere: clear session + redirect to `/login`.                        |
| `MUST_CHANGE_PASSWORD`        | Redirect to `/change-password` with a banner.                                                               |
| `FORBIDDEN`                   | Toast + render forbidden state (data view stays in last-known-good).                                        |
| `NOT_FOUND`                   | On a detail page: render an empty state with a link back to the list.                                        |
| `CONFLICT`, `DUPLICATE_AGENT_NAME`, `NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER` | Form-level error with explicit copy.        |
| `CONVERSATION_FULL`           | Inline banner; composer disabled; CTA "Start a new conversation".                                            |
| `RATE_LIMITED`                | Toast with a 1-second-resolution countdown taken from `retryAfterSeconds`.                                  |
| `LLM_UNAVAILABLE`, `MCP_SERVER_ERROR` | Toast on the chat surface; partial assistant bubble (if any) is preserved and greyed out.            |
| `NOT_ACCEPTABLE`              | Should never occur in production; logged as a bug.                                                          |
| `INTERNAL_ERROR`              | Toast "Something went wrong — please retry." No technical details surfaced.                                  |

### 10.3 Global UI error boundary

A `<RootErrorBoundary>` catches React render-time exceptions (including from a feature hook
that throws an unhandled `ApiError`). It renders a minimalist "We hit an unexpected error"
screen with a "Reload" button. The boundary **does not** log to a remote service in v1 — no
observability integration is wired yet — but it logs to `console.error` so dev sessions are
not silently lossy.

---

## 11. Design system & visual identity

### 11.1 Tokens

CSS custom properties live in `src/styles/tokens.css`, **copied verbatim from
`frontend/CLAUDE.md`** (the source of truth). Tailwind reads them via the `tailwind.config.ts`
`theme.extend.colors` entry so Tailwind classes resolve to `var(--color-...)`. Components
never hardcode hex values.

### 11.2 Typography

`Geist` and `Geist Mono` are loaded via the `geist` npm package
(`import 'geist/font/sans'; import 'geist/font/mono';`) and exposed as CSS variables. The
mono font is used for IDs, JWT-like tokens, model names, and code-shaped UI.

### 11.3 Spacing, density, and radii

The "Dark Professional" target informs:

- **Density**: tight by default. Default form input height `36 px`; row height in list views
  `48 px`. Pages that show a lot of data (admin lists, message list) use `compact` row
  variants.
- **Radii**: `6 px` on inputs/buttons, `8 px` on cards, `12 px` on modals. No fully-rounded
  pills outside `Badge`.
- **Borders**: hairline `0.5 px` borders using `--color-border-default`. Focus state uses a
  2 px outline in `--color-border-focus` plus a 2 px offset so it reads against dark backgrounds.

### 11.4 Component primitives

All primitives are headless logic + Tailwind classnames, **not** wrappers over a third-party
library (Radix etc.). Rationale: the primitive set is small (~16 components), the visual
identity is opinionated, and pulling in Radix would bring accessibility wins but a large
mental-model tax for one design. We do, however, lift Radix's keyboard/ARIA patterns by
inspection for `<Modal>`, `<Dropdown>`, `<Tooltip>`, and `<Tabs>` — see the per-component
acceptance criteria in the EPIC user stories.

### 11.5 Animation budget

Per the "no wow-effect animations" rule in CLAUDE.md, motion is limited to:

- 120 ms ease-out fades for modals, dropdowns, toasts.
- 80 ms hover/active state transitions on interactive elements.
- The **chat bubble streaming render** does NOT animate per-character (perf cost + reading
  cost); the bubble text simply re-renders as `pendingAssistantText` grows. A 1-px blinking
  caret marker at the end of the bubble indicates "still streaming".

### 11.6 Accessibility baseline

- All interactive elements must be reachable by keyboard; focus order matches visual order.
- Color contrast: every text color passes WCAG AA against its intended background (the tokens
  in CLAUDE.md were chosen with this in mind, but we verify per primitive).
- `aria-live="polite"` on the toast container; `role="dialog" aria-modal="true"` on `<Modal>`
  with focus-trap.
- The chat composer is a `<textarea>` with `Cmd/Ctrl+Enter` to send and `Esc` to stop
  streaming. The send button has both an icon and a visible label for screen readers.
- The message list region is announced as `role="log" aria-live="polite"` — incremental delta
  text is **not** announced (would flood the user); the assistant message is announced as a
  whole on `completed`.

---

## 12. Pages — informational catalog

The exhaustive page-level acceptance criteria belong in the EPIC user stories. This section
fixes the **shape** of each page so they are estimable.

### 12.1 `LoginPage`

- Centered card on `--color-bg-base`.
- Two inputs (email, password) + "Sign in" button.
- Error states: invalid credentials (form alert), rate-limited (alert + countdown), forced
  password change (auto-redirect after success).
- No "Forgot password" link — replaced by `Need access? Contact your administrator.` caption.

### 12.2 `ChangePasswordPage`

- Three fields: current, new, confirm new.
- Live policy checklist (see §9.3).
- On forced visit (`?reason=forced`), shows an explicit banner: *"Your administrator created
  this account with a temporary password. Choose a new one to continue."*

### 12.3 `AgentsPage`

- Header: page title + "New agent" CTA.
- List of `AgentCard`s in a CSS grid (1 / 2 / 3 columns by viewport).
- Empty state: "You don't have any agents yet" with a single primary "Create your first agent"
  CTA.
- Each card shows: name, description (truncated to 2 lines), tool count badge, MCP server
  badges, model badge (or "default"), team size badge if non-zero, last-updated timestamp.
- Card action menu: View, Edit, Start chat, Delete (with confirm dialog — cascade warning per
  `REQ-AGT-010`).

### 12.4 `AgentCreatePage` / `AgentEditPage`

- Single `AgentForm`:
  - **Section: Identity** — name, description (markdown-not-supported plain text).
  - **Section: Behavior** — system prompt (textarea with character count), memory size (slider
    1–36 with current value badge).
  - **Section: Model** — model name (text input with "Use platform default" toggle that
    nullifies the field), temperature, max output tokens, top-P (all optional, each with a
    "(default)" reset chip).
  - **Section: Tools** — `ToolPicker` (checkbox list against `GET /tools`).
  - **Section: MCP servers** — `McpServerPicker` (checkbox list against `GET /mcp-servers`).
  - **Section: Team** — `TeamPicker` (multi-select against own `GET /agents`, candidates whose
    team is non-empty are disabled with the tooltip from §9.2).
- Sticky action bar: "Cancel" (back to list) and "Save". On error, scroll to first error.

### 12.5 `AgentDetailPage`

- Read-only summary mirroring the form sections. CTA bar: "Start chat", "Edit", "Delete".
- A "Recent conversations with this agent" panel: top 5 conversations for the agent (calls
  `GET /conversations?agentId=...&pageSize=5`).

### 12.6 `ChatPage` (layout)

- Two-pane layout:
  - **Left (320 px, collapsible to 64 px on small screens)**: `ConversationList`, header
    showing "Chats" with a "+" button that routes to `/chat/new`. Each item: title (or
    `chat-<uuid>` fallback), agent name, last-updated, message-count chip. Active item is
    highlighted with the accent border.
  - **Right (`Outlet`)**: either `ChatNewPage` (agent picker) or `ConversationPage`.

### 12.7 `ChatNewPage`

- Searchable list of own agents → click to create a conversation via
  `POST /conversations { agentId }` and `<Navigate>` to `/chat/{newId}`.

### 12.8 `ConversationPage`

- Topbar: title (inline-editable via `EditTitleDialog`), agent name + link to detail, message
  count "X / 64", overflow menu (Delete).
- Message list: virtualized when count > 32 (we keep it simple — 64 cap means a 64-row plain
  list is fine, but we use `@tanstack/react-virtual` for the very common case of long messages
  causing scrollback jank).
- Composer: `<textarea>` with the constraints from §11.6, "Send" button, "Stop" button when
  streaming, character counter "N / 1024".
- States: "Conversation full" banner replaces the composer at cap (§10.2).

### 12.9 `ToolsPage`, `McpServersPage`

- Plain read-only tables. Useful for users to know what's available before configuring an
  agent. Filterable by name. No write actions.

### 12.10 Admin pages

- `AdminUsersPage`: list with email, role badge, disabled badge, created-at; row click →
  detail; "Create user" CTA.
- `AdminUserDetailPage`: read-only summary; "Disable/Enable" toggle (confirm dialog); "Delete"
  (confirm dialog with explicit cascade warning quoting `REQ-USR-006`).
- `AdminApiKeysPage`: list with client-id, label, created-at, disabled badge; "Create API key"
  CTA opens `CreateApiKeyDialog` (see §5.3.5); row action: revoke / re-enable.
- `AdminRateLimitPage`: form with two numeric fields (per-minute, per-hour), "Last updated by"
  caption, save button. Saving immediately re-fetches to confirm the new live config.

---

## 13. Testing strategy

### 13.1 Test pyramid

| Layer                              | Tool                          | What it asserts                                              |
|------------------------------------|-------------------------------|---------------------------------------------------------------|
| **Unit — pure logic**              | Vitest                        | Zod schemas, formatters, query-key factory, error normalizer. |
| **Unit — hooks**                   | Vitest + RTL `renderHook`     | Feature hooks against MSW handlers.                          |
| **Component**                      | Vitest + RTL + `user-event`   | Forms, pickers, dialogs, list rendering, empty states.       |
| **Page integration**               | Vitest + RTL + MSW            | Full page mount with router and providers; happy + sad paths. |
| **End-to-end (smoke only)**        | Playwright (later)            | Login → create agent → chat 1 turn. Deferred to a later EPIC. |

### 13.2 MSW handlers as a typed fixture

`src/test/server.ts` declares MSW handlers using the **same generated types** as the runtime
client. A small `defineHandler<Op extends keyof paths>()` helper keeps the response shape
honest. Mismatched response shapes fail at TypeScript compile time.

A single `setupMswServer()` is called in `src/test/setup.ts`; every test file gets a fresh
isolated server per `beforeEach`.

### 13.3 Streaming tests

For the SSE flow, the test handler emits a hand-rolled `text/event-stream` body containing
the four frame types in order. We assert:

- The user bubble renders immediately (optimistic).
- The streaming caret appears after `started`.
- The assistant bubble text grows monotonically across `delta` frames.
- On `completed`, the title is patched (first-turn case) and the message count increments.
- On `error`, the partial bubble is preserved and the toast appears.

### 13.4 Coverage target

No hard percentage. We require:

- 100% of feature `api.ts` hooks have at least one happy-path and one error-path test.
- 100% of forms have a Zod-schema test.
- The guard components have routing tests.
- The SSE bridge has the four frame-flow tests above.

---

## 14. Build, dev tooling, environment configuration

### 14.1 Scripts

```jsonc
// package.json
"scripts": {
  "dev":        "vite",
  "build":      "tsc -b && vite build",
  "preview":    "vite preview",
  "test":       "vitest",
  "test:ui":    "vitest --ui",
  "lint":       "eslint . --max-warnings 0",
  "format":     "prettier --check .",
  "format:fix": "prettier --write .",
  "gen:api":    "openapi-typescript ../openapi.yaml -o src/generated/schema.d.ts",
  "verify":    "npm run gen:api && npm run lint && npm run test --run && npm run build"
}
```

`npm run verify` is the single command CI runs and the single command a developer runs before
a commit. The `gen:api` step is intentionally a prerequisite — keeping `schema.d.ts` always in
sync with the committed `openapi.yaml`.

### 14.2 Environment variables

Vite exposes only variables prefixed `VITE_`. A small Zod schema in `src/env.ts` validates them
at module load and fails loudly with a readable error if anything is missing:

| Var                       | Required | Default                                   | Meaning                                  |
|---------------------------|----------|-------------------------------------------|------------------------------------------|
| `VITE_API_BASE_URL`       | yes (dev default supplied) | `http://localhost:8080/api/v1`  | Base URL for the typed client.           |
| `VITE_APP_NAME`           | no       | `Multi-Agent Platform`                    | Document title, login card heading.      |
| `VITE_BUILD_VERSION`      | no       | `dev`                                     | Footer label; injected by CI from git SHA.|

Production builds are static assets served behind any HTTP server. The base URL is **read at
runtime** (not baked) only if needed — for v1, baked at build time is acceptable since each
deployed environment ships its own bundle.

### 14.3 Vite configuration

- Path alias `@` → `src/`.
- `server.proxy` in dev forwards `/api` to `http://localhost:8080` so `VITE_API_BASE_URL` can
  stay `/api/v1` and avoid CORS during local development (the backend's CORS is configurable
  per `REQ-API-003` and dev typically allows `http://localhost:5173`; the proxy is a fallback).
- `define: { __APP_VERSION__: JSON.stringify(env.VITE_BUILD_VERSION ?? 'dev') }`.

### 14.4 Output and deployment

- `vite build` produces `dist/` with `index.html` + hashed JS/CSS chunks + assets.
- The artifact is **static** — any static host (nginx, S3 + CloudFront, an EC2 nginx) serves
  it. The frontend itself has no server runtime.
- A `404 → /index.html` rewrite is required on the host so deep links resolve to the SPA
  router. A `nginx.conf.example` is shipped in `frontend/docs/` (created during EPIC delivery).
- The frontend repo has no AWS dependency: the same `dist/` is uploaded to whichever target
  the deploy chooses.

---

## 15. Security considerations (frontend)

The frontend is not the security perimeter — the backend is. Frontend-side controls are
**defense-in-depth and UX**, not authoritative:

- **No secrets in the bundle.** Anything in `import.meta.env.VITE_*` is shipped to the browser
  — only non-secret config (API base URL, app name) lives there.
- **Token storage**: see §6.4. In-memory primary; sessionStorage hand-off; never localStorage.
- **XSS hygiene**: React's JSX escapes by default. We never use `dangerouslySetInnerHTML`. The
  one place we render Markdown (system prompt previews, if any) uses `react-markdown` with
  sanitization on. The chat assistant content is rendered as **plain text** in v1.
- **CSRF**: not applicable — auth is via `Authorization` header, not cookies.
- **CORS**: the backend allow-list (`REQ-API-003`) is expected to include the deployed frontend
  origin. Local dev relies on the Vite proxy (§14.3).
- **Open redirects**: the `?next=` param on `/login` is validated as a relative path; absolute
  URLs are dropped.
- **`target="_blank"` links**: always with `rel="noopener noreferrer"`.
- **Clipboard handling for cleartext API keys** (§5.3.5): the cleartext is wiped from memory
  when the dialog closes. We do not call `navigator.clipboard.readText()` anywhere.

---

## 16. Non-functional considerations

### 16.1 Performance

- The hot path is the chat composer + the streaming render. Both are isolated in a sub-tree so
  delta updates do not re-render the conversation list.
- `React.lazy()` splits each top-level route into its own chunk. The auth pages and the chat
  page are eagerly loaded; everything else is lazy.
- Bundle budget for v1 (gzip): **≤ 250 KB initial**, **≤ 150 KB per lazy route**. CI fails
  builds that exceed the budget. Tracked via `vite-plugin-bundle-visualizer` in `npm run
  build:analyze`.

### 16.2 Browser support

- Chromium ≥ 117, Firefox ≥ 117, Safari ≥ 16.4 (the floor for `fetch` SSE in Safari is
  pinned by `@microsoft/fetch-event-source`'s polyfill behavior). Internet Explorer is not
  supported.

### 16.3 Observability

- No client-side telemetry / RUM in v1. The `<RootErrorBoundary>` logs to `console.error`.
- A later EPIC may add Sentry or equivalent; the boundary is the single integration point.

---

## 17. Open items / TBD

| Ref     | Topic                                       | Default for v1                                                | Resolution owner            |
|---------|---------------------------------------------|----------------------------------------------------------------|------------------------------|
| TBD-F1  | Light theme                                  | Not built; design tokens are written as `--color-*` so a `<html data-theme="light">` swap is the seam. | Product design.    |
| TBD-F2  | Markdown rendering in assistant output       | Plain text only. Lift to `react-markdown` (sanitized) if user feedback requires it. | Product. |
| TBD-F3  | Toast deduplication for rate-limited bursts  | Last-write-wins on a single toast, replacing the countdown.    | UX review.                   |
| TBD-F4  | Conversation export                          | Not in v1. JSON export of messages is trivial once introduced. | Product.                     |
| TBD-F5  | Virtualization library for message list      | `@tanstack/react-virtual` is the picked choice; revisit if the 64-message cap is raised. | Engineering. |
| TBD-F6  | Real-time conversation list updates          | Polling (TanStack Query `refetchInterval` 30 s) — not a server-push. Adequate at v1 sizing (`REQ-NFR-005`). | Engineering. |
| TBD-F7  | E2E test framework rollout                   | Playwright deferred to a later EPIC; MSW + RTL covers the v1 acceptance baseline. | Engineering. |

These items have explicit v1 defaults — none of them block the EPIC breakdown.

---

## 18. Summary — what the EPICs will deliver

The EPIC plan (to be written in `frontend/backlog/EPICS.md`) will broadly cover:

1. **Project foundation** — Vite + TS + Tailwind + design tokens + router shell + ESLint
   boundaries + `gen:api` pipeline.
2. **Shared layer** — typed API client, error normalizer, query client, auth context + token
   storage, SSE client, design-system primitives, layouts.
3. **Auth flows** — login, force change password, sign out, token expiry handling.
4. **Agents management** — list, create, view, edit, delete, with `TeamPicker` /
   `ToolPicker` / `McpServerPicker`.
5. **Catalogs** — tools and MCP servers read-only pages.
6. **Conversations & messages (non-streaming)** — list, view, edit title, delete, paginated
   message history.
7. **SSE streaming chat** — composer, stream bridge, cancellation, error frames, partial
   bubble preservation.
8. **Admin — Users** — list, create, view, enable/disable, delete (cascade warning).
9. **Admin — API keys** — list, create (reveal-once), revoke.
10. **Admin — Rate limit** — view + update form.
11. **Cross-cutting polish** — toast queue, error boundary, empty / loading / offline states,
    a11y audit.
12. **Build & deployment** — bundle budgets, static-host config example, CI pipeline.

Each EPIC will reference back to the sections above and to the operations in `openapi.yaml`.
