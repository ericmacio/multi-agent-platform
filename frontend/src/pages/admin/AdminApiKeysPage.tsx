import { useState } from 'react';
import { ApiKeyList } from '@/features/admin-api-keys/ApiKeyList';
import { CreateApiKeyDialog } from '@/features/admin-api-keys/CreateApiKeyDialog';
import { useApiKeys, useUpdateApiKey } from '@/features/admin-api-keys/api';
import type { ApiKey } from '@/features/admin-api-keys/schema';
import { errorCopy } from '@/shared/i18n/en';
import { flattenPages } from '@/shared/lib/pagination';
import { Button } from '@/shared/ui/Button';
import { EmptyState } from '@/shared/ui/EmptyState';
import { Plus } from '@/shared/ui/icons';
import { toast } from '@/shared/ui/Toast';
import { showRateLimitedToast } from '@/shared/ui/toastPolicy';

/**
 * Sub-component so `useUpdateApiKey` — which needs a `clientId` at hook time
 * — can be scoped to each pending toggle without violating the Rules of Hooks
 * at the page level.
 */
function ToggleRunner({
  target,
  onDone,
}: {
  target: ApiKey;
  onDone: () => void;
}): null {
  const mutation = useUpdateApiKey(target.clientId);
  const [fired, setFired] = useState(false);
  if (!fired) {
    setFired(true);
    mutation.mutate(
      { disabled: !target.disabled },
      {
        onSuccess: () => {
          toast.success(target.disabled ? 'API key re-enabled.' : 'API key revoked.');
          onDone();
        },
        onError: (err) => {
          if (err.code === 'RATE_LIMITED') {
            showRateLimitedToast(err.retryAfterSeconds);
          } else {
            toast.error(errorCopy[err.code]?.title ?? errorCopy.__unknown__.title);
          }
          onDone();
        },
      },
    );
  }
  return null;
}

export default function AdminApiKeysPage(): JSX.Element {
  const query = useApiKeys();
  const keys = flattenPages(query.data);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [pendingToggle, setPendingToggle] = useState<ApiKey | null>(null);

  const showEmpty = query.isSuccess && keys.length === 0;

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-6 py-6">
      <header className="flex items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-medium text-text-primary">API Keys</h1>
          <p className="text-sm text-text-secondary">
            Mint machine-to-machine API keys for external systems.
          </p>
        </div>
        <Button
          leftIcon={<Plus aria-hidden width={16} height={16} />}
          onClick={() => setDialogOpen(true)}
        >
          Create API key
        </Button>
      </header>

      {showEmpty ? (
        <EmptyState
          title="No API keys yet"
          description="Create an API key to give an external system machine-to-machine access."
          action={<Button onClick={() => setDialogOpen(true)}>Create API key</Button>}
        />
      ) : (
        <ApiKeyList onToggleDisabled={(key) => setPendingToggle(key)} />
      )}

      <CreateApiKeyDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreated={() => {
          toast.success('API key created.');
        }}
      />

      {pendingToggle && (
        <ToggleRunner target={pendingToggle} onDone={() => setPendingToggle(null)} />
      )}
    </div>
  );
}
