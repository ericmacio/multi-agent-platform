# EPIC-11 canonical-states audit

This doc records the state-coverage sweep run for **US-11-005**. Every list and
detail page shipped by EPIC-04..EPIC-10 was inspected for the **four canonical
states**:

1. **Populated** — the golden path, tested by an existing integration test.
2. **Empty** — `<EmptyState>` when the query resolves with zero items. Documented
   as "N/A" when the aggregate cannot be empty.
3. **Loading** — `<LoadingList>` for list pages, page-specific `Skeleton`
   composition for detail pages. Never a full-page spinner.
4. **Error** — `<Card role="alert">` with `errorCopy[code]` copy + Retry.
   `FORBIDDEN` and `NOT_FOUND` route to `<ForbiddenState>` / `<NotFoundState>`
   instead of the generic card where the underlying query can produce them.

Legend: ✅ present · 🆕 added by this sweep · N/A not applicable.

## Coverage matrix

| Page                        | Populated | Empty | Loading         | Error       | 403/404 special |
|-----------------------------|-----------|-------|------------------|-------------|-----------------|
| `ToolsPage`                 | ✅        | ✅    | Skeleton block   | ✅          | N/A (list)      |
| `McpServersPage`            | ✅        | ✅    | Skeleton block   | ✅          | N/A (list)      |
| `AgentsPage`                | ✅        | ✅    | Skeleton block   | ✅          | N/A (list)      |
| `AgentCreatePage`           | ✅        | N/A   | N/A (form)       | ✅ (form)   | N/A             |
| `AgentDetailPage`           | ✅        | N/A   | 4× `Skeleton`    | ✅          | 🆕 `NotFoundState` |
| `AgentEditPage`             | ✅        | N/A   | 4× `Skeleton`    | ✅          | N/A (edit)      |
| `ChatPage` (shell)          | ✅        | ✅    | Two-pane spinner | ✅          | N/A (shell)     |
| `ChatNewPage`               | ✅        | ✅    | Skeleton block   | ✅          | N/A             |
| `ConversationPage`          | ✅        | N/A   | Topbar skeleton  | ✅          | ✅ (whole-panel `EmptyState`) |
| `AdminUsersPage`            | ✅        | ✅    | 🆕 `LoadingList` | ✅          | N/A (list)      |
| `AdminUserCreatePage`       | ✅        | N/A   | N/A (form)       | ✅ (form)   | N/A             |
| `AdminUserDetailPage`       | ✅        | N/A   | 4× `Skeleton`    | ✅          | 🆕 `NotFoundState` |
| `AdminApiKeysPage`          | ✅        | ✅    | 🆕 `LoadingList` | ✅          | N/A (list)      |
| `AdminRateLimitPage`        | ✅        | N/A   | 2× `Skeleton`    | ✅ + Retry  | N/A (single row)|

## Notes on scope

- `ChatNewPage` and `ChatPage` shell are covered by EPIC-06 integration tests.
- `AdminRateLimitPage` empty state is intentionally `N/A`: the aggregate is a
  single seeded row (see EPIC-10 US-10-001 out-of-scope).
- The auth pages (`LoginPage`, `ChangePasswordPage`) use the form-scoped alert
  pattern from EPIC-03, which is deliberately different from the "page states"
  pattern documented here (see US-11-005 out-of-scope).
- `HomePlaceholder` is a placeholder for a future dashboard with no real data
  yet — excluded from the sweep.

## Primitives referenced

- `@/shared/ui/EmptyState` — zero-items surface.
- `@/shared/ui/LoadingList` — N Card-wrapped Skeleton rows (`testId` prop).
- `@/shared/ui/ForbiddenState` — in-content 403 (`role="alert"`).
- `@/shared/ui/NotFoundState` — in-content 404 (`role="alert"`).
- Full-page fallbacks (unchanged): `pages/ForbiddenPage`, `pages/NotFoundPlaceholder`.

## Follow-ups intentionally deferred

- Migrating the `ConversationView` 404 branch from `EmptyState` (whole-panel
  centered) to `NotFoundState` — the current UI is centered inside the
  conversation panel and switching would change the visual layout materially.
  Left as-is; the a11y semantics are equivalent because both surfaces render
  meaningful copy and a "back to list" affordance.
- No dead imports remain in the touched files. `AdminUserDetailPage` dropped
  its `EmptyState` import once the 404 branch migrated to `NotFoundState`;
  `AgentDetailPage` still imports `EmptyState` for its "Recent conversations"
  empty section.
