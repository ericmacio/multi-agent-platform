import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { expectNoA11yViolations } from '@/test/axe';
import { Input } from './Input';

describe('Input', () => {
  test('renders the label and wires htmlFor → id', () => {
    render(<Input label="Email" />);
    const input = screen.getByLabelText('Email');
    expect(input).toBeInTheDocument();
    expect(input.tagName).toBe('INPUT');
  });

  test('error sets aria-invalid and renders the message', () => {
    render(<Input label="Email" error="must be a valid email" />);
    const input = screen.getByLabelText('Email');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('must be a valid email')).toBeInTheDocument();
    // The input describes the error via aria-describedby.
    expect(input).toHaveAttribute('aria-describedby');
  });

  test('helperText renders when no error is set', () => {
    render(<Input label="Name" helperText="up to 32 characters" />);
    expect(screen.getByText('up to 32 characters')).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).not.toHaveAttribute('aria-invalid');
  });

  test('error supersedes helperText', () => {
    render(<Input label="Name" helperText="up to 32 characters" error="too long" />);
    expect(screen.getByText('too long')).toBeInTheDocument();
    expect(screen.queryByText('up to 32 characters')).not.toBeInTheDocument();
  });

  test('a11y: labeled input with helperText has no violations', async () => {
    const { container } = render(
      <Input label="Email" helperText="Your work email" />,
    );
    await expectNoA11yViolations(container);
  });

  test('a11y: error state (aria-invalid + aria-describedby) has no violations', async () => {
    const { container } = render(
      <Input label="Email" error="must be a valid email" />,
    );
    await expectNoA11yViolations(container);
  });
});
