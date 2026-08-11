import {
  createContext,
  useCallback,
  useContext,
  useId,
  useState,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type ReactNode,
} from 'react';
import { cn } from '@/shared/lib/cn';

type TabsContextValue = {
  value: string;
  setValue: (value: string) => void;
  /** Stable per-Tabs prefix so multiple Tabs on the same page don't collide. */
  idPrefix: string;
};

const TabsContext = createContext<TabsContextValue | null>(null);

function useTabs(): TabsContextValue {
  const ctx = useContext(TabsContext);
  if (!ctx) throw new Error('<TabList>/<TabTrigger>/<TabContent> must be inside <Tabs>.');
  return ctx;
}

type TabsProps = {
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  children: ReactNode;
  className?: string;
};

export function Tabs({ value, defaultValue, onValueChange, children, className }: TabsProps) {
  const [uncontrolled, setUncontrolled] = useState(defaultValue ?? '');
  const current = value ?? uncontrolled;
  const setValue = useCallback(
    (next: string) => {
      if (value === undefined) setUncontrolled(next);
      onValueChange?.(next);
    },
    [value, onValueChange],
  );
  const idPrefix = useId();

  return (
    <TabsContext.Provider value={{ value: current, setValue, idPrefix }}>
      <div className={className}>{children}</div>
    </TabsContext.Provider>
  );
}

type TabListProps = HTMLAttributes<HTMLDivElement>;

export function TabList({ className, children, ...rest }: TabListProps) {
  return (
    <div
      role="tablist"
      tabIndex={-1}
      className={cn('inline-flex items-center gap-1 border-b border-border-default', className)}
      onKeyDown={(event) => {
        const tabs = Array.from(
          event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]'),
        );
        if (tabs.length === 0) return;
        const currentIndex = tabs.findIndex((t) => t === document.activeElement);
        if (event.key === 'ArrowRight') {
          event.preventDefault();
          tabs[(currentIndex + 1) % tabs.length]?.focus();
        } else if (event.key === 'ArrowLeft') {
          event.preventDefault();
          tabs[(currentIndex - 1 + tabs.length) % tabs.length]?.focus();
        } else if (event.key === 'Home') {
          event.preventDefault();
          tabs[0]?.focus();
        } else if (event.key === 'End') {
          event.preventDefault();
          tabs[tabs.length - 1]?.focus();
        }
      }}
      {...rest}
    >
      {children}
    </div>
  );
}

type TabTriggerProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  value: string;
};

export function TabTrigger({ value, className, onClick, children, ...rest }: TabTriggerProps) {
  const { value: current, setValue, idPrefix } = useTabs();
  const selected = current === value;
  return (
    <button
      type="button"
      role="tab"
      id={`${idPrefix}-tab-${value}`}
      aria-selected={selected}
      aria-controls={`${idPrefix}-panel-${value}`}
      tabIndex={selected ? 0 : -1}
      onClick={(event) => {
        onClick?.(event);
        if (!event.defaultPrevented) setValue(value);
      }}
      className={cn(
        '-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors',
        selected
          ? 'border-accent text-text-primary'
          : 'border-transparent text-text-secondary hover:text-text-primary',
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}

type TabContentProps = HTMLAttributes<HTMLDivElement> & {
  value: string;
};

export function TabContent({ value, className, children, ...rest }: TabContentProps) {
  const { value: current, idPrefix } = useTabs();
  if (current !== value) return null;
  return (
    <div
      role="tabpanel"
      id={`${idPrefix}-panel-${value}`}
      aria-labelledby={`${idPrefix}-tab-${value}`}
      tabIndex={0}
      className={cn('py-3 outline-none', className)}
      {...rest}
    >
      {children}
    </div>
  );
}
