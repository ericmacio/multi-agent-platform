import { useMemo, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAgents } from '@/features/agents/api';
import type { Agent } from '@/features/agents/schema';
import { useMcpServers, useTools } from '@/features/catalog/api';
import type { McpServerDescriptor, ToolDescriptor } from '@/features/catalog/api';
import { useConversations } from '@/features/conversations/api';
import type { Conversation } from '@/features/conversations/schema';
import { useAuth } from '@/shared/auth/AuthContext';
import type { ApiError } from '@/shared/api/errors';
import { errorCopy } from '@/shared/i18n/en';
import { cn } from '@/shared/lib/cn';
import { formatRelative } from '@/shared/lib/date';
import { flattenPages } from '@/shared/lib/pagination';
import { Card } from '@/shared/ui/Card';
import {
  Bot,
  ChevronRight,
  Key,
  MessageSquare,
  Server,
  Settings,
  Users,
  Wrench,
  type LucideIcon,
} from '@/shared/ui/icons';
import { Skeleton } from '@/shared/ui/Skeleton';

const PREVIEW_LIMIT = 4;

/**
 * Semantic tone per dashboard card. Each tone routes to a coordinated set of
 * classes (stripe / icon tile / accent CTA / hover ring / soft gradient) so
 * the whole card reads as a single-colored surface without every button in
 * the app going off-brand — the Clarté guidance keeps Indigo as the single
 * CTA color; here the tone lives only inside the tile's local ornamentation.
 */
type Tone = 'accent' | 'info' | 'warning' | 'success';

const TONE_CLASSES: Record<
  Tone,
  {
    stripe: string;
    iconTile: string;
    countText: string;
    ctaText: string;
    ctaHover: string;
    ringHover: string;
    gradient: string;
  }
> = {
  accent: {
    stripe: 'bg-accent',
    iconTile: 'bg-accent-bg text-accent',
    countText: 'text-accent',
    ctaText: 'text-accent',
    ctaHover: 'group-hover:text-accent-hover',
    ringHover: 'group-hover:border-border-accent',
    gradient: 'from-accent-bg/70 via-transparent to-transparent',
  },
  info: {
    stripe: 'bg-info',
    iconTile: 'bg-info-bg text-info',
    countText: 'text-info',
    ctaText: 'text-info',
    ctaHover: 'group-hover:opacity-80',
    ringHover: 'group-hover:border-info/40',
    gradient: 'from-info-bg/70 via-transparent to-transparent',
  },
  warning: {
    stripe: 'bg-warning',
    iconTile: 'bg-warning-bg text-warning',
    countText: 'text-warning',
    ctaText: 'text-warning',
    ctaHover: 'group-hover:opacity-80',
    ringHover: 'group-hover:border-warning/40',
    gradient: 'from-warning-bg/70 via-transparent to-transparent',
  },
  success: {
    stripe: 'bg-success',
    iconTile: 'bg-success-bg text-success',
    countText: 'text-success',
    ctaText: 'text-success',
    ctaHover: 'group-hover:opacity-80',
    ringHover: 'group-hover:border-success/40',
    gradient: 'from-success-bg/70 via-transparent to-transparent',
  },
};

type CardState =
  | { kind: 'pending' }
  | { kind: 'error'; message: string }
  | { kind: 'empty'; hint: string }
  | { kind: 'populated'; total: number; children: ReactNode };

type DashboardCardProps = {
  to: string;
  title: string;
  subtitle: string;
  icon: LucideIcon;
  tone: Tone;
  state: CardState;
  ctaLabel: string;
};

function DashboardCard({
  to,
  title,
  subtitle,
  icon: Icon,
  tone,
  state,
  ctaLabel,
}: DashboardCardProps) {
  const t = TONE_CLASSES[tone];
  const count = state.kind === 'populated' ? state.total : null;

  return (
    <Link
      to={to}
      aria-label={`${title} — ${ctaLabel}`}
      className="group rounded-lg outline-none focus-visible:outline-2 focus-visible:outline-border-focus focus-visible:outline-offset-2"
    >
      <Card
        padding="none"
        className={cn(
          'relative flex h-full flex-col overflow-hidden transition-all duration-[120ms]',
          'group-hover:-translate-y-0.5 group-hover:shadow-lg',
          t.ringHover,
        )}
      >
        {/* Top-edge tone stripe */}
        <div aria-hidden className={cn('absolute inset-x-0 top-0 h-1', t.stripe)} />
        {/* Soft radial-ish gradient bringing the tone in from the top-left */}
        <div
          aria-hidden
          className={cn(
            'pointer-events-none absolute inset-0 bg-gradient-to-br opacity-90',
            t.gradient,
          )}
        />

        <div className="relative flex h-full flex-col gap-4 px-5 pb-5 pt-6">
          <header className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3">
              <span
                className={cn(
                  'flex h-11 w-11 items-center justify-center rounded-lg shadow-sm ring-1 ring-inset ring-black/[0.04]',
                  t.iconTile,
                )}
              >
                <Icon width={22} height={22} aria-hidden />
              </span>
              <div className="flex flex-col">
                <h2 className="font-voice text-lg font-medium leading-tight text-text-primary">
                  {title}
                </h2>
                <p className="text-xs text-text-secondary">{subtitle}</p>
              </div>
            </div>

            {count !== null && (
              <div className="flex flex-col items-end leading-none">
                <span className={cn('font-voice text-4xl font-medium tabular-nums', t.countText)}>
                  {count}
                </span>
                <span className="mt-1 font-mono text-[10px] uppercase tracking-wider text-text-muted">
                  total
                </span>
              </div>
            )}
          </header>

          <div className="flex flex-1 flex-col gap-2">
            {state.kind === 'pending' && <PreviewSkeleton />}
            {state.kind === 'error' && (
              <p className="text-sm text-danger" role="alert">
                {state.message}
              </p>
            )}
            {state.kind === 'empty' && (
              <div className="flex flex-1 items-center rounded-md border border-dashed border-border-default bg-bg-surface/60 px-3 py-4 text-sm text-text-muted">
                {state.hint}
              </div>
            )}
            {state.kind === 'populated' && state.children}
          </div>

          <footer className="mt-auto flex items-center justify-between border-t border-border-default/70 pt-3 text-sm">
            <span className={cn('font-medium transition-colors', t.ctaText, t.ctaHover)}>
              {ctaLabel}
            </span>
            <ChevronRight
              width={16}
              height={16}
              className={cn(
                'transition-transform group-hover:translate-x-0.5',
                t.ctaText,
                t.ctaHover,
              )}
              aria-hidden
            />
          </footer>
        </div>
      </Card>
    </Link>
  );
}

function PreviewSkeleton(): JSX.Element {
  return (
    <div className="flex flex-col gap-2" data-testid="dashboard-card-loading">
      {Array.from({ length: 3 }).map((_, i) => (
        <Skeleton key={i} height={16} />
      ))}
    </div>
  );
}

function PreviewList({ children }: { children: ReactNode }): JSX.Element {
  return <ul className="flex flex-col gap-1.5 text-sm">{children}</ul>;
}

function PreviewRow({
  primary,
  secondary,
  primaryClassName,
}: {
  primary: ReactNode;
  secondary?: ReactNode;
  primaryClassName?: string;
}): JSX.Element {
  return (
    <li className="flex items-baseline justify-between gap-3">
      <span className={cn('truncate text-text-primary', primaryClassName)}>{primary}</span>
      {secondary !== undefined && (
        <span className="shrink-0 text-xs text-text-muted">{secondary}</span>
      )}
    </li>
  );
}

function errorMessageFor(err: ApiError): string {
  return errorCopy[err.code]?.title ?? errorCopy.__unknown__.title;
}

function useAgentsCardState(): CardState {
  const query = useAgents();
  const items = flattenPages(query.data);

  if (query.isPending) return { kind: 'pending' };
  if (query.isError) return { kind: 'error', message: errorMessageFor(query.error) };
  if (items.length === 0) {
    return { kind: 'empty', hint: 'No agents yet — create your first one.' };
  }

  const sorted = [...items]
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .slice(0, PREVIEW_LIMIT);

  return {
    kind: 'populated',
    total: items.length,
    children: (
      <PreviewList>
        {sorted.map((a: Agent) => (
          <PreviewRow
            key={a.id}
            primary={<span className="font-mono text-accent">{a.name}</span>}
            secondary={formatRelative(a.updatedAt)}
          />
        ))}
      </PreviewList>
    ),
  };
}

function useConversationsCardState(): CardState {
  const query = useConversations();
  const items = flattenPages(query.data);

  if (query.isPending) return { kind: 'pending' };
  if (query.isError) return { kind: 'error', message: errorMessageFor(query.error) };
  if (items.length === 0) {
    return { kind: 'empty', hint: 'No conversations yet — start chatting with an agent.' };
  }

  const recent = [...items]
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .slice(0, PREVIEW_LIMIT);

  return {
    kind: 'populated',
    total: items.length,
    children: (
      <PreviewList>
        {recent.map((c: Conversation) => {
          const fallback = c.title === null || c.title === undefined;
          const label = fallback ? `chat-${c.id.slice(0, 8)}` : (c.title as string);
          return (
            <PreviewRow
              key={c.id}
              primary={
                <span className={cn(fallback && 'font-mono text-text-secondary')}>{label}</span>
              }
              secondary={formatRelative(c.updatedAt)}
            />
          );
        })}
      </PreviewList>
    ),
  };
}

function useToolsCardState(): CardState {
  const query = useTools();
  if (query.isPending) return { kind: 'pending' };
  if (query.isError) return { kind: 'error', message: errorMessageFor(query.error) };
  const items = query.data ?? [];
  if (items.length === 0) return { kind: 'empty', hint: 'No tools are configured on the platform.' };

  const preview = items.slice(0, PREVIEW_LIMIT);
  return {
    kind: 'populated',
    total: items.length,
    children: (
      <PreviewList>
        {preview.map((t: ToolDescriptor) => (
          <PreviewRow
            key={t.name}
            primary={<span className="font-mono text-text-primary">{t.name}</span>}
          />
        ))}
      </PreviewList>
    ),
  };
}

function useMcpCardState(): CardState {
  const query = useMcpServers();
  if (query.isPending) return { kind: 'pending' };
  if (query.isError) return { kind: 'error', message: errorMessageFor(query.error) };
  const items = query.data ?? [];
  if (items.length === 0) {
    return { kind: 'empty', hint: 'No MCP servers configured.' };
  }

  const preview = items.slice(0, PREVIEW_LIMIT);
  return {
    kind: 'populated',
    total: items.length,
    children: (
      <PreviewList>
        {preview.map((s: McpServerDescriptor) => (
          <PreviewRow
            key={s.name}
            primary={<span className="font-mono text-text-primary">{s.name}</span>}
          />
        ))}
      </PreviewList>
    ),
  };
}

export default function HomePage(): JSX.Element {
  const { principal } = useAuth();
  const isAdmin = principal?.role === 'ADMIN';

  const agentsState = useAgentsCardState();
  const chatsState = useConversationsCardState();
  const toolsState = useToolsCardState();
  const mcpState = useMcpCardState();

  const greeting = useMemo(() => {
    const hour = new Date().getHours();
    if (hour < 5) return 'Good night';
    if (hour < 12) return 'Good morning';
    if (hour < 18) return 'Good afternoon';
    return 'Good evening';
  }, []);

  return (
    <div className="relative mx-auto flex w-full max-w-6xl flex-col gap-8 px-6 py-8">
      {/* Decorative ambient wash — sits behind the content and softens the
          expanse of the light workspace surface. Kept extremely subtle so it
          reads as atmosphere, not a graphic. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 -z-10 h-64 bg-gradient-to-b from-accent-bg/50 via-accent-bg/10 to-transparent"
      />

      <header className="flex flex-col gap-1">
        <span className="font-mono text-xs uppercase tracking-wider text-text-muted">
          Dashboard
        </span>
        <h1 className="font-voice text-3xl font-medium tracking-tight text-text-primary">
          {greeting}
        </h1>
        <p className="max-w-2xl text-sm text-text-secondary">
          A quick overview of your workspace. Pick a card below to jump into agents,
          conversations or the platform catalog.
        </p>
      </header>

      <section aria-label="Workspace overview" className="flex flex-col gap-3">
        <h2 className="font-voice text-sm font-medium uppercase tracking-wider text-text-muted">
          Workspace
        </h2>
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-4">
          <DashboardCard
            to="/agents"
            title="Agents"
            subtitle="Your AI agents"
            icon={Bot}
            tone="accent"
            state={agentsState}
            ctaLabel="Manage agents"
          />
          <DashboardCard
            to="/chat"
            title="Chats"
            subtitle="Recent conversations"
            icon={MessageSquare}
            tone="warning"
            state={chatsState}
            ctaLabel="Open chat"
          />
          <DashboardCard
            to="/tools"
            title="Tools"
            subtitle="Platform tool catalog"
            icon={Wrench}
            tone="info"
            state={toolsState}
            ctaLabel="Browse tools"
          />
          <DashboardCard
            to="/mcp-servers"
            title="MCP servers"
            subtitle="Available integrations"
            icon={Server}
            tone="success"
            state={mcpState}
            ctaLabel="Browse MCP servers"
          />
        </div>
      </section>

      {isAdmin && (
        <section aria-label="Administration" className="flex flex-col gap-3">
          <h2 className="font-voice text-sm font-medium uppercase tracking-wider text-text-muted">
            Administration
          </h2>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
            <AdminShortcut
              to="/admin/users"
              title="Users"
              subtitle="Manage platform accounts"
              icon={Users}
            />
            <AdminShortcut
              to="/admin/api-keys"
              title="API keys"
              subtitle="Machine-to-machine credentials"
              icon={Key}
            />
            <AdminShortcut
              to="/admin/rate-limit"
              title="Rate limit"
              subtitle="Live throttling configuration"
              icon={Settings}
            />
          </div>
        </section>
      )}
    </div>
  );
}

function AdminShortcut({
  to,
  title,
  subtitle,
  icon: Icon,
}: {
  to: string;
  title: string;
  subtitle: string;
  icon: LucideIcon;
}): JSX.Element {
  return (
    <Link
      to={to}
      aria-label={`${title} — ${subtitle}`}
      className="group rounded-lg outline-none focus-visible:outline-2 focus-visible:outline-border-focus focus-visible:outline-offset-2"
    >
      <Card
        padding="md"
        className="flex h-full items-center gap-3 transition-all duration-[120ms] group-hover:-translate-y-0.5 group-hover:border-border-accent group-hover:shadow-md"
      >
        <span className="flex h-9 w-9 items-center justify-center rounded-md bg-accent-bg text-accent">
          <Icon width={18} height={18} aria-hidden />
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <p className="text-sm font-medium text-text-primary">{title}</p>
          <p className="truncate text-xs text-text-secondary">{subtitle}</p>
        </div>
        <ChevronRight
          width={16}
          height={16}
          className="text-text-muted transition-transform group-hover:translate-x-0.5 group-hover:text-accent"
          aria-hidden
        />
      </Card>
    </Link>
  );
}
