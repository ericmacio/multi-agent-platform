import { describe, expect, test } from 'vitest';
import { envSchema } from './env';

describe('envSchema', () => {
  test('applies the documented defaults when no input is provided', () => {
    const result = envSchema.parse({});
    expect(result.VITE_API_BASE_URL).toBe('http://localhost:8080/api/v1');
    expect(result.VITE_APP_NAME).toBe('Multi-Agent Platform');
    expect(result.VITE_BUILD_VERSION).toBe('dev');
  });

  test('accepts a valid custom API base URL', () => {
    const result = envSchema.parse({ VITE_API_BASE_URL: 'https://api.example.com/api/v1' });
    expect(result.VITE_API_BASE_URL).toBe('https://api.example.com/api/v1');
  });

  test('rejects a non-URL value for VITE_API_BASE_URL', () => {
    const result = envSchema.safeParse({ VITE_API_BASE_URL: 'not a url with spaces' });
    expect(result.success).toBe(false);
    if (!result.success) {
      const issue = result.error.issues.find((i) => i.path[0] === 'VITE_API_BASE_URL');
      expect(issue).toBeDefined();
    }
  });

  test('rejects an empty string for VITE_APP_NAME', () => {
    const result = envSchema.safeParse({ VITE_APP_NAME: '' });
    expect(result.success).toBe(false);
  });

  test('rejects an empty string for VITE_BUILD_VERSION', () => {
    const result = envSchema.safeParse({ VITE_BUILD_VERSION: '' });
    expect(result.success).toBe(false);
  });
});
