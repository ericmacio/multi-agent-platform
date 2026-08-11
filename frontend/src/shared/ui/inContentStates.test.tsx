import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { errorCopy } from '@/shared/i18n/en';
import { ForbiddenState } from './ForbiddenState';
import { LoadingList } from './LoadingList';
import { NotFoundState } from './NotFoundState';

describe('ForbiddenState', () => {
  test('renders the default FORBIDDEN copy from errorCopy', () => {
    render(<ForbiddenState />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(errorCopy.FORBIDDEN.title);
    expect(alert).toHaveTextContent(errorCopy.FORBIDDEN.detail);
  });

  test('accepts overrides for title, description, and action', () => {
    render(
      <ForbiddenState
        title="Custom title"
        description="Custom description."
        action={<button type="button">Ask an admin</button>}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Custom title');
    expect(screen.getByRole('alert')).toHaveTextContent('Custom description.');
    expect(screen.getByRole('button', { name: /ask an admin/i })).toBeInTheDocument();
  });
});

describe('NotFoundState', () => {
  test('renders the default NOT_FOUND copy from errorCopy', () => {
    render(<NotFoundState />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(errorCopy.NOT_FOUND.title);
    expect(alert).toHaveTextContent(errorCopy.NOT_FOUND.detail);
  });

  test('accepts overrides for title, description, and action', () => {
    render(
      <NotFoundState
        title="Gone."
        description="This resource was deleted."
        action={<button type="button">Back to list</button>}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Gone.');
    expect(screen.getByRole('alert')).toHaveTextContent('This resource was deleted.');
    expect(screen.getByRole('button', { name: /back to list/i })).toBeInTheDocument();
  });
});

describe('LoadingList', () => {
  test('renders 5 skeleton rows by default', () => {
    const { container } = render(<LoadingList />);
    const skeletons = container.querySelectorAll('[aria-hidden="true"]');
    expect(skeletons.length).toBe(5);
  });

  test('rows prop controls the number of skeletons', () => {
    const { container } = render(<LoadingList rows={3} />);
    const skeletons = container.querySelectorAll('[aria-hidden="true"]');
    expect(skeletons.length).toBe(3);
  });

  test('exposes a stable data-testid for parent pages to key their loading branch', () => {
    render(<LoadingList />);
    expect(screen.getByTestId('loading-list')).toBeInTheDocument();
  });

  test('testId override lets callers scope the hook per page', () => {
    render(<LoadingList testId="admin-users-loading" />);
    expect(screen.getByTestId('admin-users-loading')).toBeInTheDocument();
  });

  test('skeleton blocks are aria-hidden so screen readers skip them', () => {
    const { container } = render(<LoadingList rows={1} />);
    const skeleton = container.querySelector('[aria-hidden="true"]');
    expect(skeleton).not.toBeNull();
  });
});
