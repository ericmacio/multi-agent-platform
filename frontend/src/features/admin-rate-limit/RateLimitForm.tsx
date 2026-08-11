import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { errorCopy } from '@/shared/i18n/en';
import { formatRelative } from '@/shared/lib/date';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { useUpdateRateLimitConfig } from './api';
import {
  rateLimitConfigSchema,
  type RateLimitConfig,
  type RateLimitConfigRequest,
  type RateLimitConfigValues,
} from './schema';

export type RateLimitFormProps = {
  defaults: RateLimitConfig;
  onSaved?: (config: RateLimitConfig) => void;
};

const KNOWN_FIELDS = new Set<keyof RateLimitConfigValues>(['perMinute', 'perHour']);

/**
 * Controlled `react-hook-form` component seeded with the current config. The
 * `defaults` prop comes from the parent's `useRateLimitConfig()` cache; on
 * post-save invalidation a fresh `defaults` re-syncs the inputs via the
 * `form.reset` effect below. Save is non-optimistic (see US-10-001 rationale)
 * and errors are routed per SW-DESIGN §10.2.
 */
export function RateLimitForm({ defaults, onSaved }: RateLimitFormProps): JSX.Element {
  const form = useForm<RateLimitConfigValues>({
    resolver: zodResolver(rateLimitConfigSchema),
    defaultValues: { perMinute: defaults.perMinute, perHour: defaults.perHour },
    mode: 'onChange',
  });

  useEffect(() => {
    form.reset({ perMinute: defaults.perMinute, perHour: defaults.perHour });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaults.perMinute, defaults.perHour, defaults.updatedAt]);

  const [topAlert, setTopAlert] = useState<string | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);
  const mutation = useUpdateRateLimitConfig();

  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      setCountdown(null);
      return;
    }
    const handle = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(handle);
  }, [countdown]);

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
        let mapped = false;
        for (const [field, message] of Object.entries(err.fieldErrors)) {
          if (KNOWN_FIELDS.has(field as keyof RateLimitConfigValues)) {
            form.setError(field as keyof RateLimitConfigValues, { message });
            mapped = true;
          }
        }
        if (!mapped) setTopAlert(errorCopy.VALIDATION_ERROR.title);
        else setTopAlert(null);
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
  const isValid = form.formState.isValid;
  const isDirty = form.formState.isDirty;

  function onSubmit(values: RateLimitConfigValues): void {
    setTopAlert(null);
    const body: RateLimitConfigRequest = {
      perMinute: values.perMinute,
      perHour: values.perHour,
    };
    mutation.mutate(body, {
      onSuccess: (newConfig) => {
        mutation.reset();
        onSaved?.(newConfig);
        form.reset({ perMinute: newConfig.perMinute, perHour: newConfig.perHour });
      },
    });
  }

  function onReset(): void {
    form.reset({ perMinute: defaults.perMinute, perHour: defaults.perHour });
  }

  return (
    <form onSubmit={form.handleSubmit(onSubmit)} noValidate className="flex flex-col gap-4">
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
        label="Requests per minute"
        type="number"
        min={1}
        step={1}
        helperText="Global bucket — minimum 1."
        {...form.register('perMinute', { valueAsNumber: true })}
        error={form.formState.errors.perMinute?.message}
      />

      <Input
        label="Requests per hour"
        type="number"
        min={1}
        step={1}
        helperText="Global bucket — minimum 1."
        {...form.register('perHour', { valueAsNumber: true })}
        error={form.formState.errors.perHour?.message}
      />

      <p className="text-sm text-text-muted">
        Last updated {formatRelative(defaults.updatedAt)}
        {defaults.updatedBy != null && (
          <>
            {' '}
            by <code className="font-mono text-xs">{defaults.updatedBy}</code>
          </>
        )}
      </p>

      <footer className="flex items-center justify-end gap-2">
        {isDirty && (
          <Button type="button" variant="secondary" onClick={onReset} disabled={isPending}>
            Reset
          </Button>
        )}
        <Button
          type="submit"
          variant="primary"
          loading={isPending}
          disabled={!isValid || isPending || isRateLimited || !isDirty}
        >
          Save
        </Button>
      </footer>
    </form>
  );
}
