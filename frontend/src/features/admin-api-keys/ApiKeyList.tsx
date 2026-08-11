import { useMemo } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { formatRelative } from '@/shared/lib/date';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from '@/shared/ui/Dropdown';
import { LoadingList } from '@/shared/ui/LoadingList';
import { MoreHorizontal } from '@/shared/ui/icons';
import { useApiKeys } from './api';
import type { ApiKey } from './schema';

export type ApiKeyListProps = {
  onToggleDisabled: (apiKey: ApiKey) => void;
};

export function ApiKeyList({ onToggleDisabled }: ApiKeyListProps): JSX.Element | null {
  const query = useApiKeys();
  const keys = useMemo(() => flattenPages(query.data), [query.data]);

  if (query.isPending) {
    return <LoadingList testId="api-key-list-loading" />;
  }

  if (query.isError) {
    return (
      <Card padding="md" className="border-danger/40">
        <div className="flex flex-col items-start gap-3" role="alert">
          <div>
            <p className="text-sm font-medium text-text-primary">
              {errorCopy[query.error.code]?.title ?? errorCopy.__unknown__.title}
            </p>
            <p className="text-sm text-text-secondary">
              {query.error.detail ??
                errorCopy[query.error.code]?.detail ??
                errorCopy.__unknown__.detail}
            </p>
          </div>
          <Button variant="secondary" size="sm" onClick={() => void query.refetch()}>
            Retry
          </Button>
        </div>
      </Card>
    );
  }

  if (keys.length === 0) return null;

  return (
    <div className="flex flex-col gap-4">
      <Card padding="none" className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border-default bg-bg-elevated text-left text-xs uppercase tracking-wide text-text-muted">
              <th className="px-4 py-2 font-medium">Client ID</th>
              <th className="px-4 py-2 font-medium">Label</th>
              <th className="px-4 py-2 font-medium">Status</th>
              <th className="px-4 py-2 font-medium">Created</th>
              <th className="w-10 px-4 py-2" aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {keys.map((key) => (
              <ApiKeyRow
                key={key.clientId}
                apiKey={key}
                onToggleDisabled={() => onToggleDisabled(key)}
              />
            ))}
          </tbody>
        </table>
      </Card>

      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button
            variant="secondary"
            size="sm"
            loading={query.isFetchingNextPage}
            onClick={() => void query.fetchNextPage()}
          >
            Load more
          </Button>
        </div>
      )}
    </div>
  );
}

type ApiKeyRowProps = {
  apiKey: ApiKey;
  onToggleDisabled: () => void;
};

function ApiKeyRow({ apiKey, onToggleDisabled }: ApiKeyRowProps): JSX.Element {
  return (
    <tr
      className="border-t border-border-default"
      data-testid={`api-key-row-${apiKey.clientId}`}
    >
      <td className="px-4 py-2 font-mono text-text-primary">{apiKey.clientId}</td>
      <td className="px-4 py-2 text-text-primary">{apiKey.label ?? '—'}</td>
      <td className="px-4 py-2">
        {apiKey.disabled ? (
          <Badge variant="danger">Revoked</Badge>
        ) : (
          <Badge variant="success">Active</Badge>
        )}
      </td>
      <td className="px-4 py-2 text-text-muted">{formatRelative(apiKey.createdAt)}</td>
      <td className="px-4 py-2" data-api-key-actions>
        <Dropdown>
          <DropdownTrigger
            aria-label={`Actions for ${apiKey.clientId}`}
            className="rounded-md p-1 text-text-muted hover:text-text-primary"
          >
            <MoreHorizontal width={16} height={16} aria-hidden />
          </DropdownTrigger>
          <DropdownContent align="end">
            <DropdownItem onClick={onToggleDisabled}>
              {apiKey.disabled ? 'Re-enable' : 'Revoke'}
            </DropdownItem>
          </DropdownContent>
        </Dropdown>
      </td>
    </tr>
  );
}
