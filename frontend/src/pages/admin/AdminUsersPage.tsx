import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DeleteUserDialog } from '@/features/admin-users/DeleteUserDialog';
import { DisableUserDialog } from '@/features/admin-users/DisableUserDialog';
import { UserList } from '@/features/admin-users/UserList';
import { useUsers } from '@/features/admin-users/api';
import type { User } from '@/features/admin-users/schema';
import { flattenPages } from '@/shared/lib/pagination';
import { Button } from '@/shared/ui/Button';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Plus } from '@/shared/ui/icons';
import { toast } from '@/shared/ui/Toast';

export default function AdminUsersPage(): JSX.Element {
  const navigate = useNavigate();
  const query = useUsers();
  const users = flattenPages(query.data);
  const [pendingToggle, setPendingToggle] = useState<User | null>(null);
  const [pendingDelete, setPendingDelete] = useState<User | null>(null);

  const showEmpty = query.isSuccess && users.length === 0;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-6">
      <header className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-medium text-text-primary">Users</h1>
          <p className="text-sm text-text-secondary">
            Create, enable, disable, and remove platform users.
          </p>
        </div>
        <Button
          leftIcon={<Plus aria-hidden width={16} height={16} />}
          onClick={() => navigate('/admin/users/new')}
        >
          Create user
        </Button>
      </header>

      {showEmpty ? (
        <EmptyState
          title="No users yet"
          description="The platform seeds a default admin; if you're seeing this, something is off."
          action={<Button onClick={() => navigate('/admin/users/new')}>Create user</Button>}
        />
      ) : (
        <UserList
          onView={(id) => navigate(`/admin/users/${id}`)}
          onToggleDisabled={(user) => setPendingToggle(user)}
          onDelete={(user) => setPendingDelete(user)}
        />
      )}

      <DisableUserDialog
        user={pendingToggle}
        open={pendingToggle !== null}
        onClose={() => setPendingToggle(null)}
        onDone={() => {
          toast.success('Account updated.');
        }}
      />

      <DeleteUserDialog
        user={pendingDelete}
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        onDeleted={() => {
          toast.success('User deleted.');
        }}
      />
    </div>
  );
}
