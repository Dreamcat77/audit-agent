import type { ReactNode } from "react";
import { LogOut, Scale } from "lucide-react";

export interface PortalNavigationItem<PageId extends string = string> {
  id: PageId;
  label: string;
  icon: ReactNode;
}

export interface PortalShellProps<PageId extends string = string> {
  items: readonly PortalNavigationItem<PageId>[];
  activePage: PageId;
  onNavigate: (page: PageId) => void;
  user: { username?: string | null; realName?: string | null } | null | undefined;
  roleLabel: string;
  onLogout: () => void;
  logoutLoading?: boolean;
  logoutError?: string | null;
  notificationSlot?: ReactNode;
  children: ReactNode;
}

export function PortalShell<PageId extends string>({
  items,
  activePage,
  onNavigate,
  user,
  roleLabel,
  onLogout,
  logoutLoading = false,
  logoutError,
  notificationSlot,
  children,
}: PortalShellProps<PageId>) {
  const displayName = user?.realName || user?.username || "已登录用户";
  return (
    <div className="contract-light contract-shell-light delivery-visual-v2 portal-shell h-screen w-screen overflow-hidden bg-stone-50">
      <header className="portal-shell-header">
        <div className="portal-shell-header-row">
          <div className="portal-brand" aria-label="采购内审智能辅助系统">
            <span className="portal-brand-mark"><Scale size={18}/></span>
            <span><strong>采购内审</strong><small>智能辅助系统</small></span>
          </div>
          <nav className="portal-primary-nav" aria-label="业务工具">
            {items.map((item) => (
              <button key={item.id} type="button" data-active={activePage === item.id}
                aria-current={activePage === item.id ? "page" : undefined}
                onClick={() => onNavigate(item.id)}>
                {item.icon}<span>{item.label}</span>
              </button>
            ))}
          </nav>
          <div className="portal-user-actions">
            {notificationSlot}
            <div className="portal-user-copy" title={user?.username ?? undefined}>
              <strong>{displayName}</strong><small>{roleLabel || "已登录用户"}</small>
            </div>
            <button type="button" onClick={onLogout} disabled={logoutLoading}
              className="portal-icon-action" aria-label="退出登录" title="退出登录">
              <LogOut size={16}/>
            </button>
            {logoutError && <span role="alert" className="portal-logout-error" title={logoutError}>退出失败</span>}
          </div>
        </div>
      </header>
      <main className="portal-main">{children}</main>
    </div>
  );
}
