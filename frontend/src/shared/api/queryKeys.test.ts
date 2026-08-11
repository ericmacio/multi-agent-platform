import { describe, expect, test } from 'vitest';
import { qk } from './queryKeys';

describe('qk factory', () => {
  test('me() returns a stable, single-element key', () => {
    expect(qk.me()).toEqual(['me']);
  });

  test('agents.all() and agents.list() are disjoint roots', () => {
    expect(qk.agents.all()).toEqual(['agents']);
    expect(qk.agents.list()).toEqual(['agents', 'list', null]);
  });

  test('agents.list() and agents.list(undefined) produce identical keys', () => {
    expect(JSON.stringify(qk.agents.list())).toBe(JSON.stringify(qk.agents.list(undefined)));
  });

  test('agents.list(cursor) preserves the cursor in the key', () => {
    expect(qk.agents.list('cursor-A')).toEqual(['agents', 'list', 'cursor-A']);
  });

  test('agents.byId(a) and agents.byId(b) are not equal', () => {
    expect(JSON.stringify(qk.agents.byId('a'))).not.toBe(JSON.stringify(qk.agents.byId('b')));
  });

  test('conversations.* keys are nested under "conversations"', () => {
    expect(qk.conversations.all()[0]).toBe('conversations');
    expect(qk.conversations.list('agent-1')).toEqual(['conversations', 'list', 'agent-1']);
    expect(qk.conversations.byId('c-1')).toEqual(['conversations', 'byId', 'c-1']);
    expect(qk.conversations.messages('c-1')).toEqual(['conversations', 'messages', 'c-1']);
  });

  test('catalog.* keys are nested under "catalog"', () => {
    expect(qk.catalog.tools()).toEqual(['catalog', 'tools']);
    expect(qk.catalog.mcpServers()).toEqual(['catalog', 'mcpServers']);
  });

  test('admin.* keys are nested under "admin"', () => {
    expect(qk.admin.users.list()).toEqual(['admin', 'users', 'list']);
    expect(qk.admin.users.byId('u-1')).toEqual(['admin', 'users', 'byId', 'u-1']);
    expect(qk.admin.apiKeys.all()).toEqual(['admin', 'apiKeys']);
    expect(qk.admin.apiKeys.list()).toEqual(['admin', 'apiKeys', 'list']);
    expect(qk.admin.rateLimit()).toEqual(['admin', 'rateLimit']);
  });
});
