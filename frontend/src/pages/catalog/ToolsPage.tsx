import { ToolList } from '@/features/catalog/ToolList';
import { useTools } from '@/features/catalog/api';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Skeleton } from '@/shared/ui/Skeleton';

export default function ToolsPage(): JSX.Element {
  const query = useTools();

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-6 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium text-text-primary">Tools</h1>
        <p className="text-sm text-text-secondary">
          Catalog of tools available for assignment to an agent.
        </p>
      </header>

      {query.isPending && (
        <Card padding="none" className="p-4">
          <div className="flex flex-col gap-3" data-testid="tools-loading">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} height={36} />
            ))}
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
                {query.error.detail ?? errorCopy[query.error.code]?.detail ?? errorCopy.__unknown__.detail}
              </p>
            </div>
            <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
              Retry
            </Button>
          </div>
        </Card>
      )}

      {query.isSuccess && <ToolList items={query.data} />}
    </div>
  );
}
