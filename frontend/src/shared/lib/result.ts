/**
 * Tiny `Result<T,E>` discriminated union for flows that must not throw —
 * e.g., JWT decode, JSON.parse of an SSE frame payload. The throwing API
 * stays available where it makes sense (HTTP middleware, render-time
 * exceptions caught by the root error boundary); `Result` covers the
 * deliberately-non-throwing paths.
 */

export type Result<T, E = Error> = { ok: true; value: T } | { ok: false; error: E };

export const ok = <T>(value: T): Result<T, never> => ({ ok: true, value });

export const err = <E>(error: E): Result<never, E> => ({ ok: false, error });
