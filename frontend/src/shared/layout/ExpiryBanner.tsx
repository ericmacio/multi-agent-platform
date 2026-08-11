import { useAuth } from '@/shared/auth/AuthContext';
import { Button } from '@/shared/ui/Button';
import { AlertTriangle, X } from '@/shared/ui/icons';

/**
 * Inline banner shown 30 s before the JWT expires (state managed by
 * `AuthContext`, US-02-007). Dismissable so a user mid-conversation can
 * silence it without losing context; the underlying expiry is unchanged.
 *
 * Rendering above the routed outlet is `AppShell`'s job.
 */
export function ExpiryBanner(): JSX.Element | null {
  const { expiryWarning, dismissExpiryWarning } = useAuth();
  if (!expiryWarning) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center gap-3 border-b border-warning/30 bg-warning-bg px-4 py-2 text-sm text-warning"
    >
      <AlertTriangle aria-hidden width={16} height={16} className="shrink-0" />
      <p className="flex-1">Session ends in 30s — finish typing and re-sign in.</p>
      <Button
        size="sm"
        variant="ghost"
        aria-label="Dismiss session warning"
        onClick={dismissExpiryWarning}
      >
        <X aria-hidden width={14} height={14} />
      </Button>
    </div>
  );
}
