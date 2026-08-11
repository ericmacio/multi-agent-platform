import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/shared/lib/cn';

export type CardPadding = 'none' | 'sm' | 'md' | 'lg';

type CardProps = HTMLAttributes<HTMLDivElement> & {
  padding?: CardPadding;
  /** Switches the border to the violet accent (`--color-border-accent`). */
  accent?: boolean;
  children?: ReactNode;
};

const PADDING_CLASSES: Record<CardPadding, string> = {
  none: '',
  sm: 'p-3',
  md: 'p-4',
  lg: 'p-6',
};

export function Card({ padding = 'md', accent = false, className, children, ...rest }: CardProps) {
  return (
    <div
      className={cn(
        'rounded-lg border bg-bg-surface shadow-md',
        accent ? 'border-border-accent bg-bg-subtle' : 'border-border-default',
        PADDING_CLASSES[padding],
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
}
