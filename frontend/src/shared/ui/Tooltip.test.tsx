import { afterEach, describe, expect, test, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { Tooltip } from './Tooltip';

afterEach(() => {
  vi.useRealTimers();
});

describe('Tooltip', () => {
  test('opens after 200ms on hover and closes immediately on leave', () => {
    vi.useFakeTimers();
    render(
      <Tooltip content="more info">
        <button>trigger</button>
      </Tooltip>,
    );
    const trigger = screen.getByRole('button', { name: 'trigger' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    fireEvent.mouseEnter(trigger);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument(); // not yet
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(screen.getByRole('tooltip')).toHaveTextContent('more info');

    fireEvent.mouseLeave(trigger);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  test('opens on focus and closes on blur', () => {
    vi.useFakeTimers();
    render(
      <Tooltip content="more info">
        <button>trigger</button>
      </Tooltip>,
    );
    const trigger = screen.getByRole('button', { name: 'trigger' });

    fireEvent.focus(trigger);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(screen.getByRole('tooltip')).toBeInTheDocument();

    fireEvent.blur(trigger);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  test('wires aria-describedby on the child while open', () => {
    vi.useFakeTimers();
    render(
      <Tooltip content="more info">
        <button>trigger</button>
      </Tooltip>,
    );
    const trigger = screen.getByRole('button', { name: 'trigger' });
    expect(trigger).not.toHaveAttribute('aria-describedby');

    fireEvent.mouseEnter(trigger);
    act(() => {
      vi.advanceTimersByTime(200);
    });
    const tip = screen.getByRole('tooltip');
    expect(trigger).toHaveAttribute('aria-describedby', tip.id);
  });

  test('preserves the wrapped element’s own onMouseEnter handler', () => {
    vi.useFakeTimers();
    const userHandler = vi.fn();
    render(
      <Tooltip content="more info">
        <button onMouseEnter={userHandler}>trigger</button>
      </Tooltip>,
    );
    fireEvent.mouseEnter(screen.getByRole('button', { name: 'trigger' }));
    expect(userHandler).toHaveBeenCalledTimes(1);
  });
});
