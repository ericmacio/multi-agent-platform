import { formatRelative } from '@/shared/lib/date';
import { cn } from '@/shared/lib/cn';
import { Badge } from '@/shared/ui/Badge';
import { Card } from '@/shared/ui/Card';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from '@/shared/ui/Dropdown';
import { MoreHorizontal } from '@/shared/ui/icons';
import type { Agent } from './schema';

export type AgentCardProps = {
  agent: Agent;
  onView: () => void;
  onEdit: () => void;
  onStartChat: () => void;
  onDelete: () => void;
};

const MAX_MCP_BADGES = 3;

/**
 * Deterministic 4-way color rotation keyed on the agent name — gives every
 * card an "identity color" that survives edits, without introducing a stored
 * field. Ordered so the palette on a page rotates through the semantic tones
 * that already exist in tokens.css.
 */
type AvatarTone = 'accent' | 'info' | 'warning' | 'success';

const AVATAR_TONES: AvatarTone[] = ['accent', 'info', 'success', 'warning'];

const AVATAR_CLASSES: Record<AvatarTone, { tile: string; stripe: string }> = {
  accent: { tile: 'bg-accent-bg text-accent', stripe: 'bg-accent' },
  info: { tile: 'bg-info-bg text-info', stripe: 'bg-info' },
  success: { tile: 'bg-success-bg text-success', stripe: 'bg-success' },
  warning: { tile: 'bg-warning-bg text-warning', stripe: 'bg-warning' },
};

function toneFor(name: string): AvatarTone {
  let hash = 0;
  for (let i = 0; i < name.length; i += 1) {
    hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  }
  return AVATAR_TONES[hash % AVATAR_TONES.length]!;
}

function initialsFor(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length === 0) return '?';
  const parts = trimmed.split(/[\s_\-.]+/).filter(Boolean);
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

export function AgentCard({
  agent,
  onView,
  onEdit,
  onStartChat,
  onDelete,
}: AgentCardProps): JSX.Element {
  const mcp = agent.enabledMcpServers ?? [];
  const visible = mcp.slice(0, MAX_MCP_BADGES);
  const overflow = mcp.length - visible.length;
  const tone = toneFor(agent.name);
  const t = AVATAR_CLASSES[tone];

  return (
    <Card
      padding="none"
      className="group relative flex flex-col gap-3 overflow-hidden p-4 transition-all duration-[120ms] hover:-translate-y-0.5 hover:border-border-accent hover:shadow-lg"
    >
      {/* Left-edge identity stripe, colored per-name */}
      <div aria-hidden className={cn('absolute inset-y-0 left-0 w-1', t.stripe)} />

      <header className="flex items-start justify-between gap-2 pl-2">
        <div className="flex min-w-0 items-center gap-3">
          <span
            aria-hidden
            className={cn(
              'flex h-10 w-10 shrink-0 items-center justify-center rounded-lg font-voice text-sm font-medium shadow-sm ring-1 ring-inset ring-black/[0.04]',
              t.tile,
            )}
          >
            {initialsFor(agent.name)}
          </span>
          <div className="flex min-w-0 flex-col">
            <h3 className="truncate font-mono text-sm font-medium text-accent">{agent.name}</h3>
            <span className="text-xs text-text-muted">{agent.llmModel ?? 'default'}</span>
          </div>
        </div>
        <Dropdown>
          <DropdownTrigger
            aria-label={`Actions for ${agent.name}`}
            className="text-text-muted hover:text-text-primary"
          >
            <MoreHorizontal width={18} height={18} aria-hidden />
          </DropdownTrigger>
          <DropdownContent align="end">
            <DropdownItem onClick={onView}>View</DropdownItem>
            <DropdownItem onClick={onEdit}>Edit</DropdownItem>
            <DropdownItem onClick={onStartChat}>Start chat</DropdownItem>
            <DropdownItem onClick={onDelete} className="text-danger">
              Delete
            </DropdownItem>
          </DropdownContent>
        </Dropdown>
      </header>

      <p className="line-clamp-2 pl-2 text-sm text-text-secondary">{agent.description}</p>

      <div className="flex flex-wrap items-center gap-1.5 pl-2">
        <Badge variant="neutral">{`${(agent.tools ?? []).length} tools`}</Badge>
        {visible.map((name) => (
          <Badge key={name} variant="info">
            {name}
          </Badge>
        ))}
        {overflow > 0 && <Badge variant="neutral">{`+${overflow}`}</Badge>}
        {(agent.team ?? []).length > 0 && (
          <Badge variant="success">{`Team of ${agent.team.length}`}</Badge>
        )}
      </div>

      <footer className="pl-2 text-xs text-text-muted">
        Last updated {formatRelative(agent.updatedAt)}
      </footer>
    </Card>
  );
}
