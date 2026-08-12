import type { ReactNode } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import {
  LayoutDashboard,
  Compass,
  PenLine,
  MessagesSquare,
  Network,
  NotebookPen,
  History,
  Settings,
  LogOut,
  type LucideIcon,
} from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import './AppShell.css';

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: '/', label: '首页', icon: LayoutDashboard, end: true },
  { to: '/plan', label: '学习计划', icon: Compass },
  { to: '/drill', label: '练习', icon: PenLine },
  { to: '/rehearsal', label: '模拟面试', icon: MessagesSquare },
  { to: '/profile', label: '掌握画像', icon: Network },
  { to: '/notes', label: '内化复盘', icon: NotebookPen },
  { to: '/history', label: '问答记录', icon: History },
  { to: '/settings', label: '设置', icon: Settings },
];

export function AppShell({ children }: { children: ReactNode }) {
  const { userId, logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="app-frame">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">面</span>
          <span className="brand-text">
            <strong>面霸</strong>
            <small>面试备考</small>
          </span>
        </div>

        <nav className="nav">
          {NAV.map((item, i) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
            >
              <item.icon size={18} strokeWidth={1.6} />
              <span>{item.label}</span>
              {i === 0 && <span className="nav-dot" aria-hidden />}
            </NavLink>
          ))}
        </nav>

        <div className="side-foot">
          <div className="user-chip">
            <span className="user-id">面霸 #{userId}</span>
            <button className="logout" onClick={onLogout} title="退出">
              <LogOut size={16} strokeWidth={1.6} />
            </button>
          </div>
        </div>
      </aside>

      <main className="main">{children}</main>
    </div>
  );
}
