import { describe, expect, test } from 'vitest';
import { createUserSchema } from './schema';

describe('createUserSchema', () => {
  test('rejects invalid email format', () => {
    const result = createUserSchema.safeParse({
      email: 'not-an-email',
      password: 'AValid!Pw1',
      role: 'ADMIN',
    });
    expect(result.success).toBe(false);
  });

  test('rejects email longer than 254 chars', () => {
    const local = 'a'.repeat(250);
    const result = createUserSchema.safeParse({
      email: `${local}@b.co`,
      password: 'AValid!Pw1',
      role: 'ADMIN',
    });
    expect(result.success).toBe(false);
  });

  test('rejects password shorter than 10 chars', () => {
    const result = createUserSchema.safeParse({
      email: 'a@b.co',
      password: 'Sh0rt!A',
      role: 'ADMIN',
    });
    expect(result.success).toBe(false);
  });

  test('rejects password without uppercase letter', () => {
    const result = createUserSchema.safeParse({
      email: 'a@b.co',
      password: 'alllowercase!1',
      role: 'ADMIN',
    });
    expect(result.success).toBe(false);
  });

  test('rejects password without special char', () => {
    const result = createUserSchema.safeParse({
      email: 'a@b.co',
      password: 'NoSpecials1x',
      role: 'ADMIN',
    });
    expect(result.success).toBe(false);
  });

  test('rejects unknown role enum values', () => {
    const result = createUserSchema.safeParse({
      email: 'a@b.co',
      password: 'AValid!Pw1',
      role: 'HACKER',
    });
    expect(result.success).toBe(false);
  });

  test('accepts a valid STANDARD user', () => {
    const result = createUserSchema.safeParse({
      email: 'a@b.co',
      password: 'AValid!Pw1',
      role: 'STANDARD',
    });
    expect(result.success).toBe(true);
  });

  test('accepts a valid ADMIN user', () => {
    const result = createUserSchema.safeParse({
      email: 'admin@example.com',
      password: 'AValid!Pw1',
      role: 'ADMIN',
    });
    expect(result.success).toBe(true);
  });
});
