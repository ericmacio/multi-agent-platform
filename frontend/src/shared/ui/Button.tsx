import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';
import { Spinner } from './Spinner';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md';

type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  type?: 'button' | 'submit' | 'reset';
};

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-bg-base hover:bg-accent/90 focus-visible:outline-border-focus disabled:bg-accent/40',
  secondary:
    'bg-bg-elevated text-text-primary border border-border-default hover:bg-bg-surface focus-visible:outline-border-focus',
  ghost: 'bg-transparent text-text-primary hover:bg-bg-elevated focus-visible:outline-border-focus',
  danger: 'bg-danger text-bg-base hover:bg-danger/90 focus-visible:outline-border-focus',
};

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs',
  md: 'h-9 px-4 text-sm',
};

/**
 * The single primary button primitive. `loading` disables the button and
 * inserts a spinner; `leftIcon` / `rightIcon` accept any node (typically an
 * icon from `@/shared/ui/icons`). The visual identity is fixed by
 * `tokens.css`; consumers extend via `className` (merged via `cn`).
 */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    disabled,
    leftIcon,
    rightIcon,
    type = 'button',
    className,
    children,
    ...rest
  },
  ref,
) {
  const isDisabled = disabled || loading;
  return (
    <button
      ref={ref}
      type={type}
      disabled={isDisabled}
      aria-disabled={isDisabled || undefined}
      aria-busy={loading || undefined}
      className={cn(
        'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md font-medium',
        'transition-colors duration-[80ms] outline-offset-2 focus-visible:outline-2',
        'disabled:cursor-not-allowed disabled:opacity-50',
        VARIANT_CLASSES[variant],
        SIZE_CLASSES[size],
        className,
      )}
      {...rest}
    >
      {loading ? <Spinner size={16} className="text-current" label="" /> : leftIcon}
      {children}
      {!loading && rightIcon}
    </button>
  );
});
