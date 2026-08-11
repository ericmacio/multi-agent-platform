# EPIC-09-US.md — User stories for EPIC-09 (Admin — API keys)

This file lists the user stories that deliver **EPIC-09 — Admin: API keys** of the
frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-09 delivers the second admin pillar: minting machine-to-machine API keys and
managing their lifecycle. The load-bearing artifact is the **reveal-once UX**
(SW-DESIGN §5.3.5): the backend returns the cleartext `apiKey` **exactly once** on
creation and never again, so the frontend has one shot to expose it to the admin
before the platform loses the plaintext for good. Every other admin-keys operation
(list, revoke, re-enable) is metadata-only.

The frontend itself never authenticates with an API key — the M2M mode is out of scope
for the browser client (SW-DESIGN §6.1). Admins mint keys here for external systems.

The section is gated by `<RequireRole role="ADMIN">` on every `/admin/api-keys` route
(the same wrapper opened by EPIC-08 US-08-004). The Admin sidebar group already has an
`API Keys` link — no sidebar change is required.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-09-<nnn>` — `09` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All five stories are `MUST` (admin API-key
  management is a v1 must-have per EPIC-09's priority).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                    | Priority | Status | Depends on                       |
|------------|------------------------------------------------------------------------------------------|----------|--------|----------------------------------|
| US-09-001  | `createApiKeySchema` Zod schema + Admin API-key query/mutation hooks                     | MUST     | Done   | EPIC-02, EPIC-08 (US-08-004)     |
| US-09-002  | `RevealOnceBanner` — cleartext-in-mono + Copy button + copy-once-then-Done gate           | MUST     | Done   | EPIC-02                          |
| US-09-003  | `CreateApiKeyDialog` — optional-label input → mutate → pivot to `RevealOnceBanner`        | MUST     | Done   | US-09-001, US-09-002             |
| US-09-004  | `ApiKeyList` (table) + row revoke / re-enable action (optimistic)                         | MUST     | Done   | US-09-001                        |
| US-09-005  | `AdminApiKeysPage` + admin route wiring + integration tests                               | MUST     | Done   | US-09-003, US-09-004             |

---

## US-09-001 — `createApiKeySchema` Zod schema + Admin API-key query/mutation hooks

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `createApiKeySchema` Zod schema mirroring `openapi.yaml.CreateApiKeyRequest`'s
client-visible constraints AND the three typed hooks against the `/admin/api-keys` collection
— `useApiKeys` (infinite, cursor-paginated), `useCreateApiKey`, `useUpdateApiKey`
(optimistic per SW-DESIGN §7.5)
**So that** every consumer (CreateApiKeyDialog, ApiKeyList, the admin API-keys page) reads
its validation rules and its HTTP plumbing from one place, and the post-mutation cache
invalidations happen in one location rather than being re-derived per call site.

### Description

Per SW-DESIGN §9.2, the create schema mirrors the openapi-documented constraints:
`label` is optional and ≤128 chars. There is no `email`, no `password`, no `role` — API
keys carry no owner identity beyond the SYSTEM principal the backend attaches on
authentication.

The spec exposes no per-key detail endpoint (`GET /admin/api-keys/{clientId}` does not
exist) and no `DELETE`. Revocation is a soft toggle via `PATCH /admin/api-keys/{clientId}`
with `disabled: true`. There is therefore **no `useApiKey(clientId)` hook** and **no
`useDeleteApiKey`** — three hooks total.

The three hooks follow the standard pattern established by EPIC-08 (US-08-001):

- **`useApiKeys`** — `useCursorInfiniteQuery` (US-02-005) against `GET /admin/api-keys`.
  The caller flattens via `flattenPages()`. Default `pageSize` 20 per openapi.
- **`useCreateApiKey`** — `useMutation` against `POST /admin/api-keys`. On success:
  invalidates `qk.admin.apiKeys.all()` so the next list read re-fetches with the new
  entry. Returns the full `ApiKeyCreated` shape (metadata **plus** the one-shot
  cleartext `apiKey`) so `CreateApiKeyDialog` (US-09-003) can pivot to
  `RevealOnceBanner` (US-09-002) with the plaintext in hand.
- **`useUpdateApiKey`** — `useMutation` against `PATCH /admin/api-keys/{clientId}`.
  **Optimistic**: on `onMutate`, snapshots the current list caches; patches the row's
  `disabled` field in every list-cache page it appears in; on `onError`, rolls the
  snapshots back; on `onSettled`, invalidates `qk.admin.apiKeys.all()` so the server's
  truth wins on the next tick.

### Acceptance criteria

- `frontend/src/features/admin-api-keys/schema.ts` exists with the following exports:
  - `import { components } from '@/generated/schema';`
  - `export type ApiKey = components['schemas']['ApiKey'];`
  - `export type ApiKeyCreated = components['schemas']['ApiKeyCreated'];`
  - `export type CreateApiKeyRequest = components['schemas']['CreateApiKeyRequest'];`
  - `export type UpdateApiKeyRequest = components['schemas']['UpdateApiKeyRequest'];`
  - `export const createApiKeySchema = z.object({
       label: z.string().max(128).optional(),
     });`
  - `export type CreateApiKeyValues = z.infer<typeof createApiKeySchema>;`
- The schema is byte-aligned with `openapi.yaml.CreateApiKeyRequest`. Label is optional
  (the backend accepts an omitted or null label); the schema does NOT set a `min(1)`
  because the spec doesn't.
- `frontend/src/features/admin-api-keys/api.ts` exists with:
  - `export function useApiKeys(opts?: { pageSize?: number }): UseInfiniteQueryResult<ApiKeyPage, ApiError>`
  - `export function useCreateApiKey(): UseMutationResult<ApiKeyCreated, ApiError, CreateApiKeyRequest>`
  - `export function useUpdateApiKey(clientId: string): UseMutationResult<ApiKey, ApiError, UpdateApiKeyRequest>`
- Query keys come from `qk.admin.apiKeys.*`; if the existing `qk.admin.apiKeys` factory
  exposes only a single `all()` builder, this story extends it with `list()` (list-only
  invalidation target used by the mutations' optimistic pipeline).
- `useApiKeys` uses the shared `useCursorInfiniteQuery` (US-02-005); `getNextPageParam`
  reads `lastPage.nextCursor`.
- `useCreateApiKey.onSuccess`: `queryClient.invalidateQueries({ queryKey: qk.admin.apiKeys.all() })`.
- `useUpdateApiKey` optimistic pipeline (per SW-DESIGN §7.5 and mirroring EPIC-08's
  `useUpdateUser` from US-08-001):
  - `onMutate({ disabled })`:
    - `await queryClient.cancelQueries({ queryKey: qk.admin.apiKeys.all() })`.
    - Snapshot the current infinite list.
    - Walk the list pages; for the matching key (by `clientId`), set `disabled` to the
      new value.
    - Return `{ previousList }` for rollback.
  - `onError(err, vars, ctx)`: restore `previousList`.
  - `onSettled`: invalidate `qk.admin.apiKeys.all()` so the server truth wins.
- **Unit tests** in `frontend/src/features/admin-api-keys/schema.test.ts`:
  - `createApiKeySchema.safeParse({})` succeeds (label omitted).
  - `createApiKeySchema.safeParse({ label: 'CI pipeline' })` succeeds.
  - `createApiKeySchema.safeParse({ label: 'x'.repeat(129) })` fails on
    `label.max(128)`.
  - `createApiKeySchema.safeParse({ label: 'x'.repeat(128) })` succeeds.
- **Unit tests** in `frontend/src/features/admin-api-keys/api.test.tsx` (MSW +
  `renderHook`):
  - `useApiKeys` — 200 with `{ items: [key], nextCursor: 'c2' }`: data has one page;
    `fetchNextPage` triggers a request with `?cursor=c2`.
  - `useCreateApiKey` — 201 happy path: `mutateAsync` resolves with the full
    `ApiKeyCreated` (metadata + `apiKey` cleartext); the list cache is invalidated.
  - `useCreateApiKey` — 400 `VALIDATION_ERROR`: the mutation `error.code` is
    `VALIDATION_ERROR`; the cache is **not** invalidated.
  - `useUpdateApiKey('cid-1')` — 200 happy path: the list cache is patched
    optimistically **before** the response returns; `onSettled` invalidates.
  - `useUpdateApiKey('cid-1')` — 500 error path: the list cache is rolled back to the
    snapshot; a sibling `useApiKeys` observes the original `disabled` value.

### Out of scope

- A `useDeleteApiKey` — the backend does not expose a delete endpoint; revocation is
  soft (`disabled: true`).
- A `useApiKey(clientId)` detail hook — no detail endpoint exists.
- A full-text search hook — the spec does not expose a `q` query param on
  `GET /admin/api-keys`.
- Any secure-storage handling for the returned cleartext — the plaintext lives only
  in the mutation's success payload; the dialog wipes it on close (US-09-003).

### Design references

- `frontend/design/SW-DESIGN.md` §7.4 (`qk` factory), §7.5 (optimistic updates —
  `useUpdateApiKey` follows the `useUpdateUser` pattern), §7.6 (stale-time table),
  §9.2 (constraint mirroring).
- `openapi.yaml` `GET /admin/api-keys`, `POST /admin/api-keys`,
  `PATCH /admin/api-keys/{clientId}`, `CreateApiKeyRequest`, `UpdateApiKeyRequest`,
  `ApiKey`, `ApiKeyCreated`, `ApiKeyPage`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `qk`, `queryClient`, `useCursorInfiniteQuery`,
  `ApiError`); EPIC-08 US-08-004 (established the `/admin/**` route + `RequireRole`
  wrapper that US-09-005 hooks into — the schema/hooks themselves are independent).

---

## US-09-002 — `RevealOnceBanner` — cleartext-in-mono + Copy button + copy-once-then-Done gate

- **Status**: Done
- **Priority**: MUST

**As an** admin creating a new API key
**I want** a `RevealOnceBanner` component that displays the cleartext value in a
`Geist Mono` field, provides a Copy button (`navigator.clipboard.writeText`), a
persistent warning that this is my only chance to grab the key, and a Done button
that stays disabled until I have clicked Copy at least once
**So that** I cannot accidentally close the dialog before capturing the plaintext,
and once I confirm capture the platform can safely wipe it from React state.

### Description

Per SW-DESIGN §5.3.5, the reveal-once UX is a **UX guard, not a security control** —
the plaintext is still in the DOM once rendered, and any browser process (extensions,
screenshots) can already see it. The Copy-then-Done gate exists to prevent the most
common user mistake: closing the dialog before hitting Copy.

The component is designed as a **standalone reusable primitive** (not a slice of the
dialog) so it can be unit-tested in isolation and re-used by any future
credential-generation flow (e.g., if the platform later adds "personal access tokens"
under `/me`).

Props:

```ts
interface RevealOnceBannerProps {
  /** The cleartext credential to reveal. */
  value: string;
  /**
   * Fired when the user clicks Done, after the Copy button has been clicked at
   * least once. The parent typically clears the cleartext from state at this
   * moment.
   */
  onDone: () => void;
  /**
   * Optional custom warning copy. Defaults to the SW-DESIGN §5.3.5 wording.
   */
  warning?: string;
  /**
   * Optional label for the mono field. Defaults to "API key".
   */
  label?: string;
}
```

The Copy button:

- Calls `navigator.clipboard.writeText(value)`.
- On success, flips the visible affordance to "Copied" for 2 seconds, then reverts.
- On failure (e.g., clipboard permission denied): shows an inline error under the
  button (*"Copy failed — select the text and copy manually."*) but still enables the
  Done button (the user has been given the opportunity — falling back to manual
  selection is acceptable).
- The "copy at least once" gate is satisfied regardless of the copy success/failure —
  the click itself is the affirmative action.

### Acceptance criteria

- `frontend/src/features/admin-api-keys/RevealOnceBanner.tsx` exists with the prop
  shape above.
- Renders inside a `Card` (or `Modal`-friendly container):
  - A persistent warning banner (`role="alert"`) with the default copy:
    *"This is the only time this key will be shown. Copy and store it securely now."*
    or the caller's `warning` override.
  - A read-only field for `value` rendered in `font-mono` with a subtle border and a
    right-aligned Copy button. The field's text is selectable.
  - A `Copy` `Button` (variant `secondary`) with a copy icon (lucide `Copy`).
  - A `Done` `Button` (variant `primary`), initially **disabled**, that becomes
    enabled after the first Copy click.
- Behavior:
  - Clicking Copy calls `navigator.clipboard.writeText(value)`. On the promise
    resolution, the button label shows a short "Copied" state for 2 seconds (a
    `setTimeout` that clears the "copied" flag).
  - On copy failure (rejected promise): renders an inline error paragraph under the
    button with the fallback copy above; the Done button is still enabled because
    the user has demonstrated intent to capture the key.
  - Clicking Done fires `onDone()`.
- Accessibility:
  - The mono field is either an `<input readonly>` (so it's selectable via keyboard)
    or a `<div>` with `tabindex="0"` and `aria-label={label}`. Prefer the input
    variant for the free selection behavior.
  - The Copy button has both an icon and a visible "Copy" label.
  - The success state ("Copied") is announced via an off-screen `aria-live="polite"`
    element to avoid interrupting the persistent warning banner.
- **Component tests** in `frontend/src/features/admin-api-keys/RevealOnceBanner.test.tsx`:
  - **Initial state**: Done button is disabled; the warning is visible; the mono
    field shows the exact `value` prop.
  - **Copy click enables Done**: mocking `navigator.clipboard.writeText` to resolve,
    clicking Copy fires the mock with the exact `value`; the Done button becomes
    enabled; the button flips to "Copied" briefly (assert via `findByText('Copied')`).
  - **Copy failure still enables Done**: mocking `writeText` to reject, clicking Copy
    renders the fallback error message; the Done button is enabled.
  - **Done click fires onDone**: with the gate satisfied, clicking Done calls
    `onDone()` exactly once.
  - **Done not fired before Copy**: with the initial disabled state, `userEvent.click`
    on Done does not fire `onDone` (the button is disabled).
  - **Custom warning**: passing a `warning` prop overrides the default copy.
  - **Custom label**: passing a `label` prop updates the mono field's accessible
    label.

### Out of scope

- Re-generating a key from the same dialog — not in the spec (a new POST is required
  to produce a fresh key).
- Downloading the key as a text file — not in v1.
- Encrypting the plaintext in transit through the DOM — out of scope (the backend
  transmits over HTTPS; the DOM is trusted for the lifetime of the tab).
- A configurable "Copied" flash duration — 2 seconds is fine.

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.5 (reveal-once UX — the canonical acceptance
  criteria), §11.4 (design-system primitives), §11.6 (a11y — non-flooding announcements).
- `openapi.yaml` `ApiKeyCreated` (the `apiKey` field returned only at creation time).

### Dependencies

- EPIC-02 (`Button`, `Input`, `Card`, `Copy` icon).

---

## US-09-003 — `CreateApiKeyDialog` — optional-label input → mutate → pivot to `RevealOnceBanner`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** a `CreateApiKeyDialog` modal that opens with an optional label input +
Create button, calls `POST /admin/api-keys` on submit, pivots to the
`RevealOnceBanner` view with the cleartext key on success, and — once I click Done —
wipes the cleartext from React state and closes so the list re-fetches with just the
metadata
**So that** the reveal-once contract from SW-DESIGN §5.3.5 is honored end-to-end and
the AdminApiKeysPage (US-09-005) is pure composition.

### Description

The dialog is a **two-phase** modal:

- **Phase 1 — Form**: label input, Create button, Cancel button. Uses
  `react-hook-form` + `zodResolver(createApiKeySchema)`. Submit calls
  `useCreateApiKey().mutateAsync(values)`. On error, the standard per-code routing
  applies (VALIDATION_ERROR → field / top alert; RATE_LIMITED → countdown; other →
  top alert).
- **Phase 2 — Reveal**: after the mutation resolves, the dialog swaps its body for
  `<RevealOnceBanner value={created.apiKey} onDone={handleDone} />`. The header title
  changes from "Create API key" to "API key created". The Cancel button is removed
  (there's nothing to cancel — the key exists on the server).

On `handleDone`:

- The dialog's local `created` state is set back to `null` (wiping the cleartext).
- `onCreated?.(clientMetadata)` is called so the parent can update its UI (typically
  a no-op because the list already invalidates on `useCreateApiKey.onSuccess`).
- `onClose()` is called to close the modal.

Backdrop/close-button dismissal in **Phase 2**:

- After the key is minted, closing the dialog via the backdrop, Esc, or the top-right
  close button is treated as an implicit Done — with a strong caveat: the cleartext
  is still wiped, and the admin has effectively skipped the Copy gate. Rather than
  fight it, the dialog disables Esc-close and backdrop-close during Phase 2 (`Modal`
  supports both via `disableEscapeClose` and `disableBackdropClose`). The only way
  out of Phase 2 is the Done button (or the top-right X, which we hide via
  `hideCloseButton` during Phase 2). This is a **hard commitment** — the admin
  cannot lose the plaintext by fumbling the mouse.

### Acceptance criteria

- `frontend/src/features/admin-api-keys/CreateApiKeyDialog.tsx` exists with the prop
  shape:
  - `interface CreateApiKeyDialogProps { open: boolean; onClose: () => void; onCreated?: (apiKey: ApiKey) => void; }`
- Uses `useForm<CreateApiKeyValues>({ resolver: zodResolver(createApiKeySchema),
  defaultValues: { label: '' }, mode: 'onChange' })`.
- Phase 1 body:
  - `Input` for `label` with `maxLength={128}` + helper text `"Optional — how to
    recognize this key later."`.
  - Footer: `Cancel` (calls `onClose()` — disabled while `mutation.isPending`) +
    `Create` (submit, `loading={mutation.isPending}`, `disabled={mutation.isPending}`).
- On mutation success:
  - Local state `created` is set to the full `ApiKeyCreated` payload.
  - The dialog transitions to Phase 2 — the same `Modal` frame, new body.
  - The Modal is configured with `disableEscapeClose` and `disableBackdropClose` +
    `hideCloseButton` for Phase 2 so the ONLY exit is the Done button.
- On mutation error, the standard per-code routing applies:
  - `VALIDATION_ERROR` → per-field `setError` (for `label`); unmatched → top-of-form
    alert.
  - `RATE_LIMITED` → top-of-form countdown alert; Create disabled for the countdown.
  - Any other code → top-of-form alert using
    `errorCopy[code].title || errorCopy.__unknown__.title`.
- Phase 2 body:
  - Header title flips from `Create API key` to `API key created` (matching
    stylesheet — same size / weight).
  - Body: `<RevealOnceBanner value={created.apiKey} onDone={handleDone} />`.
  - Below the banner: a small caption `Client ID:` + `<code>{created.clientId}</code>`
    (mono) so the admin can reference the key later even if they close the modal
    without noting the value.
  - `label` is displayed too if present (`Label: <span>{created.label}</span>`).
- `handleDone`:
  - Clear `created` back to `null` (wipes the cleartext from React state).
  - Call `onCreated?.(cleartextStripped)` where `cleartextStripped` is
    `{ clientId, label, disabled, createdAt }` — the parent never receives the
    plaintext.
  - Call `onClose()`.
- Reset behavior: when `open` transitions from `true` to `false` externally (e.g.,
  the parent closes the dialog during Phase 1 via Cancel), `mutation.reset()` runs
  in a `useEffect` to clear any lingering error state; `form.reset()` clears the
  label input; `created` is set to `null`.
- **Component tests** in
  `frontend/src/features/admin-api-keys/CreateApiKeyDialog.test.tsx`:
  - **Phase 1 empty submit succeeds**: label is optional; clicking Create with no
    label fires `POST /admin/api-keys` with an empty body (or `{ label: '' }`,
    matching the backend's tolerance). MSW returns a 201 payload; the dialog
    pivots to Phase 2.
  - **Phase 1 with label**: typing "CI" + Create sends `{ label: 'CI' }`;
    pivots to Phase 2 rendering the cleartext.
  - **Phase 2 header + banner render**: after mutation success, the header title
    changes to `API key created`; the `RevealOnceBanner` renders the cleartext.
  - **Phase 2 Client ID caption**: the client ID is visible in mono for reference.
  - **Phase 2 Done wipes cleartext + closes**: clicking Copy → Done triggers
    `onCreated({ clientId, label, disabled, createdAt })` (no `apiKey` field);
    `onClose()` is called; after re-render, the cleartext string is no longer in
    the DOM.
  - **Phase 2 Esc-close and backdrop-close are disabled**: pressing Esc / clicking
    the backdrop does NOT close the dialog after success (the cleartext stays
    visible until Done is clicked).
  - **`400 VALIDATION_ERROR` on label**: response with `errors: [{ field: 'label',
    message: '…' }]`. The label field shows the server message; the dialog stays in
    Phase 1.
  - **`429 RATE_LIMITED` with `Retry-After: 5`**: countdown alert renders; Create
    disabled for 5 seconds.
  - **`500 INTERNAL_ERROR`**: top-of-form fallback alert renders; Create re-enables.
  - **Cancel during Phase 1**: calls `onClose()`; no network call fires (assert via
    MSW handler request count).
  - **Reset on close-then-reopen**: opening the dialog after a prior Phase 2 does
    not carry over the previous key's cleartext.

### Out of scope

- Editing an existing key's label — not supported by `UpdateApiKeyRequest`.
- Delete-key affordance — not in v1 (the spec exposes no `DELETE`; disable/re-enable
  via `PATCH` covers revocation).
- A "regenerate key" affordance — a fresh key requires a new POST + client-id, not a
  regenerate on the same clientId.

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.5 (reveal-once UX contract), §9.2 (constraint
  mirroring), §10.2 (per-code error routing), §12.10 (admin pages).
- `openapi.yaml` `POST /admin/api-keys`, `CreateApiKeyRequest`, `ApiKeyCreated`.

### Dependencies

- US-09-001 (`createApiKeySchema`, `useCreateApiKey`); US-09-002 (`RevealOnceBanner`);
  EPIC-02 (`Modal`, `Input`, `Button`, alert primitives).

---

## US-09-004 — `ApiKeyList` (table) + row revoke / re-enable action

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** an `ApiKeyList` that paginates keys into a table (client-id, label,
created-at, disabled badge) with a per-row `Revoke` / `Re-enable` action that toggles
`disabled` optimistically via `useUpdateApiKey` (US-09-001)
**So that** I can scan the platform's minted API keys at a glance and toggle
individual keys without leaving the page, and a mistoggle rolls back cleanly on a
server failure.

### Description

Per SW-DESIGN §12.10, the admin API-keys surface is a **table** (mirroring the
admin users table shape from US-08-003). The columns:

- `Client ID` — mono, prominent. The primary identifier for external systems.
- `Label` — human-readable description; renders `—` (em-dash) when null.
- `Created` — relative-time formatted (`formatRelative(createdAt)`).
- `Status` — success badge `Active` when `!disabled`, danger badge `Revoked` when
  `disabled`.
- (right-aligned) Row actions.

There is no row-click navigation — the spec exposes no per-key detail endpoint
(unlike users). Every action lives in the row's action `Dropdown`.

Row action:

- Single toggle: `Revoke` when active; `Re-enable` when disabled. On click, fires
  `useUpdateApiKey(clientId).mutateAsync({ disabled: !current })` **without** a
  confirmation dialog. Revocation is a soft toggle (reversible), so the cascade
  concerns that gate user delete (US-08-003) don't apply here. The optimistic flip
  in `useUpdateApiKey` (US-09-001) means the badge changes immediately; a server
  failure rolls the cache back and surfaces a toast.

### Acceptance criteria

- `frontend/src/features/admin-api-keys/ApiKeyList.tsx` exists with the prop shape:
  - `interface ApiKeyListProps { onToggleDisabled: (apiKey: ApiKey) => void; }`
  - (Note: unlike `UserList`, there is NO `onView` because there is no detail
    endpoint. The parent still owns the toast dispatch on
    `onToggleDisabled` success — the list simply forwards the click.)
- Consumes `useApiKeys()` + `flattenPages()`. Renders:
  - A table with the four columns above, right-aligned actions column.
  - `Label` renders `—` when the value is `null` or `undefined`.
  - `Status` renders `Active` / `Revoked` badges (per the design tokens).
  - Actions column has a `<Dropdown>` with one entry: `Revoke` (when
    `!disabled`) or `Re-enable` (when `disabled`), firing `onToggleDisabled(key)`.
  - A "Load more" `Button` when `hasNextPage`, wired to `fetchNextPage` with the
    `isFetchingNextPage` spinner.
  - First-page loading: 5 skeleton rows.
  - First-page error: inline error state with Retry (mirrors `UserList`).
  - Empty flattened items: returns `null`; the page-level empty state is owned by
    `AdminApiKeysPage` (US-09-005).
- **Component tests** in
  `frontend/src/features/admin-api-keys/ApiKeyList.test.tsx`:
  - **Table columns render**: `clientId` (mono), `label` (or `—` when null),
    `Active` / `Revoked` badges per status, relative `createdAt`.
  - **Null label renders em-dash**: an entry with `label: null` shows `—`.
  - **Action menu**: for an active key, the menu shows `Revoke`; for a disabled
    key, it shows `Re-enable`.
  - **onToggleDisabled fires with the key**: clicking Revoke fires
    `onToggleDisabled(apiKey)` with the correct row's object.
  - **Pagination**: MSW returns two pages; first paint shows page 1; clicking
    "Load more" appends page 2.
  - **Skeleton loading**: 5 skeleton rows render on first paint.
  - **Error + Retry** path works.
  - **Returns null on empty**: the flattened-empty case doesn't render a table.

### Out of scope

- Bulk revoke/re-enable — not in v1.
- Sortable / filterable table headers — the spec exposes no sort/filter params on
  `GET /admin/api-keys`.
- Row-click navigation — no detail endpoint exists.
- Copy-clientId-to-clipboard affordance — nice-to-have; deferred to a polish story
  (would be one line, but explicitly out for the sizing of this story).

### Design references

- `frontend/design/SW-DESIGN.md` §7.5 (optimistic updates — the `disabled` toggle is
  the same pattern as UserList), §11.4 (primitives), §12.10 (`AdminApiKeysPage`
  table shape).
- `openapi.yaml` `ApiKey`, `UpdateApiKeyRequest`.

### Dependencies

- US-09-001 (`useApiKeys`, `useUpdateApiKey`, `ApiKey` type); EPIC-02 (`Card`,
  `Badge`, `Button`, `Dropdown`, `Skeleton`, `formatRelative`).

---

## US-09-005 — `AdminApiKeysPage` + admin route wiring + integration tests

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** the `/admin/api-keys` page composing `ApiKeyList` with the page-level
header ("API Keys" heading + "Create API key" CTA that opens
`CreateApiKeyDialog`), the empty-state branch when there are no keys yet, and the
single-route wiring under the `<RequireRole role="ADMIN">` group opened in EPIC-08
**So that** an admin has one entry point into the API-keys surface from the sidebar
(the sidebar link is already present from EPIC-02), and every deep link to
`/admin/api-keys` bounces STANDARD users to `/403`.

### Description

`AdminApiKeysPage` is intentionally thin — it composes `ApiKeyList` (US-09-004),
owns the `CreateApiKeyDialog`'s open state (US-09-003), owns the toast dispatch on
successful create and successful revoke/re-enable, and renders the page-level
header + empty-state branch.

The single route registers under the same `RequireRole(ADMIN)` group opened by
US-08-004 in `pages/routes.tsx`:

- `/admin/api-keys` → `AdminApiKeysPage` (`React.lazy()` per SW-DESIGN §16.1).

There is no `/admin/api-keys/new` (creation is a modal on the list page) and no
`/admin/api-keys/:clientId` (no detail endpoint). One route, one page.

### Acceptance criteria

- `frontend/src/pages/admin/AdminApiKeysPage.tsx` exists, exporting
  `default function AdminApiKeysPage(): JSX.Element`. Renders:
  - A page heading `API Keys` + a one-line caption
    `"Mint machine-to-machine API keys for external systems."`.
  - A primary `Button` `Create API key` (with a `Plus` icon) opening the
    `CreateApiKeyDialog`.
  - Conditional body:
    - When `useApiKeys()` is `isPending` (first paint): 5 skeleton rows (delegated to
      `ApiKeyList`).
    - When `useApiKeys()` is `isError`: inline error state with Retry (delegated).
    - When the flattened list is empty: page-level `EmptyState` with title
      `No API keys yet`, caption
      `"Create an API key to give an external system machine-to-machine access."`,
      and a single primary CTA `Create API key` opening the same dialog.
    - Otherwise: `<ApiKeyList onToggleDisabled={handleToggle} />`.
  - Owns the `CreateApiKeyDialog`'s `open` state. On the dialog's `onCreated`,
    fires a success toast `API key created.` (the reveal-once banner is the primary
    surface — the toast is a confirmation the mutation landed and the list is
    refetching).
  - `handleToggle`: when the row action fires `onToggleDisabled(apiKey)`, calls
    `useUpdateApiKey(apiKey.clientId).mutate({ disabled: !apiKey.disabled }, {
    onSuccess: () => toast.success(apiKey.disabled ? 'API key re-enabled.' :
    'API key revoked.'), onError: (err) => toast.error(errorCopy[err.code].title) })`.
    Alternatively, the toast dispatch is moved onto a shared `onSuccess` callback in
    the mutation registered via `useUpdateApiKey` inline in the page — either
    factoring is acceptable as long as the toast surfaces once per settled
    mutation and does not double-fire.
- The route table (`frontend/src/pages/routes.tsx`) is updated: add the lazy
  import for `AdminApiKeysPage` and register `/admin/api-keys` inside the existing
  `RequireRole(ADMIN)` nested group.
- The sidebar Admin group already contains the `API Keys` link pointing to
  `/admin/api-keys` (added in EPIC-02); no sidebar change is required. If the link
  is somehow missing, this story adds it.
- **Integration tests** in `frontend/src/pages/admin/AdminApiKeysPage.test.tsx`
  under the full provider stack (via `renderWithProviders` + a memory router with
  the AdminApiKeysPage under `/admin/api-keys`):
  - **Populated list**: MSW returns 3 keys; all 3 rows render (mono `clientId`,
    label, status badge); the header `Create API key` CTA opens the dialog.
  - **Empty state**: MSW returns `{ items: [], nextCursor: null }`; the empty state
    renders with the correct copy + CTA; clicking the CTA opens the dialog.
  - **Create flow**: opening the dialog, typing "CI", clicking Create → MSW
    responds 201 with a cleartext key → dialog pivots to Phase 2 → Copy → Done →
    dialog closes → a success toast `API key created.` appears → the list
    refetches (state-driven MSW to return the new entry) → the new row appears in
    the table.
  - **Reveal-once integrity**: after the dialog closes on Done, the cleartext is
    no longer anywhere in the DOM (assert `screen.queryByText(cleartext)` is
    `null`).
  - **Optimistic revoke flow**: with a stateful MSW mock, clicking `Revoke` flips
    the row's badge to `Revoked` immediately; the success toast `API key revoked.`
    appears after settle.
  - **Revoke rollback on 500**: MSW returns 500 on the PATCH; the badge reverts to
    `Active`; an error toast surfaces (the standard `errorCopy` per-code copy).
  - **Route guard integration** (in `routes.integration.test.tsx`): navigating to
    `/admin/api-keys` as `STANDARD` bounces to `/403`; as `ADMIN`, lands on the
    `AdminApiKeysPage` (heading `API Keys` visible). This test is added in the
    same file as the EPIC-08 admin-users route-guard tests, using the same
    `aBundle({ role })` helper.

### Out of scope

- Deleting a key (no backend support).
- Rotating a key (would require a new POST + acknowledgement of the old key —
  outside the v1 flow).
- Copy-clientId-to-clipboard from the table row — see US-09-004 out-of-scope note.
- Sharing minted keys via email / Slack — not in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table — the single `/admin/api-keys`
  entry), §6.5 (role gating already established), §12.10 (`AdminApiKeysPage`
  shape), §16.1 (lazy loading).
- `openapi.yaml` `GET /admin/api-keys`, `POST /admin/api-keys`,
  `PATCH /admin/api-keys/{clientId}`.

### Dependencies

- US-09-003 (`CreateApiKeyDialog`); US-09-004 (`ApiKeyList`); EPIC-08 US-08-004
  (the `/admin/**` route + `RequireRole(ADMIN)` group + the ForbiddenPage that
  bounced routes land on); EPIC-02 (`Button`, `EmptyState`, `Toast`, `Sidebar` —
  the `API Keys` link is already there).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-09-001  | `createApiKeySchema` Zod schema + Admin API-key query/mutation hooks                               | MUST     | Done   |
| US-09-002  | `RevealOnceBanner` — cleartext-in-mono + Copy button + copy-once-then-Done gate                     | MUST     | Done   |
| US-09-003  | `CreateApiKeyDialog` — optional-label input → mutate → pivot to `RevealOnceBanner`                  | MUST     | Done   |
| US-09-004  | `ApiKeyList` (table) + row revoke / re-enable action (optimistic)                                   | MUST     | Done   |
| US-09-005  | `AdminApiKeysPage` + admin route wiring + integration tests                                         | MUST     | Done   |

EPIC-09 is **Done** when all five stories above are `Done`. The admin surface then has
its second pillar in place — API keys. The next step is EPIC-10 (Rate limit — the
last admin pillar), followed by EPIC-11 (cross-cutting polish) and EPIC-12 (build
and deployment).
