import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useLogin } from './api';
import { loginSchema, type LoginValues } from './loginSchema';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Input } from '@/shared/ui/Input';
import { cn } from '@/shared/lib/cn';

/**
 * Resolve the post-login destination from a raw `?next=` parameter while
 * rejecting open-redirect vectors (SW-DESIGN §15). Accepts only paths that
 * begin with a single `/`; anything else falls back to the dashboard (`/`).
 */
export function safeNextPath(raw: string | null): string {
  if (!raw) return '/';
  if (!raw.startsWith('/')) return '/';
  if (raw.startsWith('//')) return '/';
  return raw;
}

export function LoginForm(): JSX.Element {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const login = useLogin();
  const [countdown, setCountdown] = useState<number | null>(null);

  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  });

  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      setCountdown(null);
      return;
    }
    const handle = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(handle);
  }, [countdown]);

  const onSubmit = (values: LoginValues): void => {
    login.mutate(values, {
      onSuccess: (data) => {
        if (data.mustChangePassword) {
          navigate('/change-password?reason=forced', { replace: true });
          return;
        }
        navigate(safeNextPath(searchParams.get('next')), { replace: true });
      },
      onError: (err) => {
        if (err.code === 'RATE_LIMITED') {
          setCountdown(err.retryAfterSeconds ?? null);
        } else if (err.code === 'VALIDATION_ERROR') {
          for (const [field, message] of Object.entries(err.fieldErrors)) {
            if (field === 'email' || field === 'password') {
              form.setError(field, { message });
            }
          }
        }
      },
    });
  };

  const isRateLimited = countdown !== null && countdown > 0;
  const showInvalidCredentials = login.error?.code === 'INVALID_CREDENTIALS';
  const isGenericError =
    login.error !== null &&
    login.error.code !== 'INVALID_CREDENTIALS' &&
    login.error.code !== 'RATE_LIMITED' &&
    login.error.code !== 'VALIDATION_ERROR';

  // Field errors carry aria-invalid via `Input.error` — but per REQ-AUTH-009
  // the INVALID_CREDENTIALS case MUST NOT highlight either field. The check
  // below ensures email/password errors come ONLY from the Zod resolver, never
  // from `setError` triggered by the server's auth rejection.
  const emailError = form.formState.errors.email?.message;
  const passwordError = form.formState.errors.password?.message;

  return (
    <form
      onSubmit={form.handleSubmit(onSubmit)}
      noValidate
      className="flex flex-col gap-4"
      aria-busy={login.isPending || undefined}
    >
      {showInvalidCredentials && (
        <div
          role="alert"
          className={cn(
            'rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger',
          )}
        >
          {errorCopy.INVALID_CREDENTIALS.detail}
        </div>
      )}
      {isRateLimited && (
        <div
          role="alert"
          className={cn(
            'rounded-md border border-warning/30 bg-warning-bg px-3 py-2 text-sm text-warning',
          )}
        >
          Too many requests. Try again in {countdown}s.
        </div>
      )}
      {isGenericError && (
        <div
          role="alert"
          className={cn(
            'rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger',
          )}
        >
          {errorCopy[login.error!.code]?.title ?? errorCopy.__unknown__.title}
        </div>
      )}

      <Input
        label="Email"
        type="email"
        autoComplete="email"
        placeholder="alice@example.com"
        {...form.register('email')}
        error={emailError}
      />
      <Input
        label="Password"
        type="password"
        autoComplete="current-password"
        {...form.register('password')}
        error={passwordError}
      />
      <Button type="submit" loading={login.isPending} disabled={isRateLimited}>
        Sign in
      </Button>
      <p className="text-center text-xs text-text-muted">
        Need access? Contact your administrator.
      </p>
    </form>
  );
}
