# EPIC-11-US.md — User stories for EPIC-11 (Cross-cutting UX polish)

This file lists the user stories that deliver **EPIC-11 — Cross-cutting UX polish
(toasts, error boundary, empty/loading/offline states, a11y)** of the frontend, as
defined in `frontend/backlog/EPICS.md`.

EPIC-11 is the **dedicated closing sweep** that turns the twelve feature EPICs into a
cohesive product. Most polish already landed incrementally alongside earlier EPICs
(`EmptyState`, `Skeleton`, `Toast`, `ErrorBoundary`, per-code error routing); this
EPIC ships the pieces that no single feature slice owned:

1. Mounting the existing `ErrorBoundary` at the true app root (`main.tsx`), so a
   render-time exception outside the router does not leave a blank screen.
2. Deduplicating rate-limit toast bursts (`TBD-F3`) so a client under load does not
   drown in identical `Too many requests` toasts.
3. A network-connectivity `<OfflineBanner>` — the only piece that is genuinely new
   surface (no feature EPIC owned it).
4. Standardized **in-content** state primitives (`ForbiddenState`, `NotFoundState`,
   `LoadingList`) that pages can drop inside their content column, distinct from the
   full-page `ForbiddenPage` / `NotFoundPlaceholder` that already exist as route
   fallbacks.
5. A cross-page sweep confirming that every list/detail from EPIC-04..EPIC-10 wires
   its empty/loading/error states through the standardized primitives (audit + fixes
   where gaps are found).
6. An accessibility audit backed by an automated `vitest-axe` check that flags any
   ARIA / label / contrast regression.
7. Bundle-budget instrumentation (`vite-plugin-bundle-visualizer` + `npm run
   build:analyze`) with a **soft** warning at 200 KB gzip. EPIC-12 will convert the
   soft warning into a hard CI fail.

The stories are sized so each can be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is
declared in its **Dependencies** section.

## Conventions

- **ID format**: `US-11-<nnn>` — `11` marks the EPIC; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. Six stories are `MUST` (they close v1
  UX gaps); the bundle-budget story is `SHOULD` (EPIC-12 hard-fails on the same
  budget — the visualizer here is developer-facing).
- Each story contains: a narrative ("As a … I want … so that …"), a short
  description, a bullet list of testable acceptance criteria, the out-of-scope
  items, the design references, and its dependencies.

## Story list

| ID         | Title                                                                                                | Priority | Status | Depends on              |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|-------------------------|
| US-11-001  | Mount `ErrorBoundary` at the true app root (`main.tsx`) + integration test                            | MUST     | Done   | EPIC-02 (US-02-009/011) |
| US-11-002  | Toast dedup for rate-limit bursts — last-write-wins single toast with live countdown                  | MUST     | Done   | EPIC-02 (US-02-010)     |
| US-11-003  | `<OfflineBanner>` — `navigator.onLine` listener wired into `AppShell` / `AuthShell`                   | MUST     | Done   | EPIC-02 (US-02-011)     |
| US-11-004  | In-content state primitives (`ForbiddenState`, `NotFoundState`, `LoadingList`)                        | MUST     | Done   | EPIC-02 (US-02-009)     |
| US-11-005  | Empty / loading / error sweep across every list & detail page (EPIC-04..EPIC-10)                      | MUST     | Done   | US-11-004               |
| US-11-006  | Accessibility audit + `vitest-axe` automated check on shared UI + key pages                           | MUST     | Done   | US-11-001..005          |
| US-11-007  | Bundle-budget visualization (`vite-plugin-bundle-visualizer` + `npm run build:analyze` + 200 KB warn) | SHOULD   | Done   | EPIC-01                 |

---

## US-11-001 — Mount `ErrorBoundary` at the true app root (`main.tsx`) + integration test

- **Status**: Done
- **Priority**: MUST

**As a** platform user
**I want** the `ErrorBoundary` (shipped by EPIC-02 in
`src/shared/layout/ErrorBoundary.tsx`) mounted at the **true** app root inside
`src/main.tsx` — outside `<RouterProvider>` and outside `<QueryClientProvider>` — with
an integration test that renders a throwing tree and asserts the minimalist fallback
**So that** a render-time exception in the router configuration itself, or in any
provider above the router, does not leave the user staring at a blank white screen.

### Description

Today (as of end of EPIC-10) `ErrorBoundary` exists as a class component
(`shared/layout/ErrorBoundary.tsx`) and is unit-tested in isolation, but nothing in
the production tree mounts it. `main.tsx` renders directly:

```
<StrictMode>
  <QueryClientProvider>
    <AuthProvider>
      <App /> ← RouterProvider lives here
    </AuthProvider>
  </QueryClientProvider>
</StrictMode>
```

A render-time exception in `App`, in a provider, or in a router `element` renders
nothing — the empty root element is left visible to the user, which is the failure
mode SW-DESIGN §10.3 calls out.

The remediation is mechanical: wrap `App` (or the whole subtree, including
providers, for maximum coverage) with `<ErrorBoundary>`. Wrapping **inside**
`<StrictMode>` is intentional — StrictMode double-invokes render in dev to surface
side-effect bugs; the boundary must be inside that double-invocation so dev catches
regressions.

The integration test complements the existing unit test by rendering an
intentionally-throwing component through the same provider stack as production,
proving that the boundary is actually in the render path.

### Acceptance criteria

- `src/main.tsx` is updated to wrap the app subtree in `<ErrorBoundary>`. Preferred
  layout (outermost → innermost):

  ```
  <StrictMode>
    <ErrorBoundary>
      <QueryClientProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>
  ```

  Rationale: the boundary must be OUTSIDE the providers so a provider that throws
  during initial render is caught, but INSIDE `<StrictMode>` so dev-mode double
  invocation still exercises the boundary.
- The existing per-component test `ErrorBoundary.test.tsx` is unchanged.
- A new integration test
  `src/main.integration.test.tsx` (or `src/App.errorBoundary.integration.test.tsx`
  — whichever aligns with existing file-naming) renders a throwing component
  through a wrapper equivalent to the production tree and asserts:
  - The fallback title *"We hit an unexpected error"* renders.
  - The fallback description and the "Reload" button render.
  - `console.error` was called (spied via `vi.spyOn(console, 'error')`) — the
    boundary MUST NOT swallow the error silently (SW-DESIGN §16.3).
- The "Reload" button in the fallback is a **real** browser reload:
  `window.location.reload()` — no in-app soft-recovery attempt in v1. This is the
  behavior already in the existing component; the story only verifies it via the
  integration test.
- The React 18 dev-mode error overlay is unaffected: the boundary catching a render
  error is orthogonal to Vite's dev overlay, and the test asserts the fallback
  renders regardless of `NODE_ENV`.
- No visual regression: existing snapshots (if any) do not shift; the boundary
  contributes zero DOM when its `children` render successfully.

### Out of scope

- Remote error logging (Sentry / RUM). Deferred per SW-DESIGN §16.3 — the boundary
  is documented as the single future integration point.
- Multiple, nested boundaries at page level. If a future page has an especially
  expensive subtree that we want to isolate (e.g., the message-list virtualizer),
  we can add a page-level boundary in that page's story — this EPIC-11 story
  installs only the app-root boundary.

### Design references

- `frontend/design/SW-DESIGN.md` §10.3 (global UI error boundary), §16.3
  (observability — boundary is the single future integration point).

### Dependencies

- EPIC-02 (US-02-009 for `Button` / `EmptyState`; US-02-011 for the layout shells
  the `ErrorBoundary` composes into visually). The `ErrorBoundary` class already
  lives in `src/shared/layout/ErrorBoundary.tsx`.

---

## US-11-002 — Toast dedup for rate-limit bursts — last-write-wins single toast with live countdown

- **Status**: Done
- **Priority**: MUST

**As a** platform user under a `429 RATE_LIMITED` burst
**I want** the toast surface to render a **single** rate-limit toast whose countdown
updates in place (last-write-wins on the same key), rather than a stack of
identical `Too many requests` toasts stacking up as the burst continues
**So that** the toast column stays readable and the user sees one clear signal of
"how long until the next attempt".

### Description

Per SW-DESIGN §17 `TBD-F3`, the v1 behavior for rate-limited toast bursts is
**last-write-wins on a single toast, replacing the countdown**. The Toast primitive
already implements a `key`-based dedup mechanism (`push({ key })` replaces the
existing entry with matching `key`), so the mechanism is in place — but no caller
uses it for the rate-limit code today, so a burst produces a growing stack.

The scope is small and load-bearing:

1. Introduce a shared helper `showRateLimitedToast(retryAfterSeconds)` in
   `src/shared/ui/toastPolicy.ts` (or extend `shared/ui/Toast.tsx`) that:
   - Uses a stable dedup key `rate-limit`.
   - Renders the current countdown in the message.
   - Registers a `setTimeout` that decrements the countdown and re-pushes with the
     same key (last-write-wins), until the countdown hits zero, at which point the
     toast dismisses.
2. Wire the helper into the single existing surface that today would toast a
   `RATE_LIMITED` error at the app-shell level. Per SW-DESIGN §10.2, forms handle
   their own `RATE_LIMITED` with an inline countdown alert (see `UserForm`,
   `CreateApiKeyDialog`, `RateLimitForm`) — those keep their inline behavior. The
   app-level toast route is the fallback path used when the error surfaces from
   code that does **not** own a form (e.g., an `INTERNAL_ERROR`-style upstream
   throw from `authMiddleware` on a 429 during a background query).
3. Add a unit test that pushes three consecutive `RATE_LIMITED` toasts within one
   second and asserts the DOM shows exactly one toast with the latest countdown.

### Acceptance criteria

- `src/shared/ui/toastPolicy.ts` (new) exports
  `showRateLimitedToast(retryAfterSeconds: number | undefined): void`.
  - When `retryAfterSeconds` is `undefined` or `<= 0`, falls back to a single
    stateless toast with copy `Too many requests — retry shortly.` (matching
    `errorCopy.RATE_LIMITED.title`).
  - When `retryAfterSeconds >= 1`, renders `Too many requests. Try again in Ns.`
    with a live countdown that decrements once per second.
  - Uses `toast.push({ type: 'warning', key: 'rate-limit', message, durationMs: null })`
    each tick. The `key` is the load-bearing dedup lever; without it the queue
    would stack.
  - When the countdown reaches zero: the helper dismisses the toast via
    `toast.dismiss(id)` where `id` is the id returned by the last push.
- `Toast.tsx` already supports the `key`-based dedup — no change to the primitive
  is required. If a semantic gap is found (e.g., dedup is off unless the caller
  omits `key`), the story fixes it in the primitive with a matching primitive test.
- The three existing form callers that TODAY route `RATE_LIMITED` to an **inline**
  alert (`UserForm`, `CreateApiKeyDialog`, `RateLimitForm`) are NOT changed —
  their inline countdown is the right surface per SW-DESIGN §10.2.
- Any call site that surfaces `RATE_LIMITED` via `toast.error(errorCopy.RATE_LIMITED.title)`
  today is migrated to `showRateLimitedToast(err.retryAfterSeconds)`. Search
  scope: every `.tsx` file that references `errorCopy.RATE_LIMITED` or
  `code === 'RATE_LIMITED'` outside the three form callers listed above.
- **Unit tests** in `src/shared/ui/toastPolicy.test.tsx`:
  - Three consecutive calls within one second produce **exactly one** toast in
    the DOM (assert `screen.getAllByTestId(/^toast-/)` has length 1).
  - The visible countdown is the latest value pushed (assert `getByText(/try
    again in 3s/i)` after the third push with `retryAfterSeconds=3`).
  - After enough real-time elapses for the countdown to zero, the toast is
    removed from the DOM (assert with `waitFor` + a `Retry-After: 1` seed to
    keep the test fast).
  - Passing `retryAfterSeconds = undefined` produces the fallback single-toast
    without a countdown; no timer is left running (verify no leaked timers via
    `vi.getTimerCount()` inside `vi.useFakeTimers` if the test opts in).

### Out of scope

- Toast deduplication for other error codes (e.g., `INTERNAL_ERROR`,
  `LLM_UNAVAILABLE`). Their bursts are much rarer and their content varies with
  cause; a single-shot toast per event is correct.
- Priority queues, animation staggering, or grouped notification centers. The
  Toast primitive is intentionally minimal; if we ever need "notification
  center" UX it becomes its own EPIC.
- Retry orchestration (auto-retry after the countdown expires). Not part of v1;
  the user retries manually.

### Design references

- `frontend/design/SW-DESIGN.md` §10.2 (per-code error routing table), §17
  `TBD-F3` (v1 default: last-write-wins single toast).

### Dependencies

- EPIC-02 US-02-010 (the `Toast` primitive with `key`-based dedup already exists).

---

## US-11-003 — `<OfflineBanner>` — `navigator.onLine` listener wired into `AppShell` / `AuthShell`

- **Status**: Done
- **Priority**: MUST

**As a** platform user whose browser loses network connectivity mid-session
**I want** a persistent, dismissible-through-reconnect banner at the top of the
app that reads *"You're offline — some actions will fail until connectivity is
restored."*
**So that** I understand *why* a save/send button is silently failing before I
mistakenly assume the platform is broken.

### Description

`navigator.onLine` + the `online` / `offline` window events give us a coarse but
reliable signal. The banner is a **fixed strip** rendered inside both shells
(`AppShell` and `AuthShell`) so it is visible on every route. It is intentionally
**not** dismissible manually — it disappears the moment `online` fires, so
manual dismiss would only confuse.

Non-goals: the banner is NOT a health check against the backend (we don't ping
`/actuator/health` from the frontend in v1 — SW-DESIGN §16.3). If the network is
up but the API is down, the user will still see per-request error toasts.

### Acceptance criteria

- `src/shared/ui/OfflineBanner.tsx` (new) renders `null` when `navigator.onLine`
  is `true`, and a fixed banner when it is `false`. Layout:
  - Position: `sticky top-0 z-40` (below any topbar in `AppShell`, above the
    outlet content). Height fits a single line of text.
  - Color: `bg-warning-bg` border-bottom `border-warning/30` text `text-warning`
    — mirrors the countdown-alert palette already used by `UserForm` /
    `CreateApiKeyDialog` / `RateLimitForm`.
  - Copy: `You're offline — some actions will fail until connectivity is restored.`
  - No dismiss button.
  - ARIA: `role="status"` `aria-live="polite"` so the banner is announced when
    it appears / disappears without interrupting the current focus.
- The component subscribes to `window` `online` and `offline` events on mount
  and unsubscribes on unmount. State is a single boolean `isOffline` seeded from
  `navigator.onLine`.
- SSR / non-browser guard: `typeof window === 'undefined'` short-circuits to
  `null` (defensive — the app renders in `jsdom` under tests and there is no
  server render in v1).
- `<OfflineBanner />` is mounted **once** inside `AppShell` (below `<Topbar />`,
  above the `<Outlet />` container) and once inside `AuthShell` (top of the
  centered card wrapper). No page composes it directly.
- **Unit tests** in `src/shared/ui/OfflineBanner.test.tsx`:
  - Initial render with `navigator.onLine = true` → nothing in the DOM (assert
    `container.firstChild === null`).
  - Initial render with `navigator.onLine = false` → banner is present with the
    expected copy and `role="status"`.
  - Dispatching a `window` `offline` event flips `navigator.onLine` to `false`
    and the banner appears (use `Object.defineProperty(navigator, 'onLine',
    { value: false, configurable: true })` in the test to force the value, then
    `window.dispatchEvent(new Event('offline'))`).
  - Dispatching a `window` `online` event removes the banner.
  - Unmounting removes the listeners (assert no leak — use `vi.spyOn(window,
    'addEventListener')` / `removeEventListener` counts, or the "unmount cleanup"
    check pattern already in use elsewhere).
- **Integration test**: at least one existing routes.integration test switches
  `navigator.onLine` off before rendering and asserts the banner appears on
  every already-covered route (or a dedicated smoke test that renders `AppShell`
  with an outlet and toggles `navigator.onLine`).

### Out of scope

- Backend health-check pings. Deferred per SW-DESIGN §16.3.
- Optimistic retry on reconnect. TanStack Query already retries on window focus
  by default; adding a `refetchOnReconnect: true` global default is a **later**
  polish item (not part of v1 EPIC-11).
- A dismiss button on the banner. See §Description — dismiss makes no sense for
  a signal that self-heals.

### Design references

- `frontend/design/SW-DESIGN.md` §11.6 (`aria-live` regions only where
  intended), §16.3 (no client-side health pings in v1).
- EPICs.md EPIC-11 scope: "`<OfflineBanner>` (`navigator.onLine` listener)".

### Dependencies

- EPIC-02 US-02-011 (`AppShell` / `AuthShell` layouts that mount the banner).

---

## US-11-004 — In-content state primitives (`ForbiddenState`, `NotFoundState`, `LoadingList`)

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer wiring the empty/loading/error paths on a list or
detail page
**I want** three drop-in in-content state primitives — `<ForbiddenState>`,
`<NotFoundState>`, `<LoadingList>` — that live inside a page's content column
next to `<EmptyState>` and `<Skeleton>`, distinct from the full-page
`ForbiddenPage` / `NotFoundPlaceholder` route fallbacks
**So that** every EPIC-04..EPIC-10 page can render the "resource-scoped 403 / 404 /
loading" case without each page hand-rolling the same
`Card` + `AlertTriangle` + copy from `errorCopy`.

### Description

Today the frontend has two full-page fallbacks (`pages/ForbiddenPage.tsx` and
`pages/NotFoundPlaceholder.tsx`) reached by `RequireRole` and the router's `*`
route. They are the right surface for a **route-level** 403 / 404. But when a
page loads successfully and the query it depends on returns 403 or 404 on a
specific resource (e.g., the caller lost admin role between two ticks, or the
resource was deleted by another tab), the page currently either:

- Falls back to the generic error card from that page's list (correct-shape but
  wrong tone for `FORBIDDEN` / `NOT_FOUND`), or
- Silently renders an empty list (worst case — user is confused).

The three new in-content primitives are `Card`-wrapped, keep the surrounding
page chrome (topbar, sidebar) visible, and each has a single specific job. The
`LoadingList` primitive is a convenience wrapper over `Skeleton` that renders N
row-shaped skeletons in a card — every list page already does this ad-hoc; the
primitive removes the duplication.

### Acceptance criteria

- `src/shared/ui/ForbiddenState.tsx` (new) exports
  `ForbiddenState({ title?, description?, action? })`. Renders a `<Card>` with
  `role="alert"`, an `AlertTriangle` icon, the title (defaults to
  `errorCopy.FORBIDDEN.title`), the description (defaults to
  `errorCopy.FORBIDDEN.detail`), and an optional action button. Padding + color
  palette matches the existing per-page error card pattern
  (`border-danger/40`).
- `src/shared/ui/NotFoundState.tsx` (new) — same shape, defaults to
  `errorCopy.NOT_FOUND.title` / `.detail`.
- `src/shared/ui/LoadingList.tsx` (new) exports `LoadingList({ rows?, rowHeight? })`
  with defaults `{ rows: 5, rowHeight: 24 }`. Renders `rows` `Card`-wrapped
  `<Skeleton>` blocks matching the existing `ApiKeyList` / `UserList` loading
  scaffolds.
- All three primitives:
  - Live in `src/shared/ui/` (co-located with the other atoms).
  - Accept a `className` prop merged via `cn(...)`.
  - Are documented with a JSDoc block one-liner explaining when to prefer them
    over the full-page fallbacks (rule of thumb: use the in-content primitive
    when the surrounding shell is meaningful; use the full-page fallback when
    the whole route is inaccessible).
- The primitives are NOT wired into pages by this story — that is the job of
  US-11-005 (the sweep).
- **Unit tests** in a single file
  `src/shared/ui/inContentStates.test.tsx`:
  - `ForbiddenState` renders defaults + accepts overrides + surfaces the action
    node.
  - `NotFoundState` renders defaults + accepts overrides.
  - `LoadingList` renders `rows` (default 5) `Skeleton` elements; passing
    `rows={3}` renders exactly 3.
  - `role="alert"` is present on `ForbiddenState` and `NotFoundState` (a11y
    baseline).
  - `LoadingList`'s skeletons are `aria-hidden` (Skeleton already sets this —
    the test asserts the assumption).

### Out of scope

- Deleting the existing full-page `ForbiddenPage` / `NotFoundPlaceholder`. Both
  remain — they are the route-level fallbacks and are not interchangeable with
  the in-content variants.
- A `DetailLoadingCard` primitive (page-scoped skeleton for a single-resource
  detail page). The three existing detail pages (`AdminUserDetailPage`,
  `AgentDetailPage`, `ConversationPage`) each have a specific skeleton shape —
  factoring them into one primitive would trade three tight skeletons for one
  loose one. Left as-is.

### Design references

- `frontend/design/SW-DESIGN.md` §10.2 (per-code error routing — 403 / 404
  copy), §11.4 (design-system primitives).

### Dependencies

- EPIC-02 US-02-009 (Card, Skeleton, EmptyState, icons already exist).

---

## US-11-005 — Empty / loading / error sweep across every list & detail page (EPIC-04..EPIC-10)

- **Status**: Done
- **Priority**: MUST

**As a** platform user
**I want** every list and detail page shipped by EPIC-04..EPIC-10 to render the
same **four canonical states** — populated / empty / loading / error — using the
standardized primitives, so behavior is predictable across the whole product
**So that** encountering an error on one page teaches me how error surfaces
look on every other page, and no page ever ships in a half-finished "spinner
forever on failure" state.

### Description

This is a **sweep audit** story, not a feature story. It works by inspection: a
checklist is filled per page, and any page missing a state gets a targeted fix
using the primitives from US-11-004 (`ForbiddenState`, `NotFoundState`,
`LoadingList`), plus the existing `EmptyState` and inline error cards.

Pages in scope:

- EPIC-04: `ToolsPage`, `McpServersPage`.
- EPIC-05: `AgentsPage`, `AgentCreatePage`, `AgentDetailPage`, `AgentEditPage`.
- EPIC-06: `ChatPage`, `ChatNewPage`, `ConversationPage`.
- EPIC-08: `AdminUsersPage`, `AdminUserCreatePage`, `AdminUserDetailPage`.
- EPIC-09: `AdminApiKeysPage`.
- EPIC-10: `AdminRateLimitPage`.

For each page, the sweep verifies (and fixes if missing):

1. **Populated** — the golden path is exercised by an existing integration test
   (unchanged).
2. **Empty** — the page renders a meaningful `<EmptyState>` (title, description,
   optional CTA) when the query returns zero items. If the query cannot
   naturally be empty (e.g., `AdminRateLimitPage` is a single-row aggregate),
   the sweep documents this in a code comment and no empty state is added.
3. **Loading** — the page renders `LoadingList` (list pages) or a page-specific
   skeleton (detail pages) during `isPending`. Full-page spinners are NOT
   acceptable for list pages; they hide the surrounding chrome and produce
   jarring layout shift on resolution.
4. **Error** — the page renders a `<Card role="alert">` with `errorCopy[code]`
   copy and a **Retry** button calling `query.refetch()`. For `FORBIDDEN` and
   `NOT_FOUND` errors specifically, the page renders `<ForbiddenState>` /
   `<NotFoundState>` from US-11-004 instead of the generic error card.

### Acceptance criteria

- A tracking checklist is added as a code comment (or a short
  `docs/EPIC-11-audit.md` doc) enumerating each page and its four-state coverage
  after the sweep. Pages already fully covered are marked "no change".
- Pages missing a state are patched **minimally**:
  - No new query hooks.
  - No new copy strings outside `errorCopy` (which already covers every code).
  - Only the four-state branching added.
- Every page's existing integration test suite gains **at least one new test**
  per newly-added state (e.g., if `ToolsPage` was missing the `isError` branch,
  a new MSW-500 test asserts the retry card + Retry button behavior).
- The `FORBIDDEN` and `NOT_FOUND` branches route to the new in-content
  primitives from US-11-004; the generic `Card`-based error card handles every
  other code.
- The `LoadingList` primitive from US-11-004 replaces at least the ad-hoc
  loading skeleton in `ApiKeyList.tsx` (already implemented) and `UserList.tsx`
  — the goal is to prove the primitive covers real cases and remove
  duplication.
- Every page has at least one integration test asserting the loading state
  renders (e.g., via a `delay(50)` in the MSW handler and a `getByTestId` on
  the loading marker).
- A short section is added to `frontend/CLAUDE.md` (or the frontend README)
  titled **"Four canonical page states"** documenting the pattern for new
  pages. Two paragraphs max — this is a rule of thumb, not a spec.
- The Verify pipeline (`npm run verify`) is green post-sweep — no test
  regressions.

### Out of scope

- Any behavior change on the populated state.
- Adding new API endpoints or query hooks.
- Refactoring the auth pages (`LoginPage`, `ChangePasswordPage`) — they use the
  form-scoped alert pattern from EPIC-03 which is not the "page states"
  pattern. They stay as-is.
- Refactoring `HomePlaceholder` — it is a placeholder for a future dashboard
  and does not have real data yet.

### Design references

- `frontend/design/SW-DESIGN.md` §10.2 (per-code error routing), §12
  (page-shape acceptance criteria per page).

### Dependencies

- US-11-004 (the in-content state primitives). This sweep composes them; it
  cannot begin before US-11-004 lands.

---

## US-11-006 — Accessibility audit + `vitest-axe` automated check on shared UI + key pages

- **Status**: Done
- **Priority**: MUST

**As a** platform user who navigates with a keyboard and/or a screen reader
**I want** every interactive element on the app reachable by keyboard, focus
order matching visual order, focus trapped inside modals, ARIA labels correct on
form primitives, and text/background pairs passing WCAG AA
**So that** the "Dark Professional" identity is a **usable** dark theme, not a
low-contrast one that looks sleek in mockups and fails to legally accessible on
real hardware.

### Description

The accessibility baseline is stated in SW-DESIGN §11.6. This story wires an
**automated regression check** using `vitest-axe` (or `jest-axe` under Vitest
— both work) plus a **manual audit checklist** that goes deeper than axe can
see (focus order, visible focus rings on dark backgrounds, screen-reader
announcement of dynamic content).

The load-bearing decision is: **axe checks are RUN inside existing integration
tests**, not in a separate audit suite. This is because a route mounted inside
its full provider stack + `MemoryRouter` is the only DOM shape that reflects
production; a "component-in-isolation" axe check under-tests the whole
composed page.

### Acceptance criteria

- `vitest-axe` (or `jest-axe` — pick whichever composes cleanest with Vitest 1.6)
  is added as a `devDependency`. The chosen package name is documented in
  `frontend/CLAUDE.md` alongside the other tooling picks.
- A small helper `src/test/axe.ts` exports `expectNoA11yViolations(container:
  HTMLElement)` that runs axe against `container` and asserts violations is
  empty. The helper wraps the underlying library so a future swap is a
  one-file change.
- Automated axe checks are added to:
  - Every design-system primitive test file that renders interactive DOM:
    `Button.test.tsx`, `Input.test.tsx`, `Textarea.tsx` (via a new
    `Textarea.test.tsx` if missing), `Select`, `Checkbox`, `Modal`, `Dropdown`,
    `Tooltip`, `Tabs`, `Toast`, `Badge`, `Card`, `EmptyState`, `Skeleton`,
    `Spinner`. One assertion per primitive on a **representative** DOM (a
    populated, enabled, focused variant).
  - The three most-composed pages: `LoginPage.test.tsx`,
    `ConversationPage.test.tsx` (or `ChatPage`), `AdminUsersPage.test.tsx`. One
    assertion per test file, on the populated-happy-path state.
- Every axe assertion **fails the build** on any new violation. Existing
  violations (if any) are triaged as part of this story: either fixed
  (preferred) or explicitly waivered with a `describe.skip`-annotated code
  comment linking to a follow-up ticket. Waivers require a written
  justification.
- A one-page checklist `frontend/docs/A11Y.md` (new) captures the manual audit:
  - Keyboard reachability sweep across every EPIC-04..EPIC-10 page. Result:
    pass / fail per page.
  - Focus order matches visual order on the sectioned pages (`AgentForm`,
    `UserForm`).
  - Focus trap active inside `<Modal>`, `<Dropdown>`. `Esc` closes both.
  - Focus visible on dark backgrounds — verify the `--color-border-focus`
    outline is 2 px + 2 px offset (already in tokens; the audit confirms it
    reads well on every surface).
  - `aria-live="polite"` correctly placed on: `Toast` region, chat message
    list (`role="log"`), `OfflineBanner` (US-11-003).
  - Color-contrast verification of every `text-*` on every `bg-*` pair via
    axe's contrast rules (this is covered by the automated check; the
    checklist records the manual spot-check on the three primary pages).
  - Documented WCAG AA passes: text ≥ 4.5:1, large text / UI ≥ 3:1.
- No existing test is skipped or weakened to accommodate the axe assertions.
  If a primitive fails axe on the representative variant, the primitive is
  fixed in this story — not the assertion softened.

### Out of scope

- WCAG AAA compliance. v1 targets AA per SW-DESIGN §11.6.
- Screen-reader manual testing on JAWS / NVDA / VoiceOver. Deferred to a later
  QA milestone; the `role="log"` / `role="dialog"` / `aria-live` scaffolding
  is what this story guarantees.
- Localization (`lang` on non-English content). The app is English-only in v1
  per SW-DESIGN §17 (no localization item defined; a future EPIC would own it).
- E2E accessibility runs on real browsers. Playwright's axe integration is
  deferred with the rest of the E2E layer (`TBD-F7`).

### Design references

- `frontend/design/SW-DESIGN.md` §11.6 (accessibility baseline — the exhaustive
  list), §11.4 (primitives lifted from Radix ARIA patterns), §11.5 (animation
  budget — no motion that violates `prefers-reduced-motion`).

### Dependencies

- US-11-001, US-11-002, US-11-003, US-11-004, US-11-005 — the audit is only
  meaningful once the app root is boundary-wrapped, offline banner is in
  place, in-content states are standardized, and the sweep has completed. The
  audit picks up whatever DOM shape lands after those five stories.

---

## US-11-007 — Bundle-budget visualization (`vite-plugin-bundle-visualizer` + `npm run build:analyze` + 200 KB warn)

- **Status**: Done
- **Priority**: SHOULD

**As a** frontend developer landing a feature that pulls in a large dependency
**I want** `npm run build:analyze` to open a treemap of the built bundle and to
print a **soft warning** when the initial chunk exceeds 200 KB gzip
**So that** the discovery of "this feature added 80 KB to the initial chunk"
happens **at PR review time**, not in EPIC-12 when the CI budget flips from
warn to hard-fail.

### Description

SW-DESIGN §16.1 fixes the v1 budget at **250 KB initial, 150 KB per lazy route,
both gzip**. EPIC-12 will turn that budget into a hard CI fail. This EPIC-11
story installs the **developer-facing** half: the visualizer, the analyze
script, and a soft warning at 200 KB (below the 250 KB CI threshold, so the
warning fires before CI would fail).

`vite-plugin-bundle-visualizer` (or `rollup-plugin-visualizer` — Vite exposes
Rollup plugins directly) is the standard pick. It produces a static HTML
treemap that a developer opens with the OS default browser after
`npm run build:analyze`.

### Acceptance criteria

- `rollup-plugin-visualizer` (or `vite-plugin-bundle-visualizer`) is added as a
  `devDependency`. The chosen package is documented in
  `frontend/CLAUDE.md` alongside the other tooling picks.
- `vite.config.ts` gains a conditional entry that mounts the visualizer plugin
  **only** when the env var `ANALYZE=true` is set. Rationale: production and
  CI builds should not incur the plugin's cost; `npm run build:analyze` sets
  the env var.
- `package.json` gains a `build:analyze` script:
  `"build:analyze": "cross-env ANALYZE=true vite build"` (add `cross-env` as
  a devDependency if not already present, so Windows shells work).
- The plugin config writes to `dist/stats.html` (or `dist/bundle-analysis.html`
  — pick one and document) and opens it automatically when possible; the
  script also prints the path so devs on headless environments can open it
  manually.
- The build script prints a **soft warning** when the initial chunk gzip size
  exceeds **200 KB**. Implementation: a tiny post-build script
  `scripts/check-bundle-budget.mjs` (new) reads the `stats.html` /
  metadata output from the visualizer, computes the initial-chunk gzip size,
  and prints:
  - Nothing if size ≤ 200 KB.
  - `warn: initial chunk gzip is XXX KB (soft budget 200 KB)` if size is
    between 200 and 250 KB.
  - `error: initial chunk gzip is XXX KB (hard budget 250 KB)` and exits
    with code `1` if size > 250 KB — even in EPIC-11, we do not accept
    landing a change that already busts the SW-DESIGN §16.1 budget.
- The script also prints the size of each lazy route chunk with the same
  soft (120 KB) / hard (150 KB) thresholds.
- `frontend/docs/PERFORMANCE.md` (new) — a short doc explaining:
  - How to run `npm run build:analyze`.
  - How to read the treemap.
  - Which chunks are "initial" vs "lazy" (mapping to the route-level
    `React.lazy()` splits from `routes.tsx`).
  - The v1 budgets and the escalation path (soft warn now → hard fail in
    EPIC-12).
- The Verify pipeline (`npm run verify`) is unchanged — the visualizer is a
  developer tool, not a CI gate. EPIC-12 owns the CI wiring.

### Out of scope

- Hard-failing the CI on budget breaches. That is EPIC-12 US-12-002 (or
  wherever the CI-gate story lands). This story only ships the local warning.
- Route-level code-splitting refactors. The current `React.lazy()` seams from
  EPICs 04..10 are sufficient; if the analyzer reveals a chunk-shape problem,
  the fix is a targeted follow-up, not this story's scope.
- Automatic uploads of the analysis to a shared dashboard. `dist/stats.html`
  is a local artifact.
- Sentry / RUM instrumentation. Deferred per SW-DESIGN §16.3.

### Design references

- `frontend/design/SW-DESIGN.md` §16.1 (performance budgets), §16.2 (browser
  support — the analyzer's baseline).

### Dependencies

- EPIC-01 (Vite + `package.json` + `scripts/`).

---

## Summary

| ID         | Title                                                                                                | Priority | Status |
|------------|------------------------------------------------------------------------------------------------------|----------|--------|
| US-11-001  | Mount `ErrorBoundary` at the true app root (`main.tsx`) + integration test                            | MUST     | Done   |
| US-11-002  | Toast dedup for rate-limit bursts — last-write-wins single toast with live countdown                  | MUST     | Done   |
| US-11-003  | `<OfflineBanner>` — `navigator.onLine` listener wired into `AppShell` / `AuthShell`                   | MUST     | Done   |
| US-11-004  | In-content state primitives (`ForbiddenState`, `NotFoundState`, `LoadingList`)                        | MUST     | Done   |
| US-11-005  | Empty / loading / error sweep across every list & detail page (EPIC-04..EPIC-10)                      | MUST     | Done   |
| US-11-006  | Accessibility audit + `vitest-axe` automated check on shared UI + key pages                           | MUST     | Done   |
| US-11-007  | Bundle-budget visualization (`vite-plugin-bundle-visualizer` + `npm run build:analyze` + 200 KB warn) | SHOULD   | Done   |

EPIC-11 is **Done** when all seven stories above are `Done`. At that point the
frontend has the polish surface that turns EPIC-04..EPIC-10 into a coherent
product: a true app-root boundary, deduped rate-limit toasts, an offline
banner, standardized in-content states, a full four-state sweep, an automated
a11y regression net, and developer-facing bundle-budget visibility. The next
step is **EPIC-12** (build, bundle budgets & static deployment) — which
converts the soft warning from US-11-007 into a hard CI gate and ships the
deploy configuration.
