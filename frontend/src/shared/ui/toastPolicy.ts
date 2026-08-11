import { errorCopy } from '@/shared/i18n/en';
import { toast } from './Toast';

/**
 * Stable dedup key for every rate-limit-driven toast. The Toast primitive
 * replaces in place on matching `key` (last-write-wins), so all countdown
 * ticks + concurrent bursts collapse onto a single visible toast.
 */
const RATE_LIMIT_TOAST_KEY = 'rate-limit';

let countdownTimer: ReturnType<typeof setTimeout> | undefined;
let currentToastId: string | undefined;

function clearCountdown(): void {
  if (countdownTimer !== undefined) {
    clearTimeout(countdownTimer);
    countdownTimer = undefined;
  }
}

function messageFor(remaining: number): string {
  const title = errorCopy.RATE_LIMITED.title;
  if (remaining <= 0) return `${title} — retry shortly.`;
  return `${title}. Try again in ${remaining}s.`;
}

function pushRateLimitToast(message: string, durationMs: number | null): string {
  return toast.push({
    type: 'warning',
    key: RATE_LIMIT_TOAST_KEY,
    message,
    durationMs,
  });
}

function scheduleTick(remaining: number): void {
  clearCountdown();
  countdownTimer = setTimeout(() => {
    const next = remaining - 1;
    if (next <= 0) {
      // Countdown reached zero — remove the toast; a subsequent 429 will
      // start a fresh countdown from its own Retry-After header.
      if (currentToastId !== undefined) {
        toast.dismiss(currentToastId);
        currentToastId = undefined;
      }
      countdownTimer = undefined;
      return;
    }
    currentToastId = pushRateLimitToast(messageFor(next), null);
    scheduleTick(next);
  }, 1_000);
}

/**
 * Emit (or replace, on `key`-based dedup) a single `RATE_LIMITED` toast. If
 * `retryAfterSeconds` is a positive integer, a live countdown ticks the
 * message in place once per second until it reaches zero and self-dismisses.
 *
 * Callers do NOT own the timer lifetime — the helper cleans up when the
 * countdown zeroes, when a new 429 arrives (the new call cancels the pending
 * timer), or when {@link resetRateLimitToast} runs in a test teardown.
 */
export function showRateLimitedToast(retryAfterSeconds: number | undefined): void {
  const seconds =
    typeof retryAfterSeconds === 'number' && Number.isFinite(retryAfterSeconds)
      ? Math.max(0, Math.floor(retryAfterSeconds))
      : 0;

  clearCountdown();
  currentToastId = pushRateLimitToast(messageFor(seconds), null);

  if (seconds > 0) scheduleTick(seconds);
}

/** Test-only reset. Cancels the countdown and forgets the current toast id. */
export function _resetRateLimitToast(): void {
  clearCountdown();
  currentToastId = undefined;
}
