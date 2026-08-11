import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { DeleteUserDialog } from '@/features/admin-users/DeleteUserDialog';
import { DisableUserDialog } from '@/features/admin-users/DisableUserDialog';
import { useUser } from '@/features/admin-users/api';
import type { User } from '@/features/admin-users/schema';
import { errorCopy } from '@/shared/i18n/en';
import { formatDateTime } from '@/shared/lib/date';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { NotFoundState } from '@/shared/ui/NotFoundState';
import { Skeleton } from '@/shared/ui/Skeleton';
import { toast } from '@/shared/ui/Toast';

export default function AdminUserDetailPage(): JSX.Element {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const query = useUser(userId);
  const [pendingToggle, setPendingToggle] = useState<User | null>(null);
  const [pendingDelete, setPendingDelete] = useState<User | null>(null);

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
            title="User not found"
            description="This user no longer exists."
            action={
              <Link
                to="/admin/users"
                className="inline-flex h-9 items-center justify-center rounded-md border border-border-default bg-bg-elevated px-4 text-sm font-medium text-text-primary hover:bg-bg-surface"
              >
                Back to users
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

  const user = query.data;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h1 className="font-mono text-xl font-medium text-accent">{user.email}</h1>
          <p className="text-sm text-text-secondary">User account</p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="secondary" onClick={() => setPendingToggle(user)}>
            {user.disabled ? 'Enable' : 'Disable'}
          </Button>
          <Button variant="danger" onClick={() => setPendingDelete(user)}>
            Delete
          </Button>
        </div>
      </header>

      <Card padding="md" className="flex flex-col gap-3">
        <FieldRow label="Email" value={user.email} mono />
        <FieldRow label="Role" value={<Badge variant={user.role === 'ADMIN' ? 'accent' : 'neutral'}>{user.role}</Badge>} />
        <FieldRow
          label="Status"
          value={
            user.disabled ? (
              <Badge variant="danger">Disabled</Badge>
            ) : (
              <Badge variant="success">Active</Badge>
            )
          }
        />
        <FieldRow label="Must change password" value={user.mustChangePassword ? 'Yes' : 'No'} />
        <FieldRow label="Created" value={formatDateTime(user.createdAt)} />
        <FieldRow label="Updated" value={formatDateTime(user.updatedAt)} />
      </Card>

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
          navigate('/admin/users', { replace: true });
        }}
      />
    </div>
  );
}

function FieldRow({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
}): JSX.Element {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-xs uppercase tracking-wide text-text-muted">{label}</span>
      <span className={`text-sm text-text-primary ${mono ? 'font-mono' : ''}`}>{value}</span>
    </div>
  );
}
