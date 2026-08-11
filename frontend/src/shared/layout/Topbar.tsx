import { useNavigate } from 'react-router-dom';
import { useLogout } from '@/features/auth/api';
import { useAuth } from '@/shared/auth/AuthContext';
import { Dropdown, DropdownContent, DropdownItem, DropdownTrigger } from '@/shared/ui/Dropdown';
import { LogOut, Settings, User } from '@/shared/ui/icons';

function initialsOf(email: string | undefined): string {
  if (!email) return '?';
  const local = email.split('@')[0] ?? '';
  return (local[0] ?? '?').toUpperCase();
}

export function Topbar(): JSX.Element {
  const { principal } = useAuth();
  const logout = useLogout();
  const navigate = useNavigate();

  return (
    <header className="flex h-14 items-center justify-between border-b border-border-default bg-bg-base px-4">
      <nav
        aria-label="Breadcrumb"
        data-testid="topbar-breadcrumb"
        className="text-sm text-text-muted"
      >
        {/* Real breadcrumb registration via `useBreadcrumb` lands in EPIC-11. */}
      </nav>
      <Dropdown>
        <DropdownTrigger
          aria-label="Profile menu"
          className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-accent-bg text-sm font-medium text-accent hover:bg-bg-elevated"
        >
          {initialsOf(principal?.sub)}
        </DropdownTrigger>
        <DropdownContent align="end" className="min-w-[220px]">
          <div className="border-b border-border-default px-3 py-2 text-xs text-text-muted">
            <p className="flex items-center gap-2">
              <User aria-hidden width={12} height={12} />
              <span className="truncate font-mono">{principal?.sub ?? 'unauthenticated'}</span>
            </p>
          </div>
          <DropdownItem onClick={() => navigate('/change-password')}>
            <Settings aria-hidden width={14} height={14} />
            <span>Change password</span>
          </DropdownItem>
          <DropdownItem onClick={() => logout.mutate()}>
            <LogOut aria-hidden width={14} height={14} />
            <span>Sign out</span>
          </DropdownItem>
        </DropdownContent>
      </Dropdown>
    </header>
  );
}
