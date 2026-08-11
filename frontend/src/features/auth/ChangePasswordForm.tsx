import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useChangeOwnPassword } from './api';
import { changePasswordSchema, type ChangePasswordValues } from './changePasswordSchema';
import { isPasswordPolicySatisfied } from './password';
import { PasswordPolicyChecklist } from './PasswordPolicyChecklist';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { toast } from '@/shared/ui/Toast';

type ChangePasswordFormProps = {
  /** Called after the password change succeeds (after the success toast fires). */
  onSuccess?: () => void;
};

export function ChangePasswordForm({ onSuccess }: ChangePasswordFormProps): JSX.Element {
  const change = useChangeOwnPassword();
  const [countdown, setCountdown] = useState<number | null>(null);

  const form = useForm<ChangePasswordValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmNewPassword: '' },
    mode: 'onChange', // re-validate as the user types so the submit gate is responsive
  });

  const currentPassword = form.watch('currentPassword');
  const newPassword = form.watch('newPassword');
  const confirmNewPassword = form.watch('confirmNewPassword');

  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      setCountdown(null);
      return;
    }
    const handle = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(handle);
  }, [countdown]);

  const onSubmit = (values: ChangePasswordValues): void => {
    change.mutate(
      {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      },
      {
        onSuccess: () => {
          toast.success('Password changed.', 'pwd-changed');
          onSuccess?.();
        },
        onError: (err) => {
          if (err.code === 'RATE_LIMITED') {
            setCountdown(err.retryAfterSeconds ?? null);
          } else if (err.code === 'VALIDATION_ERROR') {
            for (const [field, message] of Object.entries(err.fieldErrors)) {
              if (
                field === 'currentPassword' ||
                field === 'newPassword' ||
                field === 'confirmNewPassword'
              ) {
                form.setError(field, { message });
              }
            }
          }
        },
      },
    );
  };

  const isRateLimited = countdown !== null && countdown > 0;
  const policyOk = isPasswordPolicySatisfied(newPassword);
  const confirmMatches = confirmNewPassword.length > 0 && newPassword === confirmNewPassword;
  const submitDisabled =
    change.isPending ||
    isRateLimited ||
    currentPassword.length === 0 ||
    !policyOk ||
    !confirmMatches;

  const showGenericError =
    change.error !== null &&
    change.error.code !== 'VALIDATION_ERROR' &&
    change.error.code !== 'RATE_LIMITED';

  return (
    <form
      onSubmit={form.handleSubmit(onSubmit)}
      noValidate
      className="flex flex-col gap-4"
      aria-busy={change.isPending || undefined}
    >
      {isRateLimited && (
        <div
          role="alert"
          className="rounded-md border border-warning/30 bg-warning-bg px-3 py-2 text-sm text-warning"
        >
          Too many requests. Try again in {countdown}s.
        </div>
      )}
      {showGenericError && (
        <div
          role="alert"
          className="rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger"
        >
          {errorCopy[change.error!.code]?.title ?? errorCopy.__unknown__.title}
        </div>
      )}

      <Input
        label="Current password"
        type="password"
        autoComplete="current-password"
        {...form.register('currentPassword')}
        error={form.formState.errors.currentPassword?.message}
      />
      <Input
        label="New password"
        type="password"
        autoComplete="new-password"
        {...form.register('newPassword')}
        error={form.formState.errors.newPassword?.message}
      />
      <PasswordPolicyChecklist value={newPassword} />
      <Input
        label="Confirm new password"
        type="password"
        autoComplete="new-password"
        {...form.register('confirmNewPassword')}
        error={form.formState.errors.confirmNewPassword?.message}
      />
      <Button type="submit" loading={change.isPending} disabled={submitDisabled}>
        Change password
      </Button>
    </form>
  );
}
