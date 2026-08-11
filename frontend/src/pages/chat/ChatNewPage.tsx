import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAgents } from '@/features/agents/api';
import type { Agent } from '@/features/agents/schema';
import { useStartConversation } from '@/features/conversations/api';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Input } from '@/shared/ui/Input';
import { Skeleton } from '@/shared/ui/Skeleton';
import { Spinner } from '@/shared/ui/Spinner';
import { Bot, Search } from '@/shared/ui/icons';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Agent picker. When `?agentId=<uuid>` is present and valid, the page
 * auto-creates a conversation and navigates straight to it. Otherwise it
 * renders a searchable list of the caller's agents; clicking "Start chat"
 * fires `POST /conversations` and navigates to `/chat/<newId>`.
 */
export default function ChatNewPage(): JSX.Element {
  const [search] = useSearchParams();
  const agentId = search.get('agentId');
  const isValid = agentId !== null && UUID_RE.test(agentId);
  if (isValid && agentId) {
    return <AutoStart agentId={agentId} />;
  }
  return <AgentPicker />;
}

/** Auto-start mode: fires the mutation once and redirects on success. */
function AutoStart({ agentId }: { agentId: string }): JSX.Element {
  const navigate = useNavigate();
  const mutation = useStartConversation();
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) return;
    ranRef.current = true;
    mutation.mutate(
      { agentId },
      {
        onSuccess: (conversation) => {
          navigate(`/chat/${conversation.id}`, { replace: true });
        },
      },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId]);

  if (mutation.isError) {
    return (
      <div className="mx-auto flex w-full max-w-md flex-col items-center gap-3 px-6 py-12 text-center">
        <Card padding="md" className="w-full border-danger/40" role="alert">
          <p className="text-sm font-medium text-text-primary">
            {errorCopy[mutation.error.code]?.title ?? errorCopy.__unknown__.title}
          </p>
          <p className="mt-1 text-sm text-text-secondary">
            {mutation.error.detail ??
              errorCopy[mutation.error.code]?.detail ??
              errorCopy.__unknown__.detail}
          </p>
        </Card>
        <Link to="/chat" className="text-sm text-accent hover:underline">
          Back to chats
        </Link>
      </div>
    );
  }

  return (
    <div className="flex h-full items-center justify-center">
      <div className="flex flex-col items-center gap-3">
        <Spinner size={24} label="" />
        <p className="text-sm text-text-secondary">Starting a new conversation…</p>
      </div>
    </div>
  );
}

/** Manual picker mode. Drains the paginated agents list to completion. */
function AgentPicker(): JSX.Element {
  const navigate = useNavigate();
  const agentsQuery = useAgents();
  const [query, setQuery] = useState('');

  useEffect(() => {
    if (agentsQuery.hasNextPage && !agentsQuery.isFetchingNextPage) {
      void agentsQuery.fetchNextPage();
    }
  }, [agentsQuery]);

  const agents: Agent[] = useMemo(
    () => flattenPages(agentsQuery.data),
    [agentsQuery.data],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q === '') return agents;
    return agents.filter((a) => {
      const haystack = `${a.name} ${a.description}`.toLowerCase();
      return haystack.includes(q);
    });
  }, [agents, query]);

  if (agentsQuery.isPending) {
    return (
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-3 px-6 py-6">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} height={80} />
        ))}
      </div>
    );
  }

  if (agentsQuery.isError) {
    return (
      <div className="mx-auto flex w-full max-w-3xl px-6 py-6">
        <Card padding="md" className="w-full border-danger/40">
          <div className="flex flex-col items-start gap-3" role="alert">
            <p className="text-sm font-medium text-text-primary">
              {errorCopy[agentsQuery.error.code]?.title ?? errorCopy.__unknown__.title}
            </p>
            <Button variant="secondary" size="sm" onClick={() => void agentsQuery.refetch()}>
              Retry
            </Button>
          </div>
        </Card>
      </div>
    );
  }

  if (agents.length === 0) {
    return (
      <EmptyState
        icon={<Bot aria-hidden />}
        title="You don't have any agents yet"
        description="Create an agent before starting a conversation."
        action={
          <Button onClick={() => navigate('/agents/new')}>Create your first agent</Button>
        }
      />
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-6 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-lg font-medium text-text-primary">Start a new chat</h1>
        <p className="text-sm text-text-secondary">
          Pick one of your agents to start a conversation.
        </p>
      </header>

      <div className="flex items-center gap-2">
        <Search aria-hidden width={14} height={14} className="text-text-muted" />
        <div className="flex-1">
          <Input
            aria-label="Search agents"
            placeholder="Search agents…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <p className="text-sm text-text-secondary">{`No agents match "${query}"`}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {filtered.map((agent) => (
            <li key={agent.id}>
              <AgentRow agent={agent} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function AgentRow({ agent }: { agent: Agent }): JSX.Element {
  const navigate = useNavigate();
  const mutation = useStartConversation();

  function onStart(): void {
    mutation.mutate(
      { agentId: agent.id },
      {
        onSuccess: (conversation) => {
          navigate(`/chat/${conversation.id}`, { replace: true });
        },
      },
    );
  }

  const errorMessage = mutation.isError
    ? (errorCopy[mutation.error.code]?.title ?? errorCopy.__unknown__.title)
    : null;

  return (
    <Card padding="md" className="flex items-center justify-between gap-3">
      <div className="flex min-w-0 flex-col">
        <p className="font-mono text-sm font-medium text-accent">{agent.name}</p>
        <p className="line-clamp-1 text-sm text-text-secondary">{agent.description}</p>
        {errorMessage && (
          <p className="mt-1 text-xs text-danger" role="alert">
            {errorMessage}
          </p>
        )}
      </div>
      <Button
        variant="primary"
        size="sm"
        onClick={onStart}
        loading={mutation.isPending}
        disabled={mutation.isPending}
      >
        Start chat
      </Button>
    </Card>
  );
}
