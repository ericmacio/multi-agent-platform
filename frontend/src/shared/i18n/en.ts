import type { components } from '@/generated/schema';

/**
 * Stable machine-readable error code from `ProblemDetails.code` in the OpenAPI
 * contract. Sourced from the generated schema so a backend change to the enum
 * surfaces as a TypeScript compile error here and in every downstream consumer
 * (US-02-002's `ApiError`, the toast queue, the per-code routing in
 * SW-DESIGN §10.2).
 *
 * Includes the client-only sentinel `'CANCELLED'` (US-07-002) emitted when the
 * user aborts a streaming chat turn. Never produced by the backend.
 */
export type ProblemCode =
  | components['schemas']['ProblemDetails']['code']
  | 'CANCELLED';

/**
 * Per-code toast policy (SW-DESIGN §10.2). `'on'` means the chat surface
 * (or any consumer of the policy) should surface a toast for this error.
 * `'off'` means the UI handles the error via a dedicated surface (banner,
 * inline alert) and a toast would be noise.
 */
export type ToastPolicy = 'on' | 'off';

/**
 * Copy entry: the short `title` is meant for toasts / form banners; `detail`
 * is the longer human-readable fallback rendered when no per-call detail is
 * available from the server. `toast` is the routing decision documented in
 * SW-DESIGN §10.2 — consumers MUST consult this rather than hard-coding
 * silent-code allowlists at the call site (US-07-005).
 */
export type ErrorCopyEntry = { title: string; detail: string; toast: ToastPolicy };

/**
 * Single source of user-facing copy keyed by `ProblemCode`, plus a `__unknown__`
 * fallback bucket for forward-compatibility (an `ApiError` whose `code` is not
 * in the current enum — e.g., a future backend addition).
 *
 * Per-code UX routing (toast vs form alert vs redirect) is documented in
 * SW-DESIGN §10.2; this map only owns the strings.
 */
export const errorCopy: Record<ProblemCode | '__unknown__', ErrorCopyEntry> = {
  VALIDATION_ERROR: {
    title: 'Validation error',
    detail: 'One or more fields are invalid. Review the highlighted entries and try again.',
    // Forms surface their own per-field errors; a toast would duplicate.
    toast: 'off',
  },
  INVALID_CREDENTIALS: {
    title: 'Invalid credentials',
    detail: 'The email or password is incorrect.',
    // Login form renders its own alert; auth middleware also redirects to /login.
    toast: 'off',
  },
  MUST_CHANGE_PASSWORD: {
    title: 'Password change required',
    detail: 'Change your password before continuing.',
    // Guard redirect handles this — no toast.
    toast: 'off',
  },
  FORBIDDEN: {
    title: 'Forbidden',
    detail: 'You are not allowed to perform this action.',
    toast: 'on',
  },
  NOT_FOUND: {
    title: 'Not found',
    detail: 'The requested resource does not exist.',
    // Pages render their own empty-state for 404s.
    toast: 'off',
  },
  METHOD_NOT_ALLOWED: {
    title: 'Method not allowed',
    detail: 'This operation is not supported on this resource.',
    toast: 'on',
  },
  CONFLICT: {
    title: 'Conflict',
    detail: 'The action conflicts with the current state of the resource.',
    toast: 'on',
  },
  DUPLICATE_AGENT_NAME: {
    title: 'Duplicate agent name',
    detail: 'An agent with this name already exists. Pick a different name.',
    // AgentForm shows this inline on the name field.
    toast: 'off',
  },
  NESTED_TEAM_FORBIDDEN: {
    title: 'Nested team forbidden',
    detail: 'Team members cannot themselves have a team. Pick agents with an empty team.',
    toast: 'off',
  },
  CROSS_OWNER_TEAM_MEMBER: {
    title: 'Team member not allowed',
    detail: 'Team members must be agents you own.',
    toast: 'off',
  },
  CONVERSATION_FULL: {
    title: 'Conversation full',
    detail: 'This conversation has reached the 64-message cap. Start a new conversation.',
    // The "Conversation full" banner is the surface; no toast.
    toast: 'off',
  },
  RATE_LIMITED: {
    title: 'Too many requests',
    detail: 'Slow down and retry shortly.',
    toast: 'on',
  },
  LLM_UNAVAILABLE: {
    title: 'AI provider unavailable',
    detail: 'The language model is temporarily unreachable. Please retry.',
    toast: 'on',
  },
  MCP_SERVER_ERROR: {
    title: 'Tool server error',
    detail: 'A tool server failed to respond. Please retry.',
    toast: 'on',
  },
  NOT_ACCEPTABLE: {
    title: 'Not acceptable',
    detail: 'The request could not be served in the requested format.',
    // Engineering bug — should not occur in production. Toast + console.error.
    toast: 'on',
  },
  INTERNAL_ERROR: {
    title: 'Internal error',
    detail: 'Something went wrong — please retry.',
    toast: 'on',
  },
  CANCELLED: {
    title: 'Cancelled',
    detail: 'You stopped the response.',
    // User-initiated — the partial bubble is the visible confirmation.
    toast: 'off',
  },
  __unknown__: {
    title: 'Unexpected error',
    detail: 'Something went wrong — please retry.',
    toast: 'on',
  },
};

/**
 * UI label map. Feature EPICs append to this object as they land; the empty
 * starter shape is fixed here so consumers can `import { labels } from
 * '@/shared/i18n/en'` from the first feature slice onward without restructuring.
 */
export const labels: Record<string, string> = {};
