import type { ReactNode } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { cn } from '@/shared/lib/cn';
import { AlertTriangle } from './icons';

type NotFoundStateProps = {
  title?: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
};

/**
 * In-content 404 state. Use inside a page's content column when a specific
 * resource query returns 404 but the surrounding shell (sidebar, topbar) is
 * still meaningful (e.g., a deleted conversation while the sidebar list is
 * intact). For an unmatched route, `pages/NotFoundPlaceholder` handles the
 * whole-page case.
 */
export function NotFoundState({
  title = errorCopy.NOT_FOUND.title,
  description = errorCopy.NOT_FOUND.detail,
  action,
  className,
}: NotFoundStateProps): JSX.Element {
  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-start gap-3 rounded-lg border border-border-default bg-bg-surface p-4',
        className,
      )}
    >
      <div className="flex items-start gap-3">
        <AlertTriangle
          aria-hidden
          width={18}
          height={18}
          className="mt-0.5 shrink-0 text-text-muted"
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
