import { useCallback, useEffect, useState } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { Modal } from '@/shared/ui/Modal';
import { useDeleteUser } from './api';
import type { User } from './schema';

export type DeleteUserDialogProps = {
  user: User | null;
  open: boolean;
  onClose: () => void;
  onDeleted: (user: User) => void;
};

/**
 * Type-email-to-confirm dialog quoting the cascade warning from
 * `REQ-USR-006`: deleting a user hard-deletes all their agents and
 * conversations. The Delete button stays disabled until the typed value
 * matches the user's email exactly (case-sensitive).
 */
export function DeleteUserDialog({
  user,
  open,
  onClose,
  onDeleted,
}: DeleteUserDialogProps): JSX.Element | null {
  const [confirmText, setConfirmText] = useState('');
  const mutation = useDeleteUser();

  useEffect(() => {
    if (!open) {
      setConfirmText('');
      mutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) onClose();
    },
    [onClose],
  );

  if (!user) return null;

  const matches = confirmText === user.email;

  function handleDelete(): void {
    if (!user || !matches) return;
    mutation.mutate(
      { userId: user.id },
      {
        onSuccess: () => {
          onDeleted(user);
          onClose();
        },
      },
    );
  }

  return (
    <Modal open={open} onOpenChange={handleOpenChange} title={`Delete ${user.email}?`}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text-secondary">
          Deleting {user.email} will permanently delete their agents and conversations. This
          cannot be undone.
        </p>

        <Input
          label={`Type "${user.email}" to confirm.`}
          value={confirmText}
          onChange={(e) => setConfirmText(e.target.value)}
          placeholder={user.email}
          autoComplete="off"
        />

        {mutation.isError && (
          <div
            role="alert"
            className="rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger"
          >
            {errorCopy[mutation.error.code]?.title ?? errorCopy.__unknown__.title}
          </div>
        )}

        <footer className="flex items-center justify-end gap-2">
          <Button variant="secondary" onClick={onClose} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button
            variant="danger"
            onClick={handleDelete}
            disabled={!matches || mutation.isPending}
            loading={mutation.isPending}
          >
            Delete
          </Button>
        </footer>
      </div>
    </Modal>
  );
}
