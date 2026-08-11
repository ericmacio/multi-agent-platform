import { useEffect, useState } from 'react';

/**
 * Coarse network-connectivity indicator. Reads `navigator.onLine` and
 * subscribes to the `online` / `offline` window events; renders nothing while
 * the browser reports connectivity, and a sticky warning strip while it does
 * not. `role="status"` + `aria-live="polite"` announces the transition
 * without interrupting the current focus (SW-DESIGN §11.6).
 *
 * Deliberately NOT a backend health check — a healthy network plus a downed
 * API surfaces through per-request error toasts. Deferred per SW-DESIGN §16.3.
 */
export function OfflineBanner(): JSX.Element | null {
  const [isOffline, setIsOffline] = useState<boolean>(() =>
    typeof navigator === 'undefined' ? false : !navigator.onLine,
  );

  useEffect(() => {
    if (typeof window === 'undefined') return undefined;
    const goOffline = (): void => setIsOffline(true);
    const goOnline = (): void => setIsOffline(false);
    window.addEventListener('offline', goOffline);
    window.addEventListener('online', goOnline);
    return () => {
      window.removeEventListener('offline', goOffline);
      window.removeEventListener('online', goOnline);
    };
  }, []);

  if (typeof window === 'undefined') return null;
  if (!isOffline) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="offline-banner"
      className="sticky top-0 z-40 border-b border-warning/30 bg-warning-bg px-4 py-2 text-center text-sm text-warning"
    >
      You&apos;re offline — some actions will fail until connectivity is restored.
    </div>
  );
}
