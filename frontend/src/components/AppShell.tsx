import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import logo from '../logo.png';
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
import { drill } from '../api/drill';
import { CasualNoteDialog } from './CasualNoteDialog';
import './AppShell.css';

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: '/', label: '首页', icon: LayoutDashboard, end: true },
  { to: '/capture', label: '对话沉淀', icon: MessagesSquare },
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
  const [pendingReview, setPendingReview] = useState(0);
  const [showCasualNote, setShowCasualNote] = useState(false);

  // 主进程在窗口隐藏后仍负责定时通知；渲染层只需周期性同步今天还剩多少学习/复习任务。
  useEffect(() => {
    let alive = true;
    const sync = () => drill.today().then((tasks) => {
      if (!alive) return;
      const active = tasks.filter((t) => t.status !== 'DONE' && t.status !== 'SKIPPED');
      const review = active.filter((t) => t.kind === 'REVIEW').length;
      setPendingReview(review);
      return window.electronAPI?.updateReminderTasks({
        learn: active.filter((t) => t.kind === 'NEW').length,
        review,
      });
    }).catch(() => {});
    sync();
    const timer = window.setInterval(sync, 10 * 60 * 1000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [userId]);

  const onLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="app-frame">
      <aside className="sidebar">
        <div className="brand">
          <img src={logo} alt="面霸" className="brand-logo" />
          <span className="brand-text">
            <strong>面霸</strong>
            <small>面试备考</small>
          </span>
        </div>

        <nav className="nav">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
            >
              <item.icon size={18} strokeWidth={1.6} />
              <span>{item.label}</span>
              {item.to === '/drill' && pendingReview > 0 && (
                <span className="nav-review-badge" title={`有 ${pendingReview} 项复习任务待完成`}>
                  {pendingReview}
                </span>
              )}
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

      {/* 全局随手记：右下角悬浮入口，点击直接打开（不绑定题目/知识点） */}
      <button
        className="casual-note-fab"
        onClick={() => setShowCasualNote(true)}
        title="随手记"
        aria-label="打开随手记"
      >
        <NotebookPen size={20} strokeWidth={1.8} />
      </button>

      {showCasualNote && (
        <CasualNoteDialog onClose={() => setShowCasualNote(false)} />
      )}
    </div>
  );
}
