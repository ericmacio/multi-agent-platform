import { cn } from '@/shared/lib/cn';
import { Card } from './Card';
import { Skeleton } from './Skeleton';

type LoadingListProps = {
  /** Number of row-shaped skeletons to render. Default 5. */
  rows?: number;
  /** Height of each row skeleton in px. Default 24. */
  rowHeight?: number;
  /** Optional data-testid override — defaults to `loading-list`. */
  testId?: string;
  className?: string;
};

/**
 * List-loading scaffold. Renders N `Card`-wrapped `Skeleton` blocks matching
 * the row height of a typical list page. Prefer this to a full-page spinner
 * so the surrounding chrome (topbar / sidebar) remains readable and the page
 * height does not jump when the query resolves.
 */
export function LoadingList({
  rows = 5,
  rowHeight = 24,
  testId = 'loading-list',
  className,
}: LoadingListProps): JSX.Element {
  return (
    <div data-testid={testId} className={cn('flex flex-col gap-2', className)}>
      {Array.from({ length: rows }).map((_, i) => (
        <Card key={i} padding="sm">
          <Skeleton height={rowHeight} />
        </Card>
      ))}
    </div>
  );
}
