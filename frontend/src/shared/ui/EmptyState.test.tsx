import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { EmptyState } from './EmptyState';
import { Button } from './Button';
import { Plus } from './icons';

describe('EmptyState', () => {
  test('renders title + description', () => {
    render(<EmptyState title="No agents yet" description="Create your first agent." />);
    expect(screen.getByRole('heading', { level: 2, name: 'No agents yet' })).toBeInTheDocument();
    expect(screen.getByText('Create your first agent.')).toBeInTheDocument();
  });

  test('renders the action slot when provided', () => {
    render(
      <EmptyState
        title="Empty"
        action={<Button leftIcon={<Plus aria-hidden width={16} height={16} />}>New</Button>}
      />,
    );
    expect(screen.getByRole('button', { name: /new/i })).toBeInTheDocument();
  });

  test('omits action when not provided', () => {
    render(<EmptyState title="Empty" />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  test('omits description when not provided', () => {
    render(<EmptyState title="Empty" />);
    expect(screen.queryByText(/create/i)).not.toBeInTheDocument();
  });
});
