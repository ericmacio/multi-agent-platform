import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { DeleteAgentDialog } from '@/features/agents/DeleteAgentDialog';
import { useAgent } from '@/features/agents/api';
import type { Agent } from '@/features/agents/schema';
import { useConversations } from '@/features/conversations/api';
import type { Conversation } from '@/features/conversations/schema';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { formatRelative } from '@/shared/lib/date';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { EmptyState } from '@/shared/ui/EmptyState';
import { NotFoundState } from '@/shared/ui/NotFoundState';
import { Skeleton } from '@/shared/ui/Skeleton';
import { toast } from '@/shared/ui/Toast';

export default function AgentDetailPage(): JSX.Element {
  const { agentId } = useParams<{ agentId: string }>();
  const navigate = useNavigate();
  const query = useAgent(agentId);
  const [confirmDelete, setConfirmDelete] = useState<Agent | null>(null);

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
        <div className="mx-auto flex w-full max-w-3xl px-6 py-6">
          <NotFoundState
            className="w-full"
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
        </div>
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

  const agent = query.data;
  const usingDefault =
    agent.llmModel == null &&
    agent.temperature == null &&
    agent.maxOutputTokens == null &&
    agent.topP == null;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="font-mono text-xl font-medium text-accent">{agent.name}</h1>
          <p className="text-sm text-text-secondary">Agent configuration</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            onClick={() => navigate(`/chat/new?agentId=${agent.id}`)}
          >
            Start chat
          </Button>
          <Button variant="secondary" onClick={() => navigate(`/agents/${agent.id}/edit`)}>
            Edit
          </Button>
          <Button variant="danger" onClick={() => setConfirmDelete(agent)}>
            Delete
          </Button>
        </div>
      </header>

      <Section title="Identity">
        <FieldRow label="Name" value={agent.name} mono />
        <FieldRow label="Description" value={agent.description} />
      </Section>

      <Section title="Behavior">
        <FieldRow label="System prompt" value={agent.systemPrompt} multiline />
        <FieldRow label="Memory size" value={`${agent.memorySize} messages`} />
      </Section>

      <Section title="Model">
        {usingDefault ? (
          <p className="text-sm text-text-secondary">Using platform default</p>
        ) : (
          <>
            <FieldRow label="Model" value={agent.llmModel ?? '(none)'} mono />
            <FieldRow
              label="Temperature"
              value={agent.temperature == null ? '(none)' : String(agent.temperature)}
            />
            <FieldRow
              label="Max output tokens"
              value={agent.maxOutputTokens == null ? '(none)' : String(agent.maxOutputTokens)}
            />
            <FieldRow
              label="Top-P"
              value={agent.topP == null ? '(none)' : String(agent.topP)}
            />
          </>
        )}
      </Section>

      <Section title="Tools">
        {agent.tools.length === 0 ? (
          <p className="text-sm text-text-muted">(none)</p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {agent.tools.map((name) => (
              <Badge key={name} variant="neutral">
                {name}
              </Badge>
            ))}
          </div>
        )}
      </Section>

      <Section title="MCP servers">
        {agent.enabledMcpServers.length === 0 ? (
          <p className="text-sm text-text-muted">(none)</p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {agent.enabledMcpServers.map((name) => (
              <Badge key={name} variant="info">
                {name}
              </Badge>
            ))}
          </div>
        )}
      </Section>

      <Section title="Team">
        {agent.team.length === 0 ? (
          <p className="text-sm text-text-muted">(none)</p>
        ) : (
          <p className="text-sm text-text-secondary">
            Delegates to {agent.team.length} {agent.team.length === 1 ? 'agent' : 'agents'}
          </p>
        )}
      </Section>

      <Section title="Recent conversations">
        <RecentConversations agentId={agent.id} />
      </Section>

      <DeleteAgentDialog
        agent={confirmDelete}
        open={confirmDelete !== null}
        onClose={() => setConfirmDelete(null)}
        onDeleted={() => {
          toast.success('Agent deleted.');
          navigate('/agents', { replace: true });
        }}
      />
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}): JSX.Element {
  return (
    <Card padding="md" className="flex flex-col gap-3">
      <h2 className="text-base font-medium text-text-primary">{title}</h2>
      {children}
    </Card>
  );
}

function FieldRow({
  label,
  value,
  mono = false,
  multiline = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
  multiline?: boolean;
}): JSX.Element {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs uppercase tracking-wide text-text-muted">{label}</span>
      <span
        className={`text-sm text-text-primary ${mono ? 'font-mono' : ''} ${
          multiline ? 'whitespace-pre-wrap' : ''
        }`}
      >
        {value}
      </span>
    </div>
  );
}

const MAX_MESSAGES = 64;

function RecentConversations({ agentId }: { agentId: string }): JSX.Element {
  const query = useConversations({ agentId, pageSize: 5 });
  const items: Conversation[] = useMemo(() => flattenPages(query.data), [query.data]);

  if (query.isPending) {
    return (
      <div className="flex flex-col gap-2" data-testid="recent-conversations-loading">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} height={40} />
        ))}
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="flex flex-col items-start gap-3" role="alert">
        <p className="text-sm font-medium text-text-primary">
          {errorCopy[query.error.code]?.title ?? errorCopy.__unknown__.title}
        </p>
        <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
          Retry
        </Button>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <EmptyState
        title="No conversations yet"
        description="Start a chat with this agent to see it here."
        action={
          <Link
            to={`/chat/new?agentId=${agentId}`}
            className="inline-flex h-9 items-center justify-center rounded-md bg-accent px-4 text-sm font-medium text-bg-base hover:bg-accent/90"
          >
            Start a chat with this agent
          </Link>
        }
      />
    );
  }

  return (
    <div className="flex flex-col gap-2" data-testid="recent-conversations">
      {items.slice(0, 5).map((c) => {
        const fallback = c.title === null || c.title === undefined;
        const title = fallback ? `chat-${c.id.slice(0, 8)}` : (c.title as string);
        const atCap = c.messageCount >= MAX_MESSAGES;
        return (
          <Link
            key={c.id}
            to={`/chat/${c.id}`}
            className="flex items-center justify-between gap-3 rounded-md border border-border-default bg-bg-elevated px-3 py-2 hover:bg-bg-surface"
          >
            <div className="flex min-w-0 flex-col">
              <span
                className={`truncate text-sm text-text-primary ${fallback ? 'font-mono text-text-secondary' : ''}`}
              >
                {title}
              </span>
              <span className="text-xs text-text-muted">
                {formatRelative(c.updatedAt)}
              </span>
            </div>
            <Badge variant={atCap ? 'warning' : 'neutral'}>
              {`${c.messageCount} / ${MAX_MESSAGES}`}
            </Badge>
          </Link>
        );
      })}
      <Link
        to={`/chat?agentId=${agentId}`}
        className="text-right text-xs text-accent hover:underline"
      >
        See all
      </Link>
    </div>
  );
}
