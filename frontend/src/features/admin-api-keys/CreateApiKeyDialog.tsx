import { useCallback, useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { Modal } from '@/shared/ui/Modal';
import { useCreateApiKey } from './api';
import { RevealOnceBanner } from './RevealOnceBanner';
import {
  createApiKeySchema,
  type ApiKey,
  type ApiKeyCreated,
  type CreateApiKeyValues,
} from './schema';

export type CreateApiKeyDialogProps = {
  open: boolean;
  onClose: () => void;
  onCreated?: (apiKey: ApiKey) => void;
};

const LABEL_MAX = 128;

/**
 * Two-phase modal. Phase 1: label input + Create button. Phase 2: reveal
 * banner + Client ID caption. The Modal is switched to
 * `disableEscapeClose` + `disableBackdropClose` + `hideCloseButton` in
 * Phase 2 so the only exit is the Done button — the admin cannot fumble
 * the cleartext away by mis-clicking. Reveal-once contract from
 * SW-DESIGN §5.3.5.
 */
export function CreateApiKeyDialog({
  open,
  onClose,
  onCreated,
}: CreateApiKeyDialogProps): JSX.Element {
  const form = useForm<CreateApiKeyValues>({
    resolver: zodResolver(createApiKeySchema),
    defaultValues: { label: '' },
    mode: 'onChange',
  });

  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [topAlert, setTopAlert] = useState<string | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);
  const mutation = useCreateApiKey();

  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      setCountdown(null);
      return;
    }
    const handle = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(handle);
  }, [countdown]);

  useEffect(() => {
    if (!open) {
      form.reset({ label: '' });
      mutation.reset();
      setCreated(null);
      setTopAlert(null);
      setCountdown(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const lastErrorRef = useRef<unknown>(null);
  const currentError = mutation.error ?? null;
  useEffect(() => {
    if (currentError === lastErrorRef.current) return;
    lastErrorRef.current = currentError;
    if (currentError === null) {
      setTopAlert(null);
      return;
    }
    routeError(currentError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentError]);

  function routeError(err: NonNullable<typeof currentError>): void {
    switch (err.code) {
      case 'VALIDATION_ERROR': {
        const labelMessage = err.fieldErrors['label'];
        if (labelMessage) {
          form.setError('label', { message: labelMessage });
          setTopAlert(null);
        } else {
          setTopAlert(errorCopy.VALIDATION_ERROR.title);
        }
        return;
      }
      case 'RATE_LIMITED':
        setCountdown(err.retryAfterSeconds ?? null);
        setTopAlert(null);
        return;
      default:
        setTopAlert(errorCopy[err.code]?.title ?? errorCopy.__unknown__.title);
    }
  }

  const isPending = mutation.isPending;
  const isRateLimited = countdown !== null && countdown > 0;
  const isPhase2 = created !== null;

  function onSubmit(values: CreateApiKeyValues): void {
    setTopAlert(null);
    const body =
      values.label && values.label.length > 0 ? { label: values.label } : {};
    mutation.mutate(body, {
      onSuccess: (data) => {
        setCreated(data);
      },
    });
  }

  function handleDone(): void {
    if (!created) return;
    const stripped: ApiKey = {
      clientId: created.clientId,
      label: created.label,
      disabled: created.disabled,
      createdAt: created.createdAt,
    };
    setCreated(null);
    onCreated?.(stripped);
    onClose();
  }

  const handleOpenChange = useCallback(
    (next: boolean) => {
      if (!next && !isPhase2) onClose();
    },
    [isPhase2, onClose],
  );

  return (
    <Modal
      open={open}
      onOpenChange={handleOpenChange}
      title={isPhase2 ? 'API key created' : 'Create API key'}
      disableEscapeClose={isPhase2}
      disableBackdropClose={isPhase2}
      hideCloseButton={isPhase2}
    >
      {isPhase2 && created ? (
        <div className="flex flex-col gap-4">
          <RevealOnceBanner value={created.apiKey} onDone={handleDone} />
          <dl className="flex flex-col gap-1 text-sm">
            <div className="flex items-baseline gap-2">
              <dt className="text-text-muted">Client ID:</dt>
              <dd>
                <code className="font-mono text-text-primary">{created.clientId}</code>
              </dd>
            </div>
            {created.label && (
              <div className="flex items-baseline gap-2">
                <dt className="text-text-muted">Label:</dt>
                <dd className="text-text-primary">{created.label}</dd>
              </div>
            )}
          </dl>
        </div>
      ) : (
        <form onSubmit={form.handleSubmit(onSubmit)} className="flex flex-col gap-4">
          {topAlert && (
            <div
              role="alert"
              className="rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger"
            >
              {topAlert}
            </div>
          )}
          {isRateLimited && (
            <div
              role="alert"
              className="rounded-md border border-warning/30 bg-warning-bg px-3 py-2 text-sm text-warning"
            >
              Too many requests. Try again in {countdown}s.
            </div>
          )}

          <Input
            label="Label"
            placeholder="Optional label"
            maxLength={LABEL_MAX}
            helperText="Optional — how to recognize this key later."
            {...form.register('label')}
            error={form.formState.errors.label?.message}
          />

          <footer className="flex items-center justify-end gap-2">
            <Button
              type="button"
              variant="secondary"
              onClick={onClose}
              disabled={isPending}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="primary"
              loading={isPending}
              disabled={isPending || isRateLimited}
            >
              Create
            </Button>
          </footer>
        </form>
      )}
    </Modal>
  );
}
