import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

type InputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> & {
  label?: ReactNode;
  helperText?: ReactNode;
  /** When set, the input is announced as invalid and the message replaces helperText. */
  error?: string;
};

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { id, label, helperText, error, className, ...rest },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? autoId;
  const helperId = `${inputId}-helper`;
  const helper = error ?? helperText;

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={inputId} className="text-xs font-medium text-text-secondary">
          {label}
        </label>
      )}
      <input
        ref={ref}
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={helper ? helperId : undefined}
        className={cn(
          'h-9 rounded-md border bg-bg-elevated px-3 text-sm text-text-primary',
          'placeholder:text-text-muted',
          'outline-offset-2 focus-visible:outline-2 focus-visible:outline-border-focus',
          'transition-colors duration-[80ms]',
          error ? 'border-danger' : 'border-border-default',
          'disabled:cursor-not-allowed disabled:opacity-50',
          className,
        )}
        {...rest}
      />
      {helper && (
        <p id={helperId} className={cn('text-xs', error ? 'text-danger' : 'text-text-muted')}>
          {helper}
        </p>
      )}
    </div>
  );
});
