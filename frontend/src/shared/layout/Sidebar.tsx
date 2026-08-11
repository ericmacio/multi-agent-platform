import { useEffect, useState, type ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { env } from '@/env';
import { useAuth } from '@/shared/auth/AuthContext';
import {
  Bot,
  ChevronDown,
  ChevronRight,
  Key,
  LayoutDashboard,
  MessageSquare,
  Server,
  Settings,
  Users,
  Wrench,
  type LucideIcon,
} from '@/shared/ui/icons';
import { cn } from '@/shared/lib/cn';

// `end` matches the URL exactly — used for `/` so it doesn't stay active on
// every child route (NavLink v6 prefix-matches by default).
type NavEntry = { to: string; label: string; icon: LucideIcon; end?: boolean };

const STANDARD_NAV: NavEntry[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/agents', label: 'Agents', icon: Bot },
  { to: '/chat', label: 'Chat', icon: MessageSquare },
  { to: '/tools', label: 'Tools', icon: Wrench },
  { to: '/mcp-servers', label: 'MCP Servers', icon: Server },
];

const ADMIN_NAV: NavEntry[] = [
  { to: '/admin/users', label: 'Users', icon: Users },
  { to: '/admin/api-keys', label: 'API Keys', icon: Key },
  { to: '/admin/rate-limit', label: 'Rate Limit', icon: Settings },
];

const ADMIN_DISCLOSURE_KEY = 'mam.sidebar.admin.open';

function NavRow({ entry }: { entry: NavEntry }): JSX.Element {
  const Icon = entry.icon;
  return (
    <NavLink
      to={entry.to}
      end={entry.end}
      className={({ isActive }) =>
        cn(
          'flex items-center gap-2 rounded-md border-l-2 px-3 py-2 text-sm transition-colors',
          isActive
            ? 'border-accent bg-accent-soft font-medium text-text-on-ink'
            : 'border-transparent text-text-on-ink-2 hover:bg-bg-ink-2 hover:text-text-on-ink',
        )
      }
    >
      <Icon aria-hidden width={16} height={16} />
      <span>{entry.label}</span>
    </NavLink>
  );
}

function NavGroup({ label, children }: { label: string; children: ReactNode }): JSX.Element {
  return (
    <div className="flex flex-col gap-1">
      <h3 className="px-3 pt-2 text-xs font-medium uppercase tracking-wider text-text-on-ink-3">
        {label}
      </h3>
      <div className="flex flex-col gap-0.5">{children}</div>
    </div>
  );
}

export function Sidebar(): JSX.Element {
  const { principal } = useAuth();
  const isAdmin = principal?.role === 'ADMIN';

  const [adminOpen, setAdminOpen] = useState<boolean>(() => {
    try {
      return localStorage.getItem(ADMIN_DISCLOSURE_KEY) !== 'closed';
    } catch {
      return true;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(ADMIN_DISCLOSURE_KEY, adminOpen ? 'open' : 'closed');
    } catch {
      // ignore quota / privacy-mode failures
    }
  }, [adminOpen]);

  return (
    <aside
      aria-label="Primary navigation"
      className="flex h-full flex-col gap-3 border-r border-border-ink bg-bg-ink px-2 py-3"
    >
      <div className="px-3 py-1">
        <p className="text-xs uppercase tracking-wider text-text-on-ink-3">Multi-Agent</p>
        <p className="font-voice text-base font-medium text-accent-dim">{env.VITE_APP_NAME}</p>
      </div>

      <NavGroup label="Workspace">
        {STANDARD_NAV.map((entry) => (
          <NavRow key={entry.to} entry={entry} />
        ))}
      </NavGroup>

      {isAdmin && (
        <div data-testid="sidebar-admin-group" className="flex flex-col gap-1">
          <button
            type="button"
            aria-expanded={adminOpen}
            onClick={() => setAdminOpen((v) => !v)}
            className="flex items-center gap-1 px-3 pt-2 text-left text-xs font-medium uppercase tracking-wider text-text-on-ink-3 hover:text-text-on-ink-2"
          >
            {adminOpen ? (
              <ChevronDown aria-hidden width={12} height={12} />
            ) : (
              <ChevronRight aria-hidden width={12} height={12} />
            )}
            Admin
          </button>
          {adminOpen && (
            <div className="flex flex-col gap-0.5">
              {ADMIN_NAV.map((entry) => (
                <NavRow key={entry.to} entry={entry} />
              ))}
            </div>
          )}
        </div>
      )}
    </aside>
  );
}
