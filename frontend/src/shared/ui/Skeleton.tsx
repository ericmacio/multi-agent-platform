import type { CSSProperties, HTMLAttributes } from 'react';
import { cn } from '@/shared/lib/cn';

type SkeletonProps = HTMLAttributes<HTMLDivElement> & {
  width?: number | string;
  height?: number | string;
};

export function Skeleton({ width, height, className, style, ...rest }: SkeletonProps) {
  const inlineStyle: CSSProperties = {
    ...(width !== undefined && { width: typeof width === 'number' ? `${width}px` : width }),
    ...(height !== undefined && { height: typeof height === 'number' ? `${height}px` : height }),
    ...style,
  };
  return (
    <div
      aria-hidden
      className={cn('animate-pulse rounded-md bg-bg-elevated', className)}
      style={inlineStyle}
      {...rest}
    />
  );
}
