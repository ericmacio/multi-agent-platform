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
import { useUsers } from './api';
import type { User } from './schema';

export type UserListProps = {
  onView: (id: string) => void;
  onToggleDisabled: (user: User) => void;
  onDelete: (user: User) => void;
};

export function UserList({
  onView,
  onToggleDisabled,
  onDelete,
}: UserListProps): JSX.Element | null {
  const query = useUsers();
  const users = useMemo(() => flattenPages(query.data), [query.data]);

  if (query.isPending) {
    return <LoadingList testId="user-list-loading" />;
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

  // Empty case is owned by the page (AdminUsersPage) so it can decide the
  // page-level empty state. Returning null keeps the list a pure renderer.
  if (users.length === 0) return null;

  return (
    <div className="flex flex-col gap-4">
      <Card padding="none" className="overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border-default bg-bg-elevated text-left text-xs uppercase tracking-wide text-text-muted">
              <th className="px-4 py-2 font-medium">Email</th>
              <th className="px-4 py-2 font-medium">Role</th>
              <th className="px-4 py-2 font-medium">Status</th>
              <th className="px-4 py-2 font-medium">Created</th>
              <th className="w-10 px-4 py-2" aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <UserRow
                key={user.id}
                user={user}
                onView={() => onView(user.id)}
                onToggleDisabled={() => onToggleDisabled(user)}
                onDelete={() => onDelete(user)}
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

type UserRowProps = {
  user: User;
  onView: () => void;
  onToggleDisabled: () => void;
  onDelete: () => void;
};

function UserRow({ user, onView, onToggleDisabled, onDelete }: UserRowProps): JSX.Element {
  return (
    <tr
      className="cursor-pointer border-t border-border-default transition-colors hover:bg-bg-elevated"
      onClick={(e) => {
        // Ignore clicks that originated inside the actions cell.
        const target = e.target as HTMLElement;
        if (target.closest('[data-user-actions]')) return;
        onView();
      }}
      data-testid={`user-row-${user.id}`}
    >
      <td className="px-4 py-2 font-mono text-text-primary">{user.email}</td>
      <td className="px-4 py-2">
        <Badge variant={user.role === 'ADMIN' ? 'accent' : 'neutral'}>{user.role}</Badge>
      </td>
      <td className="px-4 py-2">
        {user.disabled ? (
          <Badge variant="danger">Disabled</Badge>
        ) : (
          <Badge variant="success">Active</Badge>
        )}
      </td>
      <td className="px-4 py-2 text-text-muted">{formatRelative(user.createdAt)}</td>
      <td className="px-4 py-2" data-user-actions>
        <Dropdown>
          <DropdownTrigger
            aria-label={`Actions for ${user.email}`}
            className="rounded-md p-1 text-text-muted hover:text-text-primary"
          >
            <MoreHorizontal width={16} height={16} aria-hidden />
          </DropdownTrigger>
          <DropdownContent align="end">
            <DropdownItem onClick={onToggleDisabled}>
              {user.disabled ? 'Enable' : 'Disable'}
            </DropdownItem>
            <DropdownItem onClick={onDelete} className="text-danger">
              Delete
            </DropdownItem>
          </DropdownContent>
        </Dropdown>
      </td>
    </tr>
  );
}
