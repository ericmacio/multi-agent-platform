import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X } from './icons';
import { cn } from '@/shared/lib/cn';

export type ModalSize = 'sm' | 'md' | 'lg';

type ModalProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title?: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  size?: ModalSize;
  /** Disable Esc-to-close (used for destructive confirms that demand an explicit Cancel). */
  disableEscapeClose?: boolean;
  /** Disable backdrop-click-to-close. */
  disableBackdropClose?: boolean;
  /** Hide the top-right close button (e.g., when the body provides its own primary actions). */
  hideCloseButton?: boolean;
};

const SIZE_CLASSES: Record<ModalSize, string> = {
  sm: 'max-w-sm',
  md: 'max-w-md',
  lg: 'max-w-2xl',
};

const FOCUSABLE_SELECTOR =
  'a[href],area[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),button:not([disabled]),iframe,object,embed,[tabindex]:not([tabindex="-1"]),[contenteditable]';

export function Modal({
  open,
  onOpenChange,
  title,
  description,
  children,
  size = 'md',
  disableEscapeClose = false,
  disableBackdropClose = false,
  hideCloseButton = false,
}: ModalProps): JSX.Element | null {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  const titleId = useId();
  const descId = useId();

  useEffect(() => {
    if (!open) return;
    previouslyFocusedRef.current = document.activeElement as HTMLElement | null;

    const node = dialogRef.current;
    if (node) {
      const first = node.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
      (first ?? node).focus();
    }

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !disableEscapeClose) {
        event.stopPropagation();
        onOpenChange(false);
        return;
      }
      if (event.key === 'Tab' && node) {
        const focusables = Array.from(node.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
        if (focusables.length === 0) {
          event.preventDefault();
          node.focus();
          return;
        }
        const first = focusables[0]!;
        const last = focusables[focusables.length - 1]!;
        const active = document.activeElement as HTMLElement;
        if (event.shiftKey && active === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && active === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previouslyFocusedRef.current?.focus?.();
    };
  }, [open, disableEscapeClose, onOpenChange]);

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[1000] flex items-center justify-center p-4"
      data-testid="modal-root"
    >
      <div
        aria-hidden
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={() => !disableBackdropClose && onOpenChange(false)}
      />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className={cn(
          'relative w-full overflow-hidden rounded-xl border border-border-default bg-bg-surface shadow-2xl',
          SIZE_CLASSES[size],
        )}
      >
        {(title || !hideCloseButton) && (
          <header className="flex items-start justify-between gap-4 border-b border-border-default px-5 py-3">
            <div className="flex flex-col gap-1">
              {title && (
                <h2 id={titleId} className="text-base font-medium text-text-primary">
                  {title}
                </h2>
              )}
              {description && (
                <p id={descId} className="text-sm text-text-secondary">
                  {description}
                </p>
              )}
            </div>
            {!hideCloseButton && (
              <button
                type="button"
                aria-label="Close"
                onClick={() => onOpenChange(false)}
                className="text-text-muted transition-colors hover:text-text-primary"
              >
                <X aria-hidden width={16} height={16} />
              </button>
            )}
          </header>
        )}
        <div className="px-5 py-4">{children}</div>
      </div>
    </div>,
    document.body,
  );
}
