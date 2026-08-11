import type { ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

type EmptyStateProps = {
  icon?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
};

export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        'mx-auto flex max-w-md flex-col items-center gap-3 px-6 py-12 text-center',
        className,
      )}
    >
      {icon && <div className="mb-1 text-text-muted [&>svg]:h-8 [&>svg]:w-8">{icon}</div>}
      <h2 className="text-base font-medium text-text-primary">{title}</h2>
      {description && <p className="text-sm text-text-secondary">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
