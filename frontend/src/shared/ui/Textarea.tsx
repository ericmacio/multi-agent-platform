import { forwardRef, useId, type TextareaHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

type TextareaProps = TextareaHTMLAttributes<HTMLTextAreaElement> & {
  label?: ReactNode;
  helperText?: ReactNode;
  error?: string;
  /** When set, a character counter is rendered below right. */
  showCounter?: boolean;
};

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { id, label, helperText, error, showCounter, maxLength, value, className, ...rest },
  ref,
) {
  const autoId = useId();
  const textareaId = id ?? autoId;
  const helperId = `${textareaId}-helper`;
  const helper = error ?? helperText;
  const currentLength = typeof value === 'string' ? value.length : 0;

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={textareaId} className="text-xs font-medium text-text-secondary">
          {label}
        </label>
      )}
      <textarea
        ref={ref}
        id={textareaId}
        value={value}
        maxLength={maxLength}
        aria-invalid={error ? true : undefined}
        aria-describedby={helper ? helperId : undefined}
        className={cn(
          'min-h-[120px] rounded-md border bg-bg-elevated px-3 py-2 text-sm text-text-primary',
          'placeholder:text-text-muted',
          'outline-offset-2 focus-visible:outline-2 focus-visible:outline-border-focus',
          'transition-colors duration-[80ms] resize-vertical',
          error ? 'border-danger' : 'border-border-default',
          'disabled:cursor-not-allowed disabled:opacity-50',
          className,
        )}
        {...rest}
      />
      <div className="flex items-start justify-between gap-2">
        {helper ? (
          <p id={helperId} className={cn('text-xs', error ? 'text-danger' : 'text-text-muted')}>
            {helper}
          </p>
        ) : (
          <span />
        )}
        {showCounter && maxLength !== undefined && (
          <p className="text-xs text-text-muted">
            {currentLength} / {maxLength}
          </p>
        )}
      </div>
    </div>
  );
});
