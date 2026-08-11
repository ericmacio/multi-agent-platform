import { useMemo, useState } from 'react';
import { Button } from '@/shared/ui/Button';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Input } from '@/shared/ui/Input';
import { Search } from '@/shared/ui/icons';
import type { ToolDescriptor } from './api';

type ToolListProps = {
  items: ToolDescriptor[];
};

export function ToolList({ items }: ToolListProps): JSX.Element {
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q === '') return items;
    return items.filter(
      (it) =>
        it.name.toLowerCase().includes(q) || (it.description ?? '').toLowerCase().includes(q),
    );
  }, [items, query]);

  if (items.length === 0) {
    return (
      <EmptyState
        title="No tools configured"
        description="The platform has no tools registered. Ask an administrator to configure tools on the backend."
      />
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <Search aria-hidden width={16} height={16} className="text-text-muted" />
        <div className="flex-1">
          <Input
            aria-label="Filter tools"
            placeholder="Filter by name or description…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <EmptyState
          title={`No tools match "${query}"`}
          description="Try a shorter or different query."
          action={
            <Button variant="secondary" size="sm" onClick={() => setQuery('')}>
              Clear filter
            </Button>
          }
        />
      ) : (
        <div className="overflow-hidden rounded-lg border border-border-default bg-bg-surface">
          <table className="w-full border-collapse text-sm">
            <thead>
              <tr className="border-b border-border-default bg-bg-elevated text-left text-xs uppercase tracking-wider text-text-muted">
                <th className="w-1/3 px-4 py-2 font-medium">Name</th>
                <th className="px-4 py-2 font-medium">Description</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((tool) => (
                <tr
                  key={tool.name}
                  className="border-b border-border-default last:border-b-0 hover:bg-bg-elevated/60"
                >
                  <td className="px-4 py-3 align-top font-mono text-text-primary">{tool.name}</td>
                  <td className="px-4 py-3 align-top text-text-secondary">{tool.description}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
