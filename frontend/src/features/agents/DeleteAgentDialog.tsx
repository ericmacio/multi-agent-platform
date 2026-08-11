import { useCallback, useEffect, useState } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { Modal } from '@/shared/ui/Modal';
import { useDeleteAgent } from './api';
import type { Agent } from './schema';

export type DeleteAgentDialogProps = {
  agent: Agent | null;
  open: boolean;
  onClose: () => void;
  onDeleted: (agent: Agent) => void;
};

export function DeleteAgentDialog({
  agent,
  open,
  onClose,
  onDeleted,
}: DeleteAgentDialogProps): JSX.Element | null {
  const [confirmText, setConfirmText] = useState('');
  const mutation = useDeleteAgent();

  // Reset on close/reopen so a previous typed value doesn't bleed across
  // separate deletes.
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

  if (!agent) return null;

  const matches = confirmText === agent.name;

  function handleDelete(): void {
    if (!agent || !matches) return;
    mutation.mutate(
      { agentId: agent.id },
      {
        onSuccess: () => {
          onDeleted(agent);
          onClose();
        },
      },
    );
  }

  return (
    <Modal
      open={open}
      onOpenChange={handleOpenChange}
      title={`Delete ${agent.name}?`}
      hideCloseButton={false}
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text-secondary">
          Deleting this agent will also delete every conversation with it. This cannot be undone.
        </p>

        <Input
          label={`Type "${agent.name}" to confirm.`}
          value={confirmText}
          onChange={(e) => setConfirmText(e.target.value)}
          placeholder={agent.name}
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
