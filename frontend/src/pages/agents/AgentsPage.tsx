import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { flattenPages } from '@/shared/lib/pagination';
import { Button } from '@/shared/ui/Button';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Plus } from '@/shared/ui/icons';
import { toast } from '@/shared/ui/Toast';
import { AgentList } from '@/features/agents/AgentList';
import { DeleteAgentDialog } from '@/features/agents/DeleteAgentDialog';
import { useAgents } from '@/features/agents/api';
import type { Agent } from '@/features/agents/schema';

export default function AgentsPage(): JSX.Element {
  const navigate = useNavigate();
  const query = useAgents();
  const agents = flattenPages(query.data);
  const [pendingDelete, setPendingDelete] = useState<Agent | null>(null);

  const showEmpty = query.isSuccess && agents.length === 0;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-6">
      <header className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-medium text-text-primary">Agents</h1>
          <p className="text-sm text-text-secondary">Create and manage your AI agents.</p>
        </div>
        <Button leftIcon={<Plus aria-hidden width={16} height={16} />} onClick={() => navigate('/agents/new')}>
          New agent
        </Button>
      </header>

      {showEmpty ? (
        <EmptyState
          title="You don't have any agents yet"
          description="Start by configuring your first agent."
          action={<Button onClick={() => navigate('/agents/new')}>Create your first agent</Button>}
        />
      ) : (
        <AgentList
          onView={(id) => navigate(`/agents/${id}`)}
          onEdit={(id) => navigate(`/agents/${id}/edit`)}
          onStartChat={(id) => navigate(`/chat/new?agentId=${id}`)}
          onDelete={(agent) => setPendingDelete(agent)}
        />
      )}

      <DeleteAgentDialog
        agent={pendingDelete}
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        onDeleted={() => {
          toast.success('Agent deleted.');
        }}
      />
    </div>
  );
}
