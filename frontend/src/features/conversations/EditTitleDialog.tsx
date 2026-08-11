import { useCallback, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { Modal } from '@/shared/ui/Modal';
import { useUpdateConversationTitle } from './api';
import {
  updateConversationSchema,
  type Conversation,
  type UpdateConversationValues,
} from './schema';

export type EditTitleDialogProps = {
  conversation: Conversation | null;
  open: boolean;
  onClose: () => void;
};

const TITLE_MAX = 32;

/**
 * Inline-edit dialog for `Conversation.title`. The optimistic patch is wired
 * in `useUpdateConversationTitle` (US-06-001), so the topbar and the left-pane
 * list update synchronously. On error this dialog renders an inline alert and
 * the cache is rolled back by the hook.
 */
export function EditTitleDialog({
  conversation,
  open,
  onClose,
}: EditTitleDialogProps): JSX.Element | null {
  const form = useForm<UpdateConversationValues>({
    resolver: zodResolver(updateConversationSchema),
    defaultValues: { title: conversation?.title ?? '' },
  });
  const mutation = useUpdateConversationTitle(conversation?.id ?? '');

  // Reset both the form and the mutation state whenever the dialog reopens or
  // the target conversation changes.
  useEffect(() => {
    if (open) {
      form.reset({ title: conversation?.title ?? '' });
      mutation.reset();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, conversation?.id]);

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next) onClose();
    },
    [onClose],
  );

  if (!conversation) return null;

  const titleValue = form.watch('title') ?? '';
  const titleError = form.formState.errors.title?.message;
  // Server-named field validation errors take precedence on the field.
  const serverFieldError = mutation.error?.fieldErrors?.['title'];

  function onSubmit(values: UpdateConversationValues): void {
    mutation.mutate(
      { title: values.title },
      {
        onSuccess: () => {
          onClose();
        },
      },
    );
  }

  return (
    <Modal
      open={open}
      onOpenChange={handleOpenChange}
      title="Rename conversation"
      hideCloseButton={false}
    >
      <form className="flex flex-col gap-4" onSubmit={form.handleSubmit(onSubmit)}>
        <div className="flex flex-col gap-1">
          <Input
            label="Title"
            maxLength={TITLE_MAX + 1}
            error={serverFieldError ?? titleError}
            {...form.register('title')}
          />
          <p className="self-end text-xs text-text-muted">
            {titleValue.length} / {TITLE_MAX}
          </p>
        </div>

        {mutation.isError && !serverFieldError && (
          <div
            role="alert"
            className="rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger"
          >
            {errorCopy[mutation.error.code]?.title ?? errorCopy.__unknown__.title}
          </div>
        )}

        <footer className="flex items-center justify-end gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={onClose}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={!form.formState.isValid || mutation.isPending}
            loading={mutation.isPending}
          >
            Save
          </Button>
        </footer>
      </form>
    </Modal>
  );
}
