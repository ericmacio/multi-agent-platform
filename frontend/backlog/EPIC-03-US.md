# EPIC-03-US.md — User stories for EPIC-03 (Authentication flows)

This file lists the user stories that deliver **EPIC-03 — Authentication flows (login,
change password, sign out, expiry)** of the frontend, as defined in
`frontend/backlog/EPICS.md`.

EPIC-03 is the first feature slice that consumes the shared layer from EPIC-02 end-to-end.
After it lands, an end-user can sign in, be routed through a forced password change on
first login, sign back out cleanly, and have their session expiry handled gracefully —
unlocking every other v1 feature slice that requires an authenticated principal.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-03-<nnn>` — `03` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All seven stories are `MUST` (the auth surface
  is a gate on every other end-user feature).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                            | Priority | Status | Depends on                  |
|------------|----------------------------------------------------------------------------------|----------|--------|-----------------------------|
| US-03-001  | `passwordPolicy` Zod schema + per-rule live evaluator (`features/auth/password.ts`) | MUST  | Done   | EPIC-02                     |
| US-03-002  | Auth mutation hooks: `useLogin`, `useLogout`, `useChangeOwnPassword`             | MUST     | Done   | EPIC-02                     |
| US-03-003  | `LoginForm` — email + password + `?next=` validation + 429 countdown             | MUST     | Done   | US-03-002                   |
| US-03-004  | `ChangePasswordForm` — three fields + live policy checklist + confirm-match      | MUST     | Done   | US-03-001, US-03-002        |
| US-03-005  | `LoginPage` — `AuthShell` composition + integration tests                        | MUST     | Done   | US-03-003                   |
| US-03-006  | `ChangePasswordPage` — `AuthShell` composition + forced-visit banner + integration tests | MUST | Done | US-03-004                   |
| US-03-007  | Routes wiring (`RequireGuest` / `RequireFreshPassword`) + session-expired toast  | MUST     | Done   | US-03-005, US-03-006        |

---

## US-03-001 — `passwordPolicy` Zod schema + per-rule live evaluator

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a single `passwordPolicy` Zod schema mirroring the openapi-documented policy
("≥10 characters, ≥1 uppercase letter, ≥1 special character") and a small companion
`evaluatePasswordPolicy()` helper that returns each rule's pass/fail state independently
**So that** the `ChangePasswordForm` (US-03-004) can both validate at submit time AND
render a live policy checklist while the user types — both surfaces reading from the same
source of truth.

### Description

Per SW-DESIGN §9.3, the password policy is canonical in
`openapi.yaml.ChangePasswordRequest.newPassword.description`. The client mirrors it as a
Zod schema (chained `.regex` refinements) that produces field-level validation at submit.

A naive `safeParse` only returns "passed / first failure" — useful for submit but useless
for the live checklist, which needs to show **each** rule's current state independently.
The companion `evaluatePasswordPolicy(value)` helper exists precisely for that view.

The schema is exported alongside the form (consumed by `ChangePasswordForm` and its
tests). The helper is exported as a separately-testable pure function.

### Acceptance criteria

- `frontend/src/features/auth/password.ts` exists with the following exports:
  - `export const passwordPolicy = z.string().min(10, 'At least 10 characters').max(256).regex(/[A-Z]/, 'At least one uppercase letter').regex(/[^A-Za-z0-9]/, 'At least one special character');`
  - `export type PasswordRuleKey = 'length' | 'uppercase' | 'special';`
  - `export type PasswordRule = { key: PasswordRuleKey; label: string; valid: boolean };`
  - `export function evaluatePasswordPolicy(value: string): PasswordRule[]` — returns
    a stable-ordered three-element array with `{ length, uppercase, special }` each
    populated via independent checks. Order is fixed so the rendered list does not
    reorder as the user types.
  - `export function isPasswordPolicySatisfied(value: string): boolean` — convenience
    `evaluatePasswordPolicy(value).every(r => r.valid)`. Used by `ChangePasswordForm` to
    gate the submit button.
- The Zod messages are byte-identical to the rule `label` strings in
  `evaluatePasswordPolicy` (so a user sees the same wording at both the live checklist and
  any submit-time field error).
- **Unit tests** in `frontend/src/features/auth/password.test.ts`:
  - `passwordPolicy.safeParse('')` fails; first issue's message is `'At least 10 characters'`.
  - `passwordPolicy.safeParse('abcdefghij')` (no uppercase) fails on the uppercase rule.
  - `passwordPolicy.safeParse('Abcdefghij')` (no special) fails on the special rule.
  - `passwordPolicy.safeParse('Abcdefghij!')` succeeds.
  - `passwordPolicy.safeParse('A'.repeat(257) + '!')` fails on max-length.
  - `evaluatePasswordPolicy('')` returns three rules all `valid: false`.
  - `evaluatePasswordPolicy('Abcdefghij!')` returns three rules all `valid: true`.
  - `evaluatePasswordPolicy('Abcdefghi')` returns
    `[{ length:false }, { uppercase:true }, { special:false }]` (still 9 chars, no special).
  - Rule order is `[length, uppercase, special]` regardless of the input contents.
  - `isPasswordPolicySatisfied('Abcdefghij!')` → `true`;
    `isPasswordPolicySatisfied('abcdefghij')` → `false`.

### Out of scope

- Server-side validation. The backend re-validates; the client schema is for UX only.
- A reusable `<PolicyChecklist />` UI primitive. EPIC-03 ships the checklist inline in
  `ChangePasswordForm`; if a second consumer appears later, the primitive can be
  extracted.
- Banned-password / dictionary checks. The backend may add these later — out of v1 scope.

### Design references

- `frontend/design/SW-DESIGN.md` §9.1 (form library choice), §9.3 (password policy regex
  + live checklist behavior).
- `openapi.yaml` `ChangePasswordRequest.newPassword.description`.

### Dependencies

- EPIC-02 (`zod` is already a project dependency via `@/env`).

---

## US-03-002 — Auth mutation hooks: `useLogin`, `useLogout`, `useChangeOwnPassword`

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** three TanStack Query mutation hooks — `useLogin`, `useLogout`,
`useChangeOwnPassword` — wrapping the three `/auth/*` endpoints and integrating with the
`AuthContext` (US-02-007) so the post-mutation state transitions happen in one place
**So that** `LoginForm` / `ChangePasswordForm` / the profile-menu "Sign out" action each
call a single typed hook and never restate auth state plumbing (signIn, signOut, mustChangePassword toggle).

### Description

Per SW-DESIGN §6, the AuthContext owns the in-memory auth state. The mutation hooks own
the HTTP round-trip and the **success-side state transition**:

- **`useLogin`** — `POST /auth/login`. On success: validate the JWT shape (already done by
  `decodeJwtPayload` inside `signIn`); call `signIn(bundle)`. On error: surface `ApiError`
  to the caller via the standard TanStack `error` field. INVALID_CREDENTIALS is **not**
  translated to a toast here — the form decides how to render it (form-level alert vs.
  global redirect; see SW-DESIGN §10.2).
- **`useLogout`** — `POST /auth/logout`. Best-effort: failures are swallowed; local state
  is cleared regardless. On settled (success OR error): call `signOut('/login')`. This
  matches SW-DESIGN §6.3 / §5.3.4.
- **`useChangeOwnPassword`** — `PUT /auth/password`. On success: clear `mustChangePassword`
  in the AuthContext (via a re-`signIn` with the existing bundle + `mustChangePassword:
  false`, OR via a new dedicated method exposed by `AuthContext` — implementer's choice).
  The JWT remains valid; we do not force a re-login (per SW-DESIGN §5.3.1 step 5).

The hooks are written against the typed `openapi-fetch` client (US-02-003) and unwrap
its envelope via `unwrap()`. They throw `ApiError` on non-2xx (which the middleware
already does).

### Acceptance criteria

- `frontend/src/features/auth/api.ts` exists with the following exports:
  - `export function useLogin(): UseMutationResult<LoginResponse, ApiError, LoginRequest>`
  - `export function useLogout(): UseMutationResult<void, ApiError, void>`
  - `export function useChangeOwnPassword(): UseMutationResult<void, ApiError, ChangePasswordRequest>`
  - Where `LoginRequest`, `LoginResponse`, `ChangePasswordRequest` are type aliases for
    the generated openapi schemas (`components['schemas']['LoginRequest']` etc.).
- `useLogin.mutationFn` body: `unwrap(await api.POST('/auth/login', { body }))` and
  returns the unwrapped `LoginResponse`.
- `useLogin.onSuccess`:
  - calls `signIn({ token, expiresAt, mustChangePassword })` derived from the response,
  - does NOT navigate (the form picks the destination after considering `?next=`).
- `useLogout.mutationFn`: `api.POST('/auth/logout', {})`. Wraps in `try/catch` so a 401
  or 5xx doesn't bubble — the caller observes only success. **Important**: even on
  network failure, the catch is swallowed.
- `useLogout.onSettled`: calls `signOut('/login')` regardless of success or failure.
- `useChangeOwnPassword.mutationFn`: `unwrap(await api.PUT('/auth/password', { body }))`.
- `useChangeOwnPassword.onSuccess`:
  - clears `mustChangePassword` in AuthContext (preserving the same `token` and
    `expiresAt`),
  - does NOT clear or rotate the token.
- If the `AuthContext` exposes a dedicated `setMustChangePassword(value: boolean)` method,
  it is added in this story (one-line context extension); otherwise the hook calls
  `signIn(currentBundle with mustChangePassword: false)`. Either is acceptable.
- **Unit tests** in `frontend/src/features/auth/api.test.tsx` (MSW + `renderHook`):
  - `useLogin` — 200 happy path: `mutate` resolves; `useAuth().token` becomes the
    server-returned token; `useAuth().mustChangePassword` reflects the response.
  - `useLogin` — 401 INVALID_CREDENTIALS: the mutation's `error` is `ApiError` with
    `code: 'INVALID_CREDENTIALS'`; `useAuth().token` remains `null` (no signIn fired).
  - `useLogout` — 204 happy path: `useAuth().token` becomes `null` after the call
    settles; the navigation intent `redirectTo === '/login'` is set on AuthContext.
  - `useLogout` — server returns 500: the mutation still settles successfully (no error
    thrown to the caller); `useAuth().token` is null afterwards.
  - `useChangeOwnPassword` — 204 happy path: pre-state has
    `mustChangePassword: true`; after the mutation, `useAuth().mustChangePassword` is
    `false` and the token is unchanged.
  - `useChangeOwnPassword` — 400 VALIDATION_ERROR: the mutation's `error.fieldErrors`
    contains the server-named field (e.g., `newPassword`); the AuthContext state is
    unchanged.

### Out of scope

- The forms / pages that call these hooks (US-03-003 onwards).
- Optimistic updates. None of the three mutations have a sensible optimistic state —
  the user always waits for the round-trip.
- Token-refresh logic. The contract has no refresh token (REQ-AUTH-004 caveat).

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.1 (first-time admin login), §5.3.4 (logout),
  §6.2 (login), §6.3 (sign-out), §7.1 (typed client), §9.4 (server-validation → fieldErrors).
- `openapi.yaml` `POST /auth/login`, `POST /auth/logout`, `PUT /auth/password`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `AuthContext`, `tokenStorage`, `ApiError`,
  `queryClient`).

---

## US-03-003 — `LoginForm` — email + password + `?next=` validation + 429 countdown

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a `LoginForm` component using `react-hook-form` + Zod against the openapi
`LoginRequest` constraints, handling the four documented error codes (validation,
INVALID_CREDENTIALS, RATE_LIMITED, MUST_CHANGE_PASSWORD), and routing to either the
validated `?next=` path, `/change-password` (if forced), or `/agents` after a successful
sign-in
**So that** `LoginPage` (US-03-005) is purely composition (`<LoginForm />` in `AuthShell`)
and the navigation logic stays where it can be unit-tested in isolation.

### Description

Per SW-DESIGN §9.1, the form library is `react-hook-form` + `@hookform/resolvers/zod`.
Per SW-DESIGN §10.2:

- `INVALID_CREDENTIALS` on `/auth/login` → **form-level alert** (NOT a redirect). The
  message is generic ("Email or password is incorrect.") per `REQ-AUTH-009`; the field
  names are NOT individually highlighted (no leak of which field was wrong).
- `RATE_LIMITED` → **inline alert** with a 1-second-resolution countdown sourced from
  `apiError.retryAfterSeconds`. The submit button is disabled while the countdown is
  active; an unobtrusive screen-reader text announces the wait.
- `MUST_CHANGE_PASSWORD` returned from `/auth/login` is **impossible by design** (the
  server only sets `mustChangePassword: true` in the body, not as an error code). But if
  the user has `mustChangePassword === true` in the `LoginResponse`, the form **navigates
  to `/change-password?reason=forced`** and ignores any `?next=` value (the forced
  change must happen first per SW-DESIGN §5.3.1).
- `VALIDATION_ERROR` with `errors[]` → `setError(field, { message })` per SW-DESIGN §9.4.
  In practice `LoginRequest` has only two fields, so this is rare client-side (the Zod
  resolver catches most cases first).

The `?next=` validation: only paths that begin with `/` and not `//` are honored. Absolute
URLs, protocol-relative URLs, and `javascript:` URIs are dropped (per SW-DESIGN §15) and
the destination falls back to `/agents`.

### Acceptance criteria

- `frontend/package.json` adds the following `dependencies`:
  - `react-hook-form ^7.52.0`,
  - `@hookform/resolvers ^3.6.0`.
- `frontend/src/features/auth/loginSchema.ts` exists with:
  - `export const loginSchema = z.object({ email: z.string().email().max(254), password: z.string().min(1).max(256) });`
  - `export type LoginValues = z.infer<typeof loginSchema>;`
- `frontend/src/features/auth/LoginForm.tsx` exists, rendering:
  - `Email` `Input` (type=email, required, autocomplete="email"),
  - `Password` `Input` (type=password, required, autocomplete="current-password"),
  - a `Sign in` `Button` (type=submit, `loading={mutation.isPending}`),
  - a static caption `Need access? Contact your administrator.` (no "Forgot password"
    link — per SW-DESIGN §12.1).
- A `safeNextPath(raw: string | null): string` helper exported from the form module:
  - returns `'/agents'` when `raw` is `null`, empty, doesn't start with `/`, starts
    with `//`, or contains a colon before the first slash (catches `javascript:`,
    `http:`, `https:`, etc.),
  - otherwise returns the raw value (`pathname + search`).
  - **Unit tests** assert the helper's behavior across the cases above.
- On submit:
  - The Zod resolver gates client-side validation; submit-button is disabled while
    `mutation.isPending` is true.
  - Calls `useLogin().mutate(values)`.
  - **On success** with `mustChangePassword: false`: `navigate(safeNextPath(searchParams.get('next')))` with `{ replace: true }`.
  - **On success** with `mustChangePassword: true`: `navigate('/change-password?reason=forced', { replace: true })`. The `?next=` is intentionally dropped (the forced change is unconditional).
  - **On error** `code === 'INVALID_CREDENTIALS'`: render a form-level alert with the
    generic copy. Field-level errors are NOT set (per `REQ-AUTH-009`).
  - **On error** `code === 'RATE_LIMITED'`: render the inline countdown using
    `apiError.retryAfterSeconds`; the submit-button is `disabled` until the countdown
    reaches 0; the form-level alert reads `"Too many requests. Try again in N s."`.
  - **On error** `code === 'VALIDATION_ERROR'`: each `fieldErrors` entry whose `field`
    matches a form field calls `form.setError(field, { message })`; any unmatched
    field-error spills into a top-of-form alert
    `"Some fields couldn't be saved — please contact support."`.
  - **On any other error**: render a generic form-level alert sourced from
    `errorCopy[code].title` (default copy fallback).
- The form is exported with TanStack Query + AuthContext access via `useLogin` (US-03-002).
- **Component tests** in `frontend/src/features/auth/LoginForm.test.tsx`:
  - Submitting an empty form shows Zod-driven field errors (email required, password required).
  - Submitting with a valid email + password issues the network call and navigates to
    `/agents` on success (assert via the standard `LocationProbe` pattern).
  - Submitting with `?next=/chat/abc` in the URL navigates to `/chat/abc` on success.
  - Submitting with `?next=//evil.com/x` navigates to `/agents` (open-redirect mitigation).
  - Submitting with `?next=https://evil.com` navigates to `/agents`.
  - On `401 INVALID_CREDENTIALS` from the server: a form-level alert is rendered with the
    generic copy; neither field has `aria-invalid='true'`.
  - On `429 RATE_LIMITED` with `Retry-After: 3`: the inline alert text contains "3" and
    the submit button is disabled. After 3 seconds the button re-enables (test with
    `vi.useFakeTimers()`).
  - On `LoginResponse.mustChangePassword === true`: the form navigates to
    `/change-password?reason=forced` regardless of the `?next=` value.
- `safeNextPath` unit tests are part of the file or a sibling file.

### Out of scope

- The page wrapper (`LoginPage`) — that's US-03-005.
- A "Remember me" checkbox — not part of the spec.
- Social login / SSO — not part of the spec.
- Brute-force protection UX (e.g., progressive backoff). The backend already rate-limits;
  the frontend honors `Retry-After`.

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.1, §5.3.2 (auth flows), §9.1–9.4 (forms), §10.2
  (per-code routing), §12.1 (LoginPage shape), §15 (`?next=` validation,
  open-redirect prevention).
- `openapi.yaml` `LoginRequest`, `LoginResponse`, `POST /auth/login` error responses.

### Dependencies

- US-03-002 (`useLogin` hook), EPIC-02 (`Input`, `Button`, `useAuth`).

---

## US-03-004 — `ChangePasswordForm` — three fields + live policy checklist + confirm-match

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a `ChangePasswordForm` with `currentPassword`, `newPassword`, `confirmNewPassword`
fields, a live policy checklist that reflects each rule's pass/fail state as the user
types, and a submit gate that requires both the policy AND the confirm match
**So that** the forced first-login change (SW-DESIGN §5.3.1) and the self-initiated change
(via profile menu) share one form component, and the user gets immediate feedback rather
than discovering policy violations only after submit.

### Description

Three fields:

1. `currentPassword` — single-line password input. No client-side policy check (the user
   may have a legacy / weaker password); only required.
2. `newPassword` — single-line password input. Live policy checklist appears below while
   the field is focused (per SW-DESIGN §9.3). Each rule renders with a filled `Check` icon
   when satisfied or an empty placeholder icon otherwise. The rule text is sourced from
   the `evaluatePasswordPolicy` helper (US-03-001) so wording stays in sync.
4. `confirmNewPassword` — must match `newPassword` exactly. Error shown once both are
   non-empty.

Submit button is **disabled** until:
- `currentPassword` is non-empty,
- `evaluatePasswordPolicy(newPassword)` passes every rule,
- `confirmNewPassword === newPassword`.

On submit:
- Calls `useChangeOwnPassword().mutate({ currentPassword, newPassword })`.
- **On success**: success toast + `onSuccess` callback (the page decides where to navigate;
  see US-03-006).
- **On `400 VALIDATION_ERROR`** with `errors[]`: maps server fields to local form fields
  via `setError`. The likely field name from the backend is `newPassword`; if the field
  isn't found locally, fall through to a top-of-form alert.
- **On `401 INVALID_CREDENTIALS`** (the backend rejecting `currentPassword`): map to
  `setError('currentPassword', { message: 'Current password is incorrect.' })`. **No**
  redirect to `/login` (it's already user-typed; surface it inline).
  - Implementation note: the auth middleware's 401 path would normally clear the token
    and emit `auth:logout`. For this specific case we want to suppress that side effect.
    The form's `onError` consumer can intercept; the AuthRedirector's listener fires
    asynchronously, so the form's `setError` is what the user sees. **In practice**, a
    401 here is genuinely an auth failure on the current token (not just the wrong
    currentPassword); the backend distinguishes via the body code. The acceptance
    criterion below pins the contract: a `400 VALIDATION_ERROR` with `field='currentPassword'`
    is the canonical "wrong current password" response.
- **On `429 RATE_LIMITED`**: top-of-form alert with countdown (same UX as `LoginForm`).
- **On any other error**: top-of-form alert using `errorCopy[code].title`.

### Acceptance criteria

- `frontend/src/features/auth/changePasswordSchema.ts` exists with:
  - `import { passwordPolicy } from './password';`
  - `export const changePasswordSchema = z.object({ currentPassword: z.string().min(1, 'Required'), newPassword: passwordPolicy, confirmNewPassword: z.string() }).refine(d => d.newPassword === d.confirmNewPassword, { path: ['confirmNewPassword'], message: 'Passwords do not match.' });`
  - `export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;`
- `frontend/src/features/auth/ChangePasswordForm.tsx` exists and accepts an `onSuccess`
  prop `(() => void) | undefined`.
- Renders three labelled `Input` fields with `type="password"` and appropriate
  `autocomplete` attributes (`current-password`, `new-password`, `new-password`).
- Below the `newPassword` field renders a `<ul>` of three list items — one per rule from
  `evaluatePasswordPolicy(newPasswordWatchValue)`. Each item:
  - has a `data-testid="rule-{key}"` for assertion ergonomics,
  - renders a filled `Check` icon (token `text-success`) when `valid: true`,
  - renders an empty placeholder icon (token `text-text-muted`) when `valid: false`,
  - has accessible text matching the rule's `label`.
- The submit button is `disabled` whenever any of: `currentPassword` is empty,
  `isPasswordPolicySatisfied(newPassword)` is false, or
  `confirmNewPassword !== newPassword`.
- On submit success: shows a success toast (`toast.success('Password changed.', 'pwd-changed')`)
  and calls `onSuccess?.()`.
- On submit failure: error routing per the description (VALIDATION_ERROR → setError,
  RATE_LIMITED → inline countdown, fallback → top alert).
- **Component tests** in `frontend/src/features/auth/ChangePasswordForm.test.tsx`:
  - Submit button starts `disabled`; remains `disabled` while any rule fails.
  - Typing a compliant password and a non-matching confirm keeps the button `disabled`;
    a matching confirm enables it.
  - Live checklist: typing `'A'` shows uppercase-rule satisfied, others not. Typing
    `'Abcdefghij!'` shows all three satisfied.
  - On 204 success: `onSuccess` is called and a success toast appears.
  - On 400 with `errors: [{ field: 'newPassword', message: 'reused' }]`: the inline error
    under `newPassword` reads `'reused'`.
  - On 429 with `Retry-After: 4`: top-of-form alert renders the countdown; submit button
    is disabled.

### Out of scope

- A standalone `<PolicyChecklist />` UI primitive (extracted only if a second consumer
  appears).
- Password-strength heuristics (zxcvbn etc.). The platform policy is the only contract.
- An "eye" icon to toggle password visibility. Could be added later; not requested.

### Design references

- `frontend/design/SW-DESIGN.md` §9.3 (password policy + live checklist), §9.4 (field
  errors), §10.2 (RATE_LIMITED countdown), §12.2 (ChangePasswordPage shape).
- `openapi.yaml` `ChangePasswordRequest`.

### Dependencies

- US-03-001 (`passwordPolicy`, `evaluatePasswordPolicy`, `isPasswordPolicySatisfied`),
  US-03-002 (`useChangeOwnPassword`), EPIC-02 (`Input`, `Button`, `toast`).

---

## US-03-005 — `LoginPage` — `AuthShell` composition + integration tests

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** `pages/LoginPage.tsx` that composes `LoginForm` inside `AuthShell` and exposes
the page-level integration tests for the standard-login happy path, INVALID_CREDENTIALS,
and the rate-limit countdown
**So that** the `/login` route renders end-to-end correctly through the providers and the
EPIC's contractual error UX is verified at the page boundary, not just at the form unit.

### Description

The page is a thin wrapper — heavier behavior lives in `LoginForm`. The page exists so
the route table (`pages/routes.tsx`) has a single import per route and the page-level
integration tests have a stable mount point.

`AuthShell` (US-02-011) already provides the centered card and the `ToastViewport`. The
page renders the form inside the card with a short heading `Sign in` above the form
(visual-only; the field labels remain on the inputs).

### Acceptance criteria

- `frontend/src/pages/LoginPage.tsx` exists, exporting `function LoginPage(): JSX.Element`.
- Renders a heading `Sign in` above the `LoginForm`.
- The page **does not** wrap in `AuthShell` itself — the route table (US-03-007) does that
  via the layout-route element. The page is just `<>{heading}<LoginForm /></>`.
- **Integration tests** in `frontend/src/pages/LoginPage.test.tsx`:
  - **Happy path**: MSW returns 200 with `mustChangePassword: false`. The test renders
    the page under the full provider stack at `initialEntries=['/login']` (via the
    updated `renderWithProviders`). After submit, the `LocationProbe` shows
    `'/agents'`.
  - **Forced password change**: MSW returns 200 with `mustChangePassword: true`. After
    submit, the location is `/change-password?reason=forced`; `useAuth().mustChangePassword`
    is `true`; the token is set.
  - **`?next=` preserved on happy path**: `initialEntries=['/login?next=/chat/abc']`.
    After submit, location is `'/chat/abc'`.
  - **`?next=` dropped on forced change**: same as above but server returns
    `mustChangePassword: true`. Location is `/change-password?reason=forced` (the next
    is NOT honored).
  - **Generic error on invalid credentials**: MSW returns 401 INVALID_CREDENTIALS. The
    form renders the generic alert; neither `input[name=email]` nor
    `input[name=password]` has `aria-invalid='true'` (REQ-AUTH-009 byte-identity
    guarantee at the UI layer).
  - **Rate-limit countdown**: MSW returns 429 with `Retry-After: 3`. With
    `vi.useFakeTimers()`, the alert text contains "3" right after submit; advancing 3
    seconds re-enables the submit button.

### Out of scope

- The `AuthShell` itself (already exists from US-02-011).
- A "Sign up" link — no public signup per SW-DESIGN §15.
- A captcha — not in v1 scope.

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.1, §5.3.2, §12.1 (LoginPage shape).

### Dependencies

- US-03-003 (`LoginForm`).

---

## US-03-006 — `ChangePasswordPage` — `AuthShell` composition + forced-visit banner + integration tests

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** `pages/ChangePasswordPage.tsx` that composes `ChangePasswordForm`, surfaces the
forced-visit banner when `?reason=forced` is in the URL, and routes the user to `/agents`
on success
**So that** the first-time-admin flow from SW-DESIGN §5.3.1 lands correctly and the
self-initiated change from the profile menu lands users back on `/agents` cleanly.

### Description

When the user lands on `/change-password?reason=forced` (i.e., redirected by
`RequireFreshPassword`), the page renders a prominent banner above the form:

> *"Your administrator created this account with a temporary password. Choose a new one
> to continue."*

The banner is non-dismissable while `mustChangePassword === true` — there's no escape
hatch other than completing the change.

The page reads the `?reason=forced` query param on initial render. After a successful
change, the page navigates to `/agents` (replace) regardless of how the user arrived.

### Acceptance criteria

- `frontend/src/pages/ChangePasswordPage.tsx` exists, exporting `function
  ChangePasswordPage(): JSX.Element`.
- Renders a heading `Change password` above the form.
- When `URLSearchParams.get('reason') === 'forced'`: renders a banner above the heading
  with the verbatim copy above. The banner uses the `info` semantic token pair (or the
  `accent` one — implementer's choice) and is **not** dismissable.
- Passes an `onSuccess` callback to `ChangePasswordForm` that calls
  `navigate('/agents', { replace: true })`.
- **Integration tests** in `frontend/src/pages/ChangePasswordPage.test.tsx`:
  - **Forced visit**: rendered at `initialEntries=['/change-password?reason=forced']` with
    a pre-seeded bundle whose `mustChangePassword: true`. The banner is present in the DOM.
    After a successful submit (MSW returns 204), the location moves to `/agents` AND
    `useAuth().mustChangePassword === false` (i.e., the token's gating flag flips locally).
  - **Self-initiated visit**: rendered at `initialEntries=['/change-password']` with a
    pre-seeded bundle whose `mustChangePassword: false`. The banner is **absent**. After
    success, location moves to `/agents`.
  - **Success keeps the existing token valid** (REQ-AUTH-005-aligned): the `token` in
    `useAuth()` is byte-identical before and after the successful change.
  - **First-time-admin redirect** (composition with US-03-007 wiring): when an
    authenticated user with `mustChangePassword: true` tries to navigate to any other
    protected route (e.g., `/agents`), they are redirected to
    `/change-password?reason=forced`. This test goes in US-03-007's integration suite,
    but the page must render correctly when the redirect target lands.

### Out of scope

- "Skip for now" affordance. The forced change is non-skippable per SW-DESIGN §5.3.1.
- A breadcrumb showing the source page. None of the route guards preserve a `from`
  parameter; we navigate to `/agents` unconditionally on success.

### Design references

- `frontend/design/SW-DESIGN.md` §5.3.1, §12.2 (ChangePasswordPage shape).

### Dependencies

- US-03-004 (`ChangePasswordForm`).

---

## US-03-007 — Routes wiring (`RequireGuest` / `RequireFreshPassword`) + session-expired toast

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** `/login` and `/change-password` registered in `pages/routes.tsx` with the
correct guards, `RequireFreshPassword` applied to every other protected route, and the
"Your session expired" toast wired into the `AuthRedirector`'s `auth:logout` handler when
the reason is `token-rejected`
**So that** the first-time-admin redirect loop and the standard sign-out flow work
end-to-end, and the integration tests listed in EPIC-03's scope all pass.

### Description

This story closes EPIC-03 by wiring the routes and the few remaining cross-cutting bits:

1. `/login` route under `<AuthShell>` with `<RequireGuest>` (already-authed users bounce
   to `/agents` per SW-DESIGN §5.1).
2. `/change-password` route under `<AuthShell>` with `<RequireAuth>` only — NOT
   `<RequireFreshPassword>` (otherwise the user is redirected to the same page they're
   trying to render).
3. Every other protected route nests under `<AppShell>` AND `<RequireFreshPassword>` (the
   layout-route element is `<RequireFreshPassword><AppShell /></RequireFreshPassword>`,
   OR the guard wraps the outlet — implementer's choice).
4. The `<AuthRedirector />` listener on `auth:logout` is enhanced: when
   `event.detail.reason === 'token-rejected'`, emit
   `toast.info('Your session expired — sign in again.', 'session-expired')` BEFORE
   triggering `signOut`. When `reason === 'token-expired'` (proactive short-circuit),
   the toast is **not** fired — the in-app expiry banner already warned the user.

### Acceptance criteria

- `frontend/src/pages/routes.tsx` is updated:
  - The `/login` route is registered under the `<AuthShell>` layout route, wrapped in
    `<RequireGuest>`.
  - The `/change-password` route is registered under the `<AuthShell>` layout route,
    wrapped in `<RequireAuth>` (not `<RequireFreshPassword>`).
  - Every other route is registered under the `<AppShell>` layout route, and
    `<RequireAuth>` + `<RequireFreshPassword>` are applied at the layout level (so
    nested children inherit them).
  - The placeholder `HomePlaceholder` route stays at `/` under the protected stack; the
    `NotFoundPlaceholder` route stays at `*`.
- `<AuthRedirector />` (US-02-007) is updated so its `auth:logout` handler:
  - Reads `event.detail.reason` (a string union `'token-expired' | 'token-rejected'`).
  - On `'token-rejected'`: calls `toast.info('Your session expired — sign in again.', 'session-expired')` once before invoking `signOut(buildLoginNext(...))`.
  - On `'token-expired'` (proactive expiry already warned via the in-app banner):
    silently invokes `signOut`.
- **Integration tests** in `frontend/src/shared/auth/sessionExpired.integration.test.tsx`:
  - With a seeded valid bundle, a request that returns `401 INVALID_CREDENTIALS` results
    in: the location moving to `/login?next=<encoded current path>`, AND
    a `session-expired`-keyed toast being visible (asserted via the
    `ToastViewport` which is part of the layout shells).
  - With a seeded **expired** bundle (so the client-side short-circuit fires before the
    network), the same auth:logout is processed but **no** `session-expired` toast is
    rendered (the proactive expiry banner already warned the user).
- **Integration tests** in `frontend/src/pages/routes.integration.test.tsx`:
  - **First-time-admin redirect**: render the router at `initialEntries=['/']` with a
    seeded bundle whose `mustChangePassword: true`. The location resolves to
    `/change-password?reason=forced` (the protected `/` is gated by
    `RequireFreshPassword`).
  - **Already-authed visiting `/login`**: render at `initialEntries=['/login']` with a
    seeded valid bundle. Location resolves to `/agents` (RequireGuest redirect).
  - **Already-authed visiting `/change-password`**: render at
    `initialEntries=['/change-password']` with a seeded valid bundle. The page renders
    (no redirect loop). The forced-visit banner is absent since `?reason=forced` is not
    in the URL.
  - **Logout end-to-end**: render `<Topbar />` inside the full router stack with a
    seeded bundle; click `Profile menu → Sign out` (uses `useLogout` from US-03-002 via
    a small wrapper — note: the existing `Topbar` calls `signOut()` directly; this story
    updates `Topbar` to call `useLogout().mutate()` instead, so the
    `POST /auth/logout` call is fired). After the mutation settles, the location is
    `/login` and the `ToastViewport` shows no error toast (the failure path is silent).
- The existing `Sidebar.test.tsx`, `Topbar.test.tsx`, and `routes.tsx`-derived smoke
  tests still pass after the rewiring (no regression).
- The placeholder `/agents` route does NOT need to exist yet — `RequireFreshPassword`'s
  forced-redirect test can target `/` instead (which is configured to render
  `HomePlaceholder` for EPIC-02 compatibility). EPIC-05 lands the real `/agents` page.

### Out of scope

- The `/agents` page itself (EPIC-05).
- Light theme (`TBD-F1`).
- Cross-tab logout sync (explicitly out per SW-DESIGN §6.4).

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route map), §5.2 (guards), §5.3.1 (first-time admin
  flow), §5.3.3 (token expiry), §5.3.4 (logout), §6.5 (role gating, already wired).

### Dependencies

- US-03-005 (`LoginPage`), US-03-006 (`ChangePasswordPage`), US-03-002 (`useLogout` used by the
  rewired `Topbar`).

---

## Summary

| ID         | Title                                                                            | Priority | Status |
|------------|----------------------------------------------------------------------------------|----------|--------|
| US-03-001  | `passwordPolicy` Zod schema + per-rule live evaluator                            | MUST     | Done   |
| US-03-002  | Auth mutation hooks: `useLogin`, `useLogout`, `useChangeOwnPassword`             | MUST     | Done   |
| US-03-003  | `LoginForm` — email + password + `?next=` validation + 429 countdown             | MUST     | Done   |
| US-03-004  | `ChangePasswordForm` — three fields + live policy checklist + confirm-match      | MUST     | Done   |
| US-03-005  | `LoginPage` — `AuthShell` composition + integration tests                        | MUST     | Done   |
| US-03-006  | `ChangePasswordPage` — `AuthShell` composition + forced-visit banner + integration tests | MUST | Done   |
| US-03-007  | Routes wiring (`RequireGuest` / `RequireFreshPassword`) + session-expired toast  | MUST     | Done   |

EPIC-03 is **Done** when all seven stories above are `Done`. The next step is then
EPIC-04 (Catalog pages — Tools & MCP servers), which delivers the first read-only
authenticated surface and unlocks the agent form's tool / MCP pickers in EPIC-05.
