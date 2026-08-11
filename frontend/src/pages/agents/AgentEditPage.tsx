import { Link, useNavigate, useParams } from 'react-router-dom';
import { AgentForm } from '@/features/agents/AgentForm';
import { useAgent } from '@/features/agents/api';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Skeleton } from '@/shared/ui/Skeleton';
import { toast } from '@/shared/ui/Toast';

export default function AgentEditPage(): JSX.Element {
  const { agentId } = useParams<{ agentId: string }>();
  const navigate = useNavigate();
  const query = useAgent(agentId);

  if (query.isPending) {
    return (
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-3 px-6 py-6">
        {Array.from({ length: 4 }).map((_, i) => (
          <Card key={i} padding="md">
            <Skeleton height={64} />
          </Card>
        ))}
      </div>
    );
  }

  if (query.isError) {
    if (query.error.status === 404) {
      return (
        <EmptyState
          title="Agent not found"
          description="This agent no longer exists."
          action={
            <Link
              to="/agents"
              className="inline-flex h-9 items-center justify-center rounded-md border border-border-default bg-bg-elevated px-4 text-sm font-medium text-text-primary hover:bg-bg-surface"
            >
              Back to agents
            </Link>
          }
        />
      );
    }
    return (
      <div className="mx-auto flex w-full max-w-3xl px-6 py-6">
        <Card padding="md" className="w-full border-danger/40">
          <div className="flex flex-col items-start gap-3" role="alert">
            <p className="text-sm font-medium text-text-primary">
              {errorCopy[query.error.code]?.title ?? errorCopy.__unknown__.title}
            </p>
            <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
              Retry
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium text-text-primary">Edit {query.data.name}</h1>
        <p className="text-sm text-text-secondary">Update the agent&apos;s configuration.</p>
      </header>
      <AgentForm
        mode="edit"
        initial={query.data}
        onSuccess={(agent) => {
          toast.success('Agent updated.');
          navigate(`/agents/${agent.id}`, { replace: true });
        }}
        onCancel={() => navigate(`/agents/${agentId}`)}
      />
    </div>
  );
}
