import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Checkbox } from '@/shared/ui/Checkbox';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Input } from '@/shared/ui/Input';
import { Select } from '@/shared/ui/Select';
import { Skeleton } from '@/shared/ui/Skeleton';
import { Spinner } from '@/shared/ui/Spinner';
import { Textarea } from '@/shared/ui/Textarea';
import { Bot, Plus } from '@/shared/ui/icons';

/**
 * DEV-only kitchen sink for visual review of every atom. Reachable in the
 * Vite dev server only (the early return below removes it from any production
 * bundle).
 */
export function DesignSystemPreview(): JSX.Element | null {
  if (import.meta.env.PROD) return null;
  return (
    <main className="mx-auto flex max-w-5xl flex-col gap-8 p-8">
      <h1 className="text-2xl font-medium">Design system — atoms</h1>

      <section className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Button>Primary</Button>
        <Button variant="secondary">Secondary</Button>
        <Button variant="ghost">Ghost</Button>
        <Button variant="danger">Danger</Button>
        <Button loading>Saving…</Button>
        <Button disabled>Disabled</Button>
        <Button leftIcon={<Plus aria-hidden width={16} height={16} />}>New</Button>
        <Button size="sm">Small</Button>
      </section>

      <section className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Input label="Email" placeholder="alice@example.com" />
        <Input label="Bad" error="must be a valid email" defaultValue="oops" />
        <Select label="Role" defaultValue="STANDARD">
          <option value="STANDARD">STANDARD</option>
          <option value="ADMIN">ADMIN</option>
        </Select>
        <Textarea label="Notes" maxLength={200} showCounter placeholder="Up to 200 chars…" />
      </section>

      <section className="flex flex-wrap gap-3">
        <Checkbox label="Accept" defaultChecked />
        <Checkbox label="Maybe" indeterminate />
        <Checkbox label="Disabled" disabled />
      </section>

      <section className="flex flex-wrap items-center gap-2">
        <Badge>neutral</Badge>
        <Badge variant="accent">accent</Badge>
        <Badge variant="success">success</Badge>
        <Badge variant="info">info</Badge>
        <Badge variant="warning">warning</Badge>
        <Badge variant="danger">danger</Badge>
      </section>

      <section className="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Card>
          <p className="text-sm">A plain card.</p>
        </Card>
        <Card accent>
          <p className="text-sm">An accent card (featured surface).</p>
        </Card>
      </section>

      <section className="flex items-center gap-4">
        <Spinner size={16} />
        <Spinner size={20} />
        <Spinner size={24} />
        <Skeleton width={200} height={20} />
        <Skeleton width={120} height={20} />
      </section>

      <section>
        <EmptyState
          icon={<Bot aria-hidden />}
          title="No agents yet"
          description="Create your first agent to start a conversation."
          action={
            <Button leftIcon={<Plus aria-hidden width={16} height={16} />}>Create agent</Button>
          }
        />
      </section>
    </main>
  );
}
