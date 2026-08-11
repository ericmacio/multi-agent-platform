import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm, type FieldPath } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { errorCopy } from '@/shared/i18n/en';
import { Button } from '@/shared/ui/Button';
import { Card } from '@/shared/ui/Card';
import { Checkbox } from '@/shared/ui/Checkbox';
import { Input } from '@/shared/ui/Input';
import { Textarea } from '@/shared/ui/Textarea';
import { cn } from '@/shared/lib/cn';
import { useCreateAgent, useUpdateAgent } from './api';
import { McpServerPicker } from './McpServerPicker';
import { TeamPicker } from './TeamPicker';
import { ToolPicker } from './ToolPicker';
import {
  agentSchema,
  type Agent,
  type AgentRequest,
  type AgentValues,
} from './schema';

type AgentFormMode = 'create' | 'edit';

export type AgentFormProps = {
  mode: AgentFormMode;
  initial?: Agent;
  onSuccess: (agent: Agent) => void;
  onCancel: () => void;
};

function emptyDefaults(): AgentValues {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    memorySize: 12,
    llmModel: null,
    temperature: null,
    maxOutputTokens: null,
    topP: null,
    tools: [],
    enabledMcpServers: [],
    team: [],
  };
}

function defaultsFromAgent(initial: Agent): AgentValues {
  return {
    name: initial.name,
    description: initial.description,
    systemPrompt: initial.systemPrompt,
    memorySize: initial.memorySize,
    llmModel: initial.llmModel ?? null,
    temperature: initial.temperature ?? null,
    maxOutputTokens: initial.maxOutputTokens ?? null,
    topP: initial.topP ?? null,
    tools: initial.tools ?? [],
    enabledMcpServers: initial.enabledMcpServers ?? [],
    team: initial.team ?? [],
  };
}

function isUsingPlatformDefault(values: Pick<AgentValues, 'llmModel' | 'temperature' | 'maxOutputTokens' | 'topP'>): boolean {
  return (
    values.llmModel == null &&
    values.temperature == null &&
    values.maxOutputTokens == null &&
    values.topP == null
  );
}

function scrollFieldIntoView(name: string): void {
  if (typeof document === 'undefined') return;
  const el = document.querySelector(
    `[data-rhf-field="${CSS.escape(name)}"], [data-rhf-section="${CSS.escape(name)}"]`,
  );
  // jsdom may not implement scrollIntoView; guard so tests don't blow up.
  if (el && typeof (el as HTMLElement).scrollIntoView === 'function') {
    (el as HTMLElement).scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}

/**
 * Cast a server-named field to an `AgentValues` key. Falls back to undefined
 * for unknown fields (which are then surfaced via the top-of-form alert).
 */
const KNOWN_FIELDS: Record<string, FieldPath<AgentValues>> = {
  name: 'name',
  description: 'description',
  systemPrompt: 'systemPrompt',
  memorySize: 'memorySize',
  llmModel: 'llmModel',
  temperature: 'temperature',
  maxOutputTokens: 'maxOutputTokens',
  topP: 'topP',
  tools: 'tools',
  enabledMcpServers: 'enabledMcpServers',
  team: 'team',
};

export function AgentForm({ mode, initial, onSuccess, onCancel }: AgentFormProps): JSX.Element {
  const initialDefaults = useMemo(
    () => (mode === 'edit' && initial ? defaultsFromAgent(initial) : emptyDefaults()),
    [mode, initial],
  );

  const form = useForm<AgentValues>({
    resolver: zodResolver(agentSchema),
    defaultValues: initialDefaults,
  });

  const [useDefault, setUseDefault] = useState<boolean>(() => isUsingPlatformDefault(initialDefaults));
  const [topAlert, setTopAlert] = useState<string | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);

  // Rate-limit countdown.
  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      setCountdown(null);
      return;
    }
    const handle = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(handle);
  }, [countdown]);

  const createMutation = useCreateAgent();
  const updateMutation = useUpdateAgent(initial?.id ?? '');
  const isPending = mode === 'create' ? createMutation.isPending : updateMutation.isPending;
  const isRateLimited = countdown !== null && countdown > 0;

  // Track the previous error so we only react to NEW errors (not the same
  // error re-read on re-render).
  const lastErrorRef = useRef<unknown>(null);
  const currentError = (mode === 'create' ? createMutation.error : updateMutation.error) ?? null;
  useEffect(() => {
    if (currentError === lastErrorRef.current) return;
    lastErrorRef.current = currentError;
    if (currentError === null) {
      setTopAlert(null);
      return;
    }
    routeError(currentError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentError]);

  function routeError(err: NonNullable<typeof currentError>): void {
    switch (err.code) {
      case 'VALIDATION_ERROR': {
        let mapped = false;
        let firstField: string | null = null;
        for (const [field, message] of Object.entries(err.fieldErrors)) {
          const known = KNOWN_FIELDS[field];
          if (known) {
            form.setError(known, { message });
            mapped = true;
            firstField ??= known;
          }
        }
        if (!mapped) {
          setTopAlert("Some fields couldn't be saved — please contact support.");
        } else {
          setTopAlert(null);
          if (firstField) scrollFieldIntoView(firstField);
        }
        return;
      }
      case 'DUPLICATE_AGENT_NAME':
        form.setError('name', { message: errorCopy.DUPLICATE_AGENT_NAME.title });
        setTopAlert(null);
        scrollFieldIntoView('name');
        return;
      case 'NESTED_TEAM_FORBIDDEN':
        form.setError('team', { message: errorCopy.NESTED_TEAM_FORBIDDEN.title });
        setTopAlert(null);
        scrollFieldIntoView('team');
        return;
      case 'CROSS_OWNER_TEAM_MEMBER':
        form.setError('team', { message: errorCopy.CROSS_OWNER_TEAM_MEMBER.title });
        setTopAlert(null);
        scrollFieldIntoView('team');
        return;
      case 'RATE_LIMITED':
        setCountdown(err.retryAfterSeconds ?? null);
        setTopAlert(null);
        return;
      default:
        setTopAlert(errorCopy[err.code]?.title ?? errorCopy.__unknown__.title);
    }
  }

  const onSubmit = (values: AgentValues): void => {
    // Submit-time materialization of the "Use platform default" toggle.
    const body: AgentRequest = {
      ...values,
      llmModel: useDefault ? null : (values.llmModel ?? null),
      temperature: useDefault ? null : (values.temperature ?? null),
      maxOutputTokens: useDefault ? null : (values.maxOutputTokens ?? null),
      topP: useDefault ? null : (values.topP ?? null),
    };
    setTopAlert(null);
    if (mode === 'create') {
      createMutation.mutate(body, { onSuccess: (agent) => onSuccess(agent) });
    } else {
      updateMutation.mutate(body, { onSuccess: (agent) => onSuccess(agent) });
    }
  };

  const onInvalid = (errors: Record<string, unknown>): void => {
    const firstKey = Object.keys(errors)[0];
    if (firstKey) scrollFieldIntoView(firstKey);
  };

  const nameValue = form.watch('name') ?? '';
  const memorySizeValue = form.watch('memorySize') ?? 12;

  return (
    <form
      onSubmit={form.handleSubmit(onSubmit, onInvalid)}
      noValidate
      className="flex flex-col gap-6 pb-24"
      aria-busy={isPending || undefined}
    >
      {topAlert && (
        <div
          role="alert"
          className="rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger"
        >
          {topAlert}
        </div>
      )}
      {isRateLimited && (
        <div
          role="alert"
          className="rounded-md border border-warning/30 bg-warning-bg px-3 py-2 text-sm text-warning"
        >
          Too many requests. Try again in {countdown}s.
        </div>
      )}

      <Section name="identity" title="Identity">
        <div data-rhf-field="name">
          <Input
            label="Name"
            maxLength={32}
            placeholder="Researcher"
            {...form.register('name')}
            error={form.formState.errors.name?.message}
            helperText={`${nameValue.length} / 32`}
          />
        </div>
        <div data-rhf-field="description">
          <Controller
            control={form.control}
            name="description"
            render={({ field, fieldState }) => (
              <Textarea
                label="Description"
                placeholder="Short summary used by other agents during delegation."
                maxLength={1024}
                showCounter
                name={field.name}
                value={field.value ?? ''}
                onChange={field.onChange}
                onBlur={field.onBlur}
                ref={field.ref}
                error={fieldState.error?.message}
              />
            )}
          />
        </div>
      </Section>

      <Section name="behavior" title="Behavior">
        <div data-rhf-field="systemPrompt">
          <Controller
            control={form.control}
            name="systemPrompt"
            render={({ field, fieldState }) => (
              <Textarea
                label="System prompt"
                placeholder="You are a helpful research assistant…"
                maxLength={1024}
                showCounter
                name={field.name}
                value={field.value ?? ''}
                onChange={field.onChange}
                onBlur={field.onBlur}
                ref={field.ref}
                error={fieldState.error?.message}
              />
            )}
          />
        </div>
        <div data-rhf-field="memorySize" className="flex items-center gap-3">
          <Input
            type="number"
            min={1}
            max={36}
            label="Memory size"
            className="w-32"
            {...form.register('memorySize', { valueAsNumber: true })}
            error={form.formState.errors.memorySize?.message}
          />
          <p className="pt-5 text-sm text-text-secondary">
            Memory: <span className="font-mono">{memorySizeValue}</span> messages
          </p>
        </div>
      </Section>

      <Section name="model" title="Model">
        <Checkbox
          label="Use platform default"
          checked={useDefault}
          onChange={(e) => setUseDefault(e.target.checked)}
        />
        {!useDefault && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div data-rhf-field="llmModel">
              <Input
                label="Model"
                placeholder="gpt-4o-mini"
                maxLength={64}
                {...form.register('llmModel', {
                  setValueAs: (v) => (v === '' || v === null ? null : v),
                })}
                error={form.formState.errors.llmModel?.message}
              />
            </div>
            <div data-rhf-field="temperature">
              <Input
                label="Temperature"
                type="number"
                step="0.1"
                {...form.register('temperature', {
                  setValueAs: (v) => (v === '' || v === null ? null : Number(v)),
                })}
                error={form.formState.errors.temperature?.message}
              />
            </div>
            <div data-rhf-field="maxOutputTokens">
              <Input
                label="Max output tokens"
                type="number"
                min={1}
                {...form.register('maxOutputTokens', {
                  setValueAs: (v) => (v === '' || v === null ? null : Number(v)),
                })}
                error={form.formState.errors.maxOutputTokens?.message}
              />
            </div>
            <div data-rhf-field="topP">
              <Input
                label="Top-P"
                type="number"
                step="0.05"
                {...form.register('topP', {
                  setValueAs: (v) => (v === '' || v === null ? null : Number(v)),
                })}
                error={form.formState.errors.topP?.message}
              />
            </div>
          </div>
        )}
      </Section>

      <Section name="tools" title="Tools">
        <div data-rhf-field="tools">
          <Controller
            control={form.control}
            name="tools"
            render={({ field }) => (
              <ToolPicker value={field.value} onChange={field.onChange} disabled={isPending} />
            )}
          />
          {form.formState.errors.tools?.message && (
            <p className="mt-1 text-xs text-danger">{form.formState.errors.tools.message}</p>
          )}
        </div>
      </Section>

      <Section name="enabledMcpServers" title="MCP servers">
        <div data-rhf-field="enabledMcpServers">
          <Controller
            control={form.control}
            name="enabledMcpServers"
            render={({ field }) => (
              <McpServerPicker
                value={field.value}
                onChange={field.onChange}
                disabled={isPending}
              />
            )}
          />
          {form.formState.errors.enabledMcpServers?.message && (
            <p className="mt-1 text-xs text-danger">
              {form.formState.errors.enabledMcpServers.message}
            </p>
          )}
        </div>
      </Section>

      <Section name="team" title="Team">
        <div data-rhf-field="team">
          <Controller
            control={form.control}
            name="team"
            render={({ field }) => (
              <TeamPicker
                value={field.value}
                onChange={field.onChange}
                excludeAgentId={initial?.id}
                disabled={isPending}
              />
            )}
          />
          {form.formState.errors.team?.message && (
            <p
              role="alert"
              className="mt-1 rounded-md border border-danger/30 bg-danger-bg px-3 py-2 text-sm text-danger"
            >
              {form.formState.errors.team.message}
            </p>
          )}
        </div>
      </Section>

      {/* Sticky action bar */}
      <div
        className={cn(
          'sticky bottom-0 z-10 -mx-6 flex items-center justify-end gap-3 border-t border-border-default bg-bg-base/95 px-6 py-3 backdrop-blur',
        )}
      >
        <Button type="button" variant="secondary" onClick={onCancel} disabled={isPending}>
          Cancel
        </Button>
        <Button type="submit" loading={isPending} disabled={isPending || isRateLimited}>
          {mode === 'create' ? 'Create agent' : 'Save'}
        </Button>
      </div>

    </form>
  );
}

function Section({
  name,
  title,
  children,
}: {
  name: string;
  title: string;
  children: React.ReactNode;
}): JSX.Element {
  return (
    <section data-rhf-section={name} className="flex flex-col gap-3">
      <Card padding="md" className="flex flex-col gap-4">
        <header>
          <h2 className="text-base font-medium text-text-primary">{title}</h2>
        </header>
        {children}
      </Card>
    </section>
  );
}
