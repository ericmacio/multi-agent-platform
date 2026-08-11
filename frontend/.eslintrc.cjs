/** @type {import('eslint').Linter.Config} */
module.exports = {
  root: true,
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  env: {
    browser: true,
    es2022: true,
    node: true,
  },
  plugins: ['@typescript-eslint', 'react', 'react-hooks', 'jsx-a11y', 'import'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
    'prettier',
  ],
  settings: {
    react: { version: 'detect' },
  },
  rules: {
    'react/prop-types': 'off',
    '@typescript-eslint/no-unused-vars': [
      'warn',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
    ],
    '@typescript-eslint/consistent-type-imports': ['warn', { prefer: 'type-imports' }],
    // User-defined components legitimately take a `role` prop (e.g.,
    // `<RequireRole role="ADMIN">`) that the a11y rule otherwise flags as a
    // missing/abstract ARIA role. `ignoreNonDOM` scopes the check to real
    // DOM elements only.
    'jsx-a11y/aria-role': ['error', { ignoreNonDOM: true }],

    // ----------------------------------------------------------------
    // Layering rule (SW-DESIGN §2.4): pages → features → shared → generated.
    // Lower layers MUST NOT depend on higher layers.
    // Sibling-feature constraints (only via index.ts) will be added as `eslint-plugin-boundaries`
    // when src/features/ exists in EPIC-02. The plain layering below catches
    // the highest-impact violations today.
    // ----------------------------------------------------------------
    'import/no-restricted-paths': [
      'error',
      {
        zones: [
          {
            target: './src/shared',
            from: './src/features',
            message: 'shared/ cannot import from features/ (layering rule, SW-DESIGN §2.4).',
          },
          {
            target: './src/shared',
            from: './src/pages',
            message: 'shared/ cannot import from pages/ (layering rule, SW-DESIGN §2.4).',
          },
          {
            target: './src/features',
            from: './src/pages',
            message: 'features/ cannot import from pages/ (layering rule, SW-DESIGN §2.4).',
          },
          {
            target: './src/generated',
            from: ['./src/shared', './src/features', './src/pages'],
            message: 'src/generated/ is auto-generated — nothing should import into it.',
          },
        ],
      },
    ],

    // Forbid direct `import.meta.env` reads outside `src/env.ts` (SW-DESIGN §14.2).
    // Everything must go through the validated `env` object.
    'no-restricted-syntax': [
      'error',
      {
        selector:
          "MemberExpression[object.type='MetaProperty'][object.meta.name='import'][object.property.name='meta'][property.name='env']",
        message: "Read env vars via '@/env' instead of `import.meta.env` directly.",
      },
    ],
  },
  overrides: [
    // src/env.ts is the only authored consumer of `import.meta.env`.
    // The DEV-only design-system preview page also gates on `import.meta.env.PROD`
    // — a one-off legitimate use that doesn't belong in the validated env module.
    {
      files: ['src/env.ts', 'src/pages/__ds_preview__.tsx'],
      rules: { 'no-restricted-syntax': 'off' },
    },
    // Generated code is exempt from project-specific rules.
    {
      files: ['src/generated/**'],
      rules: {
        '@typescript-eslint/no-unused-vars': 'off',
        '@typescript-eslint/no-explicit-any': 'off',
        '@typescript-eslint/ban-types': 'off',
        '@typescript-eslint/no-empty-interface': 'off',
        '@typescript-eslint/no-empty-object-type': 'off',
        'import/no-restricted-paths': 'off',
      },
    },
    // Test files — relax noisy rules and allow `import.meta` for vitest globals.
    {
      files: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'src/test/**'],
      rules: {
        '@typescript-eslint/no-explicit-any': 'off',
        'no-restricted-syntax': 'off',
      },
    },
  ],
  ignorePatterns: [
    'dist',
    'node_modules',
    'coverage',
    '.eslintrc.cjs',
    'postcss.config.js',
    'scripts/**/*.mjs',
    // tsc -b (composite project) emits .d.ts + .tsbuildinfo siblings for the Node
    // tsconfig's includes. They're generated, not authored.
    'vite.config.d.ts',
    'vite.config.js',
    'tailwind.config.d.ts',
    'tailwind.config.js',
    '*.tsbuildinfo',
  ],
};
