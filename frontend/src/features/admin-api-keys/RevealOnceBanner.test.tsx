import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RevealOnceBanner } from './RevealOnceBanner';

const SECRET = 'sk_live_supersecret_1234567890';

function withClipboard(impl: (text: string) => Promise<void>): () => void {
  const original = navigator.clipboard;
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn(impl) },
  });
  return () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: original,
    });
  };
}

let restoreClipboard: (() => void) | null = null;

beforeEach(() => {
  restoreClipboard = null;
});

afterEach(() => {
  restoreClipboard?.();
});

describe('RevealOnceBanner', () => {
  test('initial state: Done disabled, warning visible, mono field shows value', () => {
    render(<RevealOnceBanner value={SECRET} onDone={vi.fn()} />);

    expect(screen.getByRole('button', { name: /^done$/i })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent(/only time this key will be shown/i);
    expect(screen.getByLabelText(/api key/i)).toHaveValue(SECRET);
  });

  test('Copy click enables Done and writes to clipboard', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    restoreClipboard = withClipboard(writeText);

    render(<RevealOnceBanner value={SECRET} onDone={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /^copy$/i }));

    expect(writeText).toHaveBeenCalledWith(SECRET);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^done$/i })).toBeEnabled(),
    );
    expect(await screen.findByRole('button', { name: /^copied$/i })).toBeInTheDocument();
  });

  test('Copy failure still enables Done and shows fallback error', async () => {
    restoreClipboard = withClipboard(() => Promise.reject(new Error('denied')));

    render(<RevealOnceBanner value={SECRET} onDone={vi.fn()} />);

    await userEvent.click(screen.getByRole('button', { name: /^copy$/i }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^done$/i })).toBeEnabled(),
    );
    expect(screen.getByText(/select the text and copy manually/i)).toBeInTheDocument();
  });

  test('Done click fires onDone exactly once after Copy', async () => {
    restoreClipboard = withClipboard(() => Promise.resolve());
    const onDone = vi.fn();
    render(<RevealOnceBanner value={SECRET} onDone={onDone} />);

    await userEvent.click(screen.getByRole('button', { name: /^copy$/i }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /^done$/i })).toBeEnabled(),
    );
    await userEvent.click(screen.getByRole('button', { name: /^done$/i }));

    expect(onDone).toHaveBeenCalledTimes(1);
  });

  test('Done is not fired before Copy is clicked', async () => {
    const onDone = vi.fn();
    render(<RevealOnceBanner value={SECRET} onDone={onDone} />);

    await userEvent.click(screen.getByRole('button', { name: /^done$/i }));
    expect(onDone).not.toHaveBeenCalled();
  });

  test('custom warning overrides the default copy', () => {
    render(<RevealOnceBanner value={SECRET} onDone={vi.fn()} warning="Custom warning here." />);
    expect(screen.getByRole('alert')).toHaveTextContent('Custom warning here.');
  });

  test('custom label sets the mono field accessible label', () => {
    render(<RevealOnceBanner value={SECRET} onDone={vi.fn()} label="Personal token" />);
    expect(screen.getByLabelText('Personal token')).toHaveValue(SECRET);
  });
});
