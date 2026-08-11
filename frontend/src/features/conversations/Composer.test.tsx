import { describe, expect, test, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Composer, type ChatPhase } from './Composer';

function renderComposer(phase: ChatPhase = 'idle', opts: { onSend?: (c: string) => void; onStop?: () => void; disabled?: boolean } = {}) {
  const onSend = opts.onSend ?? vi.fn();
  const onStop = opts.onStop ?? vi.fn();
  const utils = render(
    <Composer phase={phase} onSend={onSend} onStop={onStop} disabled={opts.disabled} />,
  );
  return { ...utils, onSend, onStop };
}

const origPlatform = Object.getOwnPropertyDescriptor(navigator, 'platform');

function setPlatform(platform: string) {
  Object.defineProperty(navigator, 'platform', { value: platform, configurable: true });
}

beforeEach(() => {
  setPlatform('Win32');
});

afterEach(() => {
  if (origPlatform) Object.defineProperty(navigator, 'platform', origPlatform);
});

describe('Composer', () => {
  test('empty content: Send is disabled; Cmd+Enter does NOT fire onSend', async () => {
    const { onSend } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    expect(screen.getByTestId('composer-send')).toBeDisabled();
    textarea.focus();
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    expect(onSend).not.toHaveBeenCalled();
  });

  test('whitespace-only content: Send stays disabled; Cmd+Enter does not fire', async () => {
    const { onSend } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.type(textarea, '   ');
    expect(screen.getByTestId('composer-send')).toBeDisabled();
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    expect(onSend).not.toHaveBeenCalled();
  });

  test('Ctrl+Enter on Windows fires onSend and clears the textarea', async () => {
    const { onSend } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i) as HTMLTextAreaElement;
    await userEvent.type(textarea, 'hello');
    await userEvent.keyboard('{Control>}{Enter}{/Control}');
    expect(onSend).toHaveBeenCalledWith('hello');
    expect(textarea.value).toBe('');
  });

  test('Cmd+Enter on Mac fires onSend', async () => {
    setPlatform('MacIntel');
    const { onSend } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i) as HTMLTextAreaElement;
    await userEvent.type(textarea, 'hi');
    await userEvent.keyboard('{Meta>}{Enter}{/Meta}');
    expect(onSend).toHaveBeenCalledWith('hi');
    expect(textarea.value).toBe('');
  });

  test('plain Enter inserts a newline and does NOT fire onSend', async () => {
    const { onSend } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i) as HTMLTextAreaElement;
    await userEvent.type(textarea, 'line1{Enter}line2');
    expect(textarea.value).toBe('line1\nline2');
    expect(onSend).not.toHaveBeenCalled();
  });

  test('Send click fires onSend(content) and clears the textarea', async () => {
    const { onSend } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i) as HTMLTextAreaElement;
    await userEvent.type(textarea, 'hello');
    await userEvent.click(screen.getByTestId('composer-send'));
    expect(onSend).toHaveBeenCalledWith('hello');
    expect(textarea.value).toBe('');
  });

  test('phase idle: Send visible, Stop not in DOM', () => {
    renderComposer('idle');
    expect(screen.getByTestId('composer-send')).toBeInTheDocument();
    expect(screen.queryByTestId('composer-stop')).not.toBeInTheDocument();
  });

  test('phase sending: Send visible with loading + disabled', () => {
    renderComposer('sending');
    const send = screen.getByTestId('composer-send');
    expect(send).toBeInTheDocument();
    expect(send).toBeDisabled();
    expect(send).toHaveAttribute('aria-busy', 'true');
  });

  test('phase streaming: Send not in DOM; Stop visible', () => {
    renderComposer('streaming');
    expect(screen.queryByTestId('composer-send')).not.toBeInTheDocument();
    expect(screen.getByTestId('composer-stop')).toBeInTheDocument();
  });

  test('phase completed: Send visible and enabled with valid content', async () => {
    renderComposer('completed');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.type(textarea, 'hi');
    expect(screen.getByTestId('composer-send')).toBeEnabled();
  });

  test('phase error: Send visible and enabled with valid content', async () => {
    renderComposer('error');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.type(textarea, 'hi');
    expect(screen.getByTestId('composer-send')).toBeEnabled();
  });

  test('Esc while streaming fires onStop', async () => {
    const { onStop } = renderComposer('streaming');
    // While streaming, the textarea stays interactive so the user can prep next message.
    const textarea = screen.getByLabelText(/message composer/i);
    textarea.focus();
    await userEvent.keyboard('{Escape}');
    expect(onStop).toHaveBeenCalledTimes(1);
  });

  test('Esc while idle does NOT fire onStop', async () => {
    const { onStop } = renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    textarea.focus();
    await userEvent.keyboard('{Escape}');
    expect(onStop).not.toHaveBeenCalled();
  });

  test('counter: muted under 900', async () => {
    renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.click(textarea);
    await userEvent.paste('x'.repeat(50));
    expect(screen.getByTestId('composer-counter').className).toMatch(/text-text-muted/);
  });

  test('counter: warning at 900', async () => {
    renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.click(textarea);
    await userEvent.paste('x'.repeat(900));
    expect(screen.getByTestId('composer-counter').className).toMatch(/text-warning/);
  });

  test('counter: danger at 1024', async () => {
    renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.click(textarea);
    await userEvent.paste('x'.repeat(1024));
    expect(screen.getByTestId('composer-counter').className).toMatch(/text-danger/);
  });

  test('over-cap (1025): inline alert + Send disabled', async () => {
    renderComposer('idle');
    const textarea = screen.getByLabelText(/message composer/i);
    await userEvent.click(textarea);
    await userEvent.paste('x'.repeat(1025));
    expect(screen.getByTestId('composer-too-long')).toBeInTheDocument();
    expect(screen.getByTestId('composer-send')).toBeDisabled();
  });

  test('disabled prop disables textarea and Send', () => {
    renderComposer('idle', { disabled: true });
    expect(screen.getByLabelText(/message composer/i)).toBeDisabled();
    expect(screen.getByTestId('composer-send')).toBeDisabled();
  });
});
