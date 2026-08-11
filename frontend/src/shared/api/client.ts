import createClient, { type Middleware } from 'openapi-fetch';
import type { paths } from '@/generated/schema';
import { env } from '@/env';
import { isExpired } from '@/shared/lib/date';
import { decodeJwtPayload } from '@/shared/auth/jwt';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { ApiError, normalizeResponse } from './errors';

function dispatchAuthLogout(reason: 'token-expired' | 'token-rejected'): void {
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
    window.dispatchEvent(new CustomEvent('auth:logout', { detail: { reason } }));
  }
}

/**
 * Auth middleware. Reads the live bundle from `tokenStorage`, validates the
 * JWT payload, and either:
 * - injects `Authorization: Bearer <token>` when the JWT is well-formed and not expired, or
 * - short-circuits with `ApiError(code='INVALID_CREDENTIALS')` when it is
 *   missing-but-not-null (corrupt), expired, or otherwise unusable — saving a
 *   guaranteed-401 round-trip and producing the same downstream UX as a real
 *   server-rejected token.
 */
const authMiddleware: Middleware = {
  onRequest({ request }) {
    const bundle = tokenStorage.get();
    if (!bundle) return undefined;

    const decoded = decodeJwtPayload(bundle.token);
    if (!decoded.ok) {
      tokenStorage.clear();
      dispatchAuthLogout('token-expired');
      throw ApiError.synthesized('INVALID_CREDENTIALS', 401, 'Malformed JWT in storage');
    }
    if (isExpired(decoded.value.exp)) {
      tokenStorage.clear();
      dispatchAuthLogout('token-expired');
      throw ApiError.synthesized('INVALID_CREDENTIALS', 401, 'Token expired');
    }

    request.headers.set('Authorization', `Bearer ${bundle.token}`);
    return request;
  },
};

/**
 * Error + auth-failure middleware. SW-DESIGN §7.2 describes these as two
 * separate middlewares; collapsing them into one `onResponse` is the
 * lowest-friction factoring because the auth-failure side effect needs the
 * normalized status anyway.
 */
const errorMiddleware: Middleware = {
  async onResponse({ response }) {
    if (response.ok) return undefined;
    const err = await normalizeResponse(response);
    if (err.status === 401 && tokenStorage.get() !== null) {
      // Only treat 401 as "session terminated" when there was actually a
      // session. A 401 from `POST /auth/login` (wrong credentials) on an
      // un-authed client is just a form error and must NOT trigger the
      // global session-expired UX (US-03-007).
      tokenStorage.clear();
      dispatchAuthLogout('token-rejected');
    }
    throw err;
  },
};

export const api = createClient<paths>({
  baseUrl: env.VITE_API_BASE_URL,
  headers: { Accept: 'application/json' },
  // Resolve `globalThis.fetch` at call time rather than at createClient time.
  // Without this thunk, openapi-fetch captures the unpatched fetch at module
  // load, defeating MSW's request interceptor in tests (and any future
  // monkey-patch in prod).
  fetch: (request) => globalThis.fetch(request),
});

api.use(authMiddleware);
api.use(errorMiddleware);

/**
 * Drops the openapi-fetch `{ data, error }` envelope and returns `data`. The
 * error middleware throws on every non-2xx, so `error` is always undefined at
 * runtime; the defensive throw here protects against a middleware regression.
 *
 * For 204 endpoints (`DELETE`, `logout`), `data` is undefined — callers that
 * expect a body must assert it.
 */
export function unwrap<T>(result: { data?: T; error?: unknown }): T | undefined {
  if (result.error !== undefined) {
    throw result.error instanceof Error ? result.error : new Error(String(result.error));
  }
  return result.data;
}
