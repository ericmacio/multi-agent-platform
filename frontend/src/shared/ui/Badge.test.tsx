import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Badge } from './Badge';

describe('Badge', () => {
  test('renders its children', () => {
    render(<Badge>active</Badge>);
    expect(screen.getByText('active')).toBeInTheDocument();
  });

  test('applies the success variant classes', () => {
    render(<Badge variant="success">ok</Badge>);
    expect(screen.getByText('ok')).toHaveClass('bg-success-bg', 'text-success');
  });

  test('applies the danger variant classes', () => {
    render(<Badge variant="danger">err</Badge>);
    expect(screen.getByText('err')).toHaveClass('bg-danger-bg', 'text-danger');
  });

  test('caller className is merged', () => {
    render(<Badge className="extra-x">x</Badge>);
    expect(screen.getByText('x')).toHaveClass('extra-x');
  });
});
