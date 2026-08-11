import { useCallback, useEffect } from 'react';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Modal } from '@/shared/ui/Modal';
import { useDeleteConversation } from './api';
import type { Conversation } from './schema';

export type DeleteConversationDialogProps = {
  conversation: Conversation | null;
  open: boolean;
  onClose: () => void;
  onDeleted: (conversation: Conversation) => void;
};

/**
 * Falls back to `chat-<uuid-short>` (first 8 chars of the id) when the title
 * is null — matches the openapi default of unset-title conversations.
 */
function displayTitle(conversation: Conversation): string {
  return conversation.title ?? `chat-${conversation.id.slice(0, 8)}`;
}

/**
 * Single-confirm dialog (no type-to-confirm) — the cascade is bounded (every
 * message in the conversation, not every conversation under an agent), so a
 * single click is sufficient. The optimistic remove is wired in
 * `useDeleteConversation` (US-06-001); on error the cache is rolled back by
 * the hook and an inline alert renders.
 */
export function DeleteConversationDialog({
  conversation,
  open,
  onClose,
  onDeleted,
}: DeleteConversationDialogProps): JSX.Element | null {
  const mutation = useDeleteConversation();

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

  if (!conversation) return null;

  function handleDelete(): void {
    if (!conversation) return;
    mutation.mutate(
      { conversationId: conversation.id, agentId: conversation.agentId },
      {
        onSuccess: () => {
          onDeleted(conversation);
          onClose();
        },
      },
    );
  }

  return (
    <Modal
      open={open}
      onOpenChange={handleOpenChange}
      title="Delete this conversation?"
      hideCloseButton={false}
    >
      <div className="flex flex-col gap-4">
        <p className="text-sm text-text-secondary">
          This conversation will be permanently deleted, along with every message in it.
          This cannot be undone.
        </p>
        <p className="rounded-md border border-border-default bg-bg-elevated px-3 py-2 font-mono text-sm text-text-primary">
          {displayTitle(conversation)}
        </p>

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
            disabled={mutation.isPending}
            loading={mutation.isPending}
          >
            Delete
          </Button>
        </footer>
      </div>
    </Modal>
  );
}
