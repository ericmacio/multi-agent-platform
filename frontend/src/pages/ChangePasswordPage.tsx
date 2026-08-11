import { useNavigate, useSearchParams } from 'react-router-dom';
import { ChangePasswordForm } from '@/features/auth/ChangePasswordForm';
import { AlertTriangle } from '@/shared/ui/icons';

/**
 * The `/change-password` route. Shows a forced-visit banner when the user
 * was redirected here by `<RequireFreshPassword>` (`?reason=forced`).
 * On success the page navigates to the dashboard.
 */
export function ChangePasswordPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const forced = searchParams.get('reason') === 'forced';

  return (
    <div className="flex flex-col gap-5">
      {forced && (
        <div
          role="status"
          data-testid="forced-banner"
          className="flex items-start gap-3 rounded-md border border-accent-dim bg-accent-bg px-3 py-2 text-sm text-accent"
        >
          <AlertTriangle aria-hidden width={16} height={16} className="mt-0.5 shrink-0" />
          <p className="text-text-primary">
            Your administrator created this account with a temporary password. Choose a new one to
            continue.
          </p>
        </div>
      )}
      <h1 className="text-center text-xl font-medium text-text-primary">Change password</h1>
      <ChangePasswordForm onSuccess={() => navigate('/', { replace: true })} />
    </div>
  );
}
