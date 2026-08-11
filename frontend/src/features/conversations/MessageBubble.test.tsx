import { describe, expect, test } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MessageBubble } from './MessageBubble';
import type { Message } from './schema';

function aMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa',
    role: 'USER',
    content: 'hello world',
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('MessageBubble', () => {
  test('USER role: row is right-aligned and the data-message-id anchor is set', () => {
    const { container } = render(<MessageBubble message={aMessage({ role: 'USER' })} />);
    const row = container.firstElementChild as HTMLElement;
    expect(row.className).toContain('justify-end');
    expect(row.getAttribute('data-message-id')).toBe(
      'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa',
    );
  });

  test('ASSISTANT role: row is left-aligned', () => {
    const { container } = render(
      <MessageBubble message={aMessage({ role: 'ASSISTANT' })} />,
    );
    const row = container.firstElementChild as HTMLElement;
    expect(row.className).toContain('justify-start');
  });

  test('multi-line content is preserved via whitespace: pre-wrap', () => {
    render(<MessageBubble message={aMessage({ content: 'line one\nline two' })} />);
    const bubble = screen.getByText(/line one/);
    expect(bubble.textContent).toContain('line two');
    // whitespace: pre-wrap is applied via inline style on the bubble.
    expect(bubble).toHaveStyle({ whiteSpace: 'pre-wrap' });
  });

  test('variant=streaming: blinking caret is present', () => {
    render(<MessageBubble message={aMessage({ role: 'ASSISTANT' })} variant="streaming" />);
    expect(screen.getByTestId('streaming-caret')).toBeInTheDocument();
  });

  test('variant=stopped: "(stopped)" caption is present and the bubble is dimmed', () => {
    const { container } = render(
      <MessageBubble message={aMessage({ role: 'ASSISTANT' })} variant="stopped" />,
    );
    expect(screen.getByText('(stopped)')).toBeInTheDocument();
    // The dimmed bubble carries the opacity class.
    const dimmed = container.querySelector('.opacity-60');
    expect(dimmed).not.toBeNull();
  });

  test('default variant: no caret, no stopped caption', () => {
    render(<MessageBubble message={aMessage({ role: 'ASSISTANT' })} />);
    expect(screen.queryByTestId('streaming-caret')).not.toBeInTheDocument();
    expect(screen.queryByText('(stopped)')).not.toBeInTheDocument();
  });
});
