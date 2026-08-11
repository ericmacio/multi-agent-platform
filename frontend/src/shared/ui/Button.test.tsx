import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectNoA11yViolations } from '@/test/axe';
import { Button } from './Button';

describe('Button', () => {
  test('renders every variant without crashing', () => {
    (['primary', 'secondary', 'ghost', 'danger'] as const).forEach((variant) => {
      const { unmount } = render(<Button variant={variant}>X</Button>);
      expect(screen.getByRole('button', { name: 'X' })).toBeInTheDocument();
      unmount();
    });
  });

  test('disabled prevents onClick', async () => {
    const handler = vi.fn();
    render(
      <Button disabled onClick={handler}>
        Save
      </Button>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(handler).not.toHaveBeenCalled();
  });

  test('loading sets disabled + aria-busy and shows the spinner', () => {
    render(<Button loading>Save</Button>);
    const btn = screen.getByRole('button', { name: /save/i });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
    // Spinner uses role=status when label is non-empty; loading-mode spinner
    // is rendered with empty label (aria-hidden), so we assert structurally.
    expect(btn.querySelector('svg')).toBeTruthy();
  });

  test('merges className via cn() (caller class is applied)', () => {
    render(<Button className="custom-x">Save</Button>);
    expect(screen.getByRole('button', { name: 'Save' })).toHaveClass('custom-x');
  });

  test('forwards ref to the underlying button', () => {
    let captured: HTMLButtonElement | null = null;
    render(
      <Button
        ref={(el) => {
          captured = el;
        }}
      >
        X
      </Button>,
    );
    expect(captured).toBeInstanceOf(HTMLButtonElement);
  });

  test('a11y: primary button in default state has no violations', async () => {
    const { container } = render(<Button>Save changes</Button>);
    await expectNoA11yViolations(container);
  });
});
