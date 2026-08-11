import { useEffect, useRef, useState } from 'react';
import { useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { PasswordPolicyChecklist } from '@/features/auth/PasswordPolicyChecklist';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Input } from '@/shared/ui/Input';
import { Select } from '@/shared/ui/Select';
import { cn } from '@/shared/lib/cn';
import { useCreateUser } from './api';
import {
  createUserSchema,
  type CreateUserRequest,
  type CreateUserValues,
  type User,
} from './schema';

export type UserFormProps = {
  onSuccess: (user: User) => void;
  onCancel: () => void;
};

function scrollFieldIntoView(name: string): void {
  if (typeof document === 'undefined') return;
  const el = document.querySelector(
    `[data-rhf-field="${CSS.escape(name)}"], [data-rhf-section="${CSS.escape(name)}"]`,
  );
  if (el && typeof (el as HTMLElement).scrollIntoView === 'function') {
    (el as HTMLElement).scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}

const KNOWN_FIELDS: Record<string, FieldPath<CreateUserValues>> = {
  email: 'email',
  password: 'password',
  role: 'role',
};

export function UserForm({ onSuccess, onCancel }: UserFormProps): JSX.Element {
  const form = useForm<CreateUserValues>({
    resolver: zodResolver(createUserSchema),
    defaultValues: { email: '', password: '', role: 'STANDARD' },
    mode: 'onChange',
  });

  const [topAlert, setTopAlert] = useState<string | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);

  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      setCountdown(null);
      return;
    }
    const handle = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(handle);
  }, [countdown]);

  const create = useCreateUser();
  const isPending = create.isPending;
  const isRateLimited = countdown !== null && countdown > 0;

  const lastErrorRef = useRef<unknown>(null);
  const currentError = create.error ?? null;
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
        let firstField: string | null = null;
        for (const [field, message] of Object.entries(err.fieldErrors)) {
          const known = KNOWN_FIELDS[field];
          if (known) {
            form.setError(known, { message });
            mapped = true;
            firstField ??= known;
          }
        }
        if (!mapped) {
          setTopAlert("Some fields couldn't be saved — please contact support.");
        } else {
          setTopAlert(null);
          if (firstField) scrollFieldIntoView(firstField);
        }
        return;
      }
      case 'CONFLICT':
        form.setError('email', {
          message: errorCopy.CONFLICT.detail || 'A user with this email already exists.',
        });
        setTopAlert(null);
        scrollFieldIntoView('email');
        return;
      case 'RATE_LIMITED':
        setCountdown(err.retryAfterSeconds ?? null);
        setTopAlert(null);
        return;
      default:
        setTopAlert(errorCopy[err.code]?.title ?? errorCopy.__unknown__.title);
    }
  }

  const onSubmit = (values: CreateUserValues): void => {
    const body: CreateUserRequest = {
      email: values.email,
      password: values.password,
      role: values.role,
    };
    setTopAlert(null);
    create.mutate(body, { onSuccess: (user) => onSuccess(user) });
  };

  const onInvalid = (errors: Record<string, unknown>): void => {
    const firstKey = Object.keys(errors)[0];
    if (firstKey) scrollFieldIntoView(firstKey);
  };

  const password = form.watch('password');
  const isValid = form.formState.isValid;

  return (
    <form
      onSubmit={form.handleSubmit(onSubmit, onInvalid)}
      noValidate
      className="flex flex-col gap-6 pb-24"
      aria-busy={isPending || undefined}
    >
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

      <Section name="identity" title="Identity">
        <div data-rhf-field="email">
          <Input
            label="Email"
            type="email"
            autoComplete="off"
            placeholder="alice@example.com"
            maxLength={254}
            {...form.register('email')}
            error={form.formState.errors.email?.message}
          />
        </div>
      </Section>

      <Section name="credential" title="Credential">
        <div data-rhf-field="password">
          <Input
            label="Password"
            type="password"
            autoComplete="new-password"
            {...form.register('password')}
            error={form.formState.errors.password?.message}
          />
        </div>
        <PasswordPolicyChecklist value={password} />
      </Section>

      <Section name="role" title="Role">
        <div data-rhf-field="role">
          <Select label="Role" {...form.register('role')}>
            <option value="STANDARD">Standard</option>
            <option value="ADMIN">Admin</option>
          </Select>
          <p className="mt-1 text-xs text-text-muted">
            Admins can manage users, API keys, and the rate-limit configuration.
          </p>
        </div>
      </Section>

      {/* Sticky action bar */}
      <div
        className={cn(
          'sticky bottom-0 z-10 -mx-6 flex items-center justify-end gap-3 border-t border-border-default bg-bg-base/95 px-6 py-3 backdrop-blur',
        )}
      >
        <Button type="button" variant="secondary" onClick={onCancel} disabled={isPending}>
          Cancel
        </Button>
        <Button
          type="submit"
          loading={isPending}
          disabled={isPending || isRateLimited || !isValid}
        >
          Create user
        </Button>
      </div>
    </form>
  );
}

function Section({
  name,
  title,
  children,
}: {
  name: string;
  title: string;
  children: React.ReactNode;
}): JSX.Element {
  return (
    <section data-rhf-section={name} className="flex flex-col gap-3">
      <Card padding="md" className="flex flex-col gap-4">
        <header>
          <h2 className="text-base font-medium text-text-primary">{title}</h2>
        </header>
        {children}
      </Card>
    </section>
  );
}
