# EPIC-07-US.md — User stories for EPIC-07 (SSE streaming chat)

This file lists the user stories that deliver **EPIC-07 — SSE streaming chat** of the
frontend, as defined in `frontend/backlog/EPICS.md`.

EPIC-07 delivers the **value moment** of the product: the user types a message, sees the
assistant response stream in token-by-token, and can stop a runaway stream. It wires the
SSE primitive built in EPIC-02 (`shared/sse/chatStream.ts`, US-02-012) into the
`ConversationView` shell built in EPIC-06 (US-06-005), and replaces the disabled composer
placeholder with the real `Composer` + the streaming-aware `useChatStream` hook.

Two load-bearing artifacts ship in this EPIC:

1. **`useChatStream`** — the React-facing wrapper from SW-DESIGN §8.3. It owns the per-
   conversation streaming state, bridges the SSE primitive to TanStack Query (optimistic
   USER bubble, in-place ASSISTANT bubble growth via `React.startTransition`, first-turn
   title patching of the conversations-list cache, partial-bubble preservation on
   `error`).
2. **`Composer`** — the textarea + keyboard contract + the send/stop button pair. The
   stop button is the user's contract with `REQ-STR-003` (client-cancel disposes the
   upstream LLM call).

The stories are sized so they can each be picked up, implemented, reviewed, and merged
independently. Where a story is a prerequisite for others, the dependency is declared in
its **Dependencies** section.

## Conventions

- **ID format**: `US-07-<nnn>` — `07` marks the EPIC; `<nnn>` is a sequential three-digit
  counter.
- **Status**: `Draft`, `Ready`, `In progress`, `Done`. All stories start as `Draft`.
- **Priority**: `MUST`, `SHOULD`, `COULD`. All five stories are `MUST` (the chat surface
  is unusable without streaming).
- Each story contains: a narrative ("As a … I want … so that …"), a short description, a
  bullet list of testable acceptance criteria, the out-of-scope items, the design
  references, and its dependencies.

## Story list

| ID         | Title                                                                                    | Priority | Status | Depends on                       |
|------------|------------------------------------------------------------------------------------------|----------|--------|----------------------------------|
| US-07-001  | `Composer` — textarea + N/1024 counter + Cmd/Ctrl+Enter + send / stop buttons             | MUST     | Done   | EPIC-02                          |
| US-07-002  | `useChatStream` — state machine, frame handling, first-turn title cache patch, `startTransition` | MUST | Done   | EPIC-02 (US-02-012), EPIC-06 (US-06-001) |
| US-07-003  | `ConversationView` SSE wiring — replace placeholder, `aria-live` log, conversation-full banner | MUST | Done    | US-07-001, US-07-002, EPIC-06 (US-06-005) |
| US-07-004  | Cancellation — stop button + page-navigation cleanup + partial-bubble greying             | MUST     | Done   | US-07-002, US-07-003             |
| US-07-005  | End-to-end SSE integration tests — golden path, all error paths, 64-cap, 406              | MUST     | Done   | US-07-001 … US-07-004            |

---

## US-07-001 — `Composer` — textarea + N/1024 counter + Cmd/Ctrl+Enter + send / stop buttons

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** a `Composer` component with a `<textarea>` carrying a live `N/1024` character
counter, `Cmd/Ctrl+Enter` to submit, `Esc` to stop a stream in flight, a primary Send
button, and a destructive Stop button (visible only while streaming)
**So that** typing a message feels like a chat app (keyboard-first, character-aware),
the 1024-char cap is mirrored client-side so I see the limit before the server rejects
it, and a runaway stream can always be aborted.

### Description

Per SW-DESIGN §11.6 / §12.8, the composer is a **controlled** component owned by its
parent (`ConversationView` after US-07-003). The parent passes the current `phase`
(from `useChatStream` — `idle | sending | streaming | completed | error`) and the two
callbacks (`onSend(content)` and `onStop()`); the composer renders the right buttons
for the right state.

Per `openapi.yaml.SendMessageRequest`, `content` must be `≥1, ≤1024` characters. The
Zod schema `sendMessageSchema` is added in this story and exported alongside the
composer for unit-test convenience.

The composer does **NOT** call the API directly — `useChatStream` (US-07-002) owns the
network call. The composer is purely a controlled input + keyboard contract.

Keyboard contract (per SW-DESIGN §11.6 / §12.8):

- `Cmd+Enter` (macOS) / `Ctrl+Enter` (others) → fire `onSend(content)` if valid and
  `phase === 'idle' | 'completed' | 'error'`.
- `Enter` alone → insert newline (the default `<textarea>` behavior).
- `Esc` → fire `onStop()` if `phase === 'streaming'`.

Visual contract:

- The Send button is the only primary button when `phase !== 'streaming'`.
- The Stop button **replaces** the Send button when `phase === 'streaming'`.
- The character counter color shifts:
  - Default (muted): `0 ≤ N < 900`.
  - Warning: `900 ≤ N < 1024`.
  - Danger: `N === 1024`.
- When `N > 1024`: the counter is danger, the Send button is disabled, and an inline
  message "Message too long (max 1024 characters)" renders under the textarea. The
  textarea itself does **not** truncate — the user keeps the content so they can edit
  it down.

### Acceptance criteria

- `frontend/src/features/conversations/composer.schema.ts` exists with:
  - `export const sendMessageSchema = z.object({ content: z.string().min(1).max(1024) });`
  - `export type SendMessageValues = z.infer<typeof sendMessageSchema>;`
  - The schema is byte-aligned with `openapi.yaml.SendMessageRequest`.
- `frontend/src/features/conversations/Composer.tsx` exists with the prop shape:
  - `interface ComposerProps { phase: 'idle' | 'sending' | 'streaming' | 'completed' | 'error'; onSend: (content: string) => void; onStop: () => void; disabled?: boolean; }`
- Internally:
  - Local controlled state: `const [content, setContent] = useState('');`
  - `<textarea>` with `value={content}` + `onChange`, `placeholder="Type your message…"`,
    `rows={3}`, auto-grow to a maximum of 8 rows.
  - Below the textarea: the character counter (right-aligned), the Send / Stop button
    (right-aligned), and the inline "Message too long" alert when `content.length > 1024`
    (this state is reachable only because the textarea does not truncate).
- Send button:
  - Visible when `phase !== 'streaming'`.
  - Label: `Send`. Icon: lucide `Send`.
  - `disabled` when `disabled` prop is true, or `phase === 'sending'`, or content is
    empty / whitespace-only, or `content.length > 1024`.
  - Loading spinner when `phase === 'sending'`.
  - On click: calls `onSend(content)` then clears local state (`setContent('')`).
- Stop button:
  - Visible when `phase === 'streaming'`.
  - Label: `Stop`. Variant: destructive. Icon: lucide `Square` (or `StopCircle`).
  - On click: calls `onStop()`. The button does NOT clear the textarea (the user might
    want to revise and resend; the parent decides whether to clear).
- Keyboard:
  - `Cmd/Ctrl+Enter`: prevents default; if the Send button is enabled, fires
    `onSend(content)` and clears local state.
  - `Esc`: prevents default; if `phase === 'streaming'`, fires `onStop()`.
  - `Enter` alone: default behavior (newline).
- Accessibility:
  - The textarea has `aria-label="Message composer"`.
  - The Send button has both an icon and a visible "Send" label.
  - The Stop button has both an icon and a visible "Stop" label.
  - The character counter has `aria-live="polite"` and only announces when it crosses
    a threshold (debounced to avoid flooding the user).
- **Component tests** in `frontend/src/features/conversations/Composer.test.tsx`:
  - **Empty content**: Send is disabled; pressing Cmd+Enter does NOT fire `onSend`.
  - **Whitespace-only content**: same as empty.
  - **Valid content + Cmd+Enter**: fires `onSend('hello')`; textarea is cleared.
  - **Valid content + Ctrl+Enter** (non-mac): fires `onSend`.
  - **Plain Enter**: inserts a newline (verified by `content` containing `\n`); does
    NOT fire `onSend`.
  - **Send click**: fires `onSend(content)`; textarea is cleared.
  - **Phase transitions**:
    - `phase='idle'`: Send visible, Stop not in DOM.
    - `phase='sending'`: Send visible with `loading` and `disabled`.
    - `phase='streaming'`: Send not in DOM; Stop visible.
    - `phase='completed' | 'error'`: Send visible, enabled (assuming valid content).
  - **Esc while streaming**: fires `onStop()`.
  - **Esc while idle**: does NOT fire `onStop()`.
  - **Counter color**: at 899 chars → muted; at 900 → warning; at 1024 → danger; at
    1025 → the inline "Message too long" alert renders + Send is disabled.
  - **`disabled` prop**: passes through to the textarea and the Send button.
- **Unit tests** in `frontend/src/features/conversations/composer.schema.test.ts`:
  - `sendMessageSchema.safeParse({ content: '' })` fails on `content.min`.
  - `sendMessageSchema.safeParse({ content: 'x'.repeat(1025) })` fails on `content.max`.
  - `sendMessageSchema.safeParse({ content: 'hi' })` succeeds.

### Out of scope

- Attachments / file upload — not in the spec.
- Slash-commands or mention autocompletion — not in v1.
- Spell-check toggle — defer to platform default.
- Token-aware counters (LLM token counts instead of character counts) — not in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §9.2 (constraint mirroring — `content` ≤1024),
  §11.6 (a11y — Cmd/Ctrl+Enter, Esc), §12.8 (`ConversationPage` composer shape).
- `openapi.yaml` `SendMessageRequest`.

### Dependencies

- EPIC-02 (`Button`, `Textarea`, icon set, `Tooltip`).

---

## US-07-002 — `useChatStream` — state machine, frame handling, first-turn title cache patch, `startTransition`

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer
**I want** a `useChatStream(conversationId)` hook that owns the streaming state machine
(`idle → sending → streaming → completed | error`), consumes the SSE primitive
`streamChat` from EPIC-02, applies the optimistic USER bubble, mutates the messages
cache in place as `delta` frames arrive (off the input critical path via
`React.startTransition`), patches the conversations-list cache with the first-turn
title on `completed`, and preserves the partial assistant bubble on `error`
**So that** the entire SSE contract from SW-DESIGN §8.3 lives in one tested hook and
the `ConversationView` integration (US-07-003) is purely about UI composition.

### Description

Per SW-DESIGN §8.3, the hook exposes:

```ts
interface UseChatStreamResult {
  phase: 'idle' | 'sending' | 'streaming' | 'completed' | 'error';
  pendingUserMessage: Message | null;
  pendingAssistantText: string;
  error: ApiError | null;
  send: (content: string) => Promise<void>;
  stop: () => void;
}

function useChatStream(conversationId: string): UseChatStreamResult;
```

State machine:

- **idle**: no in-flight request. `send(content)` transitions to `sending`.
- **sending**: HTTP request is in flight (waiting for headers / first frame). The
  optimistic USER bubble is in the cache. Transitions:
  - On `started` frame → `streaming`. The bubble's temp id is replaced with the
    `userMessageId`.
  - On HTTP pre-stream error (`400 / 401 / 403 / 404 / 406 / 409 / 429`) → `error`.
    The optimistic USER bubble is rolled back.
- **streaming**: at least one `delta` has been received OR `started` has been received.
  Transitions:
  - On each `delta`: append `text` to `pendingAssistantText` via
    `React.startTransition` (to keep the input thread responsive).
  - On `completed` → `completed`. The assistant bubble is committed to the messages
    cache with `assistantMessageId`; the conversations-list cache item's
    `messageCount` is updated; **if `title !== null`**, the conversations-list cache
    item's `title` is patched (first-turn rule). `qk.conversations.byId(id)` is
    invalidated.
  - On `error` frame → `error`. The partial `pendingAssistantText` is preserved (the
    UI shows it greyed; see US-07-004). The USER bubble stays committed (the backend
    persisted it).
  - On `stop()` (US-07-004) → `error` with a synthetic `ApiError` of code
    `'CANCELLED'` (a new client-only sentinel, not from the openapi enum — flagged
    explicitly below).
- **completed**: the hook can be re-armed by calling `send(content)` again, which
  resets to `sending`. Local state for the previous turn (`pendingUserMessage`,
  `pendingAssistantText`) is cleared on re-arm.
- **error**: same re-arm semantics as `completed`.

`send` and `stop` create / use a single `AbortController` per turn. `stop` calls
`controller.abort()`, which closes the SSE stream on the wire (the backend disposes
the upstream LLM call per `REQ-STR-003`).

The hook does **NOT** own UI concerns (toasts, modals, banners) — those are
ConversationView's responsibility (US-07-003).

The hook **does** patch caches:

- Messages cache (`qk.conversations.messages(id)`): on optimistic USER bubble insert
  (with a temp UUID), on `started` (replace temp id), on `completed` (append
  assistant message). The messages cache is mutated in-place — no refetch — so the
  scroll position is preserved per SW-DESIGN §8.3.
- Conversations-list cache (`qk.conversations.list(null)` and any
  `qk.conversations.list(agentId)`): on `completed`, update `messageCount`; if
  `title !== null`, update `title`.
- Conversation detail cache (`qk.conversations.byId(id)`): invalidated on `completed`
  to re-sync any out-of-band state.

### Acceptance criteria

- `frontend/src/features/conversations/useChatStream.ts` exists, exporting the hook
  with the signature above.
- The hook is built around `streamChat` (US-02-012) and consumes:
  - `useQueryClient()` to mutate caches.
  - An internal `AbortController` reset on each `send`.
- State is held in a `useReducer` (so each `delta` is an idempotent action). The
  reducer is exported as a non-default `chatStreamReducer` for unit-test access.
- **Optimistic USER bubble**:
  - On `send(content)`, a temp UUID is generated (`crypto.randomUUID()`); a `Message`
    with `id=tempUUID, role='USER', content, createdAt=new Date().toISOString()` is
    prepended-or-appended (append) to `qk.conversations.messages(id)`.
  - On `started`, the temp UUID is replaced with `frame.userMessageId` in the cache.
  - On HTTP pre-stream error, the optimistic message is removed from the cache.
- **`delta` handling**:
  - Each `delta.text` is appended to a local `pendingAssistantText` via
    `React.startTransition` (so the input thread stays responsive while a large
    burst of deltas arrives).
  - The hook does **NOT** insert a per-delta `Message` into the cache — the partial
    assistant content is held in local state and rendered by `MessageList` as a
    pending bubble (see US-07-003 for the rendering integration). The cache only
    receives the committed assistant message on `completed`.
- **`completed` handling**:
  - A `Message` with `id=frame.assistantMessageId, role='ASSISTANT',
    content=pendingAssistantText, createdAt=new Date().toISOString()` is appended to
    the messages cache.
  - The conversations-list caches (`qk.conversations.list(null)` plus any
    `qk.conversations.list(*)` keys) are walked; the matching conversation's
    `messageCount` is set to `frame.messageCount`; if `frame.title !== null`, the
    `title` is also patched.
  - `qk.conversations.byId(id)` is invalidated (re-fetches `Conversation` to pick up
    any out-of-band changes).
  - `phase` transitions to `completed`.
- **`error` frame handling**:
  - `pendingAssistantText` is **preserved** (the partial bubble stays in local state
    for US-07-004 to render greyed). The cache is NOT mutated.
  - `phase` transitions to `error` with `error = ApiError` built from
    `frame.problem`.
- **HTTP pre-stream error handling**:
  - The optimistic USER bubble is removed from the cache.
  - `phase` transitions to `error` with the corresponding `ApiError`.
- **`stop()` handling** (full surface in US-07-004; the hook owns the abort here):
  - Calls `controller.abort()`. The wire is closed.
  - `phase` transitions to `error` with a synthetic `ApiError { code: 'CANCELLED',
    status: 0 }`. `'CANCELLED'` is a **client-only sentinel** code; it is added to
    the `ProblemCode` discriminated union in `shared/api/errors.ts` as a non-spec
    value with a `/** Client-only sentinel for user-cancelled streams. */` comment
    so consumers can route on it (in US-07-004) without false-positive toasts.
  - The partial `pendingAssistantText` is preserved.
- **Re-arming** (calling `send` after `completed` or `error`): the hook resets
  `pendingUserMessage`, `pendingAssistantText`, and `error` before transitioning to
  `sending`.
- **Unit tests** in
  `frontend/src/features/conversations/useChatStream.test.tsx` (MSW with a hand-rolled
  `text/event-stream` body — same helper as US-02-012):
  - **Golden path**: `send('hi')` → MSW emits `started`, two `delta`s, `completed`
    with `title='Hi'`, `messageCount=2`.
    - The messages cache contains the USER bubble (with the `userMessageId` from
      `started`) and the ASSISTANT bubble (with the `assistantMessageId` from
      `completed`).
    - The conversations-list cache item has the new `title='Hi'` and
      `messageCount=2`.
    - `phase` ends at `completed`.
  - **Multi-delta growth**: `pendingAssistantText` grows monotonically across the
    `delta` frames; the assistant bubble is committed on `completed` with the
    concatenated content.
  - **Title patching only on first turn**: a second `send` with MSW returning
    `completed { title: null, messageCount: 4 }` does NOT patch the list cache's
    title (only `messageCount`).
  - **Optimistic USER + rollback on pre-stream 409**: `send('hi')` → MSW responds
    `409 CONVERSATION_FULL` before any frame; the optimistic USER message is
    removed from the cache; `phase === 'error'`; `error.code === 'CONVERSATION_FULL'`.
  - **Mid-stream `error` frame**: MSW emits `started`, one `delta='partial'`,
    `error { code: 'LLM_UNAVAILABLE' }`; the USER bubble stays in the cache,
    `pendingAssistantText === 'partial'`, `phase === 'error'`,
    `error.code === 'LLM_UNAVAILABLE'`.
  - **`stop()` mid-stream**: emit `started`, one `delta`, then call `stop()` before
    `completed` arrives; the partial text is preserved; `phase === 'error'`,
    `error.code === 'CANCELLED'`; the AbortController is aborted (assert via the
    MSW handler being aborted, or by checking that `controller.signal.aborted ===
    true`).
  - **Re-arm after error**: calling `send` after an error resets local state to
    the new turn (the old `pendingAssistantText` is wiped).
  - **`HTTP 406`**: `send('hi')` → MSW responds 406; `phase === 'error'`,
    `error.code === 'NOT_ACCEPTABLE'`. (This should not occur in production but
    the hook handles it.)
  - **`HTTP 429` with `Retry-After: 5`**: the error carries `retryAfterSeconds`;
    `phase === 'error'`.
- The `'CANCELLED'` sentinel is added to `ProblemCode` (US-02-002 file) with a JSDoc
  comment marking it client-only, and to `errorCopy` (`shared/i18n/en.ts`) with a
  user-friendly title "Cancelled" and a `null` toast policy (US-07-004 explicitly
  suppresses the toast for this code).

### Out of scope

- The UI rendering of the streaming bubble — US-07-003.
- The Stop button and its keyboard binding — US-07-001 (button) + US-07-004
  (cancellation integration).
- The toast policy per error code — US-07-005.
- Connection retry / resume — the spec does not support resume; on `error` the user
  re-sends.

### Design references

- `frontend/design/SW-DESIGN.md` §7.5 (optimistic table — chat USER bubble is
  optimistic), §8.1–8.5 (SSE design — the hook is the codification of §8.3),
  §11.5 (no per-character animation).
- `openapi.yaml` `POST /conversations/{conversationId}/messages`, `SseStartedEvent`,
  `SseDeltaEvent`, `SseCompletedEvent`, the `error` frame contract.

### Dependencies

- EPIC-02 US-02-012 (`streamChat`, `SseFrame`); EPIC-02 US-02-002 (`ApiError`, the
  `ProblemCode` union — extended with `'CANCELLED'` in this story); EPIC-06 US-06-001
  (`qk.conversations.*`, the cache shapes the hook patches).

---

## US-07-003 — `ConversationView` SSE wiring — replace placeholder, `aria-live` log, conversation-full banner

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the EPIC-06 `ConversationView` to host the real composer, render the
streaming assistant bubble inline, replace the composer with the "Conversation full"
banner on the 64-message cap, and announce completed assistant messages via the
existing `role="log"` region
**So that** the chat surface is now end-to-end usable: I type, I see the response
stream in, I can tell when I'm at the cap, and a screen reader announces the final
message without flooding the user with per-token noise.

### Description

This story is the **integration layer** between `Composer` (US-07-001),
`useChatStream` (US-07-002), and the `ConversationView` shell (EPIC-06 US-06-005). The
EPIC-06 composer placeholder is removed; the SSE composer + streaming bubble take its
place.

The streaming assistant bubble is rendered by `MessageList` (US-06-002) as a **pending
row** appended after the committed messages whenever `useChatStream.phase === 'sending'
| 'streaming'`. The pending row uses the same `MessageBubble` component with
`variant='streaming'` (US-06-002 already exposed the variant) and reads
`pendingAssistantText` from the hook.

The `aria-live="polite"` log region was already wired in US-06-002. This story only
adds:

- A `useEffect` watching for `useChatStream.phase === 'completed'`: when it transitions
  to `completed`, it momentarily focuses an off-screen live-region element containing
  the **whole** committed assistant message text. This single announcement replaces
  per-delta announcements per SW-DESIGN §11.6 (delta text is **not** announced).
- A `useEffect` watching for `useChatStream.phase === 'sending' | 'streaming'`: it
  sets the focused live region to empty (so the screen reader does not announce the
  intermediate "(typing…)" state).

The "Conversation full" banner is already wired in US-06-005 (when
`conversation.messageCount === 64`). This story extends it to also fire when
`useChatStream` returns `error.code === 'CONVERSATION_FULL'` (the parent should have
prevented the call, but defense in depth).

### Acceptance criteria

- `ConversationView` (US-06-005) is updated:
  - The disabled placeholder composer is removed.
  - When `conversation.messageCount < 64`: render `<Composer phase={chat.phase}
    onSend={chat.send} onStop={chat.stop} />` (US-07-001), where `chat =
    useChatStream(conversationId)`.
  - When `conversation.messageCount === 64`: render the "Conversation full" banner
    (already wired in US-06-005).
  - When `chat.error?.code === 'CONVERSATION_FULL'` (defense-in-depth — the parent
    should re-fetch the conversation to update `messageCount`): also render the
    banner. After the banner renders, `useConversation` is invalidated so the live
    `messageCount` syncs.
- `MessageList` (US-06-002) is updated:
  - Accepts a new optional prop `pendingAssistant?: { text: string; variant: 'streaming' | 'stopped' } | null` (default `null`).
  - When `pendingAssistant !== null`: renders a synthetic `MessageBubble` at the end
    of the list with `role='ASSISTANT'`, `content=pendingAssistant.text`,
    `variant=pendingAssistant.variant`, and a temp `id='pending-assistant'`. The
    bubble is the last virtualized row.
  - The auto-scroll-to-bottom behavior is triggered on every change of
    `pendingAssistant.text` (so the bottom stays visible as the bubble grows).
- `ConversationView` passes `pendingAssistant` to `MessageList` as follows:
  - `chat.phase === 'sending'`: not yet rendered (no text yet). Skip the pending row.
  - `chat.phase === 'streaming'`: render the row with
    `{ text: chat.pendingAssistantText, variant: 'streaming' }`.
  - `chat.phase === 'error'` AND `chat.pendingAssistantText !== ''`: render the row
    with `{ text: chat.pendingAssistantText, variant: 'stopped' }` (greyed — US-07-004
    expands on this).
  - Otherwise: `null`.
- A11y live-region wiring:
  - `ConversationView` renders a visually hidden `<div role="status" aria-live="polite"
    aria-atomic="true">{liveRegionText}</div>` somewhere in its layout tree.
  - `useEffect` on `chat.phase`:
    - `'completed'`: set `liveRegionText` to the full committed assistant content for
      one tick, then clear (so successive announcements work).
    - `'sending' | 'streaming'`: keep `liveRegionText` empty.
  - The message list's `role="log" aria-live="polite"` (US-06-002) stays — but
    `aria-relevant="additions"` is set so the screen reader announces appended USER
    bubbles too. Pending assistant rows are explicitly `aria-hidden="true"` until they
    are committed (the dedicated live-region above handles the completion
    announcement).
- **Component tests** in
  `frontend/src/features/conversations/ConversationView.test.tsx` (extending the suite
  seeded in US-06-005):
  - **Composer integrated**: an enabled `<textarea>` is in the DOM; typing + Cmd+Enter
    fires the `useChatStream.send` path (verified via MSW intercepting the SSE
    request).
  - **Streaming bubble**: with MSW emitting `started → delta → delta`, after the second
    delta a pending ASSISTANT bubble with `variant='streaming'` is in the DOM with
    the concatenated text.
  - **Completed bubble committed**: on `completed`, the pending bubble is replaced by
    a real `MessageBubble` (no `variant`) at the same position; the live-region text
    becomes the assistant content briefly, then clears.
  - **Conversation-full banner (server)**: with `useConversation` returning
    `messageCount === 64`, the banner replaces the composer.
  - **Conversation-full banner (defense in depth)**: `useChatStream.error.code ===
    'CONVERSATION_FULL'` swaps in the banner AND triggers a re-fetch of
    `useConversation`.
  - **Live region**: while `phase === 'streaming'`, the live-region element has empty
    text content (verified by `expect(liveRegion).toHaveTextContent('')`).
  - **`aria-hidden` on pending row**: the pending ASSISTANT bubble has
    `aria-hidden="true"` while streaming; once committed, the live row no longer has
    the attribute.

### Out of scope

- The Stop button's wiring — US-07-004 (this story exposes `chat.stop` to the
  composer, but the integration test for stop-button lives in US-07-004 alongside the
  partial-bubble greying).
- Error toast surfacing — US-07-005.
- A "regenerate response" affordance — not in v1.

### Design references

- `frontend/design/SW-DESIGN.md` §8.3 (`useChatStream` integration), §10.2 (banner +
  composer-disabled UX on `CONVERSATION_FULL`), §11.6 (a11y — log region, delta NOT
  announced, completed announced), §12.8 (`ConversationPage` composer + banner).
- `openapi.yaml` `SseCompletedEvent`, `CONVERSATION_FULL` response.

### Dependencies

- US-07-001 (`Composer`); US-07-002 (`useChatStream`); EPIC-06 US-06-002
  (`MessageList`, `MessageBubble` — the `pendingAssistant` prop is added here),
  US-06-005 (`ConversationView` — the composer placeholder is removed here).

---

## US-07-004 — Cancellation — stop button + page-navigation cleanup + partial-bubble greying

- **Status**: Done
- **Priority**: MUST

**As a** standard end-user
**I want** the Stop button (from US-07-001) and any navigation away from the
conversation to cancel the in-flight SSE stream cleanly, with the partial assistant
bubble preserved on screen as greyed-out "(stopped)" text but NOT persisted by the
backend
**So that** I can stop a runaway response without leaving an orphan request on the
wire (`REQ-STR-003`), and accidentally clicking another chat in the left pane doesn't
strand a live stream in the background.

### Description

The cancellation contract has three layers:

1. **User-initiated** — clicking Stop (US-07-001) calls `useChatStream.stop()`
   (US-07-002), which aborts the `AbortController`. The wire is closed. The backend
   disposes the upstream LLM call per `REQ-STR-003`. The hook transitions to
   `phase='error'` with `error.code='CANCELLED'`.
2. **Navigation-initiated** — when `ConversationView` unmounts (the user navigates
   to a different conversation or any other page), the `useChatStream` hook's
   cleanup effect calls `controller.abort()`. Same wire-closing behavior.
3. **Visual preservation** — the partial `pendingAssistantText` is preserved by the
   hook (per US-07-002). `ConversationView` (US-07-003) already renders it with
   `variant='stopped'` (greyed). This story adds the explicit suppression of the
   error toast for `code='CANCELLED'` so cancelling doesn't fire a "Something went
   wrong" message at the user — they were the cause.

The greying contract:

- The partial bubble has `variant='stopped'`; `MessageBubble` (US-06-002) already
  renders it with reduced opacity and a "(stopped)" caption.
- The partial bubble has `id='pending-assistant-stopped'` (distinct from the live
  streaming `id='pending-assistant'`) so a future re-arm doesn't collide.
- The partial bubble is **not** persisted — re-mounting the conversation (e.g. via
  page reload) shows only the messages the backend persisted, which excludes the
  stopped partial. The visual greyed bubble is a session-only courtesy.

### Acceptance criteria

- `useChatStream` (US-07-002) is verified to already abort on `stop()` and to
  preserve `pendingAssistantText`. This story adds the **navigation cleanup**:
  - In a `useEffect(() => () => controller.abort(), [conversationId])` cleanup, the
    hook aborts the controller on unmount AND on `conversationId` change. The
    state for the previous conversation is wiped.
  - The story includes a regression test that mounting the hook for `c1`, sending,
    then unmounting (or changing to `c2`) aborts the controller.
- `Composer` (US-07-001) is verified to call `onStop()` on Stop click and on Esc.
- **Error-toast suppression for `'CANCELLED'`**:
  - The shared toast routing (US-07-005 wires the full table; this story adds the
    one entry it depends on) recognises `code === 'CANCELLED'` and does NOT fire a
    toast.
  - The visual partial bubble is the user-visible confirmation that the cancel
    succeeded.
- **Partial-bubble greying integration**:
  - When `chat.phase === 'error' && chat.error?.code === 'CANCELLED' &&
    chat.pendingAssistantText !== ''`, `ConversationView` renders the
    `MessageList`'s `pendingAssistant` row with `variant='stopped'`. (This is the
    same wiring as US-07-003's `variant='stopped'` branch — but for an explicit
    `CANCELLED` code rather than the generic mid-stream `error` frame.)
- **Component tests** in
  `frontend/src/features/conversations/useChatStream.test.tsx` (extending US-07-002):
  - **Stop aborts the controller**: `send('hi')`, after the first `delta`, call
    `stop()`; the underlying `controller.signal.aborted === true`; the MSW handler
    sees an aborted request (verified by the handler's request being aborted on
    the server side).
  - **Unmount aborts**: render the hook in a wrapper, call `send`, then unmount;
    the controller is aborted.
  - **`conversationId` change aborts**: re-render the hook with a different
    `conversationId`; the previous controller is aborted; the new turn starts
    with fresh state.
- **Integration test** in
  `frontend/src/features/conversations/ConversationView.test.tsx`:
  - **Stop button visible while streaming**: with MSW emitting frames, while
    `phase === 'streaming'` the Stop button is in the DOM.
  - **Click Stop**: clicking Stop greys the pending bubble (variant changes to
    `stopped`); no error toast appears; the Composer's Send button comes back.
  - **Esc while streaming**: same outcome as clicking Stop.
- **Integration test** in `frontend/src/pages/chat/ChatPage.test.tsx` (extending
  US-06-006):
  - **Navigation aborts**: start a turn on `/chat/c1`, then click `c2` in the left
    pane; the URL changes to `/chat/c2`; the previous controller is aborted (MSW
    sees the abort); no error toast appears.

### Out of scope

- A "retry" affordance after a cancel — the user can simply type and send again;
  the composer is re-enabled automatically.
- Persisting the stopped partial bubble in the backend — explicitly out per
  SW-DESIGN §8.4 ("we keep it for transparency, but … do not persist it").
- A "resume from where I stopped" affordance — the spec does not support resume.

### Design references

- `frontend/design/SW-DESIGN.md` §8.4 (cancellation — both stop-button and
  page-navigation paths, partial-bubble preservation, no-persist), §10.2 (toast
  routing — the `'CANCELLED'` sentinel is the new entry).
- `openapi.yaml` `REQ-STR-003` (cancellation propagation to the backend, surfaced
  in the openapi description of `POST /conversations/{id}/messages`).

### Dependencies

- US-07-002 (`useChatStream` — the abort plumbing); US-07-003 (`ConversationView` —
  the `pendingAssistant` prop with the `stopped` variant); EPIC-06 US-06-002
  (`MessageBubble` `variant='stopped'`).

---

## US-07-005 — End-to-end SSE integration tests — golden path, all error paths, 64-cap, 406

- **Status**: Done
- **Priority**: MUST

**As a** frontend developer landing the chat surface
**I want** a single integration-test file that mounts `ConversationPage` under the
full provider stack, drives the SSE flow via a hand-rolled `text/event-stream`
response from MSW, and asserts every contract that EPIC-07 introduces — golden path,
mid-stream `LLM_UNAVAILABLE` / `MCP_SERVER_ERROR`, 409 `CONVERSATION_FULL`, 406,
content-cap, cancel, and the per-error toast routing
**So that** every cell of the SW-DESIGN §8.5 table has a regression-locked test, and
the chat surface ships with the same coverage discipline as the auth surface
(EPIC-03).

### Description

This story is the **gate** on EPIC-07 — it locks the streaming contract behind a
test suite that, if it goes red, the chat surface does not ship.

It also wires the **per-code toast routing** for the codes that surface only on the
chat surface:

- `LLM_UNAVAILABLE`: toast `Something went wrong with the LLM — try again.` per
  SW-DESIGN §10.2.
- `MCP_SERVER_ERROR`: toast `An MCP server failed — try again.` per §10.2.
- `CONVERSATION_FULL`: no toast — the banner is the surface (§10.2).
- `NOT_ACCEPTABLE` (406): toast + `console.error` log (engineering bug per §10.2;
  should not occur).
- `RATE_LIMITED` (429): toast with countdown per the EPIC-03 pattern.
- `CANCELLED` (client-only): no toast (US-07-004).
- Pre-stream `400 VALIDATION_ERROR`: inline error in the composer (the
  client-side cap should have caught it; defense in depth).

The toast routing table is materialized in the `errorCopy` map
(`shared/i18n/en.ts`) — most entries already exist from EPIC-03; this story adds
the chat-specific ones and the per-code toast policy.

### Acceptance criteria

- The `errorCopy` map (`shared/i18n/en.ts`) is extended:
  - `LLM_UNAVAILABLE`: `{ title: 'LLM unavailable', detail: 'Something went wrong with the LLM — try again.', toast: 'on' }`.
  - `MCP_SERVER_ERROR`: `{ title: 'MCP server error', detail: 'An MCP server failed — try again.', toast: 'on' }`.
  - `CONVERSATION_FULL`: `{ title: 'Conversation full', detail: '…', toast: 'off' }`.
  - `NOT_ACCEPTABLE`: `{ title: 'Not acceptable', detail: '…', toast: 'on' }`.
  - `CANCELLED`: `{ title: 'Cancelled', detail: null, toast: 'off' }`.
- A shared toast dispatcher (already in EPIC-02 / EPIC-11) reads `errorCopy[code].toast`
  to decide whether to fire the toast. The hook does NOT itself fire toasts — the
  `ConversationView` `useEffect` watching `chat.error` does.
- **Integration tests** in
  `frontend/src/pages/chat/ConversationPage.streaming.test.tsx` (or
  `ConversationView.streaming.test.tsx` if preferred — single file, ≥ 8
  scenarios):
  - **Golden path**: type "hi" + Cmd+Enter; MSW emits `started, delta('Hello'),
    delta(', world!'), completed { title: 'Hello, world!', messageCount: 2 }`.
    - The optimistic USER bubble renders immediately.
    - The streaming caret appears after `started`.
    - The assistant text grows monotonically across deltas.
    - On `completed`, the assistant bubble is committed; the conversation's title
      in the topbar AND in the left-pane list is patched to `Hello, world!`; the
      message-count chip becomes `2 / 64`.
    - No toast is fired.
  - **First-turn title only**: a second `send` with `completed { title: null,
    messageCount: 4 }` does NOT patch the title (the left-pane list and topbar
    keep the existing title); `messageCount` updates to 4.
  - **Mid-stream `LLM_UNAVAILABLE`**: MSW emits `started, delta('partial')`, then
    `error { code: 'LLM_UNAVAILABLE' }`.
    - The partial bubble is preserved as greyed (`variant='stopped'`).
    - A toast `LLM unavailable — Something went wrong with the LLM — try again.`
      appears (`aria-live='polite'`).
    - The USER bubble stays committed.
    - The composer's Send button returns to the enabled state for a retry.
  - **Mid-stream `MCP_SERVER_ERROR`**: same scenario as above with
    `code: 'MCP_SERVER_ERROR'`; toast text matches the new `errorCopy` entry.
  - **`409 CONVERSATION_FULL` pre-stream**: MSW responds 409 before any frame.
    - The optimistic USER bubble is rolled back from the cache.
    - The composer is replaced by the "Conversation full" banner.
    - The "Start a new conversation" CTA navigates to
      `/chat/new?agentId=<agentId>`.
    - No toast is fired.
  - **`406` (engineering bug)**: MSW responds 406; a toast with the
    `NOT_ACCEPTABLE` copy appears AND `console.error` is called once (assert with
    a `vi.spyOn(console, 'error')`).
  - **`429 RATE_LIMITED` with `Retry-After: 5`**: MSW responds 429; a toast with a
    5-second countdown appears (the standard rate-limit copy from EPIC-03).
  - **Content cap defense**: type 1025 characters; the inline "Message too long"
    alert renders; Send is disabled; Cmd+Enter does NOT fire (no MSW request
    intercepted).
  - **Stop button** (extends US-07-004): MSW emits frames; while streaming,
    click Stop; the partial bubble greys; NO toast appears; the Send button
    re-appears.
  - **Page navigation aborts** (extends US-07-004): start a turn on `/chat/c1`;
    click `c2` in the left pane; MSW's handler observes an aborted request; no
    toast.
- **Bundle-size sanity** — the dedicated test file imports `ChatPage`,
  `ConversationPage`, `ConversationView`, `useChatStream`, `Composer`, and the
  EPIC-02 SSE primitive. The test runs in < 5 seconds locally (no real network);
  if any single assertion times out, the cause is in the test setup, not the
  primitive — the SSE primitive is unit-tested in EPIC-02 and the hook is
  unit-tested in US-07-002.

### Out of scope

- An E2E (Playwright) layer — deferred per SW-DESIGN §17 TBD-F7 and TBD-F1.
- Provider-specific error mapping (e.g., OpenAI 429 vs Anthropic 429) — the
  backend collapses these into `LLM_UNAVAILABLE`.
- Streaming bubble Markdown rendering — TBD-F2.

### Design references

- `frontend/design/SW-DESIGN.md` §8.5 (error table — every row of which is a test
  case here), §10.2 (per-code routing — toast vs banner vs inline), §11.5 (no
  per-character animation; the test still works with the post-mount snapshot
  approach), §13.3 (streaming tests guidance).
- `openapi.yaml` `POST /conversations/{conversationId}/messages` — every documented
  response status code is exercised.

### Dependencies

- US-07-001 (`Composer`); US-07-002 (`useChatStream`); US-07-003 (`ConversationView`
  SSE wiring); US-07-004 (cancellation); EPIC-02 (`Toast`, `errorCopy`,
  `streamChat`).

---

## Summary

| ID         | Title                                                                                              | Priority | Status |
|------------|----------------------------------------------------------------------------------------------------|----------|--------|
| US-07-001  | `Composer` — textarea + N/1024 counter + Cmd/Ctrl+Enter + send / stop buttons                       | MUST     | Done   |
| US-07-002  | `useChatStream` — state machine, frame handling, first-turn title cache patch, `startTransition`    | MUST     | Done   |
| US-07-003  | `ConversationView` SSE wiring — replace placeholder, `aria-live` log, conversation-full banner      | MUST     | Done   |
| US-07-004  | Cancellation — stop button + page-navigation cleanup + partial-bubble greying                       | MUST     | Done   |
| US-07-005  | End-to-end SSE integration tests — golden path, all error paths, 64-cap, 406                        | MUST     | Done   |

EPIC-07 is **Done** when all five stories above are `Done`. The chat surface — the value
moment of the product — is then end-to-end usable. The next steps move into the admin
surface: EPIC-08 (Users), EPIC-09 (API keys), EPIC-10 (Rate limit).
