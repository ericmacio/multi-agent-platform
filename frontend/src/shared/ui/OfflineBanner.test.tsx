import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { OfflineBanner } from './OfflineBanner';

/**
 * `navigator.onLine` is not writable directly on jsdom's Navigator, so tests
 * redefine the property before render. The teardown restores the original
 * descriptor so no test leaks the mock into the next.
 */
function setOnline(value: boolean): () => void {
  const original = Object.getOwnPropertyDescriptor(
    Object.getPrototypeOf(navigator),
    'onLine',
  );
  Object.defineProperty(navigator, 'onLine', {
    configurable: true,
    get: () => value,
  });
  return () => {
    if (original) {
      Object.defineProperty(Object.getPrototypeOf(navigator), 'onLine', original);
    } else {
      // Fallback: remove the override we set on the instance.
      // @ts-expect-error - clean up the instance-level override.
      delete (navigator as { onLine?: boolean }).onLine;
    }
  };
}

let restoreOnline: (() => void) | null = null;

beforeEach(() => {
  restoreOnline = setOnline(true);
});
afterEach(() => {
  restoreOnline?.();
  restoreOnline = null;
});

describe('OfflineBanner', () => {
  test('renders nothing while navigator.onLine is true', () => {
    const { container } = render(<OfflineBanner />);
    expect(container.firstChild).toBeNull();
  });

  test('renders the banner with role="status" when navigator.onLine is false at mount', () => {
    restoreOnline?.();
    restoreOnline = setOnline(false);

    render(<OfflineBanner />);
    const banner = screen.getByTestId('offline-banner');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveAttribute('role', 'status');
    expect(banner).toHaveAttribute('aria-live', 'polite');
    expect(banner).toHaveTextContent(/you'?re offline/i);
    expect(banner).toHaveTextContent(/some actions will fail/i);
  });

  test('reacts to a subsequent `offline` event by rendering the banner', () => {
    render(<OfflineBanner />);
    expect(screen.queryByTestId('offline-banner')).toBeNull();

    restoreOnline?.();
    restoreOnline = setOnline(false);
    act(() => {
      window.dispatchEvent(new Event('offline'));
    });

    expect(screen.getByTestId('offline-banner')).toBeInTheDocument();
  });

  test('reacts to `online` by removing the banner', () => {
    restoreOnline?.();
    restoreOnline = setOnline(false);

    render(<OfflineBanner />);
    expect(screen.getByTestId('offline-banner')).toBeInTheDocument();

    restoreOnline?.();
    restoreOnline = setOnline(true);
    act(() => {
      window.dispatchEvent(new Event('online'));
    });

    expect(screen.queryByTestId('offline-banner')).toBeNull();
  });

  test('unmount removes both event listeners', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    const removeSpy = vi.spyOn(window, 'removeEventListener');

    const { unmount } = render(<OfflineBanner />);
    const registered = addSpy.mock.calls
      .filter((c) => c[0] === 'offline' || c[0] === 'online')
      .map((c) => c[0]);
    expect(registered).toContain('offline');
    expect(registered).toContain('online');

    unmount();

    const removed = removeSpy.mock.calls
      .filter((c) => c[0] === 'offline' || c[0] === 'online')
      .map((c) => c[0]);
    expect(removed).toContain('offline');
    expect(removed).toContain('online');

    addSpy.mockRestore();
    removeSpy.mockRestore();
  });
});
