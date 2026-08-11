import { useNavigate } from 'react-router-dom';
import { AgentForm } from '@/features/agents/AgentForm';
import { toast } from '@/shared/ui/Toast';

export default function AgentCreatePage(): JSX.Element {
  const navigate = useNavigate();

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium text-text-primary">New agent</h1>
        <p className="text-sm text-text-secondary">Configure a new AI agent.</p>
      </header>
      <AgentForm
        mode="create"
        onSuccess={(agent) => {
          toast.success('Agent created.');
          navigate(`/agents/${agent.id}`, { replace: true });
        }}
        onCancel={() => navigate('/agents')}
      />
    </div>
  );
}
