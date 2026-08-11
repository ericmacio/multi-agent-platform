import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { Checkbox } from './Checkbox';

function ControlledCheckbox() {
  const [checked, setChecked] = useState(false);
  return (
    <Checkbox label="Accept" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
  );
}

describe('Checkbox', () => {
  test('Space toggles via keyboard when focused', async () => {
    render(<ControlledCheckbox />);
    const box = screen.getByLabelText('Accept') as HTMLInputElement;
    box.focus();
    expect(box.checked).toBe(false);
    await userEvent.keyboard(' ');
    expect(box.checked).toBe(true);
    await userEvent.keyboard(' ');
    expect(box.checked).toBe(false);
  });

  test('clicking the label toggles the box', async () => {
    render(<ControlledCheckbox />);
    const box = screen.getByLabelText('Accept') as HTMLInputElement;
    await userEvent.click(screen.getByText('Accept'));
    expect(box.checked).toBe(true);
  });

  test('indeterminate sets the DOM property', () => {
    render(<Checkbox label="Some" indeterminate />);
    const box = screen.getByLabelText('Some') as HTMLInputElement;
    expect(box.indeterminate).toBe(true);
  });

  test('disabled blocks user clicks', async () => {
    render(<Checkbox label="Off" disabled />);
    const box = screen.getByLabelText('Off') as HTMLInputElement;
    expect(box).toBeDisabled();
  });
});
