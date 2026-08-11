import {
  forwardRef,
  useEffect,
  useId,
  useRef,
  type InputHTMLAttributes,
  type ReactNode,
} from 'react';
import { Check } from './icons';
import { cn } from '@/shared/lib/cn';

type CheckboxProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> & {
  label?: ReactNode;
  indeterminate?: boolean;
};

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { id, label, indeterminate = false, className, checked, disabled, ...rest },
  forwardedRef,
) {
  const autoId = useId();
  const checkboxId = id ?? autoId;
  const localRef = useRef<HTMLInputElement | null>(null);

  // Keep both refs in sync so consumers and the indeterminate effect both work.
  const setRef = (el: HTMLInputElement | null) => {
    localRef.current = el;
    if (typeof forwardedRef === 'function') forwardedRef(el);
    else if (forwardedRef) forwardedRef.current = el;
  };

  useEffect(() => {
    if (localRef.current) {
      localRef.current.indeterminate = indeterminate;
    }
  }, [indeterminate]);

  return (
    <label
      htmlFor={checkboxId}
      className={cn(
        'inline-flex cursor-pointer items-center gap-2 text-sm text-text-primary',
        disabled && 'cursor-not-allowed opacity-50',
      )}
    >
      <span className="relative inline-flex h-4 w-4 items-center justify-center">
        <input
          ref={setRef}
          id={checkboxId}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          className={cn(
            'peer absolute inset-0 h-4 w-4 cursor-pointer appearance-none rounded-sm border border-border-default bg-bg-elevated outline-offset-2 checked:border-accent checked:bg-accent focus-visible:outline-2 focus-visible:outline-border-focus disabled:cursor-not-allowed',
            className,
          )}
          {...rest}
        />
        {checked && !indeterminate && (
          <Check
            aria-hidden
            width={12}
            height={12}
            className="pointer-events-none relative text-bg-base"
          />
        )}
        {indeterminate && (
          <span
            aria-hidden
            className="pointer-events-none relative h-0.5 w-2 rounded-sm bg-bg-base"
          />
        )}
      </span>
      {label}
    </label>
  );
});
