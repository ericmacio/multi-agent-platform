import { useNavigate } from 'react-router-dom';
import { UserForm } from '@/features/admin-users/UserForm';
import { toast } from '@/shared/ui/Toast';

export default function AdminUserCreatePage(): JSX.Element {
  const navigate = useNavigate();

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-6 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-medium text-text-primary">New user</h1>
        <p className="text-sm text-text-secondary">Create a new platform account.</p>
      </header>
      <UserForm
        onSuccess={(user) => {
          toast.success('User created.');
          navigate(`/admin/users/${user.id}`, { replace: true });
        }}
        onCancel={() => navigate('/admin/users')}
      />
    </div>
  );
}
