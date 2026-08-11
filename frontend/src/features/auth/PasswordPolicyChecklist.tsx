import { evaluatePasswordPolicy } from './password';
import { Check } from '@/shared/ui/icons';
import { cn } from '@/shared/lib/cn';

type PasswordPolicyChecklistProps = {
  value: string;
  /** Optional accessible label; defaults to "Password requirements". */
  ariaLabel?: string;
};

/**
 * Live per-rule policy checklist used by both `ChangePasswordForm` (US-03-004)
 * and `UserForm` (US-08-002). Reads from `evaluatePasswordPolicy` so the three
 * rules render in a stable order and never reorder as the user types.
 */
export function PasswordPolicyChecklist({
  value,
  ariaLabel = 'Password requirements',
}: PasswordPolicyChecklistProps): JSX.Element {
  const rules = evaluatePasswordPolicy(value);
  return (
    <ul aria-label={ariaLabel} className="flex flex-col gap-1 pl-1">
      {rules.map((rule) => (
        <li
          key={rule.key}
          data-testid={`rule-${rule.key}`}
          className={cn(
            'flex items-center gap-2 text-xs',
            rule.valid ? 'text-success' : 'text-text-muted',
          )}
        >
          <span
            className={cn(
              'inline-flex h-3.5 w-3.5 items-center justify-center rounded-full border',
              rule.valid ? 'border-success bg-success/15 text-success' : 'border-border-default',
            )}
            aria-hidden
          >
            {rule.valid && <Check width={10} height={10} />}
          </span>
          {rule.label}
        </li>
      ))}
    </ul>
  );
}
