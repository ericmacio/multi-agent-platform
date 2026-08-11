import { describe, expect, test } from 'vitest';
import { agentSchema } from './schema';

describe('agentSchema', () => {
  test('rejects empty name', () => {
    const result = agentSchema.safeParse({
      name: '',
      description: 'd',
      systemPrompt: 's',
    });
    expect(result.success).toBe(false);
  });

  test('rejects name longer than 32 chars', () => {
    const result = agentSchema.safeParse({
      name: 'a'.repeat(33),
      description: 'd',
      systemPrompt: 's',
    });
    expect(result.success).toBe(false);
  });

  test('memorySize bounds: 0 fails, 1 and 36 succeed, 37 fails', () => {
    const base = { name: 'a', description: 'd', systemPrompt: 's' };
    expect(agentSchema.safeParse({ ...base, memorySize: 0 }).success).toBe(false);
    expect(agentSchema.safeParse({ ...base, memorySize: 1 }).success).toBe(true);
    expect(agentSchema.safeParse({ ...base, memorySize: 36 }).success).toBe(true);
    expect(agentSchema.safeParse({ ...base, memorySize: 37 }).success).toBe(false);
  });

  test('team rejects non-UUID strings, accepts valid UUIDs', () => {
    const base = { name: 'a', description: 'd', systemPrompt: 's' };
    expect(agentSchema.safeParse({ ...base, team: ['not-a-uuid'] }).success).toBe(false);
    expect(
      agentSchema.safeParse({
        ...base,
        team: ['7c7e1a2c-2db8-4a3e-9d63-7e3f2cb1b9a1'],
      }).success,
    ).toBe(true);
  });

  test('optional fields accept null, undefined, or omission', () => {
    const base = { name: 'a', description: 'd', systemPrompt: 's' };
    expect(agentSchema.safeParse({ ...base }).success).toBe(true);
    expect(
      agentSchema.safeParse({
        ...base,
        llmModel: null,
        temperature: null,
        maxOutputTokens: null,
        topP: null,
      }).success,
    ).toBe(true);
    expect(
      agentSchema.safeParse({
        ...base,
        llmModel: undefined,
        temperature: undefined,
      }).success,
    ).toBe(true);
  });

  test('defaults apply when minimum fields are supplied', () => {
    const result = agentSchema.parse({
      name: 'a',
      description: 'd',
      systemPrompt: 's',
    });
    expect(result.memorySize).toBe(12);
    expect(result.tools).toEqual([]);
    expect(result.enabledMcpServers).toEqual([]);
    expect(result.team).toEqual([]);
  });

  test('rejects llmModel longer than 64 chars', () => {
    const base = { name: 'a', description: 'd', systemPrompt: 's' };
    expect(agentSchema.safeParse({ ...base, llmModel: 'a'.repeat(65) }).success).toBe(false);
    expect(agentSchema.safeParse({ ...base, llmModel: 'a'.repeat(64) }).success).toBe(true);
  });

  test('rejects description longer than 1024 chars', () => {
    expect(
      agentSchema.safeParse({
        name: 'a',
        description: 'd'.repeat(1025),
        systemPrompt: 's',
      }).success,
    ).toBe(false);
  });

  test('rejects systemPrompt longer than 1024 chars', () => {
    expect(
      agentSchema.safeParse({
        name: 'a',
        description: 'd',
        systemPrompt: 's'.repeat(1025),
      }).success,
    ).toBe(false);
  });
});
