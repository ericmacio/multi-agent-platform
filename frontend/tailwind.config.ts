import type { Config } from 'tailwindcss';
import defaultTheme from 'tailwindcss/defaultTheme';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        'bg-base': 'var(--color-bg-base)',
        'bg-surface': 'var(--color-bg-surface)',
        'bg-elevated': 'var(--color-bg-elevated)',
        'bg-subtle': 'var(--color-bg-subtle)',
        'bg-ink': 'var(--color-bg-ink)',
        'bg-ink-2': 'var(--color-bg-ink-2)',
        'border-default': 'var(--color-border-default)',
        'border-strong': 'var(--color-border-strong)',
        'border-ink': 'var(--color-border-ink)',
        'border-accent': 'var(--color-border-accent)',
        'border-focus': 'var(--color-border-focus)',
        'text-primary': 'var(--color-text-primary)',
        'text-secondary': 'var(--color-text-secondary)',
        'text-muted': 'var(--color-text-muted)',
        'text-disabled': 'var(--color-text-disabled)',
        'text-on-ink': 'var(--color-text-on-ink)',
        'text-on-ink-2': 'var(--color-text-on-ink-2)',
        'text-on-ink-3': 'var(--color-text-on-ink-3)',
        accent: 'var(--color-accent)',
        'accent-hover': 'var(--color-accent-hover)',
        'accent-bg': 'var(--color-accent-bg)',
        'accent-dim': 'var(--color-accent-dim)',
        'accent-soft': 'var(--color-accent-soft)',
        success: 'var(--color-success)',
        'success-bg': 'var(--color-success-bg)',
        info: 'var(--color-info)',
        'info-bg': 'var(--color-info-bg)',
        warning: 'var(--color-warning)',
        'warning-bg': 'var(--color-warning-bg)',
        danger: 'var(--color-danger)',
        'danger-bg': 'var(--color-danger-bg)',
      },
      fontFamily: {
        sans: ['var(--font-sans)', ...defaultTheme.fontFamily.sans],
        mono: ['var(--font-mono)', ...defaultTheme.fontFamily.mono],
        voice: ['var(--font-voice)', ...defaultTheme.fontFamily.serif],
      },
      borderRadius: {
        sm: '6px',
        md: '8px',
        lg: '12px',
      },
      boxShadow: {
        sm: 'var(--shadow-sm)',
        md: 'var(--shadow-md)',
        lg: 'var(--shadow-lg)',
      },
    },
  },
  plugins: [],
} satisfies Config;
