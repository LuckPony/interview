import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import logo from '../logo.png';
import {
  BrainCircuit,
  BriefcaseBusiness,
  ChevronDown,
  ChevronRight,
  ClipboardList,
  Compass,
  Database,
  FileStack,
  FolderUp,
  GraduationCap,
  History,
  Home,
  LogOut,
  MessagesSquare,
  Network,
  NotebookPen,
  PenLine,
  Settings,
  Sparkles,
  UserRound,
  type LucideIcon,
} from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { drill } from '../api/drill';
import { CasualNoteDialog } from './CasualNoteDialog';
import './AppShell.css';

interface NavItem {
  to: string;
  label: string;
  description: string;
  icon: LucideIcon;
  exact?: boolean;
}

interface NavGroup {
  id: 'learning' | 'interview' | 'knowledge';
  label: string;
  description: string;
  icon: LucideIcon;
  items: NavItem[];
}

const PRIMARY_NAV: NavItem[] = [
  { to: '/', label: '首页', description: '今日学习总览', icon: Home, exact: true },
  { to: '/capture', label: '对话沉淀', description: '随问随存知识卡片', icon: MessagesSquare },
];

const NAV_GROUPS: NavGroup[] = [
  {
    id: 'learning',
    label: '系统学习',
    description: '规划、练习与掌握进度',
    icon: GraduationCap,
    items: [
      { to: '/plan', label: '学习计划', description: '规划你的学习路径', icon: Compass },
      { to: '/drill', label: '练习', description: '针对性刷题训练', icon: PenLine },
      { to: '/profile', label: '掌握画像', description: '查看知识掌握程度', icon: Network },
      { to: '/history', label: '问答记录', description: '回顾练习与回答', icon: History },
    ],
  },
  {
    id: 'interview',
    label: '面试准备',
    description: '从简历到实战复盘',
    icon: BriefcaseBusiness,
    items: [
      { to: '/resumes', label: '简历管理', description: '管理简历，查看 AI 分析', icon: FileStack },
      { to: '/rehearsal', label: '模拟面试', description: '文字或语音面试练习', icon: Sparkles, exact: true },
      { to: '/rehearsal/history', label: '面试记录', description: '查看历次面试结果', icon: ClipboardList },
    ],
  },
];

const REVIEW_NAV: NavItem[] = [
  { to: '/notes', label: '内化复盘', description: '整理笔记，巩固理解', icon: BrainCircuit },
];

const KNOWLEDGE_GROUP: NavGroup = {
  id: 'knowledge',
  label: '知识管理',
  description: '沉淀、导入与整理资料',
  icon: Database,
  items: [
    { to: '/knowledge-base', label: '知识库管理', description: '查看资料与提取知识点', icon: Database, exact: true },
    { to: '/knowledge-base/import', label: '资料导入', description: '上传新的学习资料', icon: FolderUp, exact: true },
  ],
};

const ALL_NAV_GROUPS = [...NAV_GROUPS, KNOWLEDGE_GROUP];

const ACCOUNT_NAV: NavItem[] = [
  { to: '/account', label: '个人中心', description: '完善你的个人信息', icon: UserRound },
  { to: '/settings', label: '设置', description: '模型、外观与偏好', icon: Settings },
];

function fallbackUsername(userId: string | null): string {
  if (!userId) return '霸仔';
  const storageKey = `yan.fallback-username.${userId}`;
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved && /^霸仔[a-z0-9]{3}$/.test(saved)) return saved;

    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    const random = new Uint32Array(3);
    crypto.getRandomValues(random);
    const suffix = [...random].map(value => chars[value % chars.length]).join('');
    const generated = `霸仔${suffix}`;
    localStorage.setItem(storageKey, generated);
    return generated;
  } catch {
    return `霸仔${String(userId).padStart(3, '0').slice(-3)}`;
  }
}

function pathMatches(pathname: string, item: NavItem): boolean {
  if (item.exact || item.to === '/') return pathname === item.to;
  return pathname === item.to || pathname.startsWith(`${item.to}/`);
}

function NavItemLink({
  item,
  pathname,
  pendingReview,
  nested = false,
}: {
  item: NavItem;
  pathname: string;
  pendingReview: number;
  nested?: boolean;
}) {
  const active = pathMatches(pathname, item);
  const Icon = item.icon;

  return (
    <NavLink
      to={item.to}
      end={item.exact}
      className={`nav-item${nested ? ' is-nested' : ''}${active ? ' active' : ''}`}
      aria-current={active ? 'page' : undefined}
    >
      <span className="nav-icon" aria-hidden>
        <Icon size={19} strokeWidth={1.75} />
      </span>
      <span className="nav-copy">
        <strong>{item.label}</strong>
        <small>{item.description}</small>
      </span>
      {item.to === '/drill' && pendingReview > 0 ? (
        <span className="nav-review-badge" title={`有 ${pendingReview} 项复习任务待完成`}>
          {pendingReview}
        </span>
      ) : active && item.to !== '/capture' ? (
        <ChevronRight className="nav-active-arrow" size={16} strokeWidth={1.9} aria-hidden />
      ) : null}
    </NavLink>
  );
}

function NavGroupBlock({
  group,
  pathname,
  pendingReview,
  expanded,
  onToggle,
}: {
  group: NavGroup;
  pathname: string;
  pendingReview: number;
  expanded: boolean;
  onToggle: () => void;
}) {
  const groupActive = group.items.some(item => pathMatches(pathname, item));
  const GroupIcon = group.icon;

  return (
    <section className={`nav-group${expanded ? ' expanded' : ''}`}>
      <button
        type="button"
        className={`nav-group-trigger${groupActive ? ' has-active-child' : ''}`}
        onClick={onToggle}
        aria-expanded={expanded}
        aria-controls={`nav-group-${group.id}`}
      >
        <span className="nav-icon nav-group-icon" aria-hidden>
          <GroupIcon size={19} strokeWidth={1.75} />
        </span>
        <span className="nav-copy">
          <strong>{group.label}</strong>
          <small>{group.description}</small>
        </span>
        <ChevronDown className="nav-group-chevron" size={17} strokeWidth={1.9} aria-hidden />
      </button>
      <div className="nav-group-collapse" id={`nav-group-${group.id}`}>
        <div className="nav-group-items">
          {group.items.map(item => (
            <NavItemLink
              key={item.to}
              item={item}
              pathname={pathname}
              pendingReview={pendingReview}
              nested
            />
          ))}
        </div>
      </div>
    </section>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const { userId, profile, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [pendingReview, setPendingReview] = useState(0);
  const [showCasualNote, setShowCasualNote] = useState(false);
  const guestUsername = useMemo(() => fallbackUsername(userId), [userId]);
  const displayUsername = profile?.username?.trim() || guestUsername;
  const [expandedGroups, setExpandedGroups] = useState<Set<NavGroup['id']>>(() => {
    const active = ALL_NAV_GROUPS.find(group => group.items.some(item => pathMatches(location.pathname, item)));
    return active ? new Set([active.id]) : new Set();
  });

  // 从页面内链接进入某个模块时自动展开所属分组，避免当前页面在侧栏里不可见。
  useEffect(() => {
    const active = ALL_NAV_GROUPS.find(group => group.items.some(item => pathMatches(location.pathname, item)));
    if (!active) return;
    setExpandedGroups(current => {
      if (current.has(active.id)) return current;
      return new Set(current).add(active.id);
    });
  }, [location.pathname]);

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

  const toggleGroup = (id: NavGroup['id']) => {
    setExpandedGroups(current => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const onLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="app-frame">
      <aside className="sidebar">
        <NavLink to="/" className="brand" aria-label="返回首页">
          <span className="brand-logo-wrap">
            <img src={logo} alt="" className="brand-logo" />
          </span>
          <span className="brand-text">
            <strong>面霸</strong>
            <small>AI 面试学习助手</small>
          </span>
        </NavLink>

        <nav className="nav" aria-label="主导航">
          <div className="nav-section">
            {PRIMARY_NAV.map(item => (
              <NavItemLink
                key={item.to}
                item={item}
                pathname={location.pathname}
                pendingReview={pendingReview}
              />
            ))}
          </div>

          {NAV_GROUPS.map(group => (
            <NavGroupBlock
              key={group.id}
              group={group}
              pathname={location.pathname}
              pendingReview={pendingReview}
              expanded={expandedGroups.has(group.id)}
              onToggle={() => toggleGroup(group.id)}
            />
          ))}

          <div className="nav-section">
            {REVIEW_NAV.map(item => (
              <NavItemLink
                key={item.to}
                item={item}
                pathname={location.pathname}
                pendingReview={pendingReview}
              />
            ))}
          </div>

          <div className="nav-knowledge-block">
            <NavGroupBlock
              group={KNOWLEDGE_GROUP}
              pathname={location.pathname}
              pendingReview={pendingReview}
              expanded={expandedGroups.has(KNOWLEDGE_GROUP.id)}
              onToggle={() => toggleGroup(KNOWLEDGE_GROUP.id)}
            />
          </div>

          <div className="nav-section nav-section-secondary">
            {ACCOUNT_NAV.map(item => (
              <NavItemLink
                key={item.to}
                item={item}
                pathname={location.pathname}
                pendingReview={pendingReview}
              />
            ))}
          </div>
        </nav>

        <div className="side-foot">
          <div className="user-chip">
            <NavLink to="/account" className="user-profile-link" title="打开个人中心">
              <span className="user-avatar" aria-hidden>{displayUsername.slice(0, 1)}</span>
              <span className="user-copy">
                <small>尊敬的</small>
                <strong>{displayUsername}</strong>
              </span>
            </NavLink>
            <button className="logout" onClick={onLogout} title="退出登录" aria-label="退出登录">
              <LogOut size={16} strokeWidth={1.7} />
            </button>
          </div>
        </div>
      </aside>

      <main className="main">{children}</main>

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
