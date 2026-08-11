import { RateLimitForm } from '@/features/admin-rate-limit/RateLimitForm';
import { useRateLimitConfig } from '@/features/admin-rate-limit/api';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Skeleton } from '@/shared/ui/Skeleton';
import { toast } from '@/shared/ui/Toast';

/**
 * Admin rate-limit surface. Thin composition of the read hook and the form:
 * first-paint skeleton, first-paint error (with Retry), and — once the query
 * resolves — the `RateLimitForm` seeded with server truth. Save success is
 * announced through the global toast; the cache-invalidation refetch happens
 * inside `useUpdateRateLimitConfig` so this page does not need to invalidate.
 */
export default function AdminRateLimitPage(): JSX.Element {
  const query = useRateLimitConfig();

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium text-text-primary">Rate Limit</h1>
        <p className="text-sm text-text-secondary">
          Configure the global per-minute and per-hour request budgets.
        </p>
      </header>

      {query.isPending && (
        <Card padding="md" data-testid="rate-limit-loading">
          <div className="flex flex-col gap-4">
            <Skeleton height={56} />
            <Skeleton height={56} />
          </div>
        </Card>
      )}

      {query.isError && (
        <Card padding="md" className="border-danger/40">
          <div className="flex flex-col items-start gap-3" role="alert">
            <div>
              <p className="text-sm font-medium text-text-primary">
                {errorCopy[query.error.code]?.title ?? errorCopy.__unknown__.title}
              </p>
              <p className="text-sm text-text-secondary">
                {query.error.detail ??
                  errorCopy[query.error.code]?.detail ??
                  errorCopy.__unknown__.detail}
              </p>
            </div>
            <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
              Retry
            </Button>
          </div>
        </Card>
      )}

      {query.isSuccess && (
        <Card padding="md">
          <RateLimitForm
            defaults={query.data}
            onSaved={() => {
              toast.success('Rate limit updated.');
            }}
          />
        </Card>
      )}
    </div>
  );
}
