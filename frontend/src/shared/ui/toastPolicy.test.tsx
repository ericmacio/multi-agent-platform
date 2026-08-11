import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { ToastViewport, _resetToasts } from './Toast';
import { _resetRateLimitToast, showRateLimitedToast } from './toastPolicy';

beforeEach(() => {
  _resetToasts();
  _resetRateLimitToast();
});
afterEach(() => {
  _resetToasts();
  _resetRateLimitToast();
  vi.useRealTimers();
});

describe('showRateLimitedToast', () => {
  test('three consecutive calls collapse onto a single visible toast', () => {
    render(<ToastViewport />);
    act(() => {
      showRateLimitedToast(5);
      showRateLimitedToast(4);
      showRateLimitedToast(3);
    });
    const all = screen.getAllByTestId(/^toast-/);
    expect(all).toHaveLength(1);
    expect(all[0]).toHaveTextContent(/try again in 3s/i);
  });

  test('undefined retry-after falls back to a single stateless toast, no timer left running', () => {
    vi.useFakeTimers();
    render(<ToastViewport />);
    act(() => {
      showRateLimitedToast(undefined);
    });
    const all = screen.getAllByTestId(/^toast-/);
    expect(all).toHaveLength(1);
    expect(all[0]).toHaveTextContent(/retry shortly/i);
    // No pending timers — the toast has no countdown to tick.
    expect(vi.getTimerCount()).toBe(0);
  });

  test('countdown decrements in place each second (fake timers)', () => {
    vi.useFakeTimers();
    render(<ToastViewport />);
    act(() => {
      showRateLimitedToast(3);
    });
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/try again in 3s/i);

    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/try again in 2s/i);

    act(() => {
      vi.advanceTimersByTime(1_000);
    });
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/try again in 1s/i);
  });

  test('countdown self-dismisses when it reaches zero', () => {
    vi.useFakeTimers();
    render(<ToastViewport />);
    act(() => {
      showRateLimitedToast(2);
    });
    expect(screen.queryByTestId('toast-warning')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2_500);
    });
    expect(screen.queryByTestId('toast-warning')).not.toBeInTheDocument();
    // No leaked pending timers.
    expect(vi.getTimerCount()).toBe(0);
  });

  test('a new call cancels the previous countdown and restarts from the new value', () => {
    vi.useFakeTimers();
    render(<ToastViewport />);
    act(() => {
      showRateLimitedToast(10);
    });
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/try again in 10s/i);

    // A fresh 429 arrives before the first countdown finished — start over.
    act(() => {
      showRateLimitedToast(2);
    });
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/try again in 2s/i);
    expect(screen.getAllByTestId(/^toast-/)).toHaveLength(1);

    // Advance long enough for the SECOND countdown to zero — the toast
    // vanishes, proving the first countdown's timer no longer fires.
    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(screen.queryByTestId('toast-warning')).not.toBeInTheDocument();
    expect(vi.getTimerCount()).toBe(0);
  });

  test('zero / negative retry-after collapses to the stateless fallback', () => {
    render(<ToastViewport />);
    act(() => {
      showRateLimitedToast(0);
    });
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/retry shortly/i);

    act(() => {
      showRateLimitedToast(-5);
    });
    expect(screen.getAllByTestId(/^toast-/)).toHaveLength(1);
    expect(screen.getByTestId('toast-warning')).toHaveTextContent(/retry shortly/i);
  });
});
