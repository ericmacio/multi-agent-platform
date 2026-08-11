import { afterEach, beforeEach, describe, expect, test, vi, type MockInstance } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ErrorBoundary } from './ErrorBoundary';

function Bomb(): JSX.Element {
  throw new Error('kaboom');
}

let consoleErrorSpy: MockInstance<Parameters<typeof console.error>, void>;

beforeEach(() => {
  consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  consoleErrorSpy.mockRestore();
});

describe('ErrorBoundary', () => {
  test('renders children when nothing throws', () => {
    render(
      <ErrorBoundary>
        <p>child ok</p>
      </ErrorBoundary>,
    );
    expect(screen.getByText('child ok')).toBeInTheDocument();
  });

  test('renders fallback EmptyState + Reload button when a child throws', () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>,
    );
    expect(
      screen.getByRole('heading', { level: 2, name: /unexpected error/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument();
  });

  test('logs the error to console.error', () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>,
    );
    expect(consoleErrorSpy).toHaveBeenCalled();
  });
});
