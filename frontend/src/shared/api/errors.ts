import type { components } from '@/generated/schema';
import { errorCopy } from '@/shared/i18n/en';

/**
 * Stable machine-readable error code from `ProblemDetails.code` in the OpenAPI
 * contract. Sourced from the generated schema so a backend enum change
 * surfaces as a TypeScript compile error in every consumer.
 *
 * Includes the client-only sentinel `'CANCELLED'` (US-07-002) — emitted when
 * the user aborts a streaming chat turn. It is NOT part of the openapi spec
 * and is never produced by the backend.
 */
export type ProblemCode =
  | components['schemas']['ProblemDetails']['code']
  | 'CANCELLED';

type ProblemDetailsBody = components['schemas']['ProblemDetails'];

export type FieldError = { field: string; message: string };

export type ApiErrorInit = {
  status: number;
  code: ProblemCode | '__unknown__';
  title: string;
  detail?: string;
  type?: string;
  instance?: string;
  fieldErrors?: Record<string, string>;
  retryAfterSeconds?: number;
  cause?: unknown;
};

/**
 * Single error type that crosses the HTTP boundary. The auth middleware
 * (US-02-003) throws this; feature hooks consume the typed `code`; the toast
 * queue reads `errorCopy[code]` for user-facing copy; `react-hook-form`
 * consumes `fieldErrors` via `setError`.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ProblemCode | '__unknown__';
  readonly title: string;
  readonly detail?: string;
  readonly type?: string;
  readonly instance?: string;
  readonly fieldErrors: Record<string, string>;
  readonly retryAfterSeconds?: number;
  override readonly cause?: unknown;

  constructor(init: ApiErrorInit) {
    super(init.detail ?? init.title);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.title = init.title;
    this.detail = init.detail;
    this.type = init.type;
    this.instance = init.instance;
    this.fieldErrors = init.fieldErrors ?? {};
    this.retryAfterSeconds = init.retryAfterSeconds;
    this.cause = init.cause;
  }

  override toString(): string {
    return `${this.code}: ${this.title}${this.detail ? ' — ' + this.detail : ''}`;
  }

  /**
   * Build an `ApiError` without a `Response` — used by the auth middleware's
   * expired-token short-circuit (US-02-003) where there is nothing to fetch.
   */
  static synthesized(code: ProblemCode, status: number, detail?: string): ApiError {
    const title = errorCopy[code]?.title ?? errorCopy.__unknown__.title;
    return new ApiError({ code, status, title, detail });
  }
}

// Runtime mirror of the `ProblemCode` enum. TypeScript's `type` declarations
// have no runtime representation, so we maintain this set explicitly. The
// `en.test.ts` whitebox check (US-02-001) catches a backend enum change that
// adds a code without updating both lists.
const KNOWN_CODES = new Set<ProblemCode>([
  'VALIDATION_ERROR',
  'INVALID_CREDENTIALS',
  'MUST_CHANGE_PASSWORD',
  'FORBIDDEN',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'CONFLICT',
  'DUPLICATE_AGENT_NAME',
  'NESTED_TEAM_FORBIDDEN',
  'CROSS_OWNER_TEAM_MEMBER',
  'CONVERSATION_FULL',
  'RATE_LIMITED',
  'LLM_UNAVAILABLE',
  'MCP_SERVER_ERROR',
  'NOT_ACCEPTABLE',
  'INTERNAL_ERROR',
  // 'CANCELLED' is a client-only sentinel (US-07-002) — it never arrives on
  // the wire, so it is intentionally excluded from this server-code allowlist.
]);

function isObject(body: unknown): body is Record<string, unknown> {
  return typeof body === 'object' && body !== null && !Array.isArray(body);
}

function parseRetryAfter(headerValue: string | null): number | undefined {
  if (!headerValue) return undefined;
  // RFC 7231 also permits an HTTP-date form; backend only emits the integer
  // form (per openapi `RateLimited.headers.Retry-After`), so we reject anything
  // else loudly rather than guess at a date parse.
  const trimmed = headerValue.trim();
  if (!/^\d+$/.test(trimmed)) return undefined;
  const n = Number.parseInt(trimmed, 10);
  return Number.isFinite(n) ? n : undefined;
}

function buildFieldErrors(errors: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (!Array.isArray(errors)) return out;
  for (const entry of errors) {
    if (
      isObject(entry) &&
      typeof entry['field'] === 'string' &&
      typeof entry['message'] === 'string'
    ) {
      out[entry['field']] = entry['message']; // last-wins on duplicates (defensive)
    }
  }
  return out;
}

/**
 * Synchronous problem-body → `ApiError` builder. Reused by:
 * - `normalizeResponse` (async wrapper that handles the `response.text()` read),
 * - the SSE chat stream (US-02-012), which needs a sync path because the
 *   error-frame body is already in memory.
 */
export function buildApiErrorFromBody(
  status: number,
  body: unknown,
  retryAfterSeconds?: number,
  cause?: unknown,
): ApiError {
  if (!isObject(body)) {
    return new ApiError({
      status,
      code: 'INTERNAL_ERROR',
      title: errorCopy.INTERNAL_ERROR.title,
      detail: 'Malformed error response',
      cause,
      retryAfterSeconds,
    });
  }

  const rawCode = typeof body['code'] === 'string' ? body['code'] : undefined;
  const code: ProblemCode | '__unknown__' =
    rawCode && KNOWN_CODES.has(rawCode as ProblemCode) ? (rawCode as ProblemCode) : '__unknown__';

  const rawTitle = typeof body['title'] === 'string' ? body['title'] : undefined;
  const title =
    rawTitle ?? (code === '__unknown__' ? errorCopy.__unknown__.title : errorCopy[code].title);

  const detail = typeof body['detail'] === 'string' ? body['detail'] : undefined;
  const type = typeof body['type'] === 'string' ? body['type'] : undefined;
  const instance = typeof body['instance'] === 'string' ? body['instance'] : undefined;
  const fieldErrors = buildFieldErrors((body as Partial<ProblemDetailsBody>)['errors']);

  return new ApiError({
    status,
    code,
    title,
    detail,
    type,
    instance,
    fieldErrors,
    retryAfterSeconds,
  });
}

/**
 * Convert a non-2xx `Response` to an `ApiError`. Tolerant of:
 * - missing or non-JSON body → `INTERNAL_ERROR` with `cause` set,
 * - unknown `code` values → `__unknown__` forward-compat bucket,
 * - missing `errors[]` → empty `fieldErrors`.
 */
export async function normalizeResponse(response: Response): Promise<ApiError> {
  const status = response.status;
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'));

  let body: unknown;
  try {
    const text = await response.text();
    body = text ? JSON.parse(text) : undefined;
  } catch (cause) {
    return new ApiError({
      status,
      code: 'INTERNAL_ERROR',
      title: errorCopy.INTERNAL_ERROR.title,
      detail: 'Malformed error response',
      cause,
      retryAfterSeconds,
    });
  }

  return buildApiErrorFromBody(status, body, retryAfterSeconds);
}
