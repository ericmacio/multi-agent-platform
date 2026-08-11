import { expect } from 'vitest';
import { axe, type AxeCore } from 'vitest-axe';

/**
 * Baseline axe configuration for the app. Two rules are disabled because
 * jsdom can't evaluate them faithfully:
 *
 * - `color-contrast` — computes contrast via a canvas that jsdom doesn't
 *   implement, so the rule can't run in unit tests (it warns and skips).
 *   Contrast is verified manually in `frontend/docs/A11Y.md`.
 * - `region` — flags content outside a `<main>`/`<nav>` landmark. Our
 *   primitive-level tests render fragments (a button in isolation) that
 *   are never inside a landmark by construction; the surrounding shell is
 *   what provides the landmark, and shell-level tests cover it.
 */
const DISABLED_RULES: AxeCore.RunOptions = {
  rules: {
    'color-contrast': { enabled: false },
    region: { enabled: false },
  },
};

/**
 * Runs axe against the given container and asserts violations is empty.
 *
 * Usage:
 * ```ts
 * const { container } = render(<Button>OK</Button>);
 * await expectNoA11yViolations(container);
 * ```
 *
 * We wrap the underlying `vitest-axe` API in a single helper so a future
 * swap (e.g., to a11y-testing-library or a different rule set) is a one-file
 * change rather than touching every test.
 */
export async function expectNoA11yViolations(
  container: Element,
  options?: AxeCore.RunOptions,
): Promise<void> {
  const results = await axe(container, { ...DISABLED_RULES, ...options });
  if (results.violations.length === 0) return;

  const rendered = results.violations
    .map((v) => {
      const selectors = v.nodes.map((n) => n.target.join(', ')).join('\n    ');
      return `- ${v.id} (${v.impact ?? 'unknown'}): ${v.help}\n    ${selectors}`;
    })
    .join('\n');
  expect.fail(`Expected no accessibility violations. Found:\n${rendered}`);
}
