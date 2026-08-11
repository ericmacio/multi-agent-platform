import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoA11yViolations } from '@/test/axe';
import { ToastViewport, _resetToasts, toast } from './Toast';

beforeEach(() => {
  _resetToasts();
});

afterEach(() => {
  _resetToasts();
  vi.useRealTimers();
});

describe('toast queue', () => {
  test('toast.info enqueues a toast that the viewport renders', () => {
    render(<ToastViewport />);
    act(() => {
      toast.info('Hello');
    });
    const node = screen.getByTestId('toast-info');
    expect(node).toHaveTextContent('Hello');
  });

  test('non-error toasts auto-dismiss after 5 s', () => {
    vi.useFakeTimers();
    render(<ToastViewport />);
    act(() => {
      toast.info('Hello');
    });
    expect(screen.getByTestId('toast-info')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(5_001);
    });
    expect(screen.queryByTestId('toast-info')).not.toBeInTheDocument();
  });

  test('error toasts are sticky until dismissed', () => {
    vi.useFakeTimers();
    render(<ToastViewport />);
    act(() => {
      toast.error('boom');
    });
    expect(screen.getByTestId('toast-error')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByTestId('toast-error')).toBeInTheDocument();
  });

  test('same-key calls replace the existing toast in place', () => {
    render(<ToastViewport />);
    act(() => {
      toast.info('first', 'rl');
    });
    expect(screen.getAllByTestId(/toast-/)).toHaveLength(1);
    act(() => {
      toast.info('second', 'rl');
    });
    const all = screen.getAllByTestId(/toast-/);
    expect(all).toHaveLength(1);
    expect(all[0]).toHaveTextContent('second');
  });

  test('dismiss button removes the toast immediately', async () => {
    render(<ToastViewport />);
    act(() => {
      toast.error('boom');
    });
    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByTestId('toast-error')).not.toBeInTheDocument();
  });

  test('a11y: viewport with a queued toast has no violations', async () => {
    const { container } = render(<ToastViewport />);
    act(() => {
      toast.info('Hello');
    });
    await expectNoA11yViolations(container);
  });

  test('viewport mounted twice shares a single queue (useSyncExternalStore)', () => {
    render(
      <>
        <ToastViewport />
        <ToastViewport />
      </>,
    );
    act(() => {
      toast.success('once', 'shared');
    });
    expect(screen.getAllByTestId('toast-success')).toHaveLength(2); // rendered in both mounts
  });
});
