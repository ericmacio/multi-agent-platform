import { useMemo } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Skeleton } from '@/shared/ui/Skeleton';
import { AgentCard } from './AgentCard';
import { useAgents } from './api';
import type { Agent } from './schema';

export type AgentListProps = {
  onView: (id: string) => void;
  onEdit: (id: string) => void;
  onStartChat: (id: string) => void;
  onDelete: (agent: Agent) => void;
};

export function AgentList({
  onView,
  onEdit,
  onStartChat,
  onDelete,
}: AgentListProps): JSX.Element | null {
  const query = useAgents();
  const agents = useMemo(() => flattenPages(query.data), [query.data]);

  if (query.isPending) {
    return (
      <div
        className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
        data-testid="agent-list-loading"
      >
        {Array.from({ length: 6 }).map((_, i) => (
          <Card key={i} padding="md" className="flex flex-col gap-3">
            <Skeleton height={20} width="60%" />
            <Skeleton height={32} />
            <Skeleton height={16} width="40%" />
          </Card>
        ))}
      </div>
    );
  }

  if (query.isError) {
    return (
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
    );
  }

  // Empty case is owned by the page (renders a page-level empty state per
  // SW-DESIGN §12.3). Returning null keeps the list a pure rendering concern.
  if (agents.length === 0) return null;

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {agents.map((agent) => (
          <AgentCard
            key={agent.id}
            agent={agent}
            onView={() => onView(agent.id)}
            onEdit={() => onEdit(agent.id)}
            onStartChat={() => onStartChat(agent.id)}
            onDelete={() => onDelete(agent)}
          />
        ))}
      </div>
      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button
            variant="secondary"
            size="sm"
            loading={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            Load more
          </Button>
        </div>
      )}
    </div>
  );
}
