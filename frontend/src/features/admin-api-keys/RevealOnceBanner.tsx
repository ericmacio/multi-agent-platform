import { useEffect, useRef, useState } from 'react';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { AlertTriangle, Copy } from '@/shared/ui/icons';
import { cn } from '@/shared/lib/cn';

export type RevealOnceBannerProps = {
  /** The cleartext credential to reveal. */
  value: string;
  /**
   * Fired when the user clicks Done, after the Copy button has been clicked
   * at least once. The parent typically clears the cleartext from state at
   * this moment.
   */
  onDone: () => void;
  /**
   * Optional custom warning copy. Defaults to the SW-DESIGN §5.3.5 wording.
   */
  warning?: string;
  /** Optional label for the mono field. Defaults to "API key". */
  label?: string;
};

const DEFAULT_WARNING =
  'This is the only time this key will be shown. Copy and store it securely now.';
const COPIED_FLASH_MS = 2000;
const COPY_FAILURE_HINT = 'Copy failed — select the text and copy manually.';

/**
 * Reveal-once UX primitive (SW-DESIGN §5.3.5). The Copy-then-Done gate is a
 * UX guard, not a security control — the plaintext is already in the DOM
 * once rendered. It exists to prevent the most common user mistake:
 * dismissing the surface before capturing the key.
 */
export function RevealOnceBanner({
  value,
  onDone,
  warning,
  label = 'API key',
}: RevealOnceBannerProps): JSX.Element {
  const [copiedAt, setCopiedAt] = useState<number | null>(null);
  const [copyClicked, setCopyClicked] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

  async function handleCopy(): Promise<void> {
    setCopyClicked(true);
    try {
      await navigator.clipboard.writeText(value);
      setCopyFailed(false);
      setCopiedAt(Date.now());
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      timeoutRef.current = setTimeout(() => setCopiedAt(null), COPIED_FLASH_MS);
    } catch {
      setCopyFailed(true);
      setCopiedAt(null);
    }
  }

  const showCopied = copiedAt !== null;

  return (
    <Card padding="md" className="flex flex-col gap-4">
      <div
        role="alert"
        className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning-bg px-3 py-2 text-sm text-warning"
      >
        <AlertTriangle aria-hidden width={16} height={16} className="mt-0.5 shrink-0" />
        <p>{warning ?? DEFAULT_WARNING}</p>
      </div>

      <div className="flex items-center gap-2">
        <input
          type="text"
          readOnly
          value={value}
          aria-label={label}
          className={cn(
            'flex-1 h-9 rounded-md border border-border-default bg-bg-elevated px-3',
            'font-mono text-sm text-text-primary',
            'outline-offset-2 focus-visible:outline-2 focus-visible:outline-border-focus',
          )}
        />
        <Button
          type="button"
          variant="secondary"
          onClick={handleCopy}
          leftIcon={<Copy aria-hidden width={14} height={14} />}
        >
          {showCopied ? 'Copied' : 'Copy'}
        </Button>
      </div>

      {copyFailed && (
        <p className="text-xs text-danger" role="status">
          {COPY_FAILURE_HINT}
        </p>
      )}

      <span aria-live="polite" className="sr-only">
        {showCopied ? 'Copied to clipboard.' : ''}
      </span>

      <footer className="flex items-center justify-end">
        <Button type="button" onClick={onDone} disabled={!copyClicked}>
          Done
        </Button>
      </footer>
    </Card>
  );
}
