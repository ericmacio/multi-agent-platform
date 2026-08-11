import { useCallback, useEffect } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Modal } from '@/shared/ui/Modal';
import { useUpdateUser } from './api';
import type { User } from './schema';

export type DisableUserDialogProps = {
  user: User | null;
  open: boolean;
  onClose: () => void;
  onDone: (user: User) => void;
};

/**
 * Confirms enabling or disabling a user. The direction is read from
 * `user.disabled`: an active user is Disabled here, a disabled one is
 * Enabled. Because `useUpdateUser` is optimistic (US-08-001), the row's
 * status badge behind the modal flips immediately; a server failure rolls
 * the cache back and renders an inline error while the dialog stays open.
 */
export function DisableUserDialog({
  user,
  open,
  onClose,
  onDone,
}: DisableUserDialogProps): JSX.Element | null {
  // Snapshot the intended target user's id at mount time so calling
  // `useUpdateUser` with a stable string is safe even after the parent
  // resets `user` to null on close.
  const targetId = user?.id ?? '';
  const mutation = useUpdateUser(targetId);

  useEffect(() => {
    if (!open) mutation.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) onClose();
    },
    [onClose],
  );

  if (!user) return null;

  const disabling = !user.disabled; // The action about to be taken.

  function handleConfirm(): void {
    if (!user) return;
    mutation.mutate(
      { disabled: disabling },
      {
        onSuccess: () => {
          onDone(user);
          onClose();
        },
      },
    );
  }

  const title = disabling ? `Disable ${user.email}?` : `Re-enable ${user.email}?`;
  const body = disabling
    ? `Disable ${user.email}? They will not be able to sign in. Their agents and conversations are preserved and remain restorable by re-enabling.`
    : `Re-enable ${user.email}? They will be able to sign in again.`;
  const confirmLabel = disabling ? 'Disable' : 'Enable';
  const confirmVariant: 'danger' | 'primary' = disabling ? 'danger' : 'primary';

  return (
    <Modal open={open} onOpenChange={handleOpenChange} title={title}>
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text-secondary">{body}</p>

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
            variant={confirmVariant}
            onClick={handleConfirm}
            disabled={mutation.isPending}
            loading={mutation.isPending}
          >
            {confirmLabel}
          </Button>
        </footer>
      </div>
    </Modal>
  );
}
