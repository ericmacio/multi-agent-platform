import { LoaderCircle } from './icons';
import { cn } from '@/shared/lib/cn';

export type SpinnerSize = 16 | 20 | 24;

type SpinnerProps = {
  size?: SpinnerSize;
  className?: string;
  /** Accessible label; defaults to `'Loading'`. Set to `''` to hide from AT. */
  label?: string;
};

export function Spinner({ size = 20, className, label = 'Loading' }: SpinnerProps): JSX.Element {
  return (
    <LoaderCircle
      role="status"
      aria-label={label || undefined}
      aria-hidden={label === '' ? true : undefined}
      width={size}
      height={size}
      className={cn('animate-spin text-accent', className)}
    />
  );
}
