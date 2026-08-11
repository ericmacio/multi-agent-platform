# EPIC-02-US.md — User stories for EPIC-02 (Shared layer — API client, auth, SSE, design system, layouts)

This file lists the user stories that deliver **EPIC-02 — Shared layer (API client, auth, SSE,
design system, layouts)** of the frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-02 ships **every cross-cutting primitive** the feature slices will consume. No business
page is delivered; feature EPICs compose these primitives. The scope spans seven concerns:

1. Shared library utilities (`cn`, `date`, `result`) + i18n copy map skeleton.
2. RFC 7807 error normalizer + `ApiError` class.
3. The `openapi-fetch` typed client + the three HTTP middlewares (auth, error, auth-failure).
4. The TanStack Query client + the typed `qk` key factory.
5. Cursor-pagination helpers (`flattenPages` + `useCursorInfiniteQuery`).
6. JWT storage + decode + `AuthContext` + the three route guards.
7. The 16 design-system primitives + `AppShell` / `AuthShell` layouts.
8. The SSE primitives (`sseFrames.ts` + `chatStream.ts`).

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-02-<nnn>` — `02` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All twelve stories are `MUST` (they implement the
  shared-layer EPIC; every feature EPIC depends on them).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design references,
  and its dependencies.

## Story list

| ID         | Title                                                                            | Priority | Status | Depends on                |
|------------|----------------------------------------------------------------------------------|----------|--------|---------------------------|
| US-02-001  | Shared lib utilities (`cn`, `date`, `result`) + `i18n/en.ts` copy map skeleton   | MUST     | Done   | EPIC-01                   |
| US-02-002  | `ApiError` class + RFC 7807 normalizer (`shared/api/errors.ts`)                  | MUST     | Done   | US-02-001                 |
| US-02-003  | `openapi-fetch` typed client + three HTTP middlewares (`shared/api/client.ts`)   | MUST     | Done   | US-02-002                 |
| US-02-004  | TanStack Query client + typed `qk` key factory                                   | MUST     | Done   | US-02-003                 |
| US-02-005  | Cursor-pagination helpers (`flattenPages` + `useCursorInfiniteQuery`)            | MUST     | Done   | US-02-003, US-02-004      |
| US-02-006  | JWT token storage (`tokenStorage.ts`) + Zod-validated decode (`jwt.ts`)          | MUST     | Done   | US-02-001                 |
| US-02-007  | `AuthContext` + proactive 30 s pre-`exp` banner + `auth:logout` event wiring     | MUST     | Done   | US-02-003, US-02-006      |
| US-02-008  | Route guards (`RequireAuth`, `RequireRole`, `RequireFreshPassword`)              | MUST     | Done   | US-02-007                 |
| US-02-009  | Design-system atoms (Button, Input, Textarea, Select, Checkbox, Badge, Card, Spinner, Skeleton, EmptyState, icons) | MUST | Done | US-02-001 |
| US-02-010  | Design-system overlays (Modal, Dropdown, Tooltip, Tabs, Toast)                   | MUST     | Done   | US-02-009                 |
| US-02-011  | Layout shells (`AppShell` + `Sidebar` + `Topbar` + `AuthShell`)                  | MUST     | Done   | US-02-007, US-02-009, US-02-010 |
| US-02-012  | SSE primitives (`sseFrames.ts` + `chatStream.ts`)                                | MUST     | Done   | US-02-002, US-02-006      |

---

## US-02-001 — Shared lib utilities + i18n copy map skeleton

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** the three small `shared/lib/` utilities (`cn`, `date`, `result`) and the English
copy-map skeleton (`shared/i18n/en.ts`) ready in `src/`
**So that** every later story has a place to import `cn(...)`, format an ISO date, return a
typed `Result<T,E>` from non-throwing code, and reference user-facing copy by key — without
each story re-inventing the same helper.

### Description

This is the smallest possible "ground floor" of `src/shared/`. Each utility is tiny, but
together they unlock every other EPIC-02 story:

- `cn.ts` — `clsx` + `tailwind-merge` so primitives accept a `className` prop and merge it
  with their internal classes without specificity conflicts. **Required** by every primitive
  in US-02-009 / US-02-010.
- `date.ts` — thin wrappers over `Intl.DateTimeFormat` (no `moment`, no `dayjs` — SW-DESIGN
  §4 explicitly forbids them). Functions: `formatDateTime(iso, locale?)`,
  `formatRelative(iso, now?)` (uses `Intl.RelativeTimeFormat`), `isExpired(epochSeconds)`.
- `result.ts` — a 30-line `Result<T,E>` discriminated union (`{ ok: true, value: T } |
  { ok: false, error: E }`) for flows that must not throw (token decode, JSON.parse of an
  SSE frame, etc.). No external lib.
- `i18n/en.ts` — the **copy map skeleton**. SW-DESIGN §10.1 mandates a single `errorCopy[code]`
  map keyed by every `ProblemDetails.code` enum value. This story creates the skeleton with
  one entry per code (sourced from `ProblemDetails.code` in `openapi.yaml`); the keys and
  default English copy are the source of truth for US-02-002 (`ApiError`) and beyond.

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `clsx ^2.1.0`,
  - `tailwind-merge ^2.3.0`.
- `frontend/src/shared/lib/cn.ts` exists with:
  - `export function cn(...inputs: ClassValue[]): string` returning
    `twMerge(clsx(inputs))`.
  - A vitest unit test `cn.test.ts` asserts `cn('p-2', 'p-4')` resolves to `'p-4'` (last
    wins via `tailwind-merge`).
- `frontend/src/shared/lib/date.ts` exists with:
  - `formatDateTime(iso: string, locale?: string): string` — uses `Intl.DateTimeFormat`
    with `dateStyle: 'medium', timeStyle: 'short'`; returns `'—'` if the input cannot be
    parsed (loud but non-throwing).
  - `formatRelative(iso: string, now?: Date): string` — uses `Intl.RelativeTimeFormat` and
    picks the largest unit (`second`/`minute`/`hour`/`day`/`week`/`month`/`year`) that yields
    a value ≥ 1.
  - `isExpired(epochSeconds: number, now?: Date): boolean` — strict `<` comparison.
  - Unit tests `date.test.ts` cover each helper with fixed clocks (no real `Date.now()`).
- `frontend/src/shared/lib/result.ts` exists with:
  - `export type Result<T, E = Error> = { ok: true; value: T } | { ok: false; error: E };`
  - `export const ok = <T,>(value: T): Result<T, never> => ({ ok: true, value });`
  - `export const err = <E,>(error: E): Result<never, E> => ({ ok: false, error });`
  - No unit tests required (type-level only — verified by compilation).
- `frontend/src/shared/i18n/en.ts` exists with:
  - `export const errorCopy: Record<ProblemCode | '__unknown__', { title: string; detail: string }>`
    where `ProblemCode` is the union extracted from the openapi-generated
    `components.schemas.ProblemDetails.code` enum (US-02-002 will import this type once it
    lands; this story may use a hand-written union mirroring the spec and document the
    coupling in a code comment).
  - An entry for **every** code in `ProblemDetails.code` (16 entries):
    `VALIDATION_ERROR`, `INVALID_CREDENTIALS`, `MUST_CHANGE_PASSWORD`, `FORBIDDEN`,
    `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `CONFLICT`, `DUPLICATE_AGENT_NAME`,
    `NESTED_TEAM_FORBIDDEN`, `CROSS_OWNER_TEAM_MEMBER`, `CONVERSATION_FULL`, `RATE_LIMITED`,
    `LLM_UNAVAILABLE`, `MCP_SERVER_ERROR`, `NOT_ACCEPTABLE`, `INTERNAL_ERROR`, plus the
    `__unknown__` fallback bucket.
  - Each entry has the copy listed in SW-DESIGN §10.2 (verbatim where the design specifies
    it; concise English otherwise — "Too many requests, retry shortly", "Something went
    wrong — please retry.", etc.).
  - A vitest unit test `en.test.ts` asserts every `ProblemCode` value (looked up via the
    generated schema) maps to a non-empty entry. This catches a future openapi change that
    introduces a new code without a copy entry.
- `frontend/src/shared/i18n/en.ts` also exports `export const labels = { ... }` — an empty
  starter object for UI labels (button captions, sidebar headings) that later stories will
  extend. The export shape is fixed in this story so feature EPICs can land their entries
  without restructuring.
- `npm run verify` is green.

### Out of scope

- Translation infrastructure (i18next, format.js, …). The product ships English only in v1;
  the copy map is a single file.
- Number / currency formatting helpers — not used by any v1 page.
- `cn` variants that take a `theme` argument — Tailwind already does that.

### Design references

- `frontend/design/SW-DESIGN.md` §4 (project structure — `shared/lib/` and `shared/i18n/`
  layout), §10.1 (single error copy map), §11.6 (a11y baseline — copy must read cleanly
  for screen readers).

### Dependencies

- EPIC-01 (project scaffold, TypeScript strict, lint, test infra).

---

## US-02-002 — `ApiError` class + RFC 7807 normalizer

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `ApiError` class with a discriminated `code` field, a `fieldErrors`
record built from `ProblemDetails.errors[]`, and a `retryAfterSeconds` value taken from
the `Retry-After` header
**So that** every layer above the HTTP client (hooks, forms, components) handles a single
typed error shape — never a raw `Response`, never an unknown JSON blob — and so that the
per-code routing in SW-DESIGN §10.2 has a stable input to switch on.

### Description

The backend always emits `application/problem+json` for non-2xx (per `openapi.yaml`
`ProblemDetails` schema). This story builds the boundary that converts that to a typed
JavaScript error. The `ApiError` instance is what the auth middleware (US-02-003) throws,
what `react-hook-form` consumes via `setError` (per SW-DESIGN §9.4), and what the toast
queue reads to render copy from `errorCopy` (US-02-001).

`fieldErrors` flattens `ProblemDetails.errors[]` (an array of `{ field, message }`) into a
`Record<string,string>` keyed by `field`. If the same `field` appears twice, the last one
wins (server contract is one entry per field, but the normalizer must be defensive).

`retryAfterSeconds` is populated **only** for `429 RATE_LIMITED` responses, parsed from the
`Retry-After` header (integer-seconds form per RFC 7231; HTTP-date form is rejected — the
backend only emits the integer form per the openapi `RateLimited` response schema).

### Acceptance criteria

- `frontend/src/shared/api/errors.ts` exists with the following exports:
  - `export type ProblemCode = components['schemas']['ProblemDetails']['code'];` — sourced
    from the generated schema (US-01-007).
  - `export class ApiError extends Error {`
    - `readonly status: number;`
    - `readonly code: ProblemCode | '__unknown__';`
    - `readonly title: string;` (RFC 7807 `title`)
    - `readonly detail?: string;` (RFC 7807 `detail`)
    - `readonly type?: string;` (RFC 7807 `type`)
    - `readonly instance?: string;` (RFC 7807 `instance`)
    - `readonly fieldErrors: Record<string, string>;`
    - `readonly retryAfterSeconds?: number;`
    - `readonly cause?: unknown;` (for non-RFC-7807 surprises — e.g., a non-JSON body)
    - `constructor(init: ApiErrorInit)`
    - `}`
  - `export async function normalizeResponse(response: Response): Promise<ApiError>` —
    the function the error middleware in US-02-003 will call:
    - Reads `Content-Type`; if it matches `application/problem+json` (or `application/json`
      as fallback — some 4xx may slip), parses the body.
    - If the body parses and contains a recognized `code`, returns an `ApiError` with that
      code.
    - If the body parses but contains an **unknown** code (forward-compat), returns an
      `ApiError` with `code: '__unknown__'`, copying `title` / `detail` / `status` as-is.
    - If the body fails to parse, returns an `ApiError` with `code: 'INTERNAL_ERROR'`,
      `cause: parseError`, and a fallback `detail` of `'Malformed error response'`.
    - On `status === 429`, parses the `Retry-After` integer; on parse failure leaves
      `retryAfterSeconds` undefined.
- `ApiError.prototype.toString()` returns `${code}: ${title}${detail ? ' — ' + detail : ''}`
  for clean `console.error` output (developer ergonomics).
- A static helper `ApiError.synthesized(code: ProblemCode, status: number, detail?: string):
  ApiError` exists for the auth middleware's "token expired, short-circuit before fetch"
  path (US-02-003). It builds an `ApiError` without a `Response`.
- **Unit tests** in `errors.test.ts` cover, **at minimum**, one assertion per known
  `ProblemCode` (16 tests, parameterized) that:
  - feeds a minimal `Response`-shaped fixture (built with `new Response(JSON.stringify({...}),
    { status, headers: { 'Content-Type': 'application/problem+json' } })`),
  - awaits `normalizeResponse(fixture)`,
  - asserts `.code` equals the expected code, `.status` is correct, and (where applicable)
    `.fieldErrors` / `.retryAfterSeconds` are populated.
- A 17th test asserts the **unknown-code** branch (`code: 'FUTURE_CODE_X'` →
  `code === '__unknown__'`).
- An 18th test asserts the **malformed JSON** branch (body `'not json'`) →
  `code === 'INTERNAL_ERROR'`, `cause` is set.
- A 19th test asserts the `Retry-After: 30` header populates `retryAfterSeconds` to `30`.
- A 20th test asserts the `VALIDATION_ERROR` branch with two `errors[]` entries populates
  `fieldErrors` correctly (`{ field: 'name', message: 'too long' }` and `{ field: 'email',
  message: 'invalid' }` → `{ name: 'too long', email: 'invalid' }`).

### Out of scope

- Sentry / remote logging integration — deferred (SW-DESIGN §16.3).
- The HTTP middleware itself — US-02-003.
- The toast surface that renders the error — US-02-010 (`Toast`) + US-02-011 (mount point).

### Design references

- `frontend/design/SW-DESIGN.md` §10.1 (normalization), §10.2 (per-code routing — must use
  the same `code` discriminator), §9.4 (`fieldErrors`).
- `openapi.yaml` (`ProblemDetails` schema; `RateLimited` `Retry-After` header).

### Dependencies

- US-02-001 (`errorCopy` keys must align with `ProblemCode`).

---

## US-02-003 — `openapi-fetch` typed client + three HTTP middlewares

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `api` client built with `openapi-fetch` against the generated `paths`
type, registered with the three middlewares from SW-DESIGN §7.2 (auth header injection,
error normalization, auth-failure broadcast)
**So that** every later `api.ts` slice imports a typed, pre-configured client — and so that
401 responses, expired tokens, and RFC 7807 errors are handled uniformly without each slice
re-inventing the logic.

### Description

`openapi-fetch` is the runtime half of the codegen pair documented in SW-DESIGN §2.3. It
produces `api.GET('/agents', { params: { query: { cursor: '...' } } })`-style calls that
are fully typed against the `paths` interface from `src/generated/schema.d.ts` (US-01-007).

The three middlewares are registered in order:

1. **Auth middleware** — reads the token from `tokenStorage` (US-02-006). If a token is
   present and not expired, injects `Authorization: Bearer <token>`. If the token is
   present but **expired** (`exp < now`), the middleware **does not fetch** — it
   short-circuits with a synthesized `Response` of shape
   `{ status: 401, body: ProblemDetails(code='INVALID_CREDENTIALS') }` so the error
   middleware (next in chain) handles it uniformly.
2. **Error middleware** — for every `response.ok === false`, calls
   `normalizeResponse(response)` from US-02-002 and **throws** the resulting `ApiError`.
   `openapi-fetch` propagates the throw through its return path so the calling hook's
   `mutationFn` / `queryFn` rejects with `ApiError`.
3. **Auth-failure middleware** — observes thrown `ApiError`s; if `status === 401`, clears
   the in-memory token (`tokenStorage.clear()`) and `window.dispatchEvent(new
   CustomEvent('auth:logout', { detail: { reason: 'token-rejected' } }))`.
   `AuthContext` (US-02-007) listens for this event; the redirect to `/login` happens
   there, not in the middleware (the middleware has no router handle, per SW-DESIGN §7.2).

The client `baseUrl` comes from `env.VITE_API_BASE_URL` (US-01-006).

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `openapi-fetch ^0.10.0` (or the latest 0.x at story-pickup time; pin minor).
- `frontend/src/shared/api/client.ts` exists with:
  - `import createClient, { Middleware } from 'openapi-fetch';`
  - `import type { paths } from '@/generated/schema';`
  - `import { env } from '@/env';`
  - `export const api = createClient<paths>({ baseUrl: env.VITE_API_BASE_URL, headers: { Accept: 'application/json' } });`
  - The auth middleware is `const authMiddleware: Middleware = { onRequest({ request }) { ... }, };` — short-circuits via the optional `return new Response(...)` form on expired-token; otherwise mutates the request headers and returns `undefined`.
  - The error middleware is `const errorMiddleware: Middleware = { async onResponse({ response }) { if (!response.ok) throw await normalizeResponse(response); } };`.
  - The auth-failure middleware listens via `onResponse` (after error normalization) — actually, because the error middleware throws, the auth-failure logic is best done by catching at the same `onResponse` hook before the error throw. **Implementation note**: combine error normalization and the auth-failure side effect in a single `onResponse` middleware, registered after the auth-injecting `onRequest` middleware. The PR may also split them via a small "side-effects after error" sub-helper — either factoring is acceptable as long as the behavior below holds.
  - All three middlewares are registered via `api.use(authMiddleware); api.use(errorMiddleware);` in order.
- `frontend/src/shared/api/client.ts` also exports a small `useApiQueryFn` helper that
  wraps the typed `api.GET` / `api.POST` returns and returns `data` directly (so feature
  hooks don't have to write `const { data, error } = await api.GET(...); if (error) throw
  error; return data;` boilerplate for every endpoint). Alternative: a `unwrap()` helper.
  Either is acceptable; pick the shape consumed by US-02-005.
- **Unit tests** in `client.test.ts` use MSW v2 handlers (US-01-009) to exercise:
  - `200` with body → resolves with typed data.
  - `400 VALIDATION_ERROR` with `errors[]` → rejects with `ApiError` whose `fieldErrors` is
    populated.
  - `401 INVALID_CREDENTIALS` → rejects with `ApiError(status=401)` AND a spy on
    `window.dispatchEvent` confirms an `auth:logout` event was fired.
  - `429 RATE_LIMITED` with `Retry-After: 5` → rejects with `ApiError` whose
    `retryAfterSeconds === 5`. **No** `auth:logout` event is fired.
  - **Expired-token short-circuit**: the token storage is pre-loaded with a token whose
    decoded `exp` is in the past (use a fake token whose payload Zod-validates per
    US-02-006). The middleware must **not** issue a network request (MSW handler asserts
    zero hits) and must produce an `ApiError(code='INVALID_CREDENTIALS', status=401)`.
  - **No token**: a request issued with no token loaded sends no `Authorization` header;
    this is the contract for `POST /auth/login` (which is the only call expected to ever
    run without a token).
- A 6th test asserts the **request body encoding** path: `api.POST('/auth/login', { body:
  {...} })` serializes the body as JSON with `Content-Type: application/json` (the only
  request-body-bearing call exercised before EPIC-03 lands).

### Out of scope

- Retry logic on `429` / network blips — TanStack Query handles retry per US-02-004; the
  middleware does not retry.
- SSE — US-02-012 ships a separate client (`@microsoft/fetch-event-source` cannot pass
  through `openapi-fetch`).
- Per-request `signal` plumbing for cancellation — `openapi-fetch` exposes it natively; no
  shim required.

### Design references

- `frontend/design/SW-DESIGN.md` §7.1 (generated typed client), §7.2 (three middlewares),
  §10.1 (every error becomes an `ApiError`).
- `openapi.yaml` (the `paths` source for `openapi-fetch`).

### Dependencies

- US-02-002 (`ApiError`, `normalizeResponse`).
- US-02-006 is a soft dependency: the auth middleware reads from `tokenStorage`. **Story
  ordering choice**: the auth middleware in this story may consume `tokenStorage` via a
  thin getter `getToken()`; the `tokenStorage` module itself lands in US-02-006. The two
  PRs are coupled but can be reviewed back-to-back. To avoid a circular merge, the auth
  middleware is allowed to import a temporary `getToken = () => null` stub from a
  `client.ts`-local symbol; US-02-006 wires the real implementation in. The acceptance
  test above ("token storage pre-loaded with expired token") confirms the contract.

---

## US-02-004 — TanStack Query client + typed `qk` key factory

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a singleton `QueryClient` with the stale-time defaults from SW-DESIGN §7.6, and a
single typed `qk` factory exposing every cache-key root the app uses
**So that** every later feature hook reads from the same client, uses keys that cannot
collide by accident, and obeys the same retry / refetch policy without re-stating it.

### Description

The `QueryClient` is shared app-wide. It is mounted in `src/main.tsx` via
`<QueryClientProvider client={queryClient}>` (the mount itself is added in this story so
the smoke test from US-01-009 keeps passing).

Per SW-DESIGN §7.6, the **default** `staleTime` is `30 * 1000` (30 s) — most resources
agree on this. Per-feature overrides (e.g., catalogs with `staleTime: Infinity`, messages
with `staleTime: 0`) land in their respective EPICs by overriding on the `useQuery` call
site; this story only sets the defaults.

`retry` is set to `false` for mutations and to a small, deterministic policy for queries:
**no retry on `4xx`** (validation / auth / 404 are not transient), one retry on `5xx` or
network failure. This is implemented as `retry: (failureCount, error) => failureCount < 1
&& !(error instanceof ApiError && error.status >= 400 && error.status < 500)`.

The `qk` factory is the **single** source of cache keys. SW-DESIGN §7.3 enumerates it
verbatim; this story ships that file unchanged.

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `@tanstack/react-query ^5.40.0` (or the latest 5.x at story-pickup time).
- `frontend/src/shared/api/queryClient.ts` exists with:
  - `import { QueryClient } from '@tanstack/react-query';`
  - `import { ApiError } from './errors';`
  - `export const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: true, retry: (count, err) => count < 1 && !(err instanceof ApiError && err.status >= 400 && err.status < 500) }, mutations: { retry: false } } });`
- `frontend/src/shared/api/queryKeys.ts` exists with the **exact** structure from
  SW-DESIGN §7.3:
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
- `frontend/src/main.tsx` is updated to wrap `<App />` in a `<QueryClientProvider
  client={queryClient}>` (above the `<RouterProvider>` so every route has access). The
  `QueryClientProvider` is the only thing added; `StrictMode` and `RouterProvider` stay.
- `frontend/src/test/render.tsx` is extended so the test render helper from US-01-009 now
  wraps the rendered tree in a **fresh per-test** `QueryClient` (not the singleton — tests
  must not leak cache across each other). The `renderWithProviders(ui, { queryClient? })`
  signature accepts an override for tests that want to seed the cache.
- **Unit tests** in `queryKeys.test.ts` assert:
  - Each `qk` builder returns a stable reference structure (compared by `JSON.stringify` —
    the `as const` makes them readonly tuples).
  - `qk.agents.list()` and `qk.agents.list(undefined)` produce **the same key**
    (`['agents', 'list', null]`).
  - `qk.agents.byId('a') !== qk.agents.byId('b')` (different IDs → different keys).
- A second test file `queryClient.test.ts` covers the retry policy:
  - Throwing an `ApiError(status=400)` from a `queryFn` results in **zero retries**.
  - Throwing an `ApiError(status=502)` results in **one retry**.
  - Throwing a generic `Error` (network) results in **one retry**.

### Out of scope

- Devtools (`@tanstack/react-query-devtools`) — useful in dev; deferred to EPIC-11 polish
  if at all.
- `persistQueryClient` — not needed; in-memory cache resets on hard reload, which is the
  intended behavior given JWTs reset too (per US-02-006's sessionStorage hand-off model).
- Suspense mode — not used in v1; loading states render explicit skeletons.

### Design references

- `frontend/design/SW-DESIGN.md` §7.3 (key factory — copied verbatim), §7.6 (stale-time
  defaults).

### Dependencies

- US-02-003 (`ApiError` used in the retry predicate).

---

## US-02-005 — Cursor-pagination helpers (`flattenPages` + `useCursorInfiniteQuery`)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a thin `useCursorInfiniteQuery` helper and a `flattenPages()` flattener that
together abstract the boilerplate of consuming `PageEnvelope`-shaped endpoints
**So that** every list page (agents, conversations, admin users, admin api-keys) calls
`useCursorInfiniteQuery({ key, fetchPage })` instead of restating the
`useInfiniteQuery({ initialPageParam, queryFn, getNextPageParam })` configuration, and so
that components consume a single `items[]` array without manual page assembly.

### Description

Every list endpoint in `openapi.yaml` returns a `PageEnvelope` shape with `items[]`,
`nextCursor`, `pageSize`. SW-DESIGN §7.4 sketches the consumer pattern:

```ts
useInfiniteQuery({
  queryKey: qk.agents.list(),
  initialPageParam: undefined as string | undefined,
  queryFn: ({ pageParam }) => api.GET('/agents', { params: { query: { cursor: pageParam } } }),
  getNextPageParam: (last) => last.data?.nextCursor ?? undefined,
});
```

The helper wraps that pattern. Per SW-DESIGN §7.4, `pageSize` is left to the server default
(20) **except** for `useMessages` which uses `pageSize=64`; the helper accepts a `pageSize`
option so EPIC-06 can pass it through.

`flattenPages()` is a one-liner over `infiniteQuery.data?.pages` that returns a single
`items[]` array — but typed against the page shape, so consumers don't have to assert.

### Acceptance criteria

- `frontend/src/shared/lib/pagination.ts` exists with the following exports:
  - `export type PageEnvelope<T> = { items: T[]; nextCursor: string | null; pageSize: number };`
  - `export function flattenPages<T>(infiniteData: { pages: PageEnvelope<T>[] } | undefined): T[]` — returns `infiniteData?.pages.flatMap(p => p.items) ?? []`.
  - `export function useCursorInfiniteQuery<T>(opts: { queryKey: QueryKey; fetchPage: (cursor?: string) => Promise<PageEnvelope<T>>; pageSize?: number; enabled?: boolean; staleTime?: number; }): UseInfiniteQueryResult<{ pages: PageEnvelope<T>[]; pageParams: (string | undefined)[]; }, ApiError>`.
  - Implementation: wraps `useInfiniteQuery` with `initialPageParam: undefined`,
    `queryFn: ({ pageParam }) => opts.fetchPage(pageParam as string | undefined)`,
    `getNextPageParam: (last) => last.nextCursor ?? undefined`. `pageSize` is **not** sent
    by the helper — the caller threads it through `fetchPage` because it varies per
    endpoint and the typed `openapi-fetch` call site is where the path-typing happens.
  - `staleTime` and `enabled` are forwarded to TanStack Query.
- **Unit tests** in `pagination.test.ts`:
  - `flattenPages(undefined)` returns `[]`.
  - `flattenPages({ pages: [{ items: [1,2] }, { items: [3] }] })` returns `[1,2,3]`.
  - `useCursorInfiniteQuery` (rendered via the test helper from US-02-004) walks through
    three MSW-served pages: page 1 returns `nextCursor: 'p2'`, page 2 returns `nextCursor:
    'p3'`, page 3 returns `nextCursor: null`. After `fetchNextPage` is called twice,
    `hasNextPage === false` and `flattenPages(result.data)` returns the concatenated array.
  - On `ApiError` from `fetchPage`, the result state surfaces it (`result.error instanceof
    ApiError`), and `fetchNextPage` does not retry beyond the policy from US-02-004.
- An exported type `InferPageItem<T>` (mapped from `PageEnvelope<X>` → `X`) is **not**
  required — the typed `openapi-fetch` call site already gives the consumer the exact
  type. Skip this if it complicates the implementation.

### Out of scope

- Bidirectional pagination — the spec is one-direction (`nextCursor` only).
- Page-size negotiation in the helper — kept out so the helper stays one purpose.
- "Show more" UI — that's the consuming list page's job.

### Design references

- `frontend/design/SW-DESIGN.md` §7.4 (cursor pagination pattern).
- `openapi.yaml` (`PageEnvelope` schema, all list endpoints).

### Dependencies

- US-02-003 (`ApiError` for typed errors).
- US-02-004 (`useInfiniteQuery` consumer needs the `QueryClientProvider` from the test
  render helper).

---

## US-02-006 — JWT token storage + Zod-validated client-side decode

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a `tokenStorage` module enforcing the in-memory + `sessionStorage` hand-off
policy from SW-DESIGN §6.4, plus a `jwt.ts` module that Zod-validates the JWT payload
shape on decode
**So that** the token never lives in `localStorage`, survives a single tab reload but not
a browser-session close, and is rejected loudly if its payload doesn't match the contract
(rather than silently producing a garbage `role`).

### Description

Per SW-DESIGN §6.4, the JWT lifetime is short (30 min, `REQ-AUTH-004`), there is no refresh
token, and the dominant threat is **token exfiltration via XSS**. The policy is:

- **Primary storage: module-level closure** in `tokenStorage.ts` (in-memory). Not exposed
  on `window`; not reachable via `document`.
- **Hand-off across reloads: `sessionStorage`** with one-shot semantics: on app boot, the
  bootstrapper calls `tokenStorage.hydrateFromSession()` which moves the token from
  `sessionStorage['mam.token']` into memory and **immediately deletes** the
  `sessionStorage` entry.
- On successful login, `tokenStorage.set(token)` writes to both stores.
- On logout, `tokenStorage.clear()` clears both.

`jwt.ts` exposes a single `decodeJwtPayload(token: string): Result<JwtPayload, JwtDecodeError>`
that:

- Splits the token on `.`, base64url-decodes the middle segment, JSON-parses it.
- Validates the resulting object against the Zod schema
  `z.object({ sub: z.string().min(1), role: z.enum(['ADMIN','STANDARD']), exp: z.number().int().positive(), iat: z.number().int().positive(), jti: z.string().min(1) })`.
- Returns `ok(payload)` or `err({ reason: 'malformed' | 'invalid-payload' })`.

**The decode is for UI gating only** (per SW-DESIGN §6.2). It does NOT verify the signature
— the backend is the authority on every protected request. The decode's loud-on-failure
behavior is what catches a backend that ships a JWT shape mismatch.

### Acceptance criteria

- `frontend/src/shared/auth/tokenStorage.ts` exists with the following exports:
  - `export type TokenBundle = { token: string; expiresAt: string; mustChangePassword: boolean };` — matches `LoginResponse` minus `tokenType`.
  - `let memory: TokenBundle | null = null;` (module-scoped closure).
  - `export const tokenStorage = { get(): TokenBundle | null { return memory; }, set(bundle: TokenBundle): void { memory = bundle; sessionStorage.setItem('mam.token', JSON.stringify(bundle)); }, clear(): void { memory = null; sessionStorage.removeItem('mam.token'); }, hydrateFromSession(): TokenBundle | null { const raw = sessionStorage.getItem('mam.token'); if (!raw) return null; sessionStorage.removeItem('mam.token'); try { const parsed = JSON.parse(raw); /* lightly validate shape */ memory = parsed; return parsed; } catch { return null; } } };`
  - A **minimal Zod shape check** inside `hydrateFromSession` rejects payloads that don't
    have the three expected keys — same loud-on-malformed contract as `jwt.ts`. On reject,
    `memory` stays `null`.
- `frontend/src/shared/auth/jwt.ts` exists with:
  - `import { z } from 'zod';`
  - `import { ok, err, type Result } from '@/shared/lib/result';`
  - `export const jwtPayloadSchema = z.object({ sub: z.string().min(1), role: z.enum(['ADMIN', 'STANDARD']), exp: z.number().int().positive(), iat: z.number().int().positive(), jti: z.string().min(1) });`
  - `export type JwtPayload = z.infer<typeof jwtPayloadSchema>;`
  - `export type JwtDecodeError = { reason: 'malformed' | 'invalid-payload'; cause?: unknown };`
  - `export function decodeJwtPayload(token: string): Result<JwtPayload, JwtDecodeError>` — implementation per the description above. Uses `atob` after URL-safe `+`/`/` substitution and `=` padding.
- The auth middleware in `client.ts` (US-02-003) is updated to call
  `tokenStorage.get()` and `decodeJwtPayload(token).value?.exp` to check expiry. The
  temporary stub from US-02-003's dependencies section is **replaced** here.
- `src/main.tsx` calls `tokenStorage.hydrateFromSession()` once at app boot, **before**
  the `<QueryClientProvider>` renders, so the first protected route render already sees
  the hydrated token.
- **Unit tests** in `tokenStorage.test.ts`:
  - `set()` → `get()` returns the same bundle.
  - `set()` writes the bundle to `sessionStorage['mam.token']` as JSON.
  - `clear()` empties memory and removes the `sessionStorage` entry.
  - `hydrateFromSession()` with a populated `sessionStorage` returns the bundle, populates
    memory, and **deletes** the `sessionStorage` entry (assert via
    `sessionStorage.getItem(...) === null`).
  - `hydrateFromSession()` with no `sessionStorage` entry returns `null` and leaves memory
    unchanged.
  - `hydrateFromSession()` with a malformed JSON entry returns `null` and **does not**
    populate memory. Verifies the loud-on-malformed contract.
- **Unit tests** in `jwt.test.ts`:
  - `decodeJwtPayload(validToken)` returns `ok(payload)` where `payload.sub`, `role`,
    `exp`, `iat`, `jti` round-trip from a hand-built test token (use a fixture builder
    that base64url-encodes a known payload).
  - `decodeJwtPayload('not.a.jwt')` returns `err({ reason: 'malformed' })`.
  - `decodeJwtPayload(tokenWithMissingRole)` returns `err({ reason: 'invalid-payload' })`.
  - `decodeJwtPayload(tokenWithUnknownRole)` (`role: 'SUPER_ADMIN'`) returns
    `err({ reason: 'invalid-payload' })`.

### Out of scope

- Signature verification — explicitly out (per SW-DESIGN §6.2: the backend is the
  authority).
- Refresh tokens — none in the contract.
- Cross-tab `BroadcastChannel` sync — explicitly **out**: SW-DESIGN §6.4 chose tab-scoped
  state.
- `localStorage` migration — there's nothing to migrate from.

### Design references

- `frontend/design/SW-DESIGN.md` §6.2 (login + JWT decode contract), §6.4 (storage policy),
  §15 ("no secrets in the bundle").

### Dependencies

- US-02-001 (`Result` type).

---

## US-02-007 — `AuthContext` + proactive 30 s pre-`exp` banner + `auth:logout` wiring

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** an `AuthContext` exposing `{ token, expiresAt, principal, mustChangePassword,
signIn, signOut }`, a proactive timer that surfaces a "session ends in 30 s" banner before
the JWT actually expires, and an `auth:logout` event listener that routes the user back to
`/login?next=…`
**So that** every protected page reads auth state from one place, no in-flight user action
is silently aborted by a 401, and every layer below the context (the HTTP middleware, the
route guards, the SSE client) integrates with the same source of truth.

### Description

Per SW-DESIGN §5.3.3 and §6, the context is the **single source of truth** for the
authenticated principal. It holds:

- `token: string | null`
- `expiresAt: string | null` (ISO 8601 UTC, from `LoginResponse`)
- `principal: { sub: string; role: 'ADMIN' | 'STANDARD' } | null` (decoded from the JWT)
- `mustChangePassword: boolean`

It exposes:

- `signIn(bundle: TokenBundle): void` — calls `tokenStorage.set`, decodes the JWT, sets
  the state. If the JWT decode fails, throws (surfaced as a toast at the call site).
- `signOut(): void` — calls `tokenStorage.clear()`, clears state, navigates to `/login`.

It manages two side effects:

1. **Proactive expiry banner**: a `setTimeout` scheduled at `(expiresAtEpochSec - 30) * 1000
   - Date.now()` fires a banner: *"Session ends in 30s — finish typing and re-sign in."*
   The banner is dismissable; it is also auto-dismissed on `signOut()`. The banner
   component itself lives in `shared/ui/` (US-02-009/010) — this story wires the **trigger**
   and renders an inline banner in `AppShell` (US-02-011) that subscribes to the trigger.
2. **`auth:logout` event listener**: when the HTTP auth-failure middleware (US-02-003) fires
   the event, the context catches it, calls `signOut()`, and routes to
   `/login?next=<encoded current location>`. The `next` query parameter is the path the
   user was on (relative; validated server-side and on consumption per US-02-008).

### Acceptance criteria

- `frontend/src/shared/auth/AuthContext.tsx` exists with:
  - `export type Principal = { sub: string; role: 'ADMIN' | 'STANDARD' };`
  - `export type AuthState = { token: string | null; expiresAt: string | null; principal: Principal | null; mustChangePassword: boolean; };`
  - `export type AuthContextValue = AuthState & { signIn: (bundle: TokenBundle) => void; signOut: () => Promise<void>; };`
  - `export const AuthContext = React.createContext<AuthContextValue | null>(null);`
  - `export function useAuth(): AuthContextValue` — throws if rendered outside the provider (developer ergonomics).
  - `export function AuthProvider({ children }: { children: React.ReactNode })` — initial state hydrated from `tokenStorage.get()` at first render, JWT decoded with `decodeJwtPayload`, `principal` populated.
- The provider registers a single `window.addEventListener('auth:logout', handler)` in a
  `useEffect`, cleans it up on unmount. The handler calls `signOut()` and `navigate('/login?next=' + encodeURIComponent(location.pathname + location.search))`.
- The provider schedules the 30 s pre-expiry trigger:
  - On mount and on every `signIn`, computes `triggerAtMs = (Date.parse(expiresAt) - 30_000)`. If `triggerAtMs > Date.now()`, `setTimeout` to flip a state flag `expiryWarning: true`. The flag is exposed via the context (`AuthContextValue` extended with `expiryWarning: boolean; dismissExpiryWarning: () => void;`).
  - On `signOut`, the timer is cleared and `expiryWarning` reset to `false`.
- `signOut()` is async (returns `Promise<void>`) so the call site can chain to the
  best-effort `POST /auth/logout` request — but the **logout endpoint call itself** is the
  responsibility of the EPIC-03 `useLogout` hook. The context's `signOut` only clears
  local state and navigates; the EPIC-03 hook will compose: `await api.POST('/auth/logout');
  await signOut();` (and tolerate the endpoint failing — per SW-DESIGN §6.3).
- `src/main.tsx` wraps `<RouterProvider>` in `<AuthProvider>`, **inside** the
  `<QueryClientProvider>` (so the auth provider can use TanStack Query if needed in
  future EPICs, though it does not here).
- The test render helper `renderWithProviders` from US-02-004 is extended to wrap the
  tree in `<AuthProvider>`. The helper accepts an optional `initialAuth: Partial<AuthState>`
  override; under the hood it pre-populates `tokenStorage` before the provider mounts.
- **Unit tests** in `AuthContext.test.tsx`:
  - Initial render with no token in `tokenStorage`: `principal === null`, `token === null`.
  - Initial render with a pre-populated token: `principal.sub` matches the decoded `sub`,
    `mustChangePassword` matches the bundle.
  - `signIn(bundle)`: state updates; `tokenStorage.get()` returns the bundle.
  - `signOut()`: state cleared; `tokenStorage.get()` returns `null`; navigation called
    with `/login` (use a spy on `useNavigate`).
  - **Expiry-banner timing**: with `vi.useFakeTimers()`, sign in with `expiresAt` set 60 s
    in the future; advance `30 s`; `expiryWarning === true`. `dismissExpiryWarning()`
    flips it back to `false`. Then advancing another `25 s` does **not** re-trigger.
  - **`auth:logout` event**: dispatch `new CustomEvent('auth:logout')` from
    `window.dispatchEvent`; verify state is cleared and navigation hit `/login?next=...`.

### Out of scope

- The `useLogin` / `useLogout` / `useChangeOwnPassword` hooks — EPIC-03.
- The banner component's visual design — US-02-009 provides the `Toast` / `Banner`
  primitive; the trigger logic lives here.
- Tab-syncing — explicitly out (SW-DESIGN §6.4).

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.3 (token expiry mid-session), §5.3.4 (logout), §6
  (full auth model), §6.5 (role gating consumed via `useAuth`).

### Dependencies

- US-02-003 (the `auth:logout` event is fired by the HTTP middleware).
- US-02-006 (`tokenStorage`, `decodeJwtPayload`).

---

## US-02-008 — Route guards (`RequireAuth`, `RequireRole`, `RequireFreshPassword`)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** three React components — `<RequireAuth>`, `<RequireRole role={...}>`,
`<RequireFreshPassword>` — wrapping protected route elements
**So that** EPIC-03 onward can declare guards in `pages/routes.tsx` exactly as documented
in the route table of SW-DESIGN §5.1 / §5.2, without each page re-implementing the
redirect logic.

### Description

Per SW-DESIGN §5.2, guards are **components**, not loaders, because they need
`useAuth()`. Each guard returns one of:

- Its `children` (or `<Outlet />`) — happy path.
- A `<Navigate replace />` — redirect path.
- A "verifying…" skeleton — only for `<RequireAuth>` on a cold start where the token is in
  `sessionStorage` and `hydrateFromSession()` has not run yet (in practice it runs in
  `main.tsx` before the router mounts, so the skeleton state is a defensive fallback).

`<RequireAuth>`:

- If `useAuth().token === null` → `<Navigate to={"/login?next=" + encodeURIComponent(location.pathname + location.search)} replace />`.
- Else if `Date.parse(expiresAt) < Date.now()` → same redirect (token expired locally;
  this should be rare since the HTTP middleware catches it too).
- Else → `<Outlet />`.

`<RequireRole role={'ADMIN'}>`:

- Wraps an already-authed segment.
- If `useAuth().principal?.role !== role` → `<Navigate to="/403" replace />`.

`<RequireFreshPassword>`:

- If `useAuth().mustChangePassword === true` → `<Navigate to="/change-password?reason=forced" replace />`.

The `next` query parameter is **validated** by `/login` on consumption (EPIC-03): only
relative paths are honored. This guard escapes the path correctly so the validation has
a chance to succeed.

### Acceptance criteria

- `frontend/src/shared/auth/guards.tsx` exists with the three components above plus their
  small `Props` types.
- All three accept either a `children` prop (single child) or render `<Outlet />` (when no
  children passed — the common case in `pages/routes.tsx` where the guard wraps a route
  segment).
- An additional `<RequireGuest>` component is exposed for `/login`: if `token !== null`,
  redirect to `/agents` (per SW-DESIGN §5.1 — `/login` redirects to `/agents` if already
  authed). This is small enough to bundle in this story rather than create a separate one.
- The `next` query parameter handling:
  - Built via `'?next=' + encodeURIComponent(currentPath + currentSearch)`.
  - `currentPath` is sourced from `useLocation()`.
- **Unit tests** in `guards.test.tsx` using `MemoryRouter`:
  - `<RequireAuth>` with no token → renders `<Navigate to="/login?next=/agents">`.
  - `<RequireAuth>` with token → renders the wrapped `<Outlet>` content.
  - `<RequireAuth>` with expired token (`expiresAt` 1 s ago) → renders `<Navigate to=
    "/login?next=...">`.
  - `<RequireRole role="ADMIN">` with `principal.role === 'STANDARD'` → renders `<Navigate
    to="/403">`.
  - `<RequireRole role="ADMIN">` with `principal.role === 'ADMIN'` → renders content.
  - `<RequireFreshPassword>` with `mustChangePassword === true` → renders `<Navigate to=
    "/change-password?reason=forced">`.
  - `<RequireFreshPassword>` with `mustChangePassword === false` → renders content.
  - `<RequireGuest>` with token → renders `<Navigate to="/agents">`.
  - `<RequireGuest>` without token → renders content.
- A **composition test** verifies guards can stack: `<RequireAuth><RequireRole role="ADMIN">
  <Page /></RequireRole></RequireAuth>` evaluates outer-first and renders the page only
  when both pass.
- The `next` query parameter is correctly escaped: a path like `/agents?cursor=abc&filter=x`
  becomes `?next=%2Fagents%3Fcursor%3Dabc%26filter%3Dx`.

### Out of scope

- `403` page implementation itself — EPIC-11 polish or a stub in this PR (`pages/ForbiddenPage.tsx`
  as a placeholder is acceptable). The guard only needs the `Navigate` target.
- Server-side redirects — none; the app is SPA-only.
- Bootstrapping skeleton — the in-memory hydration in `main.tsx` (US-02-006) makes the
  cold-start case synchronous; no skeleton needed in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table), §5.2 (guard semantics), §15 (open-redirect
  prevention — relative `next` only).

### Dependencies

- US-02-007 (`useAuth`).

---

## US-02-009 — Design-system atoms

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** the foundational design-system atoms — `Button`, `Input`, `Textarea`, `Select`,
`Checkbox`, `Badge`, `Card`, `Spinner`, `Skeleton`, `EmptyState`, plus the curated
`icons.ts` re-export from `lucide-react`
**So that** every later EPIC composes its pages from a single visual vocabulary aligned
with the "Dark Professional" identity, and so that primitives accept a `className` override
that merges cleanly via `cn` (US-02-001).

### Description

Per SW-DESIGN §11.4, primitives are **headless logic + Tailwind classnames**, NOT
wrappers over Radix or similar. The footprint is intentionally small (~16 components total
across this story and US-02-010); the visual identity is opinionated; the cost-of-Radix
mental model isn't worth it for one design system.

All primitives:

- Accept `className` and merge it via `cn(builtin, className)`.
- Forward `ref` via `React.forwardRef` where appropriate (inputs, button).
- Use the design tokens from `tokens.css` exclusively (no hex literals — enforced by code
  review; we do NOT add a lint rule for it in this story).
- Render to the spacing / radii / border conventions from SW-DESIGN §11.3.

**Primitives in this story (atoms)**:

| Component   | Notes                                                                                                  |
|-------------|--------------------------------------------------------------------------------------------------------|
| `Button`    | `variant: 'primary' | 'secondary' | 'ghost' | 'danger'`; `size: 'sm' | 'md'`; `loading` shows `Spinner`.|
| `Input`     | `<input>` with error state (`aria-invalid`, helper text below); 36 px height.                          |
| `Textarea`  | `<textarea>` with optional character counter prop `maxLength`; counter renders below right.            |
| `Select`    | Native `<select>` styled (no custom popover yet — `Dropdown` is in US-02-010).                          |
| `Checkbox`  | Custom-styled checkbox; supports `indeterminate`; keyboard `space` toggles per platform default.       |
| `Badge`     | `variant: 'neutral' | 'success' | 'info' | 'warning' | 'danger' | 'accent'`; pill radius.              |
| `Card`      | Container with surface bg + border; `padding: 'sm' | 'md' | 'lg'`; optional `accent` border variant.   |
| `Spinner`   | 16 / 20 / 24 px sizes; uses `--color-accent`; `aria-label`-able for screen readers.                    |
| `Skeleton`  | Animated pulse block; `width` / `height` props.                                                        |
| `EmptyState`| Centered icon + title + caption + optional primary action.                                              |

**`icons.ts`** re-exports a curated list from `lucide-react`. Only icons actually used by
the app are re-exported, so consumers can't grab arbitrary icons (keeps the bundle slim
and the visual vocabulary consistent). Initial set: `Plus`, `Pencil`, `Trash2`,
`MoreHorizontal`, `Search`, `X`, `Check`, `ChevronDown`, `ChevronRight`, `Eye`, `EyeOff`,
`Copy`, `Send`, `Square` (stop), `LogOut`, `User`, `Users`, `Key`, `Settings`, `MessageSquare`,
`Bot`, `Wrench`, `Server`, `AlertTriangle`, `CheckCircle2`, `InfoIcon`, `LoaderCircle`.
(Adjustable on consumption; the list is non-exhaustive — features may extend it via a
follow-up PR.)

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `lucide-react ^0.395.0` (or the latest at story-pickup time; pin minor).
- Each primitive lives in its own file under `src/shared/ui/`:
  - `Button.tsx`, `Input.tsx`, `Textarea.tsx`, `Select.tsx`, `Checkbox.tsx`, `Badge.tsx`,
    `Card.tsx`, `Spinner.tsx`, `Skeleton.tsx`, `EmptyState.tsx`, `icons.ts`.
- `Button.tsx`:
  - Forwards `ref` to the underlying `<button>`.
  - `variant: 'primary' | 'secondary' | 'ghost' | 'danger'` (default `'primary'`).
  - `size: 'sm' | 'md'` (default `'md'`).
  - `loading?: boolean` — when `true`, the button is `disabled` AND renders a `<Spinner size="sm" />` to the left of the label.
  - `leftIcon` / `rightIcon` slot accepting a React node.
  - Disabled state visually obvious (`opacity-50 cursor-not-allowed pointer-events-none`).
- `Input.tsx` / `Textarea.tsx`:
  - Forward `ref`.
  - Support `error?: string` — when set, the input gets `aria-invalid="true"` and the error text renders below in `--color-danger`.
  - `label?` and `helperText?` props that compose into a labelled fieldset (`<label> + <input> + <helper>`).
  - `Input` height is `36 px` (`h-9` in Tailwind).
- `Select.tsx` — same labelled-fieldset model as `Input`; renders a native `<select>` with
  the project's chevron icon, height `36 px`.
- `Checkbox.tsx` — labelled checkbox using a custom-styled box (16×16 px); the underlying
  control is a real `<input type="checkbox">` for accessibility.
- `Badge.tsx` — small pill (`px-2 py-0.5 text-xs`); variant maps to the semantic token
  pair (`success` → `bg-success-bg text-success`, etc.).
- `Card.tsx` — `<div>` with `bg-bg-surface border border-border-default rounded-lg` plus
  padding variant; `accent` prop sets the violet accent border (`border-border-accent`).
- `Spinner.tsx` — `<LoaderCircle>` from lucide with `animate-spin`; `size: 16 | 20 | 24`;
  `aria-label` defaulting to `'Loading'`.
- `Skeleton.tsx` — `<div>` with `animate-pulse bg-bg-elevated rounded-md`; `width` /
  `height` props passthrough to inline style.
- `EmptyState.tsx` — vertical stack: `icon` (rendered at 32 px), `title`, `description`,
  optional `action` slot for a primary `Button`.
- `icons.ts` — `export { Plus, Pencil, Trash2, ... } from 'lucide-react';` for the curated set above.
- Every primitive accepts `className` and merges it via `cn(...)` (US-02-001).
- **Unit tests** in `Button.test.tsx`, `Input.test.tsx`, `Checkbox.test.tsx`,
  `Badge.test.tsx`, `EmptyState.test.tsx`:
  - `Button`: each variant renders without runtime error; `disabled` blocks `onClick`;
    `loading` adds a `Spinner` and sets `disabled`; `aria-disabled` is correct.
  - `Input`: `error` sets `aria-invalid` and renders the error text; the input is reachable
    via its `label`.
  - `Checkbox`: keyboard `space` toggles; `indeterminate` renders the indeterminate state.
  - `Badge`: each variant gets the right class set (assert via `toHaveClass`).
  - `EmptyState`: action is rendered when provided; absent when not.
- An **axe-core smoke test** is added (one-off, applied to the `Button`, `Input`, and
  `Checkbox` rendered in isolation) asserting zero a11y violations. (Optional but
  recommended; the full a11y sweep is EPIC-11.)
- A small "kitchen sink" page at `src/pages/__ds_preview__.tsx` is added behind a
  `?ds=1` query gate (or DEV-only mount) so developers can eyeball the primitives.
  **This page is excluded from production** via a `if (import.meta.env.PROD) return null`
  early-out or by being conditionally registered in the router; either is acceptable.

### Out of scope

- `Modal`, `Dropdown`, `Tooltip`, `Tabs`, `Toast` — US-02-010.
- Theming switcher / light theme — TBD-F1.
- Storybook — not in v1 scope.
- Form-state integration (`react-hook-form`) — feature EPICs use the primitives via the
  `Controller` pattern; primitives stay uncoupled from the form library.

### Design references

- `frontend/CLAUDE.md` (visual identity, color palette, typography).
- `frontend/design/SW-DESIGN.md` §11.1 (tokens), §11.2 (typography), §11.3 (density / radii
  / borders), §11.4 (component primitives — headless), §11.5 (animation budget — 80 ms hover
  transitions), §11.6 (a11y baseline).

### Dependencies

- US-02-001 (`cn` helper).

---

## US-02-010 — Design-system overlays

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** the overlay design-system primitives — `Modal`, `Dropdown`, `Tooltip`, `Tabs`,
`Toast` — with the keyboard / ARIA semantics lifted from Radix by inspection
**So that** EPIC-03 onward can build confirm dialogs, action menus, role-gated tooltips,
sectioned forms, and a global toast queue without each EPIC re-implementing focus-trap,
escape handling, and live regions.

### Description

These five components are the trickier half of the design system because they each have
non-trivial keyboard / ARIA contracts. Per SW-DESIGN §11.4, we lift Radix's patterns by
inspection rather than depending on Radix itself; the implementations are still hand-rolled
on plain DOM primitives + Tailwind.

**Modal** — `role="dialog" aria-modal="true"` with focus-trap and Escape-to-close. Renders
into a portal under `document.body`. Stacks (multiple open modals each render their own
portal at z-index `1000 + index`). 120 ms ease-out fade on enter (per §11.5).

**Dropdown** — generic anchored popover for action menus. Trigger + content slots; keyboard
arrows navigate items; `Esc` closes; click-outside closes; first item receives focus on open.

**Tooltip** — short label on hover/focus; uses `aria-describedby` to associate trigger with
tooltip text. 200 ms open delay, 0 ms close. No keyboard requirement — keyboard-only users
get the label via `aria-describedby` on focus.

**Tabs** — `role="tablist" / "tab" / "tabpanel"`; arrow-key navigation between tabs; the
selected tab announces via `aria-selected`. Controlled (`value` + `onValueChange`) and
uncontrolled modes.

**Toast** — queue-based toast surface. Mounts globally at the app root (US-02-011 wires
the mount); EPIC-02 ships the queue + the rendered component. `aria-live="polite"`.
Default lifetime 5 s; `type: 'info' | 'success' | 'warning' | 'error'`; on
`type: 'error'` the toast stays until dismissed (sticky). Toasts of identical key are
deduplicated (last-write-wins) — see TBD-F3 in SW-DESIGN §17. **In this story, the dedup
behavior is implemented as a simple "if a toast with the same `key` is already mounted,
replace its content".**

### Acceptance criteria

- Each primitive lives in its own file under `src/shared/ui/`:
  - `Modal.tsx`, `Dropdown.tsx`, `Tooltip.tsx`, `Tabs.tsx`, `Toast.tsx`.
- `Modal.tsx`:
  - Portals to `document.body` via `createPortal`.
  - Props: `open: boolean; onOpenChange: (open: boolean) => void; title?: string; description?: string; children: React.ReactNode; size?: 'sm' | 'md' | 'lg'; primaryAction?; secondaryAction?;`.
  - `role="dialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={descriptionId}`.
  - **Focus-trap**: on open, focus moves into the modal (first focusable child or the close button); Tab/Shift+Tab cycles within the modal.
  - **Esc closes** unless the consumer passes `disableEscapeClose` (used for destructive confirms that want explicit Cancel).
  - Backdrop click closes (overrideable).
  - 120 ms ease-out fade-in; 80 ms fade-out.
- `Dropdown.tsx`:
  - Compound API: `<Dropdown><DropdownTrigger>...</DropdownTrigger><DropdownContent><DropdownItem>...</DropdownItem></DropdownContent></Dropdown>`.
  - `ArrowDown` / `ArrowUp` cycle items; `Enter` / `Space` activate; `Esc` closes.
  - Click-outside closes (via a single `mousedown` listener on `document`).
- `Tooltip.tsx`:
  - `<Tooltip content="..."><button>X</button></Tooltip>` — clones the child and adds `aria-describedby`.
  - 200 ms open delay on hover/focus; 0 ms close.
  - Positioned via a small `useFloatingPosition` hook computing top/left from the trigger's bounding rect. (No `@floating-ui` dependency; the simple positioner is sufficient for v1's flat hierarchies.)
- `Tabs.tsx`:
  - `<Tabs value={...} onValueChange={...}><TabList><TabTrigger value="x">X</TabTrigger>...</TabList><TabContent value="x">...</TabContent>...</Tabs>`.
  - `role="tablist" / "tab" / "tabpanel"` plus `aria-selected`, `aria-controls`, `id`.
  - Left/Right arrows cycle between triggers; Home/End jump to first/last.
- `Toast.tsx`:
  - `export const toast = { info, success, warning, error };` — module-level imperative API. Each call enqueues a toast on a module-scoped store.
  - `<ToastViewport />` — the mount component; consumes the store via a small subscribe pattern (`useSyncExternalStore`). The viewport is mounted once by `AppShell` / `AuthShell` (US-02-011).
  - Default lifetime: 5 s for non-error; sticky for error.
  - Dedup by `key` prop: a second call with the same key replaces the first.
  - Container is `aria-live="polite"` and renders toasts in reverse-chronological order (newest on top).
  - 120 ms ease-out fade-in/out.
- **Unit tests** for each primitive:
  - `Modal.test.tsx`: open/close via `onOpenChange`; Esc closes; focus moves to the close button on open; Tab cycles within the modal (verify by tabbing through fixtures and asserting the focused element stays within the modal subtree).
  - `Dropdown.test.tsx`: arrow keys cycle; Enter activates; Esc closes; click-outside closes.
  - `Tooltip.test.tsx`: hovering for 200 ms shows the tooltip; `aria-describedby` set; blur hides.
  - `Tabs.test.tsx`: arrow-key navigation; `aria-selected` updates; the right tabpanel is rendered.
  - `Toast.test.tsx`: `toast.info('hello')` enqueues; the viewport renders the toast; 5 s timeout removes it (use `vi.useFakeTimers()`); `toast.error('boom')` does not auto-dismiss; same-key replaces.
- An additional **integration test** (`overlays.integration.test.tsx`) renders a page that
  uses all five primitives together and verifies no z-index / focus regressions (e.g.,
  opening a modal closes an open dropdown).

### Out of scope

- A full design-system component library — only the primitives the v1 EPICs need land here.
- A11y violations not specifically called out (full axe sweep is EPIC-11).
- Light-theme variants (TBD-F1).
- Tooltip auto-flip / collision detection — the simple positioner places the tooltip below
  the trigger always; consumers can pass `placement: 'top'` for the few cases where below
  doesn't fit (e.g., in the topbar).

### Design references

- `frontend/design/SW-DESIGN.md` §11.4 (primitives, Radix patterns by inspection), §11.5
  (animation budget — 120 ms fades), §11.6 (a11y — `aria-modal`, `aria-live` on toasts).

### Dependencies

- US-02-009 (atoms — overlays compose `Button`, `Spinner`, `icons`, etc.).

---

## US-02-011 — Layout shells (`AppShell` + `Sidebar` + `Topbar` + `AuthShell`)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** the two layout shells — `AppShell` (sidebar + topbar + outlet) and `AuthShell`
(centered card) — assembled from the primitives and wired to the role-gated sidebar nav
from SW-DESIGN §6.5
**So that** EPIC-03 onward composes pages by nesting them under one of these layouts in
`pages/routes.tsx`, and so that the toast viewport, the expiry banner, and the global
error boundary mount points exist exactly once in the tree.

### Description

`AppShell` is the layout every authenticated page nests under. It renders:

- A left **`Sidebar`** with:
  - The product name at the top (the value of `env.VITE_APP_NAME`, rendered in Geist sans, accent color underline).
  - A standard-user navigation group: Agents, Chat, Tools, MCP Servers.
  - A role-gated **Admin** group (collapsible, visible only when `principal.role === 'ADMIN'`): Users, API Keys, Rate Limit.
  - Each item uses the `<NavLink>` from React Router to drive the "active" highlight (accent left border, accent text per SW-DESIGN §12.6).
  - Collapsed-state behavior: on viewports `< 768 px`, the sidebar collapses to icons-only (`64 px`); a hamburger button in the topbar toggles back.
- A top **`Topbar`** with:
  - The breadcrumb on the left (placeholder — populated by feature pages via a `useBreadcrumb` hook that this story exposes as a no-op shell; EPIC-11 polish wires up real breadcrumbs).
  - A profile dropdown on the right showing the user's initials (derived from `principal.sub` — first letter of the local part), opening a `Dropdown` with "Change password", "Sign out".
- An **`<Outlet />`** for the routed page content.
- A **`<ToastViewport />`** (from US-02-010) mounted once.
- The **expiry banner** (read from `useAuth().expiryWarning`) rendered above the `<Outlet />` when `true`, dismissable.
- A **root error boundary** placeholder — this story includes a minimal `<ErrorBoundary>` wrapping the `<Outlet />`. The full polished error screen is EPIC-11; here we just need the catch-and-render-fallback path so a render-time exception doesn't take down the whole page.

`AuthShell` is dramatically simpler: a centered card on `bg-bg-base`, the product name above it, an `<Outlet />` for the auth pages, and the same `<ToastViewport />`.

### Acceptance criteria

- `frontend/src/shared/layout/AppShell.tsx`, `Sidebar.tsx`, `Topbar.tsx`, `AuthShell.tsx`,
  `ErrorBoundary.tsx` exist.
- `AppShell` composition:
  - `<div class="grid grid-cols-[240px_1fr] grid-rows-[56px_1fr] min-h-screen">` (or
    equivalent — the layout is a 240 px sidebar + 56 px topbar + outlet area).
  - The sidebar spans both rows on the left; the topbar spans the top of the right column;
    the outlet fills the remaining cell.
  - `<ToastViewport />` mounted **once**, outside the routed `<Outlet />` (so it survives
    route transitions).
  - `<ExpiryBanner />` mounted above the `<Outlet />`, conditional on `expiryWarning`.
- `Sidebar` composition:
  - Reads `useAuth().principal?.role` to gate the Admin group.
  - Items defined in a typed const array (`{ to, label, icon }` records) so the order is
    explicit.
  - The Admin group is rendered inside a small disclosure (uses `useState` for open/closed;
    persists to `localStorage['mam.sidebar.admin.open']` so the user's preference survives
    reload).
  - Each `<NavLink>` shows the active state via the project's accent color when
    `isActive === true`.
- `Topbar` composition:
  - Renders a left-side `<nav>` for the breadcrumb (empty by default; consuming pages
    populate via the breadcrumb context).
  - Renders a right-side profile dropdown using `Dropdown` (US-02-010). The dropdown
    contains:
    - A non-interactive header with the user's email (`principal.sub`).
    - "Change password" → navigates to `/change-password`.
    - "Sign out" → triggers a context-level `signOut` (which calls the EPIC-03 `useLogout`
      hook **once it lands**; in this story, `signOut()` directly clears state per
      US-02-007).
- `AuthShell` composition:
  - `<div class="min-h-screen grid place-items-center bg-bg-base">`.
  - A product-name heading above an `<Outlet />` rendered in a `<Card padding="lg">`.
  - `<ToastViewport />` mounted once.
- `ErrorBoundary.tsx`:
  - Class component implementing `getDerivedStateFromError` + `componentDidCatch`.
  - On error: renders `<EmptyState icon={<AlertTriangle />} title="We hit an unexpected error" description="Please reload the page." action={<Button onClick={() => window.location.reload()}>Reload</Button>} />`.
  - Logs to `console.error` (no remote logging — SW-DESIGN §16.3).
- `src/pages/routes.tsx` (already exists from EPIC-01) is updated to wire the layouts:
  - `<AuthShell>` wraps the `/login` and `/change-password` routes (the latter only renders
    here as a placeholder; EPIC-03 owns the page bodies).
  - `<AppShell>` wraps every other authenticated route via a layout route in
    `createBrowserRouter`.
  - The existing `HomePlaceholder` is moved under `<AppShell>` so the layout is visible on
    `npm run dev` (and the EPIC-01 smoke test from US-01-009 keeps passing, modulo the
    additional providers).
- **Unit tests**:
  - `Sidebar.test.tsx`: with `principal.role === 'STANDARD'`, the Admin group is not in
    the DOM; with `'ADMIN'`, it is.
  - `Topbar.test.tsx`: the profile dropdown opens; "Sign out" calls `useAuth().signOut`.
  - `ErrorBoundary.test.tsx`: a child that throws on render produces the fallback; the
    "Reload" button is present.
  - `AppShell.test.tsx`: renders `<ToastViewport />` exactly once; rendering twice (e.g.,
    by mounting two `AppShell` instances) produces a single global toast region (the
    viewport uses `useSyncExternalStore` so duplicate mounts share state — verify a toast
    enqueued from one mount appears in both).
- The placeholder home page from US-01-005 now renders **inside** the `AppShell`. The
  EPIC-01 smoke test from US-01-009 is updated to wrap its render in the required
  providers (`QueryClient`, `Auth`, `Router`) — this update is part of this story since
  the layout introduction is what forced it.

### Out of scope

- The real breadcrumb behavior — EPIC-11 polish wires up `useBreadcrumb` to register a
  per-page breadcrumb.
- The "Change password" / "Sign out" menu actions actually mutating server state — EPIC-03.
- The expiry banner's visual polish (it can be a single-line `Card` with `accent` variant
  in this story; EPIC-11 may polish it).

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route map shapes), §6.5 (role-gated sidebar), §11
  (visual identity for the shell chrome), §12.6 (chat layout — same two-pane pattern but
  inside the right outlet, not in the shell).

### Dependencies

- US-02-007 (`useAuth` consumed by Sidebar / Topbar / ExpiryBanner).
- US-02-009 (atoms: `Button`, `Card`, `EmptyState`, `Badge`, `icons`).
- US-02-010 (overlays: `Dropdown`, `ToastViewport`).

---

## US-02-012 — SSE primitives (`sseFrames.ts` + `chatStream.ts`)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a typed `streamChat()` function wrapping `@microsoft/fetch-event-source` with
the contract from SW-DESIGN §8.2, plus the discriminated-union `SseFrame` type
**So that** EPIC-07 (SSE streaming chat) builds the React hook around a small, well-tested
primitive — and so that the SSE plumbing (Accept negotiation, Authorization header,
JSON-body POST, AbortSignal-driven cancellation, frame parsing, error rejection) is solved
exactly once.

### Description

Per SW-DESIGN §8.1, the native `EventSource` cannot satisfy our contract: we need POST,
an `Authorization` header, and a JSON body. `@microsoft/fetch-event-source` re-implements
the SSE parser on `fetch`, preserving framing rules while opening the three doors.

The exposed function is:

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

The implementation:

- Builds the URL `${env.VITE_API_BASE_URL}/conversations/${conversationId}/messages`.
- Sets headers: `Accept: text/event-stream`, `Content-Type: application/json`,
  `Authorization: Bearer <token from tokenStorage>`.
- Body: `JSON.stringify({ content })`.
- Per-frame: when `event:` is one of `started | delta | completed | error`,
  `JSON.parse(data)` and call `onFrame(parsed)`. Unknown frame names are **logged and
  dropped** (defensive — the spec pins four names but tolerates additions).
- On HTTP error **before the stream opens** (e.g., 406, 409, 429, 401, 502), the function
  rejects with an `ApiError` built from the response (delegate to `normalizeResponse`
  from US-02-002).
- On `error` frame **during the stream**, the function calls `onFrame` with the error
  frame and then rejects with an `ApiError` built from `frame.problem`. The contract is
  that consumers receive **both** the frame (so they can preserve a partial assistant
  bubble) AND the rejection (so they can route to a toast).
- On `AbortSignal` abort, the function rejects with a `DOMException('Aborted', 'AbortError')`
  (the standard semantics; consumers check `error.name === 'AbortError'`). The underlying
  fetch is cancelled.

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `@microsoft/fetch-event-source ^2.0.1` (latest at story-pickup time; the package is
    rarely updated, so the version is essentially fixed).
- `frontend/src/shared/sse/sseFrames.ts` exists with:
  - `export type SseFrame = ...` (the discriminated union above).
  - `export const SSE_FRAME_TYPES = ['started', 'delta', 'completed', 'error'] as const;`
  - `export type SseFrameType = (typeof SSE_FRAME_TYPES)[number];`
  - A small `parseSseFrame(eventName: string, dataJson: string): Result<SseFrame, 'unknown-type' | 'malformed-data'>` helper used by `chatStream.ts` (also imported directly by the unit tests).
- `frontend/src/shared/sse/chatStream.ts` exists with the `streamChat` function as
  specified above. Implementation uses `fetchEventSource` from
  `@microsoft/fetch-event-source` with:
  - `method: 'POST'`,
  - `headers: { ... }` populated dynamically (call `tokenStorage.get()` at send time, not at module load),
  - `body: JSON.stringify({ content })`,
  - `signal: opts.signal`,
  - `openWhenHidden: true` (so a tab background-switch doesn't kill the stream),
  - `onopen(response)`: if `!response.ok`, throws `await normalizeResponse(response)` (this rejects the outer promise).
  - `onmessage(ev)`: feeds `ev.event` + `ev.data` through `parseSseFrame`; on `Result.ok`, calls `opts.onFrame(frame)`; on `unknown-type`, logs `console.warn` and continues; on `malformed-data`, logs `console.error` and continues.
  - `onerror(err)`: rethrows so the outer promise rejects with the underlying error (the library otherwise retries by default; we **disable retry** because the SSE contract is one-shot per send).
  - `onclose()`: resolves the promise if the stream closes cleanly after a `completed` frame; if it closes without `completed`, rejects with an `ApiError(code='INTERNAL_ERROR', detail='Stream closed unexpectedly')`.
- **Unit tests** in `chatStream.test.ts` and `sseFrames.test.ts`:
  - `sseFrames.test.ts`:
    - Each of the four frame types parses correctly from a valid JSON `data` payload.
    - `parseSseFrame('UNKNOWN', '{}')` returns `err('unknown-type')`.
    - `parseSseFrame('started', 'not-json')` returns `err('malformed-data')`.
  - `chatStream.test.ts` — uses MSW v2 SSE handlers (MSW v2 supports `text/event-stream`
    response bodies via `new ReadableStream`; the handler streams hand-rolled frames):
    - **Golden path**: server emits `started → delta × 3 → completed`. The promise resolves; `opts.onFrame` is called 5 times in order; the final frame is `completed`.
    - **Error frame mid-stream**: server emits `started → delta → error`. `opts.onFrame` receives 3 frames including the `error`; the promise **rejects** with an `ApiError` whose `code` matches the `error.problem.code`.
    - **HTTP error before stream**: server returns `409` with `ProblemDetails(code='CONVERSATION_FULL')`. The promise rejects with `ApiError(status=409, code='CONVERSATION_FULL')`; `onFrame` is **not** called.
    - **HTTP error before stream — 406**: server returns `406`. Rejects with `ApiError(status=406, code='NOT_ACCEPTABLE')`.
    - **AbortSignal mid-stream**: the test calls `controller.abort()` after the first `delta`. The promise rejects with a `DOMException('Aborted', 'AbortError')`. The MSW handler verifies the request was disconnected.
    - **Unknown frame is dropped**: server emits `started → mystery → completed`. The promise resolves; `onFrame` is called for `started` and `completed` only; `console.warn` was called once.
    - **Stream closes without `completed`**: server closes the stream after `started → delta`. The promise rejects with an `ApiError(code='INTERNAL_ERROR')`.
- The `Authorization` header sent by `streamChat` is sourced from `tokenStorage.get()` at
  send time; a unit test asserts that a token rotation between two `streamChat` calls
  produces two distinct `Authorization` headers (verified via MSW request inspection).

### Out of scope

- The React-facing wrapper `useChatStream.ts` — EPIC-07 (`features/conversations`).
- Server-side: the SSE schema is fixed by the backend (`SseStartedEvent`, `SseDeltaEvent`,
  `SseCompletedEvent` in `openapi.yaml`).
- Reconnection on transient network failure — explicitly out: SSE chat is one-shot per
  send; the user retries via the UI.

### Design references

- `frontend/design/SW-DESIGN.md` §8.1 (why `@microsoft/fetch-event-source`), §8.2 (the
  `streamChat` contract — copied verbatim), §8.4 (cancellation via `AbortController`),
  §8.5 (error frames vs HTTP errors).
- `openapi.yaml` (`/conversations/{conversationId}/messages` POST; `SseStartedEvent` /
  `SseDeltaEvent` / `SseCompletedEvent` schemas).

### Dependencies

- US-02-002 (`ApiError`, `normalizeResponse`).
- US-02-006 (`tokenStorage`).

---

## Summary

| ID         | Title                                                                            | Priority | Status |
|------------|----------------------------------------------------------------------------------|----------|--------|
| US-02-001  | Shared lib utilities (`cn`, `date`, `result`) + `i18n/en.ts` copy map skeleton   | MUST     | Done   |
| US-02-002  | `ApiError` class + RFC 7807 normalizer                                           | MUST     | Done   |
| US-02-003  | `openapi-fetch` typed client + three HTTP middlewares                            | MUST     | Done   |
| US-02-004  | TanStack Query client + typed `qk` key factory                                   | MUST     | Done   |
| US-02-005  | Cursor-pagination helpers (`flattenPages` + `useCursorInfiniteQuery`)            | MUST     | Done   |
| US-02-006  | JWT token storage + Zod-validated decode                                         | MUST     | Done   |
| US-02-007  | `AuthContext` + proactive 30 s pre-`exp` banner + `auth:logout` wiring           | MUST     | Done   |
| US-02-008  | Route guards (`RequireAuth`, `RequireRole`, `RequireFreshPassword`, `RequireGuest`) | MUST  | Done   |
| US-02-009  | Design-system atoms                                                              | MUST     | Done   |
| US-02-010  | Design-system overlays                                                           | MUST     | Done   |
| US-02-011  | Layout shells (`AppShell` + `Sidebar` + `Topbar` + `AuthShell`)                  | MUST     | Done   |
| US-02-012  | SSE primitives (`sseFrames.ts` + `chatStream.ts`)                                | MUST     | Done   |

EPIC-02 is **Done** when all twelve stories above are `Done`. The next step is then
EPIC-03 (Authentication flows: login, change password, sign out, expiry) — the first
EPIC that consumes the entire shared layer end-to-end.
