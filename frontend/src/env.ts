import { z } from 'zod';

/**
 * Schema for Vite-injected environment variables.
 *
 * Exported separately from the parsed `env` value so unit tests can exercise
 * defaults and rejection paths without depending on `import.meta.env`.
 *
 * NOTE: this is the only file in the project allowed to read `import.meta.env`.
 * The ESLint rule `no-restricted-syntax` enforces it.
 */
export const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url().default('http://localhost:8080/api/v1'),
  VITE_APP_NAME: z.string().min(1).default('Multi-Agent Platform'),
  VITE_BUILD_VERSION: z.string().min(1).default('dev'),
});

export type Env = z.infer<typeof envSchema>;

const parsed = envSchema.safeParse(import.meta.env);
if (!parsed.success) {
  throw new Error(
    'Invalid frontend environment:\n' + JSON.stringify(parsed.error.format(), null, 2),
  );
}

export const env: Env = parsed.data;
