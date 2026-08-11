import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merge Tailwind class names. Composes `clsx` (conditional class assembly) with
 * `tailwind-merge` (last-wins conflict resolution between Tailwind utilities so
 * `cn('p-2', 'p-4')` resolves to `'p-4'`, not the literal concatenation).
 *
 * The single utility every design-system primitive uses to accept a caller-supplied
 * `className` without specificity collisions against its own classes.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
