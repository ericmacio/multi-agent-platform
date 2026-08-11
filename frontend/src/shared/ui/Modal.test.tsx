import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { expectNoA11yViolations } from '@/test/axe';
import { Modal } from './Modal';
import { Button } from './Button';

function ControlledModal({ initial = true }: { initial?: boolean }) {
  const [open, setOpen] = useState(initial);
  return (
    <>
      <Button onClick={() => setOpen(true)}>open</Button>
      <Modal open={open} onOpenChange={setOpen} title="Confirm">
        <p>Body text</p>
        <Button>Cancel</Button>
        <Button>Ok</Button>
      </Modal>
    </>
  );
}

describe('Modal', () => {
  test('renders a dialog with role + aria-modal when open', () => {
    render(<ControlledModal />);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByText('Body text')).toBeInTheDocument();
  });

  test('renders nothing when closed', () => {
    render(<ControlledModal initial={false} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('Esc closes the dialog', async () => {
    render(<ControlledModal />);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('Close button closes the dialog', async () => {
    render(<ControlledModal />);
    await userEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  test('focus moves into the modal on open', () => {
    render(<ControlledModal />);
    // First focusable inside the modal header is the Close button.
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
  });

  test('Tab cycles within the modal (focus does not escape)', async () => {
    render(<ControlledModal />);
    const close = screen.getByRole('button', { name: 'Close' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    const ok = screen.getByRole('button', { name: 'Ok' });
    expect(document.activeElement).toBe(close);
    await userEvent.tab();
    expect(document.activeElement).toBe(cancel);
    await userEvent.tab();
    expect(document.activeElement).toBe(ok);
    // The next Tab wraps back to the first focusable.
    await userEvent.tab();
    expect(document.activeElement).toBe(close);
  });

  test('a11y: opened dialog with title, body, and buttons has no violations', async () => {
    const { container } = render(<ControlledModal />);
    await expectNoA11yViolations(container);
  });
});
