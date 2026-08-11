import {
  cloneElement,
  useEffect,
  useId,
  useRef,
  useState,
  type FocusEvent,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
} from 'react';
import { cn } from '@/shared/lib/cn';

const OPEN_DELAY_MS = 200;

type TooltipProps = {
  content: ReactNode;
  children: ReactElement;
  /** `top` (default) places the bubble above; `bottom` places it below. */
  placement?: 'top' | 'bottom';
  className?: string;
};

/**
 * Hover/focus-driven tooltip. Wraps a single child and clones it to add
 * `aria-describedby` + the listeners. The bubble is positioned via CSS
 * absolute (no Floating UI dependency); v1 hierarchies are flat enough that
 * the simple positioner is sufficient.
 */
export function Tooltip({
  content,
  children,
  placement = 'top',
  className,
}: TooltipProps): JSX.Element {
  const id = useId();
  const [open, setOpen] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    },
    [],
  );

  const openSoon = () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setOpen(true), OPEN_DELAY_MS);
  };
  const closeNow = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    setOpen(false);
  };

  const cloned = cloneElement(children, {
    'aria-describedby': open ? id : undefined,
    onMouseEnter: (event: MouseEvent) => {
      children.props.onMouseEnter?.(event);
      openSoon();
    },
    onMouseLeave: (event: MouseEvent) => {
      children.props.onMouseLeave?.(event);
      closeNow();
    },
    onFocus: (event: FocusEvent) => {
      children.props.onFocus?.(event);
      openSoon();
    },
    onBlur: (event: FocusEvent) => {
      children.props.onBlur?.(event);
      closeNow();
    },
  });

  return (
    <span className="relative inline-flex">
      {cloned}
      {open && (
        <span
          role="tooltip"
          id={id}
          className={cn(
            'pointer-events-none absolute left-1/2 z-[1050] -translate-x-1/2 whitespace-nowrap rounded-md border border-border-default bg-bg-elevated px-2 py-1 text-xs text-text-primary shadow-lg',
            placement === 'top' ? 'bottom-full mb-1.5' : 'top-full mt-1.5',
            className,
          )}
        >
          {content}
        </span>
      )}
    </span>
  );
}
