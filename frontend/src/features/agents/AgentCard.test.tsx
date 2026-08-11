import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { tokenStorage } from '@/shared/auth/tokenStorage';
import { renderWithProviders } from '@/test/render';
import { AgentCard } from './AgentCard';
import type { Agent } from './schema';

function anAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: '7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1',
    ownerId: 'a1b2c3d4-5e6f-4789-9abc-def012345678',
    name: 'Researcher',
    description: 'Reads docs and reports back.',
    systemPrompt: 'You are a researcher.',
    memorySize: 12,
    tools: [],
    enabledMcpServers: [],
    team: [],
    llmModel: null,
    temperature: null,
    maxOutputTokens: null,
    topP: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  tokenStorage.clear();
});
afterEach(() => {
  tokenStorage.clear();
});

describe('AgentCard', () => {
  test('action menu items fire their respective callbacks', async () => {
    const onView = vi.fn();
    const onEdit = vi.fn();
    const onStartChat = vi.fn();
    const onDelete = vi.fn();

    renderWithProviders(
      <AgentCard
        agent={anAgent()}
        onView={onView}
        onEdit={onEdit}
        onStartChat={onStartChat}
        onDelete={onDelete}
      />,
    );

    await userEvent.click(screen.getByRole('button', { name: /actions for researcher/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /view/i }));
    expect(onView).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: /actions for researcher/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /edit/i }));
    expect(onEdit).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: /actions for researcher/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /start chat/i }));
    expect(onStartChat).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: /actions for researcher/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: /delete/i }));
    expect(onDelete).toHaveBeenCalledTimes(1);
  });

  test('MCP server badges overflow with "+N" when count > 3', () => {
    renderWithProviders(
      <AgentCard
        agent={anAgent({
          enabledMcpServers: ['a', 'b', 'c', 'd', 'e'],
        })}
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    // First 3 are individual badges.
    expect(screen.getByText('a')).toBeInTheDocument();
    expect(screen.getByText('b')).toBeInTheDocument();
    expect(screen.getByText('c')).toBeInTheDocument();
    // d and e are summarized.
    expect(screen.queryByText('d')).not.toBeInTheDocument();
    expect(screen.queryByText('e')).not.toBeInTheDocument();
    expect(screen.getByText('+2')).toBeInTheDocument();
  });

  test('team badge is absent when team is empty', () => {
    renderWithProviders(
      <AgentCard
        agent={anAgent({ team: [] })}
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );
    expect(screen.queryByText(/team of/i)).not.toBeInTheDocument();
  });

  test('team badge appears when team is non-empty', () => {
    renderWithProviders(
      <AgentCard
        agent={anAgent({ team: ['7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a2'] })}
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );
    expect(screen.getByText(/team of 1/i)).toBeInTheDocument();
  });

  test('model badge shows "default" when llmModel is null', () => {
    renderWithProviders(
      <AgentCard
        agent={anAgent({ llmModel: null })}
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );
    expect(screen.getByText('default')).toBeInTheDocument();
  });

  test('model badge shows the model name when set', () => {
    renderWithProviders(
      <AgentCard
        agent={anAgent({ llmModel: 'gpt-4o' })}
        onView={vi.fn()}
        onEdit={vi.fn()}
        onStartChat={vi.fn()}
        onDelete={vi.fn()}
      />,
    );
    expect(screen.getByText('gpt-4o')).toBeInTheDocument();
  });
});
