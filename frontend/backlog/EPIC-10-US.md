# EPIC-10-US.md — User stories for EPIC-10 (Admin — Rate limit)

This file lists the user stories that deliver **EPIC-10 — Admin: Rate limit** of the
frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-10 closes the admin section: admins can view the live global rate-limit
configuration (per-minute and per-hour buckets) and update it. A rate-limit change is
rare and its side effects are platform-wide, so the flow is intentionally
**save-then-refetch** with a visible confirmation rather than optimistic — the admin
must see the value the server acknowledged, not a value that might roll back on error.

The section is gated by `<RequireRole role="ADMIN">` on the `/admin/rate-limit` route
(the same wrapper opened by EPIC-08 US-08-004 and re-used by EPIC-09 US-09-005). The
Admin sidebar group already has a `Rate Limit` link — no sidebar change is required.

The stories are sized so each can be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared
in its **Dependencies** section.

## Conventions

- **ID format**: `US-10-<nnn>` — `10` marks the EPIC; `<nnn>` is a sequential
  three-digit counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All three stories are `MUST` (admin
  rate-limit management is a v1 must-have per EPIC-10's priority).
- Each story contains: a narrative ("As a … I want … so that …"), a short
  description, a bullet list of testable acceptance criteria, the out-of-scope items,
  the design references, and its dependencies.

## Story list

| ID         | Title                                                                                              | Priority | Status | Depends on                       |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|----------------------------------|
| US-10-001  | `rateLimitConfigSchema` Zod schema + Admin rate-limit query/mutation hooks                         | MUST     | Done   | EPIC-02, EPIC-08 (US-08-004)     |
| US-10-002  | `RateLimitForm` — two numeric inputs + updated-at/by caption + save button + per-code error routing | MUST    | Done   | US-10-001                        |
| US-10-003  | `AdminRateLimitPage` + admin route wiring + integration tests                                       | MUST     | Done   | US-10-002                        |

---

## US-10-001 — `rateLimitConfigSchema` Zod schema + Admin rate-limit query/mutation hooks

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `rateLimitConfigSchema` Zod schema mirroring
`openapi.yaml.RateLimitConfigRequest`'s client-visible constraints AND two typed hooks
against the `/admin/rate-limit` endpoint — `useRateLimitConfig` (single-object read)
and `useUpdateRateLimitConfig` (save-then-refetch)
**So that** the `RateLimitForm` and the `AdminRateLimitPage` read their validation
rules and HTTP plumbing from one place, and the post-mutation cache refresh happens in
one location rather than being re-derived per call site.

### Description

Per SW-DESIGN §9.2, the schema mirrors the openapi-documented constraints:
`perMinute` and `perHour` are both **integers ≥ 1**. There is nothing else on
`RateLimitConfigRequest` — no per-IP overrides, no time-zone, no per-endpoint
overrides. `REQ-RL-003` explicitly excludes per-IP/per-user limits.

The response type `RateLimitConfig` extends the request with three read-only fields:
`updatedAt` (ISO 8601) and the optional `updatedBy` (UUID of the admin who last saved
it, nullable). Consumers must not send those back on update — the schema is
therefore split into a **request schema** (what the form validates) and a **response
type** (what the query returns).

The two hooks follow the standard pattern established by EPIC-08 (US-08-001) and
EPIC-09 (US-09-001):

- **`useRateLimitConfig`** — `useQuery` against `GET /admin/rate-limit`. Single object
  (no pagination). Query key `qk.admin.rateLimit()` (already present in the `qk`
  factory).
- **`useUpdateRateLimitConfig`** — `useMutation` against `PUT /admin/rate-limit`.
  **Non-optimistic**: on `onSuccess`, invalidate `qk.admin.rateLimit()` so a fresh
  server truth is displayed. Rationale: rate-limit changes are rare and cross-cutting;
  the admin should see the value the server actually accepted, not a value that a
  transient error would silently roll back.

### Acceptance criteria

- `frontend/src/features/admin-rate-limit/schema.ts` exists with the following
  exports:
  - `import { components } from '@/generated/schema';`
  - `export type RateLimitConfig = components['schemas']['RateLimitConfig'];`
  - `export type RateLimitConfigRequest = components['schemas']['RateLimitConfigRequest'];`
  - `export const rateLimitConfigSchema = z.object({
       perMinute: z.number().int().min(1),
       perHour: z.number().int().min(1),
     });`
  - `export type RateLimitConfigValues = z.infer<typeof rateLimitConfigSchema>;`
- The schema is byte-aligned with `openapi.yaml.RateLimitConfigRequest`: both fields
  are required, `int()`, `min(1)`. The schema does NOT set an upper bound (the spec
  doesn't).
- The schema uses `z.number().int()` — number inputs coerced from the HTML input
  are validated as integers. Empty strings / non-numeric inputs surface a standard
  Zod "expected number" error routed to the field by `react-hook-form`.
- `frontend/src/features/admin-rate-limit/api.ts` exists with:
  - `export function useRateLimitConfig(): UseQueryResult<RateLimitConfig, ApiError>`
  - `export function useUpdateRateLimitConfig(): UseMutationResult<RateLimitConfig, ApiError, RateLimitConfigRequest>`
- Query keys come from `qk.admin.rateLimit()` — the existing factory already exposes
  this builder (added in EPIC-02's `queryKeys.ts`). No new builder is required.
- `useRateLimitConfig`:
  - `queryKey: qk.admin.rateLimit()`.
  - `queryFn`: `unwrap(await api.GET('/admin/rate-limit'))`.
  - Standard defaults (no custom `staleTime`) — a rate-limit change surfaced via the
    admin page will benefit from the standard invalidation-on-mutation flow.
- `useUpdateRateLimitConfig`:
  - `mutationFn`: `unwrap(await api.PUT('/admin/rate-limit', { body }))`.
  - `onSuccess`: `queryClient.invalidateQueries({ queryKey: qk.admin.rateLimit() })`
    so the next paint of `AdminRateLimitPage` sees the server's acknowledged value
    (including the new `updatedAt` / `updatedBy`).
- **Unit tests** in
  `frontend/src/features/admin-rate-limit/schema.test.ts`:
  - `rateLimitConfigSchema.safeParse({ perMinute: 60, perHour: 3600 })` succeeds.
  - `rateLimitConfigSchema.safeParse({ perMinute: 1, perHour: 1 })` succeeds
    (boundary — the min is exactly `1`).
  - `rateLimitConfigSchema.safeParse({ perMinute: 0, perHour: 3600 })` fails on
    `perMinute` with the `min` error code.
  - `rateLimitConfigSchema.safeParse({ perMinute: 60, perHour: 0 })` fails on
    `perHour`.
  - `rateLimitConfigSchema.safeParse({ perMinute: 1.5, perHour: 3600 })` fails on
    `perMinute` with the `int` error code (integer constraint).
  - `rateLimitConfigSchema.safeParse({ perMinute: -1, perHour: 3600 })` fails on
    `perMinute`.
  - `rateLimitConfigSchema.safeParse({})` fails with two field errors
    (`perMinute` + `perHour` — both required).
- **Unit tests** in
  `frontend/src/features/admin-rate-limit/api.test.tsx` (MSW + `renderHook`):
  - `useRateLimitConfig` — 200 happy path: data is the full `RateLimitConfig`
    payload (with `perMinute`, `perHour`, `updatedAt`, optional `updatedBy`).
  - `useRateLimitConfig` — 500 error: `error.code` is `INTERNAL_ERROR`; consumers
    can render a retry state.
  - `useUpdateRateLimitConfig` — 200 happy path: `mutateAsync({ perMinute: 60,
    perHour: 3600 })` resolves with the updated config; the query cache is
    invalidated.
  - `useUpdateRateLimitConfig` — 400 `VALIDATION_ERROR`: `error.code` is
    `VALIDATION_ERROR`; the cache is **not** invalidated (no state change to reflect).
  - `useUpdateRateLimitConfig` — 429 `RATE_LIMITED`: `error.code` is `RATE_LIMITED`;
    surfaced to callers per the standard error-routing table.

### Out of scope

- A `useDeleteRateLimitConfig` — there is no delete endpoint. The rate-limit
  configuration is a single-row aggregate that always exists (seeded by
  `V003__seed_rate_limit_config.sql` on the backend).
- Per-endpoint or per-user rate-limit configuration — explicitly excluded by
  `REQ-RL-003`.
- An optimistic `useUpdateRateLimitConfig` pipeline — see §Description above for the
  save-then-refetch rationale.

### Design references

- `frontend/design/SW-DESIGN.md` §7.4 (`qk` factory), §7.6 (query defaults), §9.2
  (constraint mirroring), §12.10 (`AdminRateLimitPage`).
- `openapi.yaml` `GET /admin/rate-limit`, `PUT /admin/rate-limit`, `RateLimitConfig`,
  `RateLimitConfigRequest`.
- Backend requirement anchors: `REQ-RL-001..005`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `qk`, `queryClient`, `ApiError`); EPIC-08 US-08-004
  (established the `/admin/**` route + `RequireRole(ADMIN)` wrapper that US-10-003
  hooks into — the schema/hooks themselves are independent).

---

## US-10-002 — `RateLimitForm` — two numeric inputs + updated-at/by caption + save button + per-code error routing

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** a `RateLimitForm` component with two numeric inputs (per-minute and
per-hour), a "Last updated" caption (relative time + optional actor UUID), and a Save
button that submits the payload and surfaces the standard per-code errors
**So that** the `AdminRateLimitPage` composition stays thin and every rate-limit
edit flow — happy path, validation error, rate-limit-during-rate-limit-save (yes,
really), server error — behaves the same way as the rest of the admin surface.

### Description

The form is a **controlled `react-hook-form` component** seeded with the current
config's `perMinute` and `perHour` values. Because the initial values come from the
server (via `useRateLimitConfig()` in the parent page), the form receives them via
props and calls `form.reset(defaults)` in an effect whenever the defaults change
(e.g., after a successful save re-populates the cache).

Both inputs are `<Input type="number" min="1" step="1">` with the standard label +
error surface from the design system. Because the schema uses `z.number().int()`,
`react-hook-form`'s `valueAsNumber: true` registration option is required — otherwise
the HTML input returns strings and Zod rejects them with the wrong error code
(`"expected number, got string"` instead of `"integer"`).

The "Last updated" caption:

- Renders as a subtle metadata line between the form fields and the Save button.
- Format: `Last updated <relative>` (e.g., `Last updated 2 hours ago`), and — when
  `updatedBy` is non-null — appended as `by <uuid>` in a monospace span.
- When `updatedBy` is null (fresh install, or the backend didn't set it for some
  reason), the "by …" segment is omitted entirely — never rendered as "by null" or
  "by unknown".
- Uses `formatRelative()` from `@/shared/lib/date` — the same helper that powers
  the user-list "Created" column.

Save behavior:

- Disabled while either input is invalid, while the mutation is in-flight, or while
  a `RATE_LIMITED` countdown is active.
- On success: the parent page's toast (`toast.success('Rate limit updated.')`) is
  the primary confirmation surface; the form itself remains mounted and its
  `updatedAt` caption refreshes automatically once the parent re-fetches (US-10-001
  invalidates on success).
- Error routing (mirrors EPIC-09 US-09-003's `CreateApiKeyDialog`):
  - `VALIDATION_ERROR` with per-field `errors[]`: routed to the matching field via
    `form.setError(field, ...)`.
  - `VALIDATION_ERROR` without a matching field: top-of-form alert with
    `errorCopy.VALIDATION_ERROR.title`.
  - `RATE_LIMITED`: top-of-form countdown alert reading
    `Too many requests. Try again in Ns.`; Save disabled for the countdown; on
    countdown-zero, Save re-enables.
  - Any other code: top-of-form alert with
    `errorCopy[code]?.title ?? errorCopy.__unknown__.title`.

### Acceptance criteria

- `frontend/src/features/admin-rate-limit/RateLimitForm.tsx` exists with the prop
  shape:
  - `interface RateLimitFormProps { defaults: RateLimitConfig; onSaved?: (config: RateLimitConfig) => void; }`
- Uses `useForm<RateLimitConfigValues>({ resolver:
  zodResolver(rateLimitConfigSchema), defaultValues: { perMinute:
  defaults.perMinute, perHour: defaults.perHour }, mode: 'onChange' })`.
- A `useEffect` on `defaults` calls `form.reset({ perMinute, perHour })` so an
  external cache invalidation (e.g., a post-save refetch) re-syncs the inputs to
  the server truth.
- Body layout:
  - Two `Input` components stacked vertically:
    - `perMinute`: `label="Requests per minute"`, `type="number"`, `min={1}`,
      `step={1}`, registered with `valueAsNumber: true`.
    - `perHour`: `label="Requests per hour"`, same options.
  - Helper text under each: `"Global bucket — minimum 1."`.
  - Between the fields and the footer: the "Last updated" caption. Format:
    - `defaults.updatedBy != null` → `Last updated 2 hours ago by <code>{uuid}</code>`.
    - `defaults.updatedBy == null` → `Last updated 2 hours ago`.
    - The `<code>` is styled `font-mono text-xs`; the caption itself is
      `text-sm text-text-muted`.
  - Footer: a right-aligned `Save` `Button` (variant `primary`), plus an inline
    "Reset" secondary button visible only when `form.formState.isDirty` is true
    (calls `form.reset(defaults)`).
- Error surfacing:
  - Top-of-form alert (`role="alert"`) renders when a non-field, non-rate-limit
    error is active, using the `errorCopy` title for the code.
  - Top-of-form countdown alert renders when `mutation.error?.code === 'RATE_LIMITED'`
    with `Retry-After` in play. The countdown decrements once per second; when it
    reaches zero, the alert disappears and Save re-enables.
  - Per-field errors from `VALIDATION_ERROR` route via `form.setError('perMinute',
    ...)` / `form.setError('perHour', ...)`.
- Save button state:
  - `disabled={!form.formState.isValid || mutation.isPending || isRateLimited}`.
  - `loading={mutation.isPending}` so the design-system spinner appears in place of
    the label during the request.
- On mutation success:
  - `mutation.reset()` clears any lingering error state.
  - `onSaved?.(newConfig)` is invoked so the parent can fire the success toast.
  - `form.reset({ perMinute: newConfig.perMinute, perHour: newConfig.perHour })`
    resets the dirty state (the `useEffect` also handles this once the cache
    invalidation re-runs, but calling explicitly avoids a one-frame flash of dirty).
- **Component tests** in
  `frontend/src/features/admin-rate-limit/RateLimitForm.test.tsx`:
  - **Initial render**: both inputs seeded with `defaults.perMinute` /
    `defaults.perHour`; Save is disabled (form not dirty); the "Last updated"
    caption renders the relative time.
  - **Caption with updatedBy**: passing `defaults.updatedBy = 'uuid-1'` renders
    `by <code>uuid-1</code>` in monospace; the code text is present in the DOM.
  - **Caption without updatedBy**: passing `defaults.updatedBy = null` does NOT
    render the "by …" segment (assert `screen.queryByText(/by null/i)` is `null`,
    `screen.queryByText(/by unknown/i)` is `null`).
  - **Dirty → Save enables**: typing a new value into `perMinute` enables the Save
    button and reveals the Reset button.
  - **Reset**: clicking Reset restores the original `defaults` and disables Save.
  - **Zod min=1**: setting `perMinute` to `0` and submitting shows the field's
    "Number must be greater than or equal to 1" error and keeps Save disabled.
  - **Zod integer**: setting `perMinute` to `1.5` shows the "Expected integer"
    error.
  - **Save success**: MSW returns 200 with a new config (bumped `updatedAt`);
    the mutation resolves; `onSaved` is called exactly once with the new config;
    the form's dirty state clears.
  - **VALIDATION_ERROR routing**: MSW returns 400 with `errors: [{ field:
    'perMinute', message: 'server-side-only rule violated' }]`; the field shows
    the server message; the form stays mounted; Save is disabled until the value
    changes again.
  - **RATE_LIMITED countdown**: MSW returns 429 with `Retry-After: 5`; the alert
    reads `Too many requests. Try again in 5s.` and Save is disabled; after fake
    timer advance of 5 seconds, the alert disappears and Save re-enables.
  - **500 INTERNAL_ERROR**: MSW returns 500; the top-of-form alert renders with
    the `errorCopy.INTERNAL_ERROR.title`; Save re-enables.

### Out of scope

- A "history of past values" view — the backend only stores the current row
  (`updatedAt` / `updatedBy` reflect the last save, not a log).
- A "revert" button that restores a prior configuration — no history.
- A per-endpoint or per-role override UI — see `REQ-RL-003`.
- A visualization of current bucket consumption — the backend does not expose
  per-bucket metrics.

### Design references

- `frontend/design/SW-DESIGN.md` §9.2 (constraint mirroring), §10.2 (per-code error
  routing), §11.4 (design-system primitives), §12.10 (`AdminRateLimitPage`).
- `openapi.yaml` `PUT /admin/rate-limit`, `RateLimitConfigRequest`, `RateLimitConfig`.
- Backend requirement anchors: `REQ-RL-001..005`.

### Dependencies

- US-10-001 (`rateLimitConfigSchema`, `useUpdateRateLimitConfig`); EPIC-02 (`Button`,
  `Input`, `formatRelative`, `errorCopy`).

---

## US-10-003 — `AdminRateLimitPage` + admin route wiring + integration tests

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** the `/admin/rate-limit` page composing `RateLimitForm` with the
page-level header ("Rate Limit" heading + one-line caption), the loading skeleton
during the first read, an inline error state on read failure, and the single-route
wiring under the `<RequireRole role="ADMIN">` group opened in EPIC-08
**So that** an admin has one entry point into the rate-limit surface from the
sidebar (the sidebar link is already present from EPIC-02), every deep link to
`/admin/rate-limit` bounces STANDARD users to `/403`, and a save success surfaces a
clear confirmation toast.

### Description

`AdminRateLimitPage` is intentionally thin — it composes `useRateLimitConfig()`
(US-10-001) with `RateLimitForm` (US-10-002), owns the success-toast dispatch on
save, and renders the page-level header + first-read skeleton + first-read error
state.

The single route registers under the same `RequireRole(ADMIN)` group opened by
US-08-004 in `pages/routes.tsx` and extended by US-09-005:

- `/admin/rate-limit` → `AdminRateLimitPage` (`React.lazy()` per SW-DESIGN §16.1).

There is no `/admin/rate-limit/new` and no `/admin/rate-limit/:id` — the aggregate
is a single row. One route, one page.

### Acceptance criteria

- `frontend/src/pages/admin/AdminRateLimitPage.tsx` exists, exporting
  `default function AdminRateLimitPage(): JSX.Element`. Renders:
  - A page heading `Rate Limit` + a one-line caption
    `"Configure the global per-minute and per-hour request budgets."`.
  - Conditional body:
    - When `useRateLimitConfig()` is `isPending` (first paint): a `Card`-wrapped
      block of 2 `Skeleton` rows (one per input) so the layout height matches the
      form's final render.
    - When `useRateLimitConfig()` is `isError`: an inline error `Card` with
      `role="alert"`, the standard `errorCopy[code].title` / `.detail` copy, and a
      Retry `Button` calling `query.refetch()`. Mirrors the pattern used by
      `ApiKeyList` / `UserList`.
    - Otherwise: `<RateLimitForm defaults={config} onSaved={handleSaved} />`.
  - `handleSaved(newConfig)`:
    - Fires `toast.success('Rate limit updated.')` as the confirmation surface.
    - The form itself already resets its dirty state; the parent does not need to
      manually invalidate the cache because `useUpdateRateLimitConfig.onSuccess`
      does that in US-10-001.
- The route table (`frontend/src/pages/routes.tsx`) is updated: add the lazy
  import for `AdminRateLimitPage` and register `/admin/rate-limit` inside the
  existing `RequireRole(ADMIN)` nested group (next to the API-keys route from
  US-09-005).
- The sidebar Admin group already contains the `Rate Limit` link pointing to
  `/admin/rate-limit` (added in EPIC-02); no sidebar change is required. If the
  link is somehow missing, this story adds it.
- **Integration tests** in
  `frontend/src/pages/admin/AdminRateLimitPage.test.tsx` under the full provider
  stack (via `renderWithProviders`):
  - **First-paint skeleton**: MSW stubs the GET endpoint with a delayed response;
    the initial paint shows the 2-row skeleton (assert via a `data-testid`
    marker on the loading card).
  - **Populated form**: MSW returns 200 with `{ perMinute: 60, perHour: 3600,
    updatedAt: '2026-01-01T00:00:00Z', updatedBy: 'uuid-admin' }`; both inputs
    render seeded with `60` and `3600`; the "Last updated" caption is present.
  - **First-paint error + Retry**: MSW returns 500 on the first call, 200 on the
    second; the initial paint shows the error state; clicking Retry re-fetches
    and reveals the form.
  - **Save success end-to-end**: with the form populated, editing `perMinute` to
    `120`, clicking Save → MSW responds 200 with the new config → the success
    toast `Rate limit updated.` appears → the form's dirty state clears → the
    caption's `updatedAt` refreshes after the invalidation-triggered refetch
    (state-driven MSW returns the bumped `updatedAt`).
  - **429 during save**: MSW returns 429 with `Retry-After: 3`; the countdown
    alert renders inside the form; Save is disabled; no toast fires (the alert is
    the surface, per SW-DESIGN §10.2's per-code routing for
    `RATE_LIMITED` in a form).
  - **500 during save**: MSW returns 500; the top-of-form alert renders with the
    `errorCopy.INTERNAL_ERROR.title`; the form stays mounted; Save re-enables.
  - **Route guard integration** (in `routes.integration.test.tsx`, next to the
    EPIC-08 / EPIC-09 guard tests, using the same `aBundle({ role })` helper):
    - Navigating to `/admin/rate-limit` as `STANDARD` bounces to `/403`.
    - Navigating as `ADMIN` (with a MSW-stubbed GET returning a fresh config)
      lands on the `AdminRateLimitPage` (heading `Rate Limit` visible).

### Out of scope

- Deleting the rate-limit config — no backend support (see US-10-001 out-of-scope).
- Per-endpoint or per-role rate-limit UIs — explicitly excluded by `REQ-RL-003`.
- A "current bucket consumption" gauge — the backend does not expose bucket
  telemetry. When observability improves (EPIC-15 landed on the backend for
  Actuator/health), a future story may add a read-only "current usage" strip;
  that is a v2 concern.
- A confirmation dialog on Save — the value is small, the change is reversible on
  the next save. The toast is enough. If operators later request one, it will be a
  separate polish story.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table — the single
  `/admin/rate-limit` entry), §6.5 (role gating already established), §10.2
  (per-code error routing), §12.10 (`AdminRateLimitPage` shape), §16.1 (lazy
  loading).
- `openapi.yaml` `GET /admin/rate-limit`, `PUT /admin/rate-limit`.
- Backend requirement anchors: `REQ-RL-001..005`.

### Dependencies

- US-10-001 (`useRateLimitConfig` hook); US-10-002 (`RateLimitForm`); EPIC-08
  US-08-004 (the `/admin/**` route + `RequireRole(ADMIN)` group + the ForbiddenPage
  that bounced routes land on); EPIC-02 (`Button`, `Card`, `Skeleton`, `Toast` —
  the `Rate Limit` sidebar link is already there).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-10-001  | `rateLimitConfigSchema` Zod schema + Admin rate-limit query/mutation hooks                         | MUST     | Done   |
| US-10-002  | `RateLimitForm` — two numeric inputs + updated-at/by caption + save button + per-code error routing | MUST    | Done   |
| US-10-003  | `AdminRateLimitPage` + admin route wiring + integration tests                                       | MUST     | Done   |

EPIC-10 is **Done** when all three stories above are `Done`. The admin surface
then has its third and final pillar in place — rate-limit. The next step is
EPIC-11 (cross-cutting UX polish — toasts, error boundary, empty/loading states,
a11y sweep), followed by EPIC-12 (build, bundle budgets & static deployment).
