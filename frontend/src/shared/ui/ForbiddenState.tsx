import type { ReactNode } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { cn } from '@/shared/lib/cn';
import { AlertTriangle } from './icons';

type ForbiddenStateProps = {
  title?: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
};

/**
 * In-content 403 state. Use inside a page's content column when a specific
 * resource is forbidden but the surrounding shell (sidebar, topbar) is still
 * meaningful. For a route-level 403 (whole page inaccessible), route to
 * `pages/ForbiddenPage` via `RequireRole` instead.
 */
export function ForbiddenState({
  title = errorCopy.FORBIDDEN.title,
  description = errorCopy.FORBIDDEN.detail,
  action,
  className,
}: ForbiddenStateProps): JSX.Element {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-start gap-3 rounded-lg border border-danger/40 bg-bg-surface p-4',
        className,
      )}
    >
      <div className="flex items-start gap-3">
        <AlertTriangle
          aria-hidden
          width={18}
          height={18}
          className="mt-0.5 shrink-0 text-danger"
        />
        <div>
          <p className="text-sm font-medium text-text-primary">{title}</p>
          <p className="text-sm text-text-secondary">{description}</p>
        </div>
      </div>
      {action && <div className="pl-[26px]">{action}</div>}
    </div>
  );
}
