import { useSyncExternalStore } from 'react';
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from './icons';
import { cn } from '@/shared/lib/cn';

export type ToastType = 'info' | 'success' | 'warning' | 'error';

export type ToastInput = {
  type: ToastType;
  message: string;
  /** Identical-key calls replace the existing toast in place (last-write-wins). */
  key?: string;
  /** Override the default lifetime. `null` keeps it open until the user dismisses. */
  durationMs?: number | null;
};

export type ToastEntry = ToastInput & {
  id: string;
};

const DEFAULT_DURATION_MS = 5_000;

let entries: ToastEntry[] = [];
const listeners = new Set<() => void>();
const timers = new Map<string, ReturnType<typeof setTimeout>>();

function notify(): void {
  for (const listener of listeners) listener();
}

function scheduleDismiss(id: string, durationMs: number | null | undefined): void {
  const effective = durationMs === undefined ? DEFAULT_DURATION_MS : durationMs;
  if (effective === null) return;
  const handle = setTimeout(() => dismissToast(id), effective);
  timers.set(id, handle);
}

function clearScheduled(id: string): void {
  const handle = timers.get(id);
  if (handle) {
    clearTimeout(handle);
    timers.delete(id);
  }
}

function nextId(): string {
  return `t_${Math.random().toString(36).slice(2, 10)}_${Date.now()}`;
}

function pushToast(input: ToastInput): string {
  const id = nextId();
  // Error toasts are sticky by default — too important to disappear silently.
  const durationMs =
    input.durationMs !== undefined
      ? input.durationMs
      : input.type === 'error'
        ? null
        : DEFAULT_DURATION_MS;

  const newEntry: ToastEntry = { id, ...input, durationMs };

  if (input.key) {
    const existingIndex = entries.findIndex((e) => e.key === input.key);
    if (existingIndex >= 0) {
      const existing = entries[existingIndex]!;
      clearScheduled(existing.id);
      entries = entries.slice();
      entries[existingIndex] = newEntry;
      notify();
      scheduleDismiss(id, durationMs);
      return id;
    }
  }

  entries = [...entries, newEntry];
  notify();
  scheduleDismiss(id, durationMs);
  return id;
}

export function dismissToast(id: string): void {
  clearScheduled(id);
  entries = entries.filter((e) => e.id !== id);
  notify();
}

/** Test helper — wipes the queue between tests. */
export function _resetToasts(): void {
  for (const handle of timers.values()) clearTimeout(handle);
  timers.clear();
  entries = [];
  notify();
}

export const toast = {
  info: (message: string, key?: string): string => pushToast({ type: 'info', message, key }),
  success: (message: string, key?: string): string => pushToast({ type: 'success', message, key }),
  warning: (message: string, key?: string): string => pushToast({ type: 'warning', message, key }),
  error: (message: string, key?: string): string => pushToast({ type: 'error', message, key }),
  /** Imperatively enqueue with full control over duration. */
  push: pushToast,
  dismiss: dismissToast,
};

const ICON_FOR_TYPE = {
  info: Info,
  success: CheckCircle2,
  warning: AlertTriangle,
  error: XCircle,
} as const;

const CLASSES_FOR_TYPE: Record<ToastType, string> = {
  info: 'border-info/30 bg-info-bg text-info',
  success: 'border-success/30 bg-success-bg text-success',
  warning: 'border-warning/30 bg-warning-bg text-warning',
  error: 'border-danger/30 bg-danger-bg text-danger',
};

/**
 * Single global toast surface. Mount once at the app root (`AppShell` /
 * `AuthShell` do this in US-02-011). Subscribers use `useSyncExternalStore`
 * so duplicate mounts share the same queue.
 */
export function ToastViewport(): JSX.Element {
  const snapshot = useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => entries,
    () => entries,
  );
  return (
    <div
      role="region"
      aria-label="Notifications"
      aria-live="polite"
      className="pointer-events-none fixed right-4 top-4 z-[1100] flex w-full max-w-sm flex-col gap-2"
    >
      {snapshot
        .slice()
        .reverse()
        .map((entry) => {
          const Icon = ICON_FOR_TYPE[entry.type];
          return (
            <div
              key={entry.id}
              data-testid={`toast-${entry.type}`}
              className={cn(
                'pointer-events-auto flex items-start gap-3 rounded-md border px-3 py-2 text-sm shadow-lg backdrop-blur',
                CLASSES_FOR_TYPE[entry.type],
              )}
            >
              <Icon aria-hidden width={16} height={16} className="mt-0.5 shrink-0" />
              <p className="flex-1 text-text-primary">{entry.message}</p>
              <button
                type="button"
                aria-label="Dismiss"
                onClick={() => dismissToast(entry.id)}
                className="text-text-muted transition-colors hover:text-text-primary"
              >
                <X aria-hidden width={14} height={14} />
              </button>
            </div>
          );
        })}
    </div>
  );
}
