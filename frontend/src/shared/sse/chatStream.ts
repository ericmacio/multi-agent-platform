import { fetchEventSource } from '@microsoft/fetch-event-source';
import { env } from '@/env';
import { ApiError, buildApiErrorFromBody, normalizeResponse } from '@/shared/api/errors';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { parseSseFrame, type SseFrame } from './sseFrames';

export type StreamChatOptions = {
  signal: AbortSignal;
  onFrame: (frame: SseFrame) => void;
};

function apiErrorFromProblem(problem: Record<string, unknown>): ApiError {
  const status = typeof problem['status'] === 'number' ? (problem['status'] as number) : 502;
  return buildApiErrorFromBody(status, problem);
}

type Outcome = { kind: 'pending' } | { kind: 'completed' } | { kind: 'errored'; error: unknown };

/**
 * Submit a chat message and stream the assistant response. The function:
 * - sets `Accept: text/event-stream` + `Authorization: Bearer <token>`,
 * - POSTs `{ content }` to `/conversations/{conversationId}/messages`,
 * - delivers each typed frame via `opts.onFrame`,
 * - resolves when the `completed` frame has been delivered and the stream closes,
 * - rejects with `ApiError` on HTTP errors before the stream opens OR on
 *   `error` frames mid-stream,
 * - rejects with `DOMException('AbortError')` when `opts.signal.abort()` fires.
 *
 * Reconnection is disabled — chat sends are one-shot.
 */
export function streamChat(
  conversationId: string,
  content: string,
  opts: StreamChatOptions,
): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const bundle = tokenStorage.get();
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    };
    if (bundle) headers['Authorization'] = `Bearer ${bundle.token}`;

    const url = `${env.VITE_API_BASE_URL}/conversations/${encodeURIComponent(conversationId)}/messages`;

    let outcome: Outcome = { kind: 'pending' };

    fetchEventSource(url, {
      method: 'POST',
      headers,
      body: JSON.stringify({ content }),
      signal: opts.signal,
      openWhenHidden: true,
      // Resolve `globalThis.fetch` at call time so MSW (and any future
      // monkey-patch) is honored. Mirrors the trick in `client.ts`.
      fetch: ((input: RequestInfo, init?: RequestInit) =>
        globalThis.fetch(input, init)) as typeof fetch,

      async onopen(response) {
        if (!response.ok) {
          const apiErr = await normalizeResponse(response);
          outcome = { kind: 'errored', error: apiErr };
          throw apiErr;
        }
        // The native lib also asserts content-type; skipping that here is
        // fine — backend always sends `text/event-stream` for 200 on this
        // endpoint, and MSW handlers in tests do the same.
      },

      onmessage(ev) {
        const eventName = ev.event || 'message';
        if (!ev.data) return; // ignore keepalive / comment-only events
        const parsed = parseSseFrame(eventName, ev.data);
        if (!parsed.ok) {
          if (parsed.error === 'unknown-type') {
            // eslint-disable-next-line no-console
            console.warn('streamChat: dropping unknown SSE frame', eventName);
          } else {
            // eslint-disable-next-line no-console
            console.error('streamChat: malformed SSE data for event', eventName);
          }
          return;
        }
        const frame = parsed.value;
        opts.onFrame(frame);
        if (frame.type === 'completed') {
          outcome = { kind: 'completed' };
          return;
        }
        if (frame.type === 'error') {
          // Convert the problem details to an ApiError synchronously and
          // throw it to short-circuit the stream. `onerror` rethrows, which
          // causes the outer promise to reject (then routed via the outcome).
          const apiErr = apiErrorFromProblem(frame.problem);
          outcome = { kind: 'errored', error: apiErr };
          throw apiErr;
        }
      },

      // No automatic reconnect — chat sends are one-shot.
      onerror(error: unknown) {
        if (outcome.kind === 'pending') {
          outcome = { kind: 'errored', error };
        }
        throw error;
      },
    }).then(
      () => {
        // Library resolves on clean close OR on signal abort.
        if (opts.signal.aborted && outcome.kind === 'pending') {
          reject(new DOMException('Aborted', 'AbortError'));
          return;
        }
        if (outcome.kind === 'completed') {
          resolve();
          return;
        }
        if (outcome.kind === 'errored') {
          reject(outcome.error);
          return;
        }
        reject(
          new ApiError({
            status: 0,
            code: 'INTERNAL_ERROR',
            title: 'Stream closed unexpectedly',
            detail: 'The SSE stream closed before a `completed` frame was received.',
          }),
        );
      },
      (libErr) => {
        if (opts.signal.aborted) {
          reject(new DOMException('Aborted', 'AbortError'));
          return;
        }
        if (outcome.kind === 'errored') {
          reject(outcome.error);
          return;
        }
        reject(libErr);
      },
    );
  });
}
