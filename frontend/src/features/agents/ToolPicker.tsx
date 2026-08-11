import { useMemo, useState } from 'react';
import { useTools } from '@/features/catalog/api';
import { errorCopy } from '@/shared/i18n/en';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Checkbox } from '@/shared/ui/Checkbox';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Input } from '@/shared/ui/Input';
import { Skeleton } from '@/shared/ui/Skeleton';
import { Search } from '@/shared/ui/icons';

export type ToolPickerProps = {
  value: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
};

export function ToolPicker({ value, onChange, disabled = false }: ToolPickerProps): JSX.Element {
  const [query, setQuery] = useState('');
  const tools = useTools();

  const filtered = useMemo(() => {
    if (!tools.data) return [];
    const q = query.trim().toLowerCase();
    if (q === '') return tools.data;
    return tools.data.filter(
      (it) =>
        it.name.toLowerCase().includes(q) || (it.description ?? '').toLowerCase().includes(q),
    );
  }, [tools.data, query]);

  const selectedSet = useMemo(() => new Set(value), [value]);

  function toggle(name: string): void {
    if (disabled) return;
    if (selectedSet.has(name)) {
      onChange(value.filter((v) => v !== name));
    } else {
      onChange([...value, name]);
    }
  }

  return (
    <Card padding="md" className="flex flex-col gap-3">
      <header className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-text-primary">Tools</h3>
        <Badge variant="accent" data-testid="tool-picker-count">{`${value.length} selected`}</Badge>
      </header>

      {tools.isPending && (
        <div className="flex flex-col gap-2" data-testid="tool-picker-loading">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} height={28} />
          ))}
        </div>
      )}

      {tools.isError && (
        <div className="flex flex-col items-start gap-2" role="alert">
          <p className="text-sm font-medium text-text-primary">
            {errorCopy[tools.error.code]?.title ?? errorCopy.__unknown__.title}
          </p>
          <Button variant="secondary" size="sm" onClick={() => void tools.refetch()}>
            Retry
          </Button>
        </div>
      )}

      {tools.isSuccess && tools.data.length === 0 && (
        <EmptyState
          title="No tools configured"
          description="Ask an administrator to configure tools on the backend."
        />
      )}

      {tools.isSuccess && tools.data.length > 0 && (
        <>
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
            <ul className="flex max-h-72 flex-col gap-1 overflow-auto">
              {filtered.map((tool) => (
                <li
                  key={tool.name}
                  className={`rounded-md px-2 py-1.5 ${
                    disabled ? 'opacity-60' : 'hover:bg-bg-elevated'
                  }`}
                >
                  <Checkbox
                    checked={selectedSet.has(tool.name)}
                    disabled={disabled}
                    onChange={() => toggle(tool.name)}
                    label={
                      <span className="flex flex-col">
                        <span className="font-mono text-sm text-text-primary">{tool.name}</span>
                        <span className="text-xs text-text-secondary">{tool.description}</span>
                      </span>
                    }
                  />
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </Card>
  );
}
