import { z } from 'zod';
import { err, ok, type Result } from '@/shared/lib/result';

/**
 * Discriminated union over the four named SSE frame types defined by the
 * backend SSE contract (`openapi.yaml` /conversations/{id}/messages POST).
 * The `error` frame's `problem` is the raw RFC 7807 body — converted to
 * `ApiError` by `chatStream.ts` so consumers only see one error type.
 */
export type SseFrame =
  | { type: 'started'; userMessageId: string; conversationId: string }
  | { type: 'delta'; text: string }
  | {
      type: 'completed';
      assistantMessageId: string;
      title: string | null;
      messageCount: number;
    }
  | { type: 'error'; problem: Record<string, unknown> };

export const SSE_FRAME_TYPES = ['started', 'delta', 'completed', 'error'] as const;
export type SseFrameType = (typeof SSE_FRAME_TYPES)[number];

const startedSchema = z.object({
  userMessageId: z.string().min(1),
  conversationId: z.string().min(1),
});

const deltaSchema = z.object({
  text: z.string(),
});

const completedSchema = z.object({
  assistantMessageId: z.string().min(1),
  title: z.string().nullable(),
  messageCount: z.number().int().nonnegative(),
});

const FRAME_NAMES = new Set<string>(SSE_FRAME_TYPES);

export type SseParseError = 'unknown-type' | 'malformed-data';

/**
 * Parse a raw SSE event into a typed `SseFrame`. The error frame is
 * intentionally validated loosely (any JSON object passes) because the body
 * is an RFC 7807 problem details that `chatStream.ts` will normalize via
 * `ApiError`. The other three frame types are strictly Zod-validated.
 */
export function parseSseFrame(
  eventName: string,
  dataJson: string,
): Result<SseFrame, SseParseError> {
  if (!FRAME_NAMES.has(eventName)) return err('unknown-type');

  let raw: unknown;
  try {
    raw = JSON.parse(dataJson);
  } catch {
    return err('malformed-data');
  }

  if (eventName === 'started') {
    const parsed = startedSchema.safeParse(raw);
    if (!parsed.success) return err('malformed-data');
    return ok({ type: 'started', ...parsed.data });
  }
  if (eventName === 'delta') {
    const parsed = deltaSchema.safeParse(raw);
    if (!parsed.success) return err('malformed-data');
    return ok({ type: 'delta', ...parsed.data });
  }
  if (eventName === 'completed') {
    const parsed = completedSchema.safeParse(raw);
    if (!parsed.success) return err('malformed-data');
    return ok({ type: 'completed', ...parsed.data });
  }
  // 'error' frame: accept any object body; the caller normalizes via ApiError.
  if (typeof raw === 'object' && raw !== null) {
    return ok({ type: 'error', problem: raw as Record<string, unknown> });
  }
  return err('malformed-data');
}
