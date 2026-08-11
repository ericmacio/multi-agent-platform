# EPIC-06-US.md — User stories for EPIC-06 (Conversations & messages — non-streaming surface)

This file lists the user stories that deliver **EPIC-06 — Conversations & messages
(non-streaming surface)** of the frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-06 delivers the **non-streaming half** of the chat surface: an end-user can start a
conversation with one of their agents, browse the conversation list, open a past
conversation and view its persisted messages, rename it, and delete it. The composer is a
**disabled placeholder** until EPIC-07 wires the SSE send-message flow. This split keeps
the list / view / rename / delete surface reviewable independently of the SSE bridge.

Two load-bearing artifacts ship in this EPIC:

1. The six conversation/message hooks in `features/conversations/api.ts` — they are the
   single API surface for every page that touches conversations (AgentDetailPage's
   "Recent conversations" panel from EPIC-05 finally gets a live data source here).
2. The `ConversationView` shell — the right pane of `ChatPage`, with the topbar
   (inline-editable title, agent link, message-count "X / 64", overflow menu) and the
   virtualized message list. EPIC-07 plugs the SSE composer into this shell.

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-06-<nnn>` — `06` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All seven stories are `MUST` (the non-streaming
  surface is a gate on EPIC-07 — the composer cannot ship without the topbar, message
  list, and conversation list around it).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                        | Priority | Status | Depends on                       |
|------------|----------------------------------------------------------------------------------------------|----------|--------|----------------------------------|
| US-06-001  | Conversation / Message types + Zod schemas + six conversation hooks (incl. optimistic updates) | MUST   | Done   | EPIC-02                          |
| US-06-002  | `MessageBubble` + virtualized `MessageList` (USER / ASSISTANT styling + 64-row scroll)        | MUST     | Done   | US-06-001                        |
| US-06-003  | `EditTitleDialog` + `DeleteConversationDialog` (modal-based edits with optimistic flow)       | MUST     | Done   | US-06-001                        |
| US-06-004  | `ConversationList` + `ConversationListItem` (left-pane list with active highlighting)         | MUST     | Done   | US-06-001                        |
| US-06-005  | `ConversationView` — topbar (title / agent link / count / overflow) + message list + composer placeholder | MUST | Done | US-06-002, US-06-003           |
| US-06-006  | `ChatPage` layout (two-pane) + `ChatNewPage` (agent picker) + routes wiring + sidebar entry   | MUST     | Done   | US-06-004, EPIC-05 (US-05-001)   |
| US-06-007  | `ConversationPage` composition + `AgentDetailPage` "Recent conversations" wire-up + integration tests | MUST | Done   | US-06-005, US-06-006             |

---

## US-06-001 — Conversation / Message types + Zod schemas + six conversation hooks

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** the six typed hooks against the `/conversations` collection — `useConversations`
(infinite, optional `agentId` filter), `useConversation`, `useMessages` (single page sized
at 64 to fit the conversation cap), `useStartConversation`, `useUpdateConversationTitle`
(optimistic per SW-DESIGN §7.5), `useDeleteConversation` (optimistic) — together with the
`updateConversationSchema` Zod schema mirroring `UpdateConversationRequest`
**So that** every consumer (`ChatPage` left pane, `ConversationView` topbar,
`ChatNewPage`, `AgentDetailPage`'s "Recent conversations" panel) reads its HTTP plumbing
and rollback contracts from one place, and the optimistic update paths are tested once
at the hook level.

### Description

Per SW-DESIGN §7.4 / §7.5 / §7.6, the six hooks split cleanly:

- **`useConversations({ agentId? })`** — `useCursorInfiniteQuery` against
  `GET /conversations` with an optional `agentId` query parameter. Default `pageSize` 20
  per openapi. `staleTime: 0` (the list can change from message activity per SW-DESIGN
  §7.6). `refetchOnWindowFocus: true`.
- **`useConversation(conversationId)`** — `useQuery` against
  `GET /conversations/{conversationId}`. Disabled when `conversationId` is empty.
- **`useMessages(conversationId)`** — `useQuery` (NOT infinite) against
  `GET /conversations/{conversationId}/messages?pageSize=64`. The 64-message cap from
  `REQ-CHAT-010` means a single round-trip fits the entire conversation. `staleTime: 0`,
  `refetchOnWindowFocus: false` (per SW-DESIGN §7.6 — updated explicitly after SSE
  `completed`).
- **`useStartConversation`** — `useMutation` against
  `POST /conversations { agentId }`. On success: invalidates `qk.conversations.all()`
  AND `qk.conversations.list(agentId)` so the list refetches. Returns the created
  `Conversation` so the caller can `<Navigate>` to its id.
- **`useUpdateConversationTitle`** — `useMutation` against
  `PATCH /conversations/{conversationId} { title }`. **Optimistic** per §7.5:
  - `onMutate`: snapshot the current `qk.conversations.byId(id)` cache, the
    `qk.conversations.list(null)` cache, and any `qk.conversations.list(agentId)`
    caches in flight. Patch each in place with the new title.
  - `onError`: roll back via the snapshots.
  - `onSettled`: invalidate `qk.conversations.byId(id)` and `qk.conversations.all()` to
    re-sync.
- **`useDeleteConversation`** — `useMutation` against
  `DELETE /conversations/{conversationId}`. **Optimistic** per §7.5:
  - `onMutate`: snapshot the relevant list caches; remove the conversation from each.
  - `onError`: roll back via the snapshots.
  - `onSettled`: invalidate `qk.conversations.all()`.

The Zod schema mirrors only the openapi-documented constraints (`title` ≥1, ≤32).
`CreateConversationRequest` is a single `agentId: uuid` field — no schema needed at the
hook level; the `ChatNewPage` agent picker passes a validated id.

### Acceptance criteria

- `frontend/src/features/conversations/schema.ts` exists with:
  - `import { components } from '@/generated/schema';`
  - `export type Conversation = components['schemas']['Conversation'];`
  - `export type Message = components['schemas']['Message'];`
  - `export type MessageRole = components['schemas']['MessageRole'];`
  - `export type UpdateConversationRequest = components['schemas']['UpdateConversationRequest'];`
  - `export const updateConversationSchema = z.object({ title: z.string().min(1).max(32) });`
  - `export type UpdateConversationValues = z.infer<typeof updateConversationSchema>;`
- The schema is byte-aligned with `openapi.yaml.UpdateConversationRequest`.
- `frontend/src/features/conversations/api.ts` exists with:
  - `export function useConversations(opts?: { agentId?: string; pageSize?: number }): UseInfiniteQueryResult<ConversationPage, ApiError>`
  - `export function useConversation(conversationId: string | undefined): UseQueryResult<Conversation, ApiError>`
  - `export function useMessages(conversationId: string | undefined): UseQueryResult<Message[], ApiError>`
  - `export function useStartConversation(): UseMutationResult<Conversation, ApiError, { agentId: string }>`
  - `export function useUpdateConversationTitle(conversationId: string): UseMutationResult<Conversation, ApiError, UpdateConversationRequest>`
  - `export function useDeleteConversation(): UseMutationResult<void, ApiError, { conversationId: string; agentId?: string }>`
- Query keys come from `qk.conversations.*` (US-02-004). The factory already exposes
  `all`, `list(agentId?)`, `byId(id)`, `messages(id)` — no additions needed.
- `useConversations` uses `useCursorInfiniteQuery` (US-02-005); `getNextPageParam` reads
  `lastPage.nextCursor`; the optional `agentId` is wired through both the query key
  (`qk.conversations.list(agentId)`) and the request query parameter.
- `useMessages` calls `GET /conversations/{id}/messages?pageSize=64`; the hook unwraps
  the page envelope and selects `.items` (returns `Message[]`).
- **Optimistic title update** (`useUpdateConversationTitle`):
  - `onMutate` cancels in-flight queries for `qk.conversations.byId(id)`, then sets the
    new title on the cached `Conversation` (preserving the rest of the fields).
  - It also patches every list cache (`qk.conversations.list(null)` plus the
    agent-filtered variant if a non-`null` `agentId` exists in the cache) to update the
    item with the matching id in place.
  - `onError` restores all snapshots verbatim.
  - `onSettled` invalidates `qk.conversations.byId(id)` and `qk.conversations.all()`.
- **Optimistic delete** (`useDeleteConversation`):
  - `onMutate` cancels in-flight list queries, then removes the conversation from each
    snapshot.
  - `onError` restores all snapshots.
  - `onSettled` invalidates `qk.conversations.all()`. The `qk.conversations.byId(id)`
    cache is **not** invalidated — by the time settle fires, the conversation may be
    gone server-side; readers should rely on the list refetch.
- `useStartConversation.onSuccess`: invalidates `qk.conversations.all()` (the
  `agentId`-filtered variant is covered by the `all()` invalidation since list keys
  start with `['conversations', 'list', …]`).
- `useConversation` and `useMessages` are `disabled` when the id is `undefined` /
  empty; no network request fires.
- **Unit tests** in `frontend/src/features/conversations/schema.test.ts`:
  - `updateConversationSchema.safeParse({ title: '' })` fails on `title.min`.
  - `{ title: 'x'.repeat(33) }` fails on `title.max(32)`.
  - `{ title: 'My chat' }` succeeds.
- **Unit tests** in `frontend/src/features/conversations/api.test.tsx` (MSW + `renderHook`
  under the standard provider stack):
  - **`useConversations` happy path**: MSW returns `{ items: [c1], nextCursor: 'c2' }`;
    `data.pages[0].items[0]` is `c1`; `fetchNextPage` fires a second request with
    `?cursor=c2`.
  - **`useConversations({ agentId })` includes the filter**: assert the MSW handler sees
    `?agentId=<uuid>`; the cache key is `qk.conversations.list(agentId)`.
  - **`useConversation(undefined)`**: query is `disabled`; no network request fires.
  - **`useMessages` returns the items array** of the 200 response.
  - **`useMessages` requests `pageSize=64`**: MSW handler asserts the query string.
  - **`useStartConversation` happy path**: `mutateAsync({ agentId })` resolves with the
    spec `Conversation`; the conversations list cache is invalidated (verify via a
    sibling `useConversations` that re-fetches after the mutation settles).
  - **`useUpdateConversationTitle` optimistic**: with a pre-seeded list cache, calling
    `mutate({ title: 'new' })` updates the cache **before** the network responds; on
    500, the cache is rolled back to the original title.
  - **`useUpdateConversationTitle` optimistic on the detail cache**: with a pre-seeded
    `qk.conversations.byId(id)` cache, the title is patched in place before the
    network responds; rollback on error works for the detail cache too.
  - **`useDeleteConversation` optimistic**: with a pre-seeded list cache containing
    `[c1, c2, c3]`, calling `mutate({ conversationId: c2.id })` immediately removes
    `c2` from the cache; on 500, `c2` is restored.
  - **`useDeleteConversation` on 204**: the list is invalidated on settle (the
    server-confirmed state replaces the optimistic state).

### Out of scope

- Server-Sent Events / message-send hook — that's EPIC-07 (`useChatStream`).
- A "duplicate conversation" hook — not in the spec.
- Server-side conversation search — not exposed by the API; client-side filter inside
  `ConversationList` lands in US-06-004.
- Polling for new messages — explicitly out; the chat surface mutates the cache itself
  via SSE in EPIC-07.

### Design references

- `frontend/design/SW-DESIGN.md` §7.4 (`qk` factory), §7.5 (optimistic updates table),
  §7.6 (stale-time table), §9.2 (constraint mirroring), §10.2 (per-code routing).
- `openapi.yaml` `GET /conversations`, `POST /conversations`, `GET/PATCH/DELETE
  /conversations/{conversationId}`, `GET /conversations/{conversationId}/messages`,
  `Conversation`, `ConversationPage`, `Message`, `MessagePage`,
  `UpdateConversationRequest`.

### Dependencies

- EPIC-02 (`api` client, `unwrap`, `qk`, `queryClient`, `useCursorInfiniteQuery`,
  `ApiError`).

---

## US-06-002 — `MessageBubble` + virtualized `MessageList`

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user opening a past conversation
**I want** a `MessageList` that renders every persisted message of the conversation —
USER bubbles right-aligned, ASSISTANT bubbles left-aligned, both as plain text — with
virtualization via `@tanstack/react-virtual` so that scrolling 64 messages with long
bodies stays smooth, plus an end-of-list marker so I know the conversation is fully
loaded
**So that** I can read my chat history at a glance, scrolling does not jank when bubbles
contain multi-paragraph content, and the same component can host the streaming
ASSISTANT bubble that EPIC-07 will append.

### Description

Per SW-DESIGN §12.8 and §15 (no Markdown in v1), each message renders as **plain text**
inside a styled bubble. Long content uses CSS `whitespace: pre-wrap` so newlines are
preserved. Markdown / code-fence rendering is explicitly deferred (TBD-F2).

Per SW-DESIGN §12.8, the message list is virtualized with `@tanstack/react-virtual`
even though the cap is 64 — long messages cause scrollback jank otherwise. The library
is added to `package.json` in this story (a one-line addition; it is the same library
already mentioned in the EPICS scope).

`MessageBubble` accepts a `Message` plus an optional `variant?: 'streaming' | 'stopped'`
prop reserved for EPIC-07 (`streaming` renders the blinking caret marker; `stopped`
greys out the bubble per SW-DESIGN §8.4). In EPIC-06 the variant is unused; the API is
already in place so EPIC-07 is purely additive.

The list also handles the `useMessages` lifecycle: loading skeletons, error state with
retry, and the **empty-conversation** state ("No messages yet — say hi to your agent.").

### Acceptance criteria

- `frontend/src/features/conversations/MessageBubble.tsx` exists with the prop shape:
  - `interface MessageBubbleProps { message: Message; variant?: 'streaming' | 'stopped'; }`
- Renders:
  - A row container with `justify-end` when `message.role === 'USER'`, `justify-start`
    when `'ASSISTANT'`.
  - A bubble with:
    - USER: accent background (`--color-accent-bg`), accent border, text aligned right.
    - ASSISTANT: elevated background (`--color-bg-elevated`), default border, text
      aligned left.
  - Content rendered as plain text with `whitespace: pre-wrap`, max width 70% of the
    list container.
  - A timestamp caption under the bubble using `formatRelative(message.createdAt)`
    (US-02-001); muted color.
  - When `variant === 'streaming'`: a 1-px blinking caret at the end of the content
    (CSS animation, 1 s blink, respects `prefers-reduced-motion`).
  - When `variant === 'stopped'`: the bubble is rendered with reduced opacity
    (`--color-text-disabled` tone) plus a small "(stopped)" caption inside the bubble.
- The bubble container has `data-message-id={message.id}` so EPIC-07's streaming hook
  can patch it cleanly.
- `frontend/src/features/conversations/MessageList.tsx` exists with the prop shape:
  - `interface MessageListProps { conversationId: string; }`
- Internally:
  - Calls `useMessages(conversationId)` (US-06-001).
  - Uses `@tanstack/react-virtual`'s `useVirtualizer` over the flat `Message[]` array
    with dynamic row heights (estimated 80 px; measured on mount).
  - The scroll container has `role="log" aria-live="polite" aria-relevant="additions"`
    so EPIC-07's `completed` event is announced. The EPIC-06 message list is static —
    the attributes are wired in now so EPIC-07 is purely additive.
  - The list **auto-scrolls to the bottom** on mount and whenever the message count
    grows (so the latest message is in view).
- States:
  - `isPending`: 4 skeleton bubbles alternating left/right alignment.
  - `isError` with `error.status === 404`: render an `<EmptyState>` "Conversation not
    found" with a "Back to chats" link to `/chat`.
  - Other `isError`: inline error state with `Retry` button (calls `query.refetch()`).
  - Empty (`items.length === 0`): inline `<EmptyState>` "No messages yet" with caption
    "Send a message below to start the conversation." (The composer below will be a
    placeholder in EPIC-06; the caption already anticipates EPIC-07.)
- The list renders nothing when the cap is reached (the "Conversation full" banner is
  rendered by `ConversationView`'s composer area in US-06-005, not by the list).
- **Component tests** in `frontend/src/features/conversations/MessageBubble.test.tsx`:
  - USER role: row is right-aligned; bubble has the accent-bg class.
  - ASSISTANT role: row is left-aligned; bubble has the elevated-bg class.
  - Multi-line content: a `\n` in the content renders as a visible line break.
  - `variant='streaming'`: the blinking caret element is present.
  - `variant='stopped'`: the "(stopped)" caption is present; the bubble has reduced
    opacity.
- **Component tests** in `frontend/src/features/conversations/MessageList.test.tsx`:
  - 64-message MSW response: every bubble in the rendered window is in the DOM (the
    virtualizer renders only the visible window — assert that the data is present in
    the cache and that scrolling reveals later items).
  - Empty conversation: the "No messages yet" empty state renders.
  - Error: 500 surfaces the retryable error state.
  - 404 from `useMessages`: the "Conversation not found" empty state renders with the
    Back link to `/chat`.
  - `role="log"` is present on the scroll container.
  - Auto-scroll: after first paint, the scroll container's `scrollTop` is at the
    bottom (verified via the virtualizer's `scrollToIndex` having been called with the
    last index).

### Out of scope

- Markdown rendering, code-block syntax highlighting, link auto-detection — all
  deferred per SW-DESIGN §15 and TBD-F2.
- Message editing or deletion at the message level — the API does not expose per-message
  mutations.
- Message reactions, copy-to-clipboard affordance — not in v1.
- Per-message timestamps in absolute form — relative form is sufficient; a hover
  tooltip with absolute time is a polish item for EPIC-11.

### Design references

- `frontend/design/SW-DESIGN.md` §11.5 (animation budget — the 1 s caret blink fits the
  budget), §11.6 (a11y — `role="log"`), §12.8 (`ConversationPage` message list shape),
  §15 (plain text only).
- `openapi.yaml` `Message`, `MessagePage`, `MessageRole`.

### Dependencies

- US-06-001 (`useMessages`, `Message` type); EPIC-02 (`Skeleton`, `EmptyState`,
  `Button`, `formatRelative`).

---

## US-06-003 — `EditTitleDialog` + `DeleteConversationDialog`

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user owning a conversation
**I want** an `EditTitleDialog` to rename a conversation (with the 1–32 char policy
mirrored from `UpdateConversationRequest`) and a `DeleteConversationDialog` to delete it
with a single-confirm pattern, both wired to the optimistic mutation hooks from US-06-001
**So that** I can keep my chat history organized without page reloads, and a misclick
on Delete never destroys a conversation I cared about.

### Description

The two dialogs are **paired in one story** because both are `Modal`-wrapped, both
consume one of the optimistic mutation hooks from US-06-001, and the integration tests
for "optimistic + rollback on error" are the load-bearing part of both.

`EditTitleDialog` follows the standard form pattern: `react-hook-form` +
`zodResolver(updateConversationSchema)`, a single `Input` with a character counter
`N/32`, Save / Cancel buttons. Submit calls
`useUpdateConversationTitle(conversationId).mutateAsync({ title })`; on success the
dialog closes; on error the dialog stays open and renders an inline alert. The
optimistic update is already wired in the hook, so the calling page (left-pane list,
topbar) sees the new title immediately.

`DeleteConversationDialog` is a **single-confirm** dialog (no "type to confirm" gate —
the cascade is much smaller than agent deletion, which removes every conversation
under the agent; this only removes one conversation). The body explains the
consequence:

> *"This conversation will be permanently deleted, along with every message in it. This
> cannot be undone."*

On confirm, the dialog calls `useDeleteConversation().mutateAsync({ conversationId,
agentId })`. The optimistic remove from the list is wired in the hook, so the dialog
closes immediately. On error, the dialog **re-opens** (the consumer page restores it
via the rollback-aware error toast — the dialog itself doesn't persist its own state).

### Acceptance criteria

- `frontend/src/features/conversations/EditTitleDialog.tsx` exists with the prop shape:
  - `interface EditTitleDialogProps { conversation: Conversation | null; open: boolean; onClose: () => void; }`
- Renders a `Modal` with:
  - Heading: `Rename conversation`.
  - A single `Input` bound to a `react-hook-form` field `title`, with the
    `zodResolver(updateConversationSchema)` resolver and a character counter `N/32`.
  - Default value: `conversation.title ?? ''`.
  - Footer: `Cancel` + `Save` (the latter has `loading={mutation.isPending}` and is
    disabled when the form is invalid or `mutation.isPending`).
- On submit success: closes the dialog (`onClose()`) — the optimistic patch from the
  hook is already visible on the list and topbar.
- On submit error: an inline alert renders inside the dialog with
  `errorCopy[code].title || errorCopy.fallback.title`; the dialog stays open so the
  user can retry; the optimistic rollback in the hook has already restored the original
  title on the list and topbar.
- The dialog is focus-trapped (already enforced by `<Modal>`); the `Input` receives
  focus on open.
- `frontend/src/features/conversations/DeleteConversationDialog.tsx` exists with the
  prop shape:
  - `interface DeleteConversationDialogProps { conversation: Conversation | null; open: boolean; onClose: () => void; onDeleted: (conversation: Conversation) => void; }`
- Renders a `Modal` with:
  - Heading: `Delete this conversation?`
  - Body: the cascade-warning copy verbatim above + the conversation's title (or
    `chat-<uuid-short>` fallback when `title === null`).
  - Footer: `Cancel` + `Delete` (destructive variant). Delete loading while the
    mutation is in flight.
- On success: calls `onDeleted(conversation)` then `onClose()`. The optimistic remove
  from the list is wired in the hook.
- On error: inline alert renders inside the dialog with `errorCopy[code].title`; the
  dialog stays open; the optimistic rollback has already restored the conversation
  on the list.
- **Component tests** in `frontend/src/features/conversations/EditTitleDialog.test.tsx`:
  - **Default value**: opens with the conversation's current title pre-filled.
  - **Empty title**: submit is disabled when `title.length === 0`.
  - **Title too long**: typing 33 chars shows the Zod error under the field; submit
    disabled.
  - **Happy path**: a 200 from MSW closes the dialog and the optimistic patch is
    visible on a sibling component (verify via a `useConversation` snapshot in the
    same test).
  - **Server 500**: the dialog stays open with an inline alert; the optimistic title
    is rolled back on the sibling cache.
  - **Server 400 `VALIDATION_ERROR` with `errors: [{ field: 'title' }]`**: the inline
    alert renders the field-level message.
- **Component tests** in
  `frontend/src/features/conversations/DeleteConversationDialog.test.tsx`:
  - **Title fallback**: when `conversation.title === null`, the body renders
    `chat-<uuid-short>` (first 8 chars of the id).
  - **Confirm fires delete**: clicking Delete calls `DELETE
    /conversations/{id}` (MSW assertion); on 204, `onDeleted` is called and the
    dialog closes.
  - **Optimistic remove**: with a pre-seeded list cache, the conversation is removed
    from the cache before the network responds.
  - **Server 500**: the inline alert renders; the dialog stays open; the conversation
    is restored on the list cache (rollback verified).
  - **`404 NOT_FOUND` (deleted in another tab)**: the dialog renders the inline alert
    but `onDeleted` is **not** called; on close, the list cache is invalidated so the
    stale item is removed.

### Out of scope

- Multi-select / bulk delete — not in v1.
- "Restore last deleted conversation" affordance — not in the spec.
- Audit-log surface — not in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §7.5 (optimistic table — both these mutations are
  listed as optimistic), §9.1 (forms), §10.2 (per-code routing), §12.8
  (`ConversationPage` topbar — the entry points for both dialogs).
- `openapi.yaml` `UpdateConversationRequest`, `DELETE
  /conversations/{conversationId}`, `PATCH /conversations/{conversationId}`.

### Dependencies

- US-06-001 (`updateConversationSchema`, `useUpdateConversationTitle`,
  `useDeleteConversation`); EPIC-02 (`Modal`, `Input`, `Button`, alert primitive).

---

## US-06-004 — `ConversationList` + `ConversationListItem` (left-pane list)

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the left pane of `ChatPage` to show all my conversations — paginated
cursor-style, with the active conversation highlighted, a "+" button to start a new
chat, and a "Load more" affordance for older entries
**So that** I can switch between past conversations without page reloads and start a
new chat from the same surface that lists the old ones.

### Description

Per SW-DESIGN §12.6, the left pane is 320 px wide on `>= md` viewports and collapses
to 64 px on smaller ones. The collapsed form shows only the active item's first letter
as an avatar-style chip — the EPICs scope considers the collapsed form a polish item
for EPIC-11, so this story ships the **expanded** form only. The collapse interaction
is added in EPIC-11.

`ConversationListItem` renders:

- The conversation title, falling back to `chat-<uuid-short>` when `title === null`
  (matching the openapi default behavior).
- The agent name on a secondary line (the agent name is **not** included in the
  `Conversation` schema — the item dereferences the agent via the EPIC-05 `useAgent`
  hook; cache hit is the common case because the agent list was loaded for
  `AgentList`).
- A message-count chip `X / 64` (muted when X < 64; warning tone when X === 64).
- A relative `updatedAt` caption.
- The active state when `conversationId === currentRouteConversationId` — accent
  border on the left edge per SW-DESIGN §12.6.

The list filters via a search `Input` (case-insensitive substring on
`title || ''` plus `agentName || ''`). The filter is **client-side only** — the
openapi does not expose a server-side title-search parameter.

### Acceptance criteria

- `frontend/src/features/conversations/ConversationListItem.tsx` exists with the prop
  shape:
  - `interface ConversationListItemProps { conversation: Conversation; active: boolean; onClick: () => void; }`
- Renders:
  - A focusable button-style row (`role="option" aria-selected={active}`).
  - Title: `conversation.title ?? 'chat-' + conversation.id.slice(0, 8)`, with a
    `font-mono` style on the fallback prefix to distinguish it visually.
  - Secondary line: the agent name (resolved via `useAgent(conversation.agentId)`).
    While that hook is pending, render `Loading agent…` muted; on error, render
    `Unknown agent` muted (do not block the row).
  - Message-count chip: `${conversation.messageCount} / 64`. When `messageCount ===
    64`, the chip uses the `warning` tone.
  - Relative `updatedAt` caption using `formatRelative()`.
  - Active state: a 2-px accent border on the left edge + `--color-bg-elevated`
    background.
- `frontend/src/features/conversations/ConversationList.tsx` exists with the prop
  shape:
  - `interface ConversationListProps { activeConversationId?: string; onSelect: (id: string) => void; onNew: () => void; }`
- Internally:
  - Renders a header with title `Chats` + a "+" `Button` calling `onNew()`.
  - Renders a search `Input` filtering case-insensitive against
    `(title || '') + ' ' + (agentName || '')`.
  - Consumes `useConversations()` + `flattenPages()`.
  - Renders one `<ConversationListItem>` per result; clicking calls `onSelect(id)`.
  - Renders a "Load more" `Button` (with `loading={isFetchingNextPage}`) when
    `hasNextPage`.
- States:
  - First-page loading: 5 skeleton rows.
  - First-page error: inline error state with Retry.
  - Empty (`items.length === 0`): inline `<EmptyState>` "No chats yet" with caption
    "Start a conversation with one of your agents." and a primary CTA "Start a chat"
    calling `onNew()`.
  - Filter matches nothing: inline message `No chats match "<query>"` with a Clear
    button.
- Keyboard navigation: `ArrowUp` / `ArrowDown` move focus through the rows; `Enter`
  calls `onSelect` on the focused row. The container has `role="listbox"`.
- The list **re-orders** on optimistic title edit: the in-place patch from US-06-001
  preserves the item position (sorted by `updatedAt` desc; a rename does not bump
  the timestamp).
- **Component tests** in
  `frontend/src/features/conversations/ConversationListItem.test.tsx`:
  - Title fallback: `title === null` renders `chat-<uuid-short>`.
  - Message-count chip warning at `messageCount === 64`.
  - Active state: `active=true` adds the accent border class; `aria-selected="true"`
    is set.
  - Click fires `onClick`.
- **Component tests** in
  `frontend/src/features/conversations/ConversationList.test.tsx`:
  - **Pagination**: MSW returns two pages; first paint shows page 1; clicking Load
    more appends page 2.
  - **Filter narrows**: typing into the search input reduces the visible rows; the
    "Clear" affordance restores the full list.
  - **Empty state + CTA**: empty list shows the empty state; clicking "Start a chat"
    calls `onNew()`.
  - **Active highlighting**: passing `activeConversationId={c2.id}` highlights `c2`
    (verified by `aria-selected="true"`).
  - **Keyboard nav**: pressing ArrowDown / ArrowUp moves the focus ring; pressing
    Enter fires `onSelect` with the focused conversation's id.
  - **Agent name resolution**: MSW returns a `Conversation` with `agentId=a1` and
    `useAgent(a1)` resolves to `{ name: 'Helper' }`; the row's secondary line shows
    `Helper`.

### Out of scope

- The 64-px collapsed form for small viewports — EPIC-11 polish.
- Pinning / favoriting conversations — not in the spec.
- Server-side search — not exposed by the API.
- Drag-and-drop reorder — `Conversation.order` doesn't exist in the spec.

### Design references

- `frontend/design/SW-DESIGN.md` §12.6 (`ChatPage` layout — left pane shape), §11.4
  (primitives), §11.6 (a11y — `role="listbox"`).
- `openapi.yaml` `GET /conversations`, `Conversation`, `ConversationPage`.

### Dependencies

- US-06-001 (`useConversations`, `Conversation` type); EPIC-05 US-05-001 (`useAgent`
  for the agent-name lookup); EPIC-02 (`Input`, `Button`, `Badge`, `Skeleton`,
  `EmptyState`, `flattenPages`, `formatRelative`).

---

## US-06-005 — `ConversationView` — topbar + message list + composer placeholder

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the right pane of `ChatPage` to show the current conversation's topbar
(title with inline-edit affordance, link to the agent's detail page, message-count
"X / 64", overflow menu with Delete) and the message list below — with a disabled
composer placeholder at the bottom that EPIC-07 will replace
**So that** I can rename, navigate to the agent, see how close I am to the 64-message
cap, and delete the conversation, all from the conversation view itself.

### Description

`ConversationView` is the **shell** for the conversation surface. Its three regions
are:

- **Topbar** — a slim header with:
  - Title region: the current title (with the `chat-<uuid-short>` fallback when
    `null`) + a pencil icon that opens `EditTitleDialog` (US-06-003).
  - Agent link: text `→ ${agent.name}` linking to `/agents/${agent.id}`. While
    `useAgent` is pending, render `→ Loading…` muted.
  - Message-count chip: `${messageCount} / 64`. Warning tone at 64.
  - Overflow menu (`Dropdown`): one entry "Delete conversation" opening
    `DeleteConversationDialog` (US-06-003); on `onDeleted`, the view calls the
    `onDeleted(conversation)` prop (the page navigates back to `/chat`).
- **Message list region** — `<MessageList conversationId={id}>` (US-06-002).
- **Composer region** — a **disabled placeholder**:
  - When `messageCount < 64`: a disabled `<textarea>` with placeholder text
    `Streaming chat coming next (EPIC-07).` and a disabled Send button. The
    placeholder has the same visual height as the eventual composer so the layout
    doesn't shift when EPIC-07 lands.
  - When `messageCount === 64`: an inline `<Banner>` "Conversation full" with caption
    "This conversation has reached its 64-message cap." and a primary CTA "Start a
    new conversation" calling the `onStartNew()` prop (which `ChatPage` wires to
    `/chat/new?agentId=<agentId>`). This is the same banner EPIC-07 will render on
    `409 CONVERSATION_FULL` per SW-DESIGN §10.2 / §8.5.

The view owns the `EditTitleDialog` and `DeleteConversationDialog` open states. It
does **not** own routing; navigation away from the conversation (after delete) is the
parent page's responsibility — `ConversationView` exposes an `onDeleted(conversation)`
prop for that.

### Acceptance criteria

- `frontend/src/features/conversations/ConversationView.tsx` exists with the prop
  shape:
  - `interface ConversationViewProps { conversationId: string; onDeleted: (conversation: Conversation) => void; onStartNew: (agentId: string) => void; }`
- Internally:
  - Calls `useConversation(conversationId)` and `useAgent(conversation?.agentId)`.
  - Renders the topbar with the four regions above.
  - Renders `<MessageList conversationId={conversationId}>` below the topbar.
  - Renders the composer-placeholder region below the message list:
    - `messageCount < 64`: disabled `<textarea>` + disabled Send button + caption
      "Streaming chat coming next (EPIC-07)." (a TODO comment in the source names
      US-07-001 as the story that replaces it).
    - `messageCount === 64`: the "Conversation full" banner with the "Start a new
      conversation" CTA calling `onStartNew(agent.id)`.
- States:
  - `useConversation` `isPending`: skeleton topbar + skeleton message list (already
    rendered by US-06-002).
  - `useConversation` `isError` with `status === 404`: full-page
    `<EmptyState>` "Conversation not found" with a "Back to chats" link to `/chat`.
  - Other `isError`: retryable inline error state in the topbar slot.
- Owns the `EditTitleDialog` and `DeleteConversationDialog` open states. On
  `onDeleted` from the delete dialog, calls the view's `onDeleted(conversation)`
  prop and fires a success toast "Conversation deleted." (i18n key TBD; literal copy
  acceptable).
- Inline-edit title: clicking the pencil icon opens `EditTitleDialog`; the
  optimistic patch from US-06-001 is visible in the topbar immediately.
- The agent link uses React Router `<Link>` so deep-link copying works.
- **Component tests** in
  `frontend/src/features/conversations/ConversationView.test.tsx`:
  - **Topbar renders** the title (with fallback when null), the agent name (after
    `useAgent` resolves), the message-count chip, and the overflow trigger.
  - **Title edit**: clicking the pencil opens the dialog; saving patches the topbar
    title optimistically (verified via the rendered title text).
  - **Overflow menu Delete**: opening the menu and clicking Delete opens the delete
    dialog; confirming fires `DELETE` and calls `onDeleted(conversation)`.
  - **Composer placeholder (< 64)**: a disabled `<textarea>` and a disabled Send
    button are present; both have the `disabled` attribute.
  - **Conversation-full banner (=== 64)**: the placeholder is replaced by the
    banner; clicking "Start a new conversation" calls `onStartNew(agentId)`.
  - **404 from `useConversation`**: the "Conversation not found" empty state
    renders with the Back link.
  - **Agent link target**: the agent name in the topbar is a `<Link>` with
    `to="/agents/<agentId>"`.

### Out of scope

- The real composer / SSE — EPIC-07.
- "Continue from this message" / branch-off — not in the spec.
- Conversation export — TBD-F4.
- Message search inside a conversation — not in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §10.2 (`CONVERSATION_FULL` banner), §12.6 (chat
  layout), §12.8 (`ConversationPage` topbar shape).
- `openapi.yaml` `Conversation`, `GET /conversations/{conversationId}`.

### Dependencies

- US-06-002 (`MessageList`); US-06-003 (`EditTitleDialog`,
  `DeleteConversationDialog`); EPIC-05 US-05-001 (`useAgent`); EPIC-02 (`Dropdown`,
  `Button`, `EmptyState`, `Toast`).

---

## US-06-006 — `ChatPage` layout + `ChatNewPage` (agent picker) + routes + sidebar entry

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the `/chat` two-pane layout (left = `ConversationList`, right =
`<Outlet>`), the `/chat/new` agent-picker page that creates a conversation and
redirects to it, plus the sidebar entry and the four-route wiring
(`/chat`, `/chat/new`, `/chat/:conversationId`) registered under the protected
`AppShell`
**So that** I have one obvious entry into the chat surface from the sidebar, deep
links to a conversation work, and "Start chat" buttons across the app (from
`AgentCard`, `AgentDetailPage`, the empty conversation list, the "Conversation full"
banner) all converge on the same flow.

### Description

`ChatPage` is the layout shell. It hosts the `ConversationList` (US-06-004) on the
left and a React Router `<Outlet>` on the right. The outlet resolves to either
`ChatNewPage` (when on `/chat/new` or `/chat`) or `ConversationPage` (when on
`/chat/:conversationId` — page body lands in US-06-007).

`ChatNewPage` is the **agent picker**. Per SW-DESIGN §12.7:

- A search `Input` on top.
- A list of own agents (via `useAgents()` + `flattenPages()`).
- Clicking an agent calls `useStartConversation().mutateAsync({ agentId })` and
  `<Navigate to="/chat/{newId}" replace />` on success.
- The page also honors the `?agentId=` query parameter — if present **and** valid,
  it bypasses the picker, creates the conversation, and navigates straight to it.
  This is what enables the EPIC-05 "Start chat" buttons (which currently navigate to
  `/chat/new?agentId=:id`) to land directly in the new conversation.

The sidebar gains a **Chats** entry. It lives under the standard-user group (next to
Agents, Tools, MCP servers) and is visible to all authenticated principals.

The two new routes are registered under the protected `<AppShell>`:

- `/chat` → `ChatPage` layout. When the user lands at exactly `/chat`, the outlet
  renders `ChatNewPage` (so the right pane is never empty).
- `/chat/new` → `ChatPage` layout with outlet = `ChatNewPage`.
- `/chat/:conversationId` → `ChatPage` layout with outlet = `ConversationPage`.

For US-06-006, the `ConversationPage` body is a **placeholder** (`<>Coming in
US-06-007</>`) — US-06-007 replaces it.

### Acceptance criteria

- `frontend/src/pages/chat/ChatPage.tsx` exists, exporting
  `function ChatPage(): JSX.Element`. Renders:
  - A two-pane grid: left pane `320 px` (collapsing to a 64-px stub on `< md` is
    deferred to EPIC-11; for v1 the left pane is hidden behind a hamburger on
    `< md` and visible at `>= md`).
  - Left: `<ConversationList activeConversationId={params.conversationId} onSelect={(id) => navigate('/chat/' + id)} onNew={() => navigate('/chat/new')} />`.
  - Right: `<Outlet />`.
- `frontend/src/pages/chat/ChatNewPage.tsx` exists, exporting
  `function ChatNewPage(): JSX.Element`. Behavior:
  - Reads `?agentId=` via `useSearchParams()`. If the param is present and a valid
    UUID:
    - Fires `useStartConversation().mutateAsync({ agentId })` once on mount; on
      success, `<Navigate to="/chat/" + result.id replace />`.
    - While the mutation is in flight: render a centered spinner with caption
      "Starting a new conversation…".
    - On error: render a retryable error state with a "Back to chats" link.
  - Otherwise (no `agentId` param):
    - Heading: "Start a new chat".
    - Caption: "Pick one of your agents to start a conversation.".
    - A search `Input` filtering by `agent.name || agent.description`.
    - A list of own agents (via `useAgents()` + `flattenPages()` — drained to
      completion exactly as `TeamPicker` does in US-05-003).
    - Each agent row: name, description (truncated 1 line), a primary `Button`
      "Start chat" calling `useStartConversation().mutateAsync({ agentId: agent.id
      })`; on success `navigate('/chat/' + result.id, { replace: true })`.
    - Empty agents: `<EmptyState>` "You don't have any agents yet" + a CTA
      "Create your first agent" navigating to `/agents/new`.
- The route table (`frontend/src/pages/routes.tsx`) is updated to register:
  - `/chat` → `ChatPage` (lazy-loaded) with index outlet = `ChatNewPage`.
  - `/chat/new` → outlet = `ChatNewPage`.
  - `/chat/:conversationId` → outlet = placeholder `<>Coming in US-06-007</>`
    (US-06-007 swaps in the real page).
- The `Sidebar` (US-02-011) gains a **Chats** link wired to `/chat` with the
  `MessageSquare`-or-equivalent lucide icon, placed in the standard-user nav group.
- **Integration tests** in `frontend/src/pages/chat/ChatPage.test.tsx`:
  - Mount at `/chat`: the left pane renders the conversation list; the right pane
    renders `ChatNewPage` (the index outlet).
  - Clicking a conversation in the left pane navigates to `/chat/<id>`; the active
    item is highlighted.
  - Clicking "+" in the left pane navigates to `/chat/new`.
- **Integration tests** in `frontend/src/pages/chat/ChatNewPage.test.tsx`:
  - Mount at `/chat/new` with `useAgents()` returning 3 agents: the picker shows
    all 3.
  - Clicking "Start chat" on agent `a2` fires `POST /conversations { agentId: a2.id
    }`; MSW responds 201 with the new conversation; the URL changes to
    `/chat/<newId>`.
  - Mount at `/chat/new?agentId=<uuid>`: the spinner renders; `POST /conversations`
    fires once with the URL-param `agentId`; on 201, the URL changes to
    `/chat/<newId>` (replace history so the back button doesn't return to
    `/chat/new`).
  - Mount with an invalid `?agentId=foo`: the spinner does NOT fire; the picker
    renders normally (the invalid param is ignored).
  - Empty agents: the "You don't have any agents yet" empty state renders with the
    `/agents/new` CTA.
  - **`404 NOT_FOUND` on POST (agent deleted)**: a retryable error renders with
    "Back to chats" link.

### Out of scope

- The `ConversationPage` body — US-06-007.
- The 64-px collapsed left pane — EPIC-11 polish.
- A "recent agent" shortcut in the picker — not in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (route table — `/chat/*` routes), §12.6
  (`ChatPage` layout), §12.7 (`ChatNewPage` shape), §16.1 (lazy loading).
- `openapi.yaml` `POST /conversations`.

### Dependencies

- US-06-004 (`ConversationList`); EPIC-05 US-05-001 (`useAgents` for the picker);
  EPIC-02 (`Sidebar`, `AppShell`, `Outlet`, `EmptyState`, `Input`, `Button`,
  `Spinner`, `flattenPages`, route table).

---

## US-06-007 — `ConversationPage` composition + `AgentDetailPage` "Recent conversations" wire-up + integration tests

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the `/chat/:conversationId` page composing `ConversationView` (US-06-005)
with the correct navigation on delete and the conversation-full "Start new" handler,
AND the "Recent conversations" panel stubbed in `AgentDetailPage` (US-05-007) finally
wired to the real `useConversations({ agentId, pageSize: 5 })` hook
**So that** my chat round-trip is complete end-to-end (list → pick a conversation →
view messages → rename / delete) and the agent-detail page surfaces real recent
chats instead of the EPIC-05 placeholder empty state.

### Description

Two paired pieces of work:

1. **`ConversationPage`** — a thin page wrapping `ConversationView`. It reads
   `conversationId` from the URL, passes it through, and handles the two callbacks:
   - `onDeleted(conversation)`: `navigate('/chat', { replace: true })` so the user
     lands on the conversation list (the active item is gone).
   - `onStartNew(agentId)`: `navigate('/chat/new?agentId=' + agentId)`.

2. **`AgentDetailPage` "Recent conversations" panel** — replaces the EPIC-05
   `<EmptyState>` "Conversations coming soon" stub with a live call to
   `useConversations({ agentId, pageSize: 5 })`. Each conversation renders as a
   compact row (title with fallback, message-count chip, relative `updatedAt`)
   linking to `/chat/${conversation.id}`. The 5-row cap is the design's choice — a
   "See all" link at the bottom navigates to `/chat?agentId=${agentId}` (which the
   conversation list already filters via its `agentId` URL param).

`ChatPage` does **not** today read an `agentId` URL param to filter the left-pane
list. This story adds that — when `?agentId=<id>` is present on `/chat`, the
`ConversationList` is constructed with `agentId={id}` and the URL param survives
selection.

### Acceptance criteria

- `frontend/src/pages/chat/ConversationPage.tsx` exists, exporting
  `function ConversationPage(): JSX.Element`. Reads
  `useParams<{ conversationId: string }>()` and renders:
  - `<ConversationView conversationId={conversationId} onDeleted={(c) => { navigate('/chat', { replace: true }); }} onStartNew={(agentId) => navigate('/chat/new?agentId=' + agentId)} />`.
- The placeholder registered in US-06-006 for `/chat/:conversationId` is replaced
  with the lazy-loaded real page.
- **`AgentDetailPage` "Recent conversations" wire-up**:
  - Replaces the `<EmptyState>` stub (with the `// TODO US-06-...` comment) with
    a section calling `useConversations({ agentId, pageSize: 5 })`.
  - Renders up to 5 rows; each row is a `<Link to="/chat/${c.id}">` showing the
    title fallback, the relative `updatedAt`, and the message-count chip.
  - When `useConversations` returns 0 items: renders the empty state "No
    conversations yet" + CTA "Start a chat with this agent" navigating to
    `/chat/new?agentId=${agentId}`.
  - When `useConversations` returns ≥ 1 item: appends a "See all" link to
    `/chat?agentId=${agentId}` at the bottom of the panel.
  - Loading / error: skeleton rows / retryable inline error.
- **`ChatPage` agentId filter**: when `?agentId=<id>` is present on `/chat`,
  `ConversationList` is constructed with `agentId={id}`. Selecting a conversation
  preserves the URL param (so back-button navigation returns to the filtered
  list).
- **Integration tests** in `frontend/src/pages/chat/ConversationPage.test.tsx`:
  - Mount at `/chat/<id>`: the topbar shows the conversation title; the message
    list shows the messages.
  - **Rename**: clicking the pencil + saving updates the title both in the
    topbar (the live cache) and in the left-pane list (the optimistic patch
    from US-06-001).
  - **Delete**: clicking overflow → Delete → confirming the dialog fires
    `DELETE`; on 204, the URL changes to `/chat`; the conversation is removed
    from the left-pane list (optimistic).
  - **Conversation full**: when `useConversation` returns `{ messageCount: 64
    }`, the composer placeholder is replaced by the "Conversation full" banner;
    clicking "Start a new conversation" navigates to
    `/chat/new?agentId=<agentId>`.
  - **404**: navigating to `/chat/<bad-id>` renders the "Conversation not
    found" empty state with the Back to chats link (provided by US-06-005); the
    left-pane list still renders (no full-page error).
- **Integration tests** in `frontend/src/pages/agents/AgentDetailPage.test.tsx`
  (extending US-05-007):
  - **Populated**: MSW returns 3 conversations for the agent; the panel shows
    all 3 rows linking to `/chat/<id>`; the "See all" link points to
    `/chat?agentId=<agentId>`.
  - **Empty**: 0 conversations → the empty state with the "Start a chat with
    this agent" CTA renders; clicking it navigates to `/chat/new?agentId=...`.
  - **404 / error**: the panel surfaces a retryable inline error; the rest of
    the page is unaffected.
- **Integration test** in `frontend/src/pages/chat/ChatPage.test.tsx`
  (extending US-06-006):
  - Mount at `/chat?agentId=<id>`: the conversation list shows only that
    agent's conversations (MSW asserts the `?agentId=<id>` query parameter);
    selecting a conversation navigates to `/chat/<conversationId>?agentId=<id>`
    (param preserved).

### Out of scope

- A "recent agents" or "favorite chats" surface — not in v1.
- Server-side filtering of recent conversations by status (e.g., "only active") —
  no such field in the spec.
- Sharing / exporting a conversation — TBD-F4.

### Design references

- `frontend/design/SW-DESIGN.md` §5.1 (routes), §12.5 (`AgentDetailPage` Recent
  conversations panel), §12.6 (chat layout — left pane scoped by agent), §12.8
  (`ConversationPage`).
- `openapi.yaml` `GET /conversations` (with `agentId` query parameter),
  `GET /conversations/{conversationId}`.

### Dependencies

- US-06-005 (`ConversationView`); US-06-006 (`ChatPage`, `ChatNewPage`, routes);
  EPIC-05 US-05-007 (`AgentDetailPage` — the panel stub to replace).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-06-001  | Conversation / Message types + Zod schemas + six conversation hooks (incl. optimistic updates)     | MUST     | Done   |
| US-06-002  | `MessageBubble` + virtualized `MessageList` (USER / ASSISTANT styling + 64-row scroll)             | MUST     | Done   |
| US-06-003  | `EditTitleDialog` + `DeleteConversationDialog` (modal-based edits with optimistic flow)            | MUST     | Done   |
| US-06-004  | `ConversationList` + `ConversationListItem` (left-pane list with active highlighting)              | MUST     | Done   |
| US-06-005  | `ConversationView` — topbar (title / agent link / count / overflow) + message list + composer placeholder | MUST | Done   |
| US-06-006  | `ChatPage` layout (two-pane) + `ChatNewPage` (agent picker) + routes wiring + sidebar entry        | MUST     | Done   |
| US-06-007  | `ConversationPage` composition + `AgentDetailPage` "Recent conversations" wire-up + integration tests | MUST  | Done   |

EPIC-06 is **Done** when all seven stories above are `Done`. The next step is then
EPIC-07 (SSE streaming chat), which replaces the EPIC-06 composer placeholder with the
real SSE-driven composer + streaming bubble.
