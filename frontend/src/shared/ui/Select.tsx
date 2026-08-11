import { forwardRef, useId, type ReactNode, type SelectHTMLAttributes } from 'react';
import { ChevronDown } from './icons';
import { cn } from '@/shared/lib/cn';

type SelectProps = SelectHTMLAttributes<HTMLSelectElement> & {
  label?: ReactNode;
  helperText?: ReactNode;
  error?: string;
};

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { id, label, helperText, error, className, children, ...rest },
  ref,
) {
  const autoId = useId();
  const selectId = id ?? autoId;
  const helperId = `${selectId}-helper`;
  const helper = error ?? helperText;

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={selectId} className="text-xs font-medium text-text-secondary">
          {label}
        </label>
      )}
      <div className="relative">
        <select
          ref={ref}
          id={selectId}
          aria-invalid={error ? true : undefined}
          aria-describedby={helper ? helperId : undefined}
          className={cn(
            'h-9 w-full appearance-none rounded-md border bg-bg-elevated px-3 pr-9 text-sm text-text-primary',
            'outline-offset-2 focus-visible:outline-2 focus-visible:outline-border-focus',
            'transition-colors duration-[80ms]',
            error ? 'border-danger' : 'border-border-default',
            'disabled:cursor-not-allowed disabled:opacity-50',
            className,
          )}
          {...rest}
        >
          {children}
        </select>
        <ChevronDown
          aria-hidden
          width={16}
          height={16}
          className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-text-muted"
        />
      </div>
      {helper && (
        <p id={helperId} className={cn('text-xs', error ? 'text-danger' : 'text-text-muted')}>
          {helper}
        </p>
      )}
    </div>
  );
});
