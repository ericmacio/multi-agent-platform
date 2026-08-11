import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from './Dropdown';

function fixture(onPick?: (name: string) => void) {
  return (
    <>
      <button>outside</button>
      <Dropdown>
        <DropdownTrigger>menu</DropdownTrigger>
        <DropdownContent>
          <DropdownItem onClick={() => onPick?.('one')}>one</DropdownItem>
          <DropdownItem onClick={() => onPick?.('two')}>two</DropdownItem>
          <DropdownItem onClick={() => onPick?.('three')}>three</DropdownItem>
        </DropdownContent>
      </Dropdown>
    </>
  );
}

describe('Dropdown', () => {
  test('clicking the trigger opens the menu', async () => {
    render(fixture());
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
  });

  test('aria-expanded reflects open state', async () => {
    render(fixture());
    const trigger = screen.getByRole('button', { name: 'menu' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
  });

  test('first item gets focus on open', async () => {
    render(fixture());
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'one' }));
  });

  test('ArrowDown / ArrowUp cycle through items', async () => {
    render(fixture());
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'two' }));
    await userEvent.keyboard('{ArrowDown}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'three' }));
    await userEvent.keyboard('{ArrowDown}'); // wraps
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'one' }));
    await userEvent.keyboard('{ArrowUp}');
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'three' }));
  });

  test('Enter activates the focused item and closes the menu', async () => {
    const onPick = vi.fn();
    render(fixture(onPick));
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    await userEvent.keyboard('{ArrowDown}'); // focus 'two'
    await userEvent.keyboard('{Enter}');
    expect(onPick).toHaveBeenCalledWith('two');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  test('Esc closes the menu and returns focus to the trigger', async () => {
    render(fixture());
    const trigger = screen.getByRole('button', { name: 'menu' });
    await userEvent.click(trigger);
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(trigger);
  });

  test('clicking outside closes the menu', async () => {
    render(fixture());
    await userEvent.click(screen.getByRole('button', { name: 'menu' }));
    await userEvent.click(screen.getByRole('button', { name: 'outside' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
