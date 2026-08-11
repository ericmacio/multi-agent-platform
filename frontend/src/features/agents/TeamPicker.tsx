import { useEffect, useMemo, useState } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Checkbox } from '@/shared/ui/Checkbox';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Input } from '@/shared/ui/Input';
import { Skeleton } from '@/shared/ui/Skeleton';
import { Tooltip } from '@/shared/ui/Tooltip';
import { Search } from '@/shared/ui/icons';
import { useAgents } from './api';
import type { Agent } from './schema';

export type TeamPickerProps = {
  value: string[];
  onChange: (next: string[]) => void;
  /**
   * Agent currently being edited. Excluded from the candidate list so an
   * agent cannot delegate to itself.
   */
  excludeAgentId?: string;
  disabled?: boolean;
};

const NESTED_TEAM_TOOLTIP = 'Has a team of its own — cannot be a delegate.';

export function TeamPicker({
  value,
  onChange,
  excludeAgentId,
  disabled = false,
}: TeamPickerProps): JSX.Element {
  const [query, setQuery] = useState('');
  const [showOnlySelected, setShowOnlySelected] = useState(false);
  const agents = useAgents();

  // Drain pagination automatically: a v1 owner is expected to have few
  // agents (`REQ-NFR-005`), so the simplest correct behavior is to expose
  // the entire owned set.
  useEffect(() => {
    if (agents.hasNextPage && !agents.isFetchingNextPage) {
      void agents.fetchNextPage();
    }
  }, [agents]);

  const allAgents: Agent[] = useMemo(() => flattenPages(agents.data), [agents.data]);

  const selectedSet = useMemo(() => new Set(value), [value]);

  const candidates = useMemo(() => {
    let list = allAgents;
    if (excludeAgentId) {
      list = list.filter((a) => a.id !== excludeAgentId);
    }
    if (showOnlySelected) {
      list = list.filter((a) => selectedSet.has(a.id));
    }
    const q = query.trim().toLowerCase();
    if (q !== '') {
      list = list.filter(
        (a) => a.name.toLowerCase().includes(q) || a.description.toLowerCase().includes(q),
      );
    }
    return list;
  }, [allAgents, excludeAgentId, showOnlySelected, query, selectedSet]);

  function toggle(agentId: string): void {
    if (disabled) return;
    if (selectedSet.has(agentId)) {
      onChange(value.filter((v) => v !== agentId));
    } else {
      onChange([...value, agentId]);
    }
  }

  const drainedEmpty =
    agents.isSuccess &&
    !agents.hasNextPage &&
    allAgents.filter((a) => a.id !== excludeAgentId).length === 0;
  const stillDraining = agents.hasNextPage || agents.isFetchingNextPage;

  return (
    <Card padding="md" className="flex flex-col gap-3">
      <header className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-text-primary">Team</h3>
        <Badge variant="accent" data-testid="team-picker-count">{`${value.length} selected`}</Badge>
      </header>

      {agents.isPending && (
        <div className="flex flex-col gap-2" data-testid="team-picker-loading">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} height={28} />
          ))}
        </div>
      )}

      {agents.isError && (
        <div className="flex flex-col items-start gap-2" role="alert">
          <p className="text-sm font-medium text-text-primary">
            {errorCopy[agents.error.code]?.title ?? errorCopy.__unknown__.title}
          </p>
          <Button variant="secondary" size="sm" onClick={() => void agents.refetch()}>
            Retry
          </Button>
        </div>
      )}

      {agents.isSuccess && drainedEmpty && (
        <EmptyState
          title="No other agents to delegate to"
          description="Create another agent first."
        />
      )}

      {agents.isSuccess && !drainedEmpty && (
        <>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <div className="flex flex-1 items-center gap-2">
              <Search aria-hidden width={16} height={16} className="text-text-muted" />
              <div className="flex-1">
                <Input
                  aria-label="Filter agents"
                  placeholder="Filter by name or description…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                />
              </div>
            </div>
            <Checkbox
              label="Show only selected"
              checked={showOnlySelected}
              onChange={(e) => setShowOnlySelected(e.target.checked)}
            />
          </div>

          {stillDraining && (
            <p className="text-xs text-text-muted" data-testid="team-picker-draining">
              Loading agents… ({allAgents.length} loaded)
            </p>
          )}

          {candidates.length === 0 ? (
            <EmptyState
              title={query.trim() !== '' ? `No agents match "${query}"` : 'No candidates to show'}
              description={
                showOnlySelected
                  ? 'Try unchecking "Show only selected".'
                  : 'Try a shorter or different query.'
              }
              action={
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    setQuery('');
                    setShowOnlySelected(false);
                  }}
                >
                  Clear filters
                </Button>
              }
            />
          ) : (
            <ul className="flex max-h-72 flex-col gap-1 overflow-auto">
              {candidates.map((agent) => {
                const hasOwnTeam = agent.team.length > 0;
                const row = (
                  <div
                    className={`rounded-md px-2 py-1.5 ${
                      hasOwnTeam || disabled ? 'opacity-60' : 'hover:bg-bg-elevated'
                    }`}
                  >
                    <Checkbox
                      checked={selectedSet.has(agent.id)}
                      disabled={hasOwnTeam || disabled}
                      onChange={() => toggle(agent.id)}
                      label={
                        <span className="flex flex-col">
                          <span className="font-mono text-sm text-text-primary">{agent.name}</span>
                          <span className="line-clamp-1 text-xs text-text-secondary">
                            {agent.description}
                          </span>
                        </span>
                      }
                    />
                  </div>
                );
                return (
                  <li key={agent.id}>
                    {hasOwnTeam ? <Tooltip content={NESTED_TEAM_TOOLTIP}>{row}</Tooltip> : row}
                  </li>
                );
              })}
            </ul>
          )}
        </>
      )}
    </Card>
  );
}
