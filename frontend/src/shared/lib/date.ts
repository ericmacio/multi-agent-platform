/**
 * Date / time formatting helpers built on the Intl API. No `moment`, no `dayjs`
 * (SW-DESIGN §4 forbids them). Loud-on-bad-input: invalid ISO strings yield the
 * em-dash placeholder `'—'` rather than throwing.
 */

const FALLBACK = '—';

function safeParse(iso: string): Date | null {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/**
 * Format an ISO 8601 timestamp as a medium date + short time, using the caller's
 * locale (or the document default). Example output: `"Jun 3, 2026, 14:05"`.
 */
export function formatDateTime(iso: string, locale?: string): string {
  const d = safeParse(iso);
  if (!d) return FALLBACK;
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(d);
}

const RELATIVE_UNITS: Array<{ unit: Intl.RelativeTimeFormatUnit; seconds: number }> = [
  { unit: 'year', seconds: 60 * 60 * 24 * 365 },
  { unit: 'month', seconds: 60 * 60 * 24 * 30 },
  { unit: 'week', seconds: 60 * 60 * 24 * 7 },
  { unit: 'day', seconds: 60 * 60 * 24 },
  { unit: 'hour', seconds: 60 * 60 },
  { unit: 'minute', seconds: 60 },
  { unit: 'second', seconds: 1 },
];

/**
 * Format an ISO timestamp relative to `now` (default: `new Date()`).
 * Picks the largest unit that yields a magnitude ≥ 1 and falls back to `'now'`
 * for sub-second deltas.
 */
export function formatRelative(iso: string, now: Date = new Date(), locale?: string): string {
  const d = safeParse(iso);
  if (!d) return FALLBACK;
  const diffSeconds = Math.round((d.getTime() - now.getTime()) / 1000);
  const absSeconds = Math.abs(diffSeconds);
  if (absSeconds < 1) return 'now';

  const fmt = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  for (const { unit, seconds } of RELATIVE_UNITS) {
    if (absSeconds >= seconds) {
      const value = Math.round(diffSeconds / seconds);
      return fmt.format(value, unit);
    }
  }
  return 'now';
}

/**
 * Strict less-than comparison of the given epoch-seconds against `now`.
 * Used by the auth middleware to short-circuit on expired tokens (US-02-003).
 */
export function isExpired(epochSeconds: number, now: Date = new Date()): boolean {
  return epochSeconds * 1000 < now.getTime();
}
