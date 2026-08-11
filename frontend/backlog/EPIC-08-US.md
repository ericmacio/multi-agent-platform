# EPIC-08-US.md — User stories for EPIC-08 (Admin — Users)

This file lists the user stories that deliver **EPIC-08 — Admin: Users** of the
frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-08 opens the **admin surface** of the platform. It lets admin principals list,
create, view, enable/disable, and delete platform users. The section is gated by
`<RequireRole role="ADMIN">` at the route level (per SW-DESIGN §5.1 / §6.5) and by an
Admin sidebar group that is only rendered when `principal.role === 'ADMIN'`. Standard
users never see any admin entry point.

The `UserForm` (create-only) mirrors `CreateUserRequest` and reuses the
`passwordPolicy` checklist from EPIC-03 (US-03-001) so the admin sees the same live
validation the user later sees on `PUT /auth/password`. The Delete dialog quotes the
cascade warning required by `REQ-USR-006` — deleting a user hard-deletes all owned
agents and conversations — so the admin cannot accidentally wipe someone's work.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-08-<nnn>` — `08` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All five stories are `MUST` (admin user
  management is a v1 must-have per EPIC-08's priority).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                    | Priority | Status | Depends on                       |
|------------|------------------------------------------------------------------------------------------|----------|--------|----------------------------------|
| US-08-001  | `createUserSchema` Zod schema + Admin-user query/mutation hooks                           | MUST     | Done   | EPIC-02, EPIC-03 (US-03-001)     |
| US-08-002  | `UserForm` — email + password (with policy checklist) + role + error routing              | MUST     | Done   | US-08-001, EPIC-03 (US-03-001)   |
| US-08-003  | `UserList` (table) + `DisableUserDialog` + `DeleteUserDialog` (optimistic + cascade warn) | MUST     | Done   | US-08-001                        |
| US-08-004  | `AdminUsersPage` + admin routes wiring + role-gated sidebar group                         | MUST     | Done   | US-08-003                        |
| US-08-005  | `AdminUserCreatePage` + `AdminUserDetailPage` (page composition + integration tests)      | MUST     | Done   | US-08-002, US-08-004             |

---

## US-08-001 — `createUserSchema` Zod schema + Admin-user query/mutation hooks

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `createUserSchema` Zod schema mirroring `openapi.yaml.CreateUserRequest`'s
client-visible constraints AND the five typed hooks against the `/admin/users` collection —
`useUsers` (infinite, cursor-paginated), `useUser`, `useCreateUser`, `useUpdateUser`
(optimistic per SW-DESIGN §7.5), `useDeleteUser`
**So that** every consumer (UserForm, UserList, the admin pages) reads its validation rules
and its HTTP plumbing from one place, and the post-mutation cache invalidations happen in
one location rather than being re-derived per call site.

### Description

Per SW-DESIGN §9.2 / §9.3, the create schema mirrors the openapi-documented constraints:
`email` (RFC 5322 format, ≤254), `password` (≥10, ≤256, with the shared `passwordPolicy`
regex — reused from `features/auth/password.ts` shipped in US-03-001), and `role` (the
enum `ADMIN | STANDARD`).

There is intentionally **no** `updateUserSchema`: `UpdateUserRequest` exposes exactly one
boolean field (`disabled`), which the UI toggles directly via `useUpdateUser`. The
disable/enable dialog (US-08-003) does not need a form.

The five hooks follow the standard pattern established by EPIC-05 (US-05-001) and
EPIC-06 (US-06-001):

- **`useUsers`** — `useCursorInfiniteQuery` (US-02-005) against `GET /admin/users`. The
  caller flattens via `flattenPages()`. Default `pageSize` 20 per openapi.
- **`useUser(userId)`** — `useQuery` against `GET /admin/users/{userId}`. Disabled when
  `userId` is empty. `enabled: Boolean(userId)`.
- **`useCreateUser`** — `useMutation` against `POST /admin/users`. On success: invalidates
  `qk.adminUsers.all()` so the list refetches. Does **not** push the new user into the
  cache manually; a clean refetch keeps the cache honest (the list is small and not hot).
- **`useUpdateUser`** — `useMutation` against `PATCH /admin/users/{userId}`.
  **Optimistic**: on `onMutate`, snapshots the current caches for the list and the
  detail, then patches the user's `disabled` field in every cache entry it appears in
  (list pages + detail); on `onError`, rolls the snapshots back; on `onSettled`,
  invalidates `qk.adminUsers.all()` + `qk.adminUsers.byId(userId)` so the server truth
  wins on the next tick.
- **`useDeleteUser`** — `useMutation` against `DELETE /admin/users/{userId}`. On success:
  invalidates `qk.adminUsers.all()`. Does **not** optimistically remove — the cascade
  warning in the dialog (US-08-003) already pauses the admin, and a server round-trip
  is cheap.

### Acceptance criteria

- `frontend/src/features/admin-users/schema.ts` exists with the following exports:
  - `import { components } from '@/generated/schema';`
  - `export type User = components['schemas']['User'];`
  - `export type CreateUserRequest = components['schemas']['CreateUserRequest'];`
  - `export type UpdateUserRequest = components['schemas']['UpdateUserRequest'];`
  - `export type Role = components['schemas']['Role'];`
  - `import { passwordPolicy } from '@/features/auth/password';`
  - `export const createUserSchema = z.object({
       email: z.string().email().max(254),
       password: passwordPolicy,
       role: z.enum(['ADMIN', 'STANDARD']),
     });`
  - `export type CreateUserValues = z.infer<typeof createUserSchema>;`
- The schema is byte-aligned with `openapi.yaml.CreateUserRequest`. The `password` field
  reuses the exact `passwordPolicy` from US-03-001 so the checklist copy is identical
  to `PUT /auth/password`.
- `frontend/src/features/admin-users/api.ts` exists with:
  - `export function useUsers(opts?: { pageSize?: number }): UseInfiniteQueryResult<UserPage, ApiError>`
  - `export function useUser(userId: string | undefined): UseQueryResult<User, ApiError>`
  - `export function useCreateUser(): UseMutationResult<User, ApiError, CreateUserRequest>`
  - `export function useUpdateUser(userId: string): UseMutationResult<User, ApiError, UpdateUserRequest>`
  - `export function useDeleteUser(): UseMutationResult<void, ApiError, { userId: string }>`
- Query keys come from `qk.adminUsers.*` (US-02-004); if `qk.adminUsers.all/list/byId`
  are not yet defined, this story adds them — one-line additions per SW-DESIGN §7.4.
- `useUsers` uses the shared `useCursorInfiniteQuery` (US-02-005); `getNextPageParam`
  reads `lastPage.nextCursor`.
- `useCreateUser.onSuccess`: `queryClient.invalidateQueries({ queryKey: qk.adminUsers.all() })`.
- `useUpdateUser` optimistic pipeline (per SW-DESIGN §7.5):
  - `onMutate({ disabled })`:
    - `await queryClient.cancelQueries({ queryKey: qk.adminUsers.all() })`.
    - Snapshot the current infinite list (`qk.adminUsers.list(null)`) AND the detail
      (`qk.adminUsers.byId(userId)`).
    - Walk the list pages; for the matching user, set `disabled` to the new value +
      bump `updatedAt` to `new Date().toISOString()`.
    - Patch the detail cache the same way.
    - Return `{ previousList, previousDetail }` for rollback.
  - `onError(err, vars, ctx)`: restore `previousList` and `previousDetail`.
  - `onSettled`: invalidate `qk.adminUsers.all()` + `qk.adminUsers.byId(userId)`.
- `useDeleteUser.onSuccess`: invalidate `qk.adminUsers.all()`.
- **Unit tests** in `frontend/src/features/admin-users/schema.test.ts`:
  - `createUserSchema.safeParse({ email: 'not-an-email', password: 'Aa!aaaaaaa', role: 'ADMIN' })`
    fails on `email.format`.
  - `createUserSchema.safeParse({ email: 'a@b.co', password: 'short1!A', role: 'ADMIN' })`
    fails on `password.min(10)`.
  - `createUserSchema.safeParse({ email: 'a@b.co', password: 'alllowercase!', role: 'ADMIN' })`
    fails on the uppercase regex.
  - `createUserSchema.safeParse({ email: 'a@b.co', password: 'NoSpecials1', role: 'ADMIN' })`
    fails on the special-char regex.
  - `createUserSchema.safeParse({ email: 'a@b.co', password: 'AValid!Pw1', role: 'HACKER' })`
    fails on the role enum.
  - `createUserSchema.safeParse({ email: 'a@b.co', password: 'AValid!Pw1', role: 'STANDARD' })`
    succeeds.
- **Unit tests** in `frontend/src/features/admin-users/api.test.tsx` (MSW + `renderHook`):
  - `useUsers` — 200 with `{ items: [user], nextCursor: 'c2' }`: data has one page;
    `fetchNextPage` triggers a request with `?cursor=c2`.
  - `useUser('id')` — 200 happy path returns the spec `User` shape.
  - `useUser(undefined)` — query is `disabled`; no network request fires.
  - `useCreateUser` — 201 happy path: `mutateAsync` resolves with the spec `User`; the
    admin-users list cache is invalidated (assert via a sibling `useUsers` that re-fetches
    after the mutation settles).
  - `useCreateUser` — 409 `CONFLICT` (duplicate email): the mutation `error.code` is
    `CONFLICT`; the cache is **not** invalidated.
  - `useUpdateUser('id')` — 200 happy path: detail cache is patched optimistically
    **before** the response returns (assert via reading the cache mid-flight);
    invalidation runs on `onSettled`.
  - `useUpdateUser('id')` — 500 error path: the list + detail caches are rolled back to
    the snapshot; a sibling `useUsers` observes the original `disabled` value.
  - `useDeleteUser` — 204 happy path: the users list is invalidated.

### Out of scope

- Full-text search on the users list — the spec does not expose a `q` query param.
- Bulk create / bulk disable — no backend support in v1.
- Optimistic delete — the cascade-warning dialog (US-08-003) is the explicit human pause;
  optimistic removal would ghost a user that failed to delete.
- Self password reset by email — not a backend capability (`REQ-USR-004` requires an
  admin action).

### Design references

- `frontend/design/SW-DESIGN.md` §7.4 (`qk` factory), §7.5 (optimistic updates —
  `useUpdateUser`), §7.6 (stale-time table), §9.2 (constraint mirroring), §9.3 (password
  policy — reused).
- `openapi.yaml` `GET /admin/users`, `POST /admin/users`, `GET/PATCH/DELETE /admin/users/{userId}`,
  `CreateUserRequest`, `UpdateUserRequest`, `User`, `UserPage`, `Role`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `qk`, `queryClient`, `useCursorInfiniteQuery`,
  `ApiError`); EPIC-03 US-03-001 (`passwordPolicy` — reused verbatim).

---

## US-08-002 — `UserForm` — email + password (with policy checklist) + role + error routing

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** a single `UserForm` component with an email field, a password field carrying
the same live policy checklist as `ChangePasswordForm` (US-03-004), and a role dropdown
(`ADMIN` / `STANDARD`), using `react-hook-form` + `zod` on the `createUserSchema`, with a
sticky action bar and explicit routing for every documented server error code
**So that** `AdminUserCreatePage` (US-08-005) is pure composition (`<UserForm
onSuccess={...} onCancel={...} />`) and the form's error-handling contract is verified
once at the form level.

### Description

Per SW-DESIGN §9.1 / §9.3 / §12.10, the form is `react-hook-form` +
`@hookform/resolvers/zod` against the `createUserSchema` from US-08-001. It is
**create-only** — there is no edit mode because the only user field the API mutates is
`disabled`, which the detail page handles via a confirm dialog (US-08-003).

On submit:

- Calls `useCreateUser().mutateAsync(values)`.
- On success: an `onSuccess(user: User)` prop is called — the page decides where to
  navigate (typically to `AdminUserDetailPage`).

On error, the form maps each documented code to the user-facing UX:

| `ApiError.code`     | Routing                                                                                                                                                                                        |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `VALIDATION_ERROR`  | `setError(field, { message })` for each entry in `error.fieldErrors`; unmatched fields spill into top-of-form alert.                                                                           |
| `CONFLICT`          | `setError('email', { message: errorCopy.CONFLICT.detail || 'A user with this email already exists.' })`. Scroll to the `email` field. (The server returns 409 with `code: CONFLICT` per the openapi `Conflict` response for duplicate email — REQ-USR-002.) |
| `RATE_LIMITED`      | Top-of-form alert with countdown (same UX as `LoginForm`).                                                                                                                                     |
| Any other code      | Top-of-form alert using `errorCopy[code].title || errorCopy.fallback.title`.                                                                                                                   |

The password field re-uses the **live policy checklist** primitive from
`ChangePasswordForm` (US-03-004). If that primitive is not yet exported for reuse, this
story extracts it into `features/auth/PasswordPolicyChecklist.tsx` (or wherever US-03-004
placed it) with no behavior change. The password is a **new** account credential — the
admin sees the checklist because they are the one authoring it.

### Acceptance criteria

- `frontend/src/features/admin-users/UserForm.tsx` exists with the prop shape:
  - `interface UserFormProps { onSuccess: (user: User) => void; onCancel: () => void; }`
- Uses `useForm<CreateUserValues>({ resolver: zodResolver(createUserSchema),
  defaultValues: { email: '', password: '', role: 'STANDARD' } })`.
- Renders three field groups, each wrapped in a `<section data-rhf-section="<name>">`:
  - **Identity**: `Input type="email"` for `email`, autocomplete `off` (never `email`,
    to avoid leaking the admin's own address into the field), `aria-invalid` when the
    field has an error.
  - **Credential**: `Input type="password"` for `password`, with the reusable
    `PasswordPolicyChecklist` beneath (three rules: ≥10 chars, ≥1 uppercase, ≥1 special)
    that updates on every keystroke. The checklist is visible whenever the field is
    focused OR has any content.
  - **Role**: A `Select` bound to `role` with two options — `Standard` (default,
    `STANDARD`) and `Admin` (`ADMIN`). A one-line caption clarifies the distinction:
    *"Admins can manage users, API keys, and the rate-limit configuration."*.
- The sticky action bar at the bottom holds:
  - `Cancel` button calling `onCancel()`.
  - `Create user` button with `type="submit"`, `loading={mutation.isPending}`,
    `disabled={mutation.isPending || !form.formState.isValid}`.
- On submit success: calls `onSuccess(user)` with the returned `User`.
- On submit failure, the error-routing table above is honored exactly. The
  `CONFLICT`-triggered `setError('email', …)` scrolls the `email` anchor into view via
  `scrollIntoView({ block: 'center', behavior: 'smooth' })`.
- Every input renders `data-rhf-field={fieldName}` on its outermost wrapper so scroll-to-
  error has a stable anchor.
- **Component tests** in `frontend/src/features/admin-users/UserForm.test.tsx`:
  - **Empty-form submit**: Zod-driven field errors render under `email` and `password`.
  - **Invalid email**: submitting `not-an-email` shows the email format error.
  - **Password policy checklist**: typing `alllowercase` shows the two failing rules
    (uppercase, special) as unchecked; adding `!A` at the end flips them to checked.
  - **Role default**: on first render, `role` is `STANDARD`; the caption is visible.
  - **Create happy path**: filling valid values + clicking Create issues
    `POST /admin/users` (verify via MSW); `onSuccess` is called with the server's `User`;
    the password is **not** in the request body log (assert via inspecting MSW's captured
    request that the body is JSON with `password` present, then verify the form does not
    call `console.log`/`console.info` with the password — regression against a stray
    debug statement).
  - **`409 CONFLICT`**: `email` field shows the conflict copy; no other field has an
    error; the scrolled section is Identity.
  - **`400 VALIDATION_ERROR` with `errors: [{ field: 'email', message: '…' }]`**: `email`
    field shows the server-provided message.
  - **`400 VALIDATION_ERROR` with `errors: [{ field: 'password', message: '…' }]`**:
    `password` field shows the server-provided message (defense in depth — the client
    already enforces the policy but the server is authoritative).
  - **`429 RATE_LIMITED` with `Retry-After: 5`**: top-of-form countdown alert renders;
    Submit is disabled for 5 seconds.
  - **`500 INTERNAL_ERROR`**: top-of-form fallback alert renders; Submit re-enables.
  - **Cancel** calls `onCancel()` (no network call fires).

### Out of scope

- Editing an existing user's email / password / role — not supported by
  `UpdateUserRequest` (which only exposes `disabled`).
- Password-strength meter / entropy score — the three-rule checklist is the spec.
- Sending the new account credentials by email — the backend does not send email; the
  admin must communicate the initial password out-of-band (`REQ-USR-004`).
- Confirming the password (typing it twice) — the create form is admin-authored; there
  is no self-serve typo risk to protect against. The admin can copy the password out
  before submission if needed.

### Design references

- `frontend/design/SW-DESIGN.md` §9.1 (forms), §9.2 (constraint mirroring), §9.3
  (password policy — reused), §9.4 (server-side errors), §10.2 (per-code routing),
  §12.10 (admin pages).
- `openapi.yaml` `CreateUserRequest`, `User`, `POST /admin/users`.

### Dependencies

- US-08-001 (`createUserSchema`, `useCreateUser`); EPIC-03 US-03-001
  (`passwordPolicy`) + US-03-004 (`PasswordPolicyChecklist` — extracted for reuse if
  not already export-ready); EPIC-02 (`Input`, `Select`, `Button`, `Card`, alert / toast
  primitives).

---

## US-08-003 — `UserList` (table) + `DisableUserDialog` + `DeleteUserDialog`

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** a `UserList` that paginates users into a table (email / role badge / disabled
badge / created-at), a `DisableUserDialog` confirming the enable/disable toggle
(optimistic per SW-DESIGN §7.5), and a `DeleteUserDialog` that quotes the cascade warning
required by `REQ-USR-006` before confirming
**So that** I can scan the platform's users at a glance, disable an account without
scrolling to a detail page, and never accidentally delete a user whose agents and
conversations I did not mean to wipe.

### Description

Per SW-DESIGN §12.10, the admin users surface is a **table**, not a card grid — the
information density suits an admin scan (email / role / status / created-at). This
distinguishes it from `AgentList` (US-05-005), which is a card grid because agents are
richer objects.

`UserList` renders one row per user (after `flattenPages`), the fields above, and a
per-row `<Dropdown>` action menu with two entries — **Disable** (or **Enable** when
`user.disabled === true`) and **Delete**. Row clicks navigate to the detail page.

`DisableUserDialog` is a `Modal` wrapping a confirm dialog. The body wording is symmetric:

- **When enabling**: *"Re-enable `alice@example.com`? They will be able to sign in
  again."* — Confirm label: **Enable**.
- **When disabling**: *"Disable `alice@example.com`? They will not be able to sign in.
  Their agents and conversations are preserved and remain restorable by re-enabling."* —
  Confirm label: **Disable** (destructive variant).

On confirm: calls `useUpdateUser(userId).mutateAsync({ disabled })`. Because the hook is
optimistic (US-08-001), the row's `Disabled` badge flips **immediately** on click; on
error the badge rolls back and an inline error appears in the dialog. On success the
dialog closes.

`DeleteUserDialog` is a `Modal` wrapping a stronger confirm dialog. The body quotes the
cascade warning verbatim from EPIC-08's scope:

> *"Deleting `alice@example.com` will permanently delete their agents and conversations.
> This cannot be undone."*

The admin types the user's exact `email` into a confirmation `Input` (the Delete button
stays disabled until the typed text exactly matches, case-sensitive). On confirm: calls
`useDeleteUser().mutateAsync({ userId })`; on success closes the dialog and the
`onDeleted(user)` prop is called.

### Acceptance criteria

- `frontend/src/features/admin-users/UserList.tsx` exists with the prop shape:
  - `interface UserListProps { onView: (id: string) => void; onToggleDisabled: (user: User) => void; onDelete: (user: User) => void; }`
- Consumes `useUsers()` + `flattenPages()`. Renders:
  - A table with columns `Email` (mono font), `Role` (`Badge` — accent variant for
    `ADMIN`, muted for `STANDARD`), `Status` (`Badge` — success `Active` when
    `!disabled`, danger `Disabled` when `disabled`), `Created` (`formatRelative(createdAt)`),
    and a right-aligned actions column.
  - Row click (anywhere except the actions column) fires `onView(user.id)`.
  - Actions column has a `<Dropdown>` with two entries:
    - `Disable` (when `!user.disabled`) or `Enable` (when `user.disabled`) firing
      `onToggleDisabled(user)`.
    - `Delete` firing `onDelete(user)`.
  - A "Load more" `Button` when `hasNextPage`, wired to `fetchNextPage` with the
    `isFetchingNextPage` spinner.
  - First-page loading: 5 skeleton rows.
  - First-page error: inline error state with Retry.
  - Empty flattened items: `UserList` returns `null`; the page-level empty state is
    owned by `AdminUsersPage` (US-08-004) — mirrors the EPIC-05 pattern.
- `frontend/src/features/admin-users/DisableUserDialog.tsx` exists with the prop shape:
  - `interface DisableUserDialogProps { user: User | null; open: boolean; onClose: () => void; onDone: (user: User) => void; }`
- Renders a `Modal` whose body copy switches on `user.disabled`:
  - The two verbatim wordings above.
  - Confirm button label matches the direction (`Enable` / `Disable`).
  - `Disable` uses the destructive variant; `Enable` uses the primary variant.
- On confirm: calls `useUpdateUser(user.id).mutateAsync({ disabled: !user.disabled })`.
  Because the hook is optimistic:
  - The row's `Status` badge flips immediately (visible in the list behind the modal).
  - On success: closes the dialog and calls `onDone(user)`.
  - On error: renders an inline error alert inside the dialog; the dialog stays open;
    the cache is rolled back (verified by the badge behind the modal reverting).
- `frontend/src/features/admin-users/DeleteUserDialog.tsx` exists with the prop shape:
  - `interface DeleteUserDialogProps { user: User | null; open: boolean; onClose: () => void; onDeleted: (user: User) => void; }`
- Renders a `Modal` with:
  - Heading: `Delete ${user.email}?`
  - Body: the cascade warning quoted above + a confirmation `Input` labelled
    `Type "${user.email}" to confirm.`
  - Footer: `Cancel` + `Delete` (destructive variant). Delete is disabled until the typed
    text matches (case-sensitive); loading while the mutation is in flight.
  - On success: calls `onDeleted(user)` then `onClose()`.
  - On error: renders an inline error alert inside the dialog (the dialog stays open so
    the admin can retry).
- **Component tests** in `frontend/src/features/admin-users/UserList.test.tsx`:
  - **Table columns render**: `email`, `Role` badge with the right variant per role,
    `Active` / `Disabled` badge per status, relative created-at.
  - **Row click**: clicking anywhere outside the actions column fires `onView(user.id)`.
  - **Action menu**: for a `!disabled` user, the menu shows `Disable`; for a `disabled`
    user, it shows `Enable`.
  - **Pagination**: MSW returns two pages; first paint shows page 1; clicking "Load
    more" appends page 2.
  - **Skeleton loading**: 5 skeleton rows render on first paint.
  - **Error + Retry** path works.
- **Component tests** in `frontend/src/features/admin-users/DisableUserDialog.test.tsx`:
  - **Disable direction copy**: opening for a `!disabled` user shows the disable copy and
    the destructive `Disable` button.
  - **Enable direction copy**: opening for a `disabled` user shows the enable copy and
    the primary `Enable` button.
  - **Optimistic flip**: clicking Confirm flips the row's badge immediately (behind the
    modal) — verified by asserting the cache value on the same tick as the click.
  - On 200 success: `onDone(user)` is called and the dialog closes.
  - On 500 error: an inline error alert renders inside the dialog; the dialog stays
    open; the badge behind the modal reverts to the pre-click value (rollback).
- **Component tests** in `frontend/src/features/admin-users/DeleteUserDialog.test.tsx`:
  - Delete button starts `disabled`; typing the user's exact email enables it; typing a
    wrong value or a differently-cased value disables it again (case-sensitive).
  - On 204 success: `onDeleted` is called and the dialog closes.
  - On 500 error: an inline error alert renders inside the dialog; the dialog stays
    open; the users list cache is **not** invalidated (verified by a sibling `useUsers`
    not refetching).
  - On 404 NOT_FOUND (user already deleted in another tab): the dialog renders the error
    inline AND `onDeleted` is **not** called.

### Out of scope

- Bulk enable/disable/delete — not in v1.
- Sortable / filterable table headers — the spec does not expose sort/filter params on
  `GET /admin/users`. Filtering is a client-side polish item deferred to EPIC-11 if
  asked.
- Editing the user's email or role — no `UpdateUserRequest` field for either.
- Undo-delete — not supported by the backend (`REQ-USR-006` — hard delete only).

### Design references

- `frontend/design/SW-DESIGN.md` §7.5 (optimistic updates — the `disabled` toggle is the
  canonical example), §11.4 (primitives), §12.10 (`AdminUsersPage` table shape + confirm
  dialogs).
- `openapi.yaml` `User`, `UpdateUserRequest`, `DELETE /admin/users/{userId}` (cascade
  note in the description).

### Dependencies

- US-08-001 (`useUsers`, `useUpdateUser`, `useDeleteUser`, `User` type); EPIC-02 (`Card`,
  `Badge`, `Button`, `Dropdown`, `Modal`, `Input`, `Skeleton`, `formatRelative`).

---

## US-08-004 — `AdminUsersPage` + admin routes wiring + role-gated sidebar group

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** the `/admin/users` page composing `UserList` with the page-level header
("Users" heading + "Create user" CTA), the dedicated empty state when zero users are
returned (edge case — the platform seeds at least one admin), the role-gated Admin
sidebar group, and the three-route wiring (`/admin/users`, `/admin/users/new`,
`/admin/users/:userId`) registered under the protected `AppShell` with
`<RequireRole role="ADMIN">`
**So that** I have one obvious entry point into the admin surface from the sidebar, no
standard user ever sees the admin nav or hits an admin route without a `403` bounce, and
every deep link routes to the right page.

### Description

`AdminUsersPage` is intentionally thin — it composes `UserList` (US-08-003), adds the
page-level header + empty-state branch, and owns the `DisableUserDialog` +
`DeleteUserDialog` open states. It does **not** own the create form (US-08-005's create
page does).

The three routes are all registered under the protected `<AppShell>` layout established
in US-03-007, and each is additionally wrapped in `<RequireRole role="ADMIN">`:

- `/admin/users` → `AdminUsersPage` (this story).
- `/admin/users/new` → `AdminUserCreatePage` (placeholder in this story; body in
  US-08-005).
- `/admin/users/:userId` → `AdminUserDetailPage` (placeholder in this story; body in
  US-08-005).

All three routes are `React.lazy()`-loaded per SW-DESIGN §16.1.

The sidebar gains an **Admin** group — a new nav section rendered **only** when
`principal.role === 'ADMIN'`. The group holds a single link **Users** (with the `Users`
lucide icon) for this EPIC; EPIC-09 (API keys) and EPIC-10 (Rate limit) will append their
own links to the same group. Standard users never see the group header nor its links.

### Acceptance criteria

- `frontend/src/pages/admin/AdminUsersPage.tsx` exists, exporting
  `function AdminUsersPage(): JSX.Element`. Renders:
  - A page heading `Users` + a one-line caption "Create, enable, disable, and remove
    platform users.".
  - A primary `Button` "Create user" navigating to `/admin/users/new`.
  - Conditional body:
    - When `useUsers()` is `isPending` (first paint): 5 skeleton rows (delegated to
      `UserList`).
    - When `useUsers()` is `isError` on first paint: inline error state with Retry.
    - When the flattened users array is empty: the page-level `EmptyState` with title
      `No users yet` and a caption "The platform seeds a default admin; if you're
      seeing this, something is off." + a single primary CTA `Create user` navigating
      to `/admin/users/new`. This branch is a defensive check — in practice the
      admin-seed migration guarantees ≥1 user.
    - Otherwise: `<UserList onView={…} onToggleDisabled={…} onDelete={…} />` with the
      three callbacks navigating to `/admin/users/:id` and opening the two dialogs
      respectively.
  - Owns the `DisableUserDialog` and `DeleteUserDialog` open states. On
    `DisableUserDialog.onDone` and `DeleteUserDialog.onDeleted`, fires a success toast
    (`Account updated` / `User deleted`).
- The route table (`frontend/src/pages/routes.tsx`) is updated to register the three
  routes under the protected `<AppShell>` layout with `React.lazy()` imports, each
  additionally wrapped in `<RequireRole role="ADMIN">`. For US-08-004 the create/detail
  routes can resolve to a minimal placeholder page (`<>Coming in US-08-005</>`) —
  US-08-005 replaces the placeholders.
- The `Sidebar` gains an **Admin** group rendered **only** when
  `principal.role === 'ADMIN'`. The group has a `Users` link wired to `/admin/users`.
  The existing standard-user groups remain untouched. When rendered, the group visually
  separates from the standard-user groups (a divider + a muted `Admin` label above the
  first admin link).
- **Integration tests** in `frontend/src/pages/admin/AdminUsersPage.test.tsx` under the
  full provider stack (authenticated as an admin):
  - **Populated list**: MSW returns 3 users; all 3 rows render; the header CTA `Create
    user` navigates to `/admin/users/new`.
  - **Empty state** (defensive): MSW returns `{ items: [], nextCursor: null }`; the
    empty state + its single CTA render; clicking `Create user` navigates to
    `/admin/users/new`.
  - **Pagination**: with 2 pages of MSW responses, the list renders page 1; clicking
    Load more appends page 2.
  - **Optimistic disable/enable**: clicking a row's `Disable` action opens the confirm
    dialog; clicking Confirm flips the row's badge **immediately** (before the MSW
    response resolves); after the 200 the dialog closes and the toast renders.
  - **Optimistic rollback on error**: the same flow with MSW returning 500 keeps the
    dialog open, renders the inline error, and reverts the row's badge to its original
    value.
  - **Delete-with-cascade**: clicking a row's `Delete` action opens the dialog with the
    cascade warning; typing the exact email + clicking Delete fires
    `DELETE /admin/users/{id}` (MSW asserts the call); after 204 the dialog closes, the
    success toast renders, and the user is no longer in the list (after the cache
    invalidation refetches).
  - **Delete failure**: 500 on delete leaves the dialog open and the list intact.
- **Integration tests** in `frontend/src/pages/routes.integration.test.tsx` (extending
  the suite seeded in US-03-007):
  - Navigating to `/admin/users` while authenticated as `STANDARD` bounces to `/403`.
  - Navigating to `/admin/users` while authenticated as `ADMIN` renders the page.
  - Navigating to `/admin/users/new` and `/admin/users/abc` while authenticated as
    `ADMIN` land on the placeholder pages (proving the routes are registered; the real
    pages land in US-08-005).
  - Navigating to `/admin/users` while unauthenticated bounces to
    `/login?next=/admin/users` (the standard `RequireAuth` behavior).
- **Sidebar integration test** in `frontend/src/shared/layout/Sidebar.admin.test.tsx`
  (new file or extension of the existing Sidebar test):
  - When `principal.role === 'ADMIN'`: the `Admin` group is rendered with a `Users`
    link pointing to `/admin/users`.
  - When `principal.role === 'STANDARD'`: the `Admin` group is **not** in the DOM at all
    (asserted via `queryByRole('link', { name: 'Users' })` returning `null` AND the
    group's `Admin` label being absent from the tree).

### Out of scope

- The two create/detail page bodies — US-08-005.
- API-key and rate-limit sidebar links — EPIC-09 and EPIC-10 append them. This story
  ships the group container with a single link so those EPICs are purely additive.
- A separate `ForbiddenPage` — the guard bounces to `/403` (registered in the route
  table in SW-DESIGN §5.1), which is already implemented by EPIC-02's guards
  (US-02-008).

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table — the three `/admin/users/...`
  routes), §5.2 (guards — `RequireRole`), §6.5 (role gating in the sidebar), §12.10
  (`AdminUsersPage` shape), §16.1 (lazy loading).
- `openapi.yaml` `GET /admin/users`, `DELETE /admin/users/{userId}`.

### Dependencies

- US-08-003 (`UserList`, `DisableUserDialog`, `DeleteUserDialog`); EPIC-02 (`Button`,
  `EmptyState`, `Sidebar`, `AppShell`, `Toast`, route table, `RequireRole`).

---

## US-08-005 — `AdminUserCreatePage` + `AdminUserDetailPage` (page composition + integration tests)

- **Status**: Done
- **Priority**: MUST

**As an** admin
**I want** the two remaining admin-user pages — Create and Detail — composing the form
(US-08-002) and the read-only detail view (with the enable/disable + delete actions in a
CTA bar)
**So that** my admin CRUD round-trip is complete end-to-end: I can land on
`/admin/users/new`, fill the form, hit Create, get redirected to `/admin/users/:id`,
click Disable or Delete, and end up back on the list.

### Description

The two pages are paired in one story because they are pure composition — the heavy
lifting is in `UserForm` (US-08-002), `useUser` (US-08-001), and the two dialogs
(US-08-003). Splitting them would just multiply boilerplate.

- **`AdminUserCreatePage`** — `/admin/users/new`. Renders heading "New user" +
  `<UserForm onSuccess={(user) => navigate('/admin/users/' + user.id)} onCancel={() => navigate('/admin/users')} />`.
- **`AdminUserDetailPage`** — `/admin/users/:userId`. Reads `userId` from the URL; calls
  `useUser(userId)`. Renders a **read-only** summary card mirroring the User shape:
  `email` (mono, prominent), `Role` (`Badge`), `Status` (`Badge`, live off `disabled`),
  `Created`, `Updated`, `Must change password` (`Yes` / `No` badge). A CTA bar at the top
  of the card holds two actions:
  - **Disable** (or **Enable** when `disabled === true`) — opens `DisableUserDialog`
    (US-08-003) with the current user.
  - **Delete** — opens `DeleteUserDialog` (US-08-003); on success navigates back to
    `/admin/users`.

The detail page **does not** show the user's owned agents / conversations counts — the
spec does not expose those fields on `User`. The cascade warning in the delete dialog
communicates the consequence at the moment of the action.

### Acceptance criteria

- `frontend/src/pages/admin/AdminUserCreatePage.tsx` exists, exporting
  `function AdminUserCreatePage(): JSX.Element`. Renders heading `New user` + a
  one-line caption + `UserForm`.
  - On `onSuccess(user)`: `navigate('/admin/users/' + user.id, { replace: true })` and
    fires a success toast `User created`.
  - On `onCancel`: `navigate('/admin/users')`.
- `frontend/src/pages/admin/AdminUserDetailPage.tsx` exists, exporting
  `function AdminUserDetailPage(): JSX.Element`. Reads `useParams<{ userId: string }>()`;
  calls `useUser(userId)`. Render branches:
  - `isPending`: a skeleton card with 5 skeleton lines.
  - `isError` with `error.status === 404`: an `<EmptyState>` `User not found` + a link
    `Back to users` → `/admin/users`.
  - Other `isError`: inline error state with Retry.
  - `isSuccess`:
    - Heading: `user.email` (font-mono) + the two-action CTA bar (Disable/Enable +
      Delete).
    - Read-only summary card with the six fields listed above.
    - The `Status` badge and the CTA button label reflect `user.disabled` live (i.e.,
      after the optimistic update from `DisableUserDialog` flips the cache, the badge and
      button both flip in the same tick — no extra re-fetch needed because the same
      cache entry drives both).
  - Owns the `DisableUserDialog` and `DeleteUserDialog` open states. On the disable
    dialog's `onDone`: toast `Account updated`. On the delete dialog's `onDeleted`:
    `navigate('/admin/users', { replace: true })` and toast `User deleted`.
- The two route entries in `pages/routes.tsx` (registered as placeholders in US-08-004)
  are swapped for `React.lazy()` imports of the real pages.
- **Integration tests** in `frontend/src/pages/admin/AdminUserCreatePage.test.tsx`:
  - Filling valid form + Create → MSW responds 201 → location moves to
    `/admin/users/<id>` → toast appears.
  - `409 CONFLICT` → the email field shows the conflict copy (already verified at the
    form level, but page-level smoke confirms it surfaces).
  - Clicking Cancel returns to `/admin/users`.
- **Integration tests** in `frontend/src/pages/admin/AdminUserDetailPage.test.tsx`:
  - Mount at `/admin/users/abc` with MSW returning a populated `User` (`disabled: false`,
    `role: 'STANDARD'`): the summary card renders every field with the correct values;
    the CTA bar shows `Disable` + `Delete`.
  - Clicking `Disable` opens the confirm dialog; confirming optimistically flips the
    `Status` badge to `Disabled` AND the CTA button label to `Enable` in the same tick;
    after the MSW 200, the dialog closes; the toast appears.
  - Clicking `Enable` on a `disabled: true` mount does the mirror flow.
  - Optimistic rollback: MSW returning 500 on the disable/enable call reverts both the
    badge and the CTA label; the dialog stays open with an inline error.
  - Clicking `Delete` + completing the dialog navigates to `/admin/users` + fires the
    `User deleted` toast; the users-list cache is invalidated (verified by re-mount at
    `/admin/users` observing the user absent).
  - `404 NOT_FOUND` (e.g., the user was deleted in another tab): the not-found empty
    state renders with a Back link to `/admin/users`.
  - Route guard: mounting at `/admin/users/abc` while authenticated as `STANDARD` still
    bounces to `/403` (defense in depth — the route wrapper in US-08-004 covers this;
    the page-level test is a smoke).

### Out of scope

- Editing the user's email or role — no `UpdateUserRequest` field for either.
- Resetting the user's password — no backend endpoint. The admin re-creates the account
  or communicates the reset out-of-band.
- Showing owned-agents / conversations counts — not on the `User` schema.
- An "Impersonate user" affordance — not in the spec (and out of scope for security).

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (routes), §12.10 (Admin user pages).
- `openapi.yaml` `GET/PATCH/DELETE /admin/users/{userId}`, `User`.

### Dependencies

- US-08-002 (`UserForm`); US-08-003 (`DisableUserDialog`, `DeleteUserDialog`);
  US-08-004 (routes + sidebar group + the `AdminUsersPage` that serves as the navigation
  target on cancel / delete-success); EPIC-02 (`Skeleton`, `EmptyState`, `Button`,
  `Badge`, `Card`, `Toast`).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-08-001  | `createUserSchema` Zod schema + Admin-user query/mutation hooks                                    | MUST     | Done   |
| US-08-002  | `UserForm` — email + password (with policy checklist) + role + error routing                        | MUST     | Done   |
| US-08-003  | `UserList` (table) + `DisableUserDialog` + `DeleteUserDialog` (optimistic + cascade warn)           | MUST     | Done   |
| US-08-004  | `AdminUsersPage` + admin routes wiring + role-gated sidebar group                                   | MUST     | Done   |
| US-08-005  | `AdminUserCreatePage` + `AdminUserDetailPage` (page composition + integration tests)                | MUST     | Done   |

EPIC-08 is **Done** when all five stories above are `Done`. The admin surface then has
its first pillar in place — Users. The next steps are EPIC-09 (API keys — which appends
a second link to the Admin sidebar group opened here) and EPIC-10 (Rate limit — which
appends the third).
