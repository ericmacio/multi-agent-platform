import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type ReactNode,
} from 'react';
import { cn } from '@/shared/lib/cn';

type DropdownContextValue = {
  open: boolean;
  setOpen: (value: boolean) => void;
  triggerId: string;
  contentId: string;
  triggerRef: React.MutableRefObject<HTMLButtonElement | null>;
  contentRef: React.MutableRefObject<HTMLDivElement | null>;
};

const DropdownContext = createContext<DropdownContextValue | null>(null);

function useDropdown(): DropdownContextValue {
  const ctx = useContext(DropdownContext);
  if (!ctx)
    throw new Error(
      '<DropdownTrigger>/<DropdownContent>/<DropdownItem> must be inside <Dropdown>.',
    );
  return ctx;
}

type DropdownProps = {
  children: ReactNode;
  /** Controlled mode opt-in. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
};

export function Dropdown({ children, open: controlledOpen, onOpenChange }: DropdownProps) {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const open = controlledOpen ?? uncontrolledOpen;
  const setOpen = useCallback(
    (value: boolean) => {
      if (controlledOpen === undefined) setUncontrolledOpen(value);
      onOpenChange?.(value);
    },
    [controlledOpen, onOpenChange],
  );

  const triggerId = useId();
  const contentId = useId();
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const contentRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (event: MouseEvent) => {
      const t = event.target as Node;
      if (triggerRef.current?.contains(t) || contentRef.current?.contains(t)) {
        return;
      }
      setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open, setOpen]);

  return (
    <DropdownContext.Provider
      value={{ open, setOpen, triggerId, contentId, triggerRef, contentRef }}
    >
      <div className="relative inline-block">{children}</div>
    </DropdownContext.Provider>
  );
}

type DropdownTriggerProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-expanded'>;

export function DropdownTrigger({
  children,
  className,
  onClick,
  onKeyDown,
  ...rest
}: DropdownTriggerProps) {
  const { open, setOpen, triggerId, contentId, triggerRef } = useDropdown();
  return (
    <button
      ref={triggerRef}
      id={triggerId}
      type="button"
      aria-haspopup="menu"
      aria-expanded={open}
      aria-controls={open ? contentId : undefined}
      className={cn('inline-flex items-center', className)}
      onClick={(event) => {
        onClick?.(event);
        if (!event.defaultPrevented) setOpen(!open);
      }}
      onKeyDown={(event) => {
        onKeyDown?.(event);
        if (event.defaultPrevented) return;
        if (event.key === 'ArrowDown' || event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          setOpen(true);
        }
      }}
      {...rest}
    >
      {children}
    </button>
  );
}

type DropdownContentProps = {
  children: ReactNode;
  className?: string;
  /** Alignment relative to the trigger. */
  align?: 'start' | 'end';
};

export function DropdownContent({ children, className, align = 'start' }: DropdownContentProps) {
  const { open, setOpen, triggerId, contentId, contentRef, triggerRef } = useDropdown();

  useEffect(() => {
    if (!open) return;
    const first = contentRef.current?.querySelector<HTMLElement>(
      '[role="menuitem"]:not([aria-disabled="true"])',
    );
    first?.focus();
  }, [open, contentRef]);

  if (!open) return null;

  const itemsOf = () =>
    Array.from(
      contentRef.current?.querySelectorAll<HTMLElement>(
        '[role="menuitem"]:not([aria-disabled="true"])',
      ) ?? [],
    );

  return (
    <div
      ref={contentRef}
      id={contentId}
      role="menu"
      aria-labelledby={triggerId}
      tabIndex={-1}
      className={cn(
        'absolute z-50 mt-1 min-w-[180px] rounded-md border border-border-default bg-bg-elevated p-1 shadow-lg',
        align === 'end' ? 'right-0' : 'left-0',
        className,
      )}
      onKeyDown={(event) => {
        const items = itemsOf();
        if (items.length === 0) return;
        const currentIndex = items.findIndex((el) => el === document.activeElement);
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          const next = items[(currentIndex + 1) % items.length]!;
          next.focus();
        } else if (event.key === 'ArrowUp') {
          event.preventDefault();
          const prev = items[(currentIndex - 1 + items.length) % items.length]!;
          prev.focus();
        } else if (event.key === 'Home') {
          event.preventDefault();
          items[0]!.focus();
        } else if (event.key === 'End') {
          event.preventDefault();
          items[items.length - 1]!.focus();
        } else if (event.key === 'Escape') {
          event.preventDefault();
          setOpen(false);
          triggerRef.current?.focus();
        }
      }}
    >
      {children}
    </div>
  );
}

type DropdownItemProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'role'> & {
  /** When set, the item is rendered but non-interactive (kept for visual separators). */
  disabled?: boolean;
};

export function DropdownItem({
  children,
  className,
  disabled,
  onClick,
  ...rest
}: DropdownItemProps) {
  const { setOpen, triggerRef } = useDropdown();
  return (
    <button
      role="menuitem"
      tabIndex={-1}
      aria-disabled={disabled || undefined}
      disabled={disabled}
      className={cn(
        'flex w-full items-center gap-2 rounded-sm px-3 py-1.5 text-left text-sm text-text-primary',
        'focus:bg-bg-surface focus:outline-none',
        'disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      onClick={(event) => {
        if (disabled) return;
        onClick?.(event);
        if (!event.defaultPrevented) {
          setOpen(false);
          triggerRef.current?.focus();
        }
      }}
      {...rest}
    >
      {children}
    </button>
  );
}
