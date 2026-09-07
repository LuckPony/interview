import { useEffect, useId, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  CalendarDays,
  Check,
  CheckCheck,
  ChevronRight,
  CircleCheck,
  Compass,
  FileCheck2,
  GraduationCap,
  Layers3,
  LayoutDashboard,
  MessagesSquare,
  NotebookPen,
  Plus,
  RefreshCw,
  RotateCcw,
  Sparkles,
  type LucideIcon,
} from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { drill, studyPlan } from '../api/drill';
import { knowledgeApi } from '../api/knowledge';
import { interviewApi, type InterviewListItem } from '../api/interview';
import { PlanSwitcher } from '../components/PlanSwitcher';
import { useActivePlan } from '../lib/useActivePlan';
import { fallbackUsername } from '../lib/userDisplay';
import type { DebtView, PlanView, DailyTaskView, KnowledgeCard } from '../api/types';
import './Dashboard.css';

interface DashboardData {
  plans: PlanView[] | null;
  today: DailyTaskView[] | null;
  cards: KnowledgeCard[] | null;
  due: KnowledgeCard[] | null;
  interviews: InterviewListItem[] | null;
  debt: DebtView[] | null;
}
const INITIAL_DATA: DashboardData = {
  plans: null,
  today: null,
  cards: null,
  due: null,
  interviews: null,
  debt: null,
};
const EMPTY_PLANS: PlanView[] = [];
const countFormat = new Intl.NumberFormat('zh-CN');
const displayCount = (value: number | null) => (value === null ? '—' : countFormat.format(value));
const percent = (part: number, total: number) =>
  total > 0 ? Math.min(100, Math.round((part / total) * 100)) : 0;
const dateKey = (date: Date) => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;

/** 按用户本地日期统计真实创建记录；空日期补零，不使用示例曲线。 */
function dailyCounts(dates: string[], days: number, now: Date) {
  const counts = new Map<string, number>();
  dates.forEach((value) => {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return;
    const key = dateKey(date);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  });
  return Array.from({ length: days }, (_, index) => {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - days + index + 1);
    return { label: `${date.getMonth() + 1}/${date.getDate()}`, value: counts.get(dateKey(date)) ?? 0 };
  });
}
function valueOf<T>(result: PromiseSettledResult<T>): T | null {
  return result.status === 'fulfilled' ? result.value : null;
}

function MiniChart({ values, bars = false }: { values: number[]; bars?: boolean }) {
  const max = Math.max(1, ...values);
  const points = values.map(
    (value, i) => `${(i * 180) / Math.max(1, values.length - 1)},${57 - (value / max) * 44}`,
  );
  return (
    <svg className="dashboard-mini-chart" viewBox="0 0 180 64" aria-hidden="true" preserveAspectRatio="none">
      {bars ? (
        values.map((value, i) => (
          <rect
            key={i}
            x={(i * 180) / values.length + 3}
            y={58 - (value / max) * 46}
            width={180 / values.length - 8}
            height={Math.max(2, (value / max) * 46)}
            rx="3"
            fill="currentColor"
            opacity={0.4 + (i / values.length) * 0.5}
          />
        ))
      ) : (
        <>
          <polygon points={`0,64 ${points.join(' ')} 180,64`} fill="currentColor" opacity="0.13" />
          <polyline
            points={points.join(' ')}
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinejoin="round"
          />
          {values.map(
            (value, i) =>
              value > 0 && (
                <circle
                  key={i}
                  cx={(i * 180) / Math.max(1, values.length - 1)}
                  cy={57 - (value / max) * 44}
                  r="3"
                  fill="currentColor"
                />
              ),
          )}
        </>
      )}
    </svg>
  );
}

function Stat({
  icon: Icon,
  label,
  value,
  suffix,
  hint,
  to,
}: {
  icon: LucideIcon;
  label: string;
  value: number | null;
  suffix?: string;
  hint: string;
  to: string;
}) {
  return (
    <Link className="dashboard-stat" to={to}>
      <span className="dashboard-stat-label">
        <Icon size={14} strokeWidth={1.7} />
        {label}
      </span>
      <strong>
        {displayCount(value)}
        <small>{suffix}</small>
      </strong>
      <span className="dashboard-stat-hint">
        {hint}
        <ArrowUpRight size={13} />
      </span>
    </Link>
  );
}

function EmptyState({
  icon: Icon,
  title,
  hint,
  to,
  action,
}: {
  icon: LucideIcon;
  title: string;
  hint: string;
  to?: string;
  action?: string;
}) {
  return (
    <div className="dashboard-empty">
      <span>
        <Icon size={22} strokeWidth={1.4} />
      </span>
      <strong>{title}</strong>
      <p>{hint}</p>
      {to && (
        <Link to={to}>
          {action}
          <ArrowRight size={13} />
        </Link>
      )}
    </div>
  );
}

function ActivityChart({
  points,
  available,
  loading,
}: {
  points: { label: string; value: number }[];
  available: boolean;
  loading: boolean;
}) {
  const gradientId = useId();
  const [hovered, setHovered] = useState<number | null>(null);
  const peak = Math.ceil(Math.max(4, ...points.map((point) => point.value)) / 4) * 4;
  const xy = points.map((point, i) => ({
    x: 32 + (i / (points.length - 1)) * 600,
    y: 154 - (point.value / peak) * 122,
  }));
  const line = xy.map((point) => `${point.x},${point.y}`).join(' ');
  const selected = hovered === null ? null : points[hovered];
  const active = hovered === null ? null : xy[hovered];
  if (!available)
    return (
      <EmptyState
        icon={Layers3}
        title={loading ? '正在读取沉淀记录…' : '暂时无法读取趋势'}
        hint={loading ? '整理你的每一份积累' : '可点击页面右上角刷新重试'}
      />
    );
  return (
    <div className="dashboard-chart-wrap">
      <svg
        className="dashboard-chart"
        viewBox="0 0 648 190"
        role="img"
        aria-label={`最近 ${points.length} 天每日新增知识卡片，共 ${points.reduce((sum, p) => sum + p.value, 0)} 张`}
        onMouseLeave={() => setHovered(null)}
      >
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="currentColor" stopOpacity="0.28" />
            <stop offset="1" stopColor="currentColor" stopOpacity="0.03" />
          </linearGradient>
        </defs>
        {[0, 1, 2, 3, 4].map((step) => (
          <g key={step}>
            <line
              x1="32"
              x2="632"
              y1={154 - step * 30.5}
              y2={154 - step * 30.5}
              className="dashboard-chart-grid"
            />
            <text x="19" y={158 - step * 30.5} textAnchor="end">
              {(step * peak) / 4}
            </text>
          </g>
        ))}
        <polygon points={`32,154 ${line} 632,154`} fill={`url(#${gradientId})`} />
        <polyline
          points={line}
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        {points.map((point, i) => (
          <g key={i}>
            {(i === 0 || i === points.length - 1 || i % Math.ceil(points.length / 6) === 0) && (
              <text x={xy[i].x} y="181" textAnchor="middle">
                {point.label}
              </text>
            )}
            {point.value > 0 && <circle cx={xy[i].x} cy={xy[i].y} r="3" fill="currentColor" />}
            <rect
              x={xy[i].x - 300 / (points.length - 1)}
              y="18"
              width={600 / (points.length - 1)}
              height="140"
              fill="transparent"
              onMouseEnter={() => setHovered(i)}
            >
              <title>
                {point.label}：新增 {point.value} 张
              </title>
            </rect>
          </g>
        ))}
        {active && (
          <g pointerEvents="none">
            <line
              x1={active.x}
              x2={active.x}
              y1="20"
              y2="154"
              stroke="currentColor"
              strokeDasharray="4 4"
              opacity="0.4"
            />
            <circle
              cx={active.x}
              cy={active.y}
              r="5"
              fill="currentColor"
              stroke="var(--paper-2)"
              strokeWidth="2"
            />
          </g>
        )}
      </svg>
      <div className="dashboard-chart-caption" aria-live="polite">
        <span className="dashboard-legend-dot" />
        {selected
          ? `${selected.label} · 新增 ${selected.value} 张知识卡片`
          : points.some((point) => point.value > 0)
            ? '每日新增知识卡片 · 按本地日期统计'
            : '这段时间还没有新增卡片，下一次积累从今天开始'}
      </div>
    </div>
  );
}

export function Dashboard() {
  const { userId, profile } = useAuth();
  const displayName = useMemo(
    () => profile?.username?.trim() || fallbackUsername(userId),
    [profile?.username, userId],
  );
  const [data, setData] = useState<DashboardData>(INITIAL_DATA);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState<string[]>([]);
  const [revision, setRevision] = useState(0);
  const [days, setDays] = useState(14);
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    let alive = true;
    setLoading(true);
    const names = ['学习计划', '今日任务', '知识卡片', '卡片复习', '面试记录', '内化复盘'];
    Promise.allSettled([
      studyPlan.list(),
      drill.today(),
      knowledgeApi.list(),
      knowledgeApi.due(),
      interviewApi.list(),
      drill.debt(),
    ]).then((results) => {
      if (!alive) return;
      const [plans, today, cards, due, interviews, debt] = results;
      setData({
        plans: valueOf(plans),
        today: valueOf(today),
        cards: valueOf(cards),
        due: valueOf(due),
        interviews: valueOf(interviews),
        debt: valueOf(debt),
      });
      setFailed(results.flatMap((result, i) => (result.status === 'rejected' ? [names[i]] : [])));
      setNow(new Date());
      setLoading(false);
    });
    return () => {
      alive = false;
    };
  }, [userId, revision]);

  const plans = data.plans ?? EMPTY_PLANS;
  const { activePlan, activeId, switchPlan } = useActivePlan(plans);
  const tasks = data.today ?? [];
  const actionable = tasks.filter((task) => task.status !== 'SKIPPED');
  const completed = actionable.filter((task) => task.status === 'DONE').length;
  const reviewCount = tasks.filter(
    (task) => task.kind === 'REVIEW' && task.status !== 'DONE' && task.status !== 'SKIPPED',
  ).length;
  const planTasks = actionable.filter((task) => task.planId === activeId);
  const planDone = planTasks.filter((task) => task.status === 'DONE').length;
  const pendingTasks = planTasks
    .filter((task) => task.status !== 'DONE')
    .sort((a, b) => Number(b.kind === 'REVIEW') - Number(a.kind === 'REVIEW'));
  const debts = (data.debt ?? []).filter((debt) => debt.planId === activeId);
  const cards = data.cards ?? [];
  const reviewedCards = cards.filter((card) => card.reviewCount > 0).length;
  const mastery = activePlan ? percent(activePlan.masteredCount, activePlan.totalCount) : 0;
  const recentCards = [...cards]
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .slice(0, 3);
  const activity = useMemo(
    () =>
      dailyCounts(
        (data.cards ?? []).map((card) => card.createdAt),
        days,
        now,
      ),
    [data.cards, days, now],
  );
  const cardWeek = useMemo(
    () =>
      dailyCounts(
        (data.cards ?? []).map((card) => card.createdAt),
        7,
        now,
      ),
    [data.cards, now],
  );
  const interviewWeek = useMemo(
    () =>
      dailyCounts(
        (data.interviews ?? []).map((session) => session.createdAt),
        7,
        now,
      ),
    [data.interviews, now],
  );
  const hour = now.getHours();
  const greeting = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好';

  return (
    <div className="dashboard-page">
      <div className="dashboard-ambient" aria-hidden="true">
        <BookOpen className="dashboard-ambient-book" strokeWidth={0.7} />
        <GraduationCap className="dashboard-ambient-cap" strokeWidth={0.8} />
        <span className="dashboard-ambient-offer">
          <FileCheck2 size={26} strokeWidth={1} />
          <span>OFFER</span>
        </span>
      </div>
      <div className="dashboard-content">
        <div className="dashboard-topbar">
          <span className="dashboard-breadcrumb">
            <LayoutDashboard size={16} strokeWidth={1.6} />
            我的工作台<span>/</span>
            <b>学习总览</b>
          </span>
          <div className="dashboard-topbar-actions">
            <span className="dashboard-date">
              <CalendarDays size={14} />
              {now.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })}
            </span>
            <button
              className={`dashboard-refresh${loading ? ' is-loading' : ''}`}
              type="button"
              disabled={loading}
              onClick={() => setRevision((value) => value + 1)}
              aria-label="刷新首页数据"
              title="刷新首页数据"
            >
              <RefreshCw size={16} />
            </button>
            <Link
              className="dashboard-avatar"
              to="/account"
              title={`${displayName} · 个人中心`}
              aria-label="打开个人中心"
            >
              {displayName.slice(0, 1)}
            </Link>
          </div>
        </div>
        <header className="dashboard-welcome">
          <div>
            <span className="dashboard-eyebrow">A LITTLE PROGRESS, EVERY DAY</span>
            <h1>
              {greeting}，{displayName}
              <span className="dashboard-greeting-dot">.</span>
            </h1>
            <p>把每一点积累，变成下一份 Offer 的底气。</p>
          </div>
          <Link className="dashboard-outline-button" to="/capture">
            <Plus size={16} />
            开启一段学习对话
          </Link>
        </header>
        {failed.length > 0 && (
          <div className="dashboard-notice" role="status">
            {failed.join('、')}暂时加载失败，其他数据仍可查看。
            <button type="button" disabled={loading} onClick={() => setRevision((value) => value + 1)}>
              重新加载
            </button>
          </div>
        )}
        <section className="dashboard-stats" aria-label="今日学习统计" aria-busy={loading}>
          <Stat
            icon={CheckCheck}
            label="今日已完成"
            value={data.today === null ? null : completed}
            suffix={data.today ? ` / ${actionable.length}` : undefined}
            hint="全部方向 · 学习与复习任务"
            to="/drill"
          />
          <Stat
            icon={RotateCcw}
            label="今日待复习"
            value={data.today === null ? null : reviewCount}
            suffix=" 项"
            hint="按学习间隔安排的复习任务"
            to="/drill"
          />
          <Stat
            icon={Layers3}
            label="待复习卡片"
            value={data.due?.length ?? null}
            suffix=" 张"
            hint="回看一次，让记忆更牢固"
            to="/notes?tab=card"
          />
          <Stat
            icon={Compass}
            label="学习方向"
            value={data.plans?.length ?? null}
            suffix=" 个"
            hint="你的成长，正在有序展开"
            to="/plan"
          />
        </section>
        <div className="dashboard-section-heading dashboard-overview-heading">
          <div>
            <h2>成长概览</h2>
            <span>每一次认真，都有迹可循</span>
          </div>
          <Link to="/profile">
            掌握画像
            <ArrowUpRight size={14} />
          </Link>
        </div>
        <section className="dashboard-overview" aria-label="成长概览">
          <Link to="/profile" className="dashboard-overview-card tone-mint">
            <span className="dashboard-card-top">
              <BookOpen size={18} />
              <span className="dashboard-card-arrow">
                <ArrowUpRight size={16} />
              </span>
            </span>
            <strong className="dashboard-card-number">
              {data.plans === null ? '—' : activePlan && activePlan.totalCount > 0 ? `${mastery}%` : '—'}
            </strong>
            <h3>知识掌握率</h3>
            <p>
              {activePlan
                ? `${activePlan.masteredCount} / ${activePlan.totalCount} 个知识点已掌握`
                : data.plans === null
                  ? loading
                    ? '正在读取学习方向…'
                    : '学习方向暂不可用'
                  : '创建方向，开始你的学习'}
            </p>
            <div className="dashboard-mastery-track" aria-hidden="true">
              {Array.from({ length: 20 }, (_, i) => (
                <span key={i} className={i < Math.round(mastery / 5) ? 'is-filled' : ''} />
              ))}
            </div>
            <span className="dashboard-card-foot">
              当前方向
              <ChevronRight size={12} />
              <span>{activePlan?.title || '尚未设置'}</span>
            </span>
          </Link>
          <Link to="/capture" className="dashboard-overview-card tone-lime">
            <span className="dashboard-card-top">
              <Layers3 size={18} />
              <span className="dashboard-card-arrow">
                <ArrowUpRight size={16} />
              </span>
            </span>
            <strong className="dashboard-card-number">
              {displayCount(data.cards?.length ?? null)}
              <small> 张</small>
            </strong>
            <h3>沉淀知识卡片</h3>
            <p>
              {data.cards
                ? `已复习 ${reviewedCards} 张 · 覆盖率 ${percent(reviewedCards, cards.length)}%`
                : loading
                  ? '正在读取你的知识积累'
                  : '知识卡片暂不可用'}
            </p>
            {data.cards ? (
              <MiniChart values={cardWeek.map((point) => point.value)} />
            ) : (
              <div className="dashboard-mini-chart" />
            )}
            <span className="dashboard-card-foot">
              近 7 天新增 {data.cards ? cardWeek.reduce((sum, point) => sum + point.value, 0) : '—'} 张
            </span>
          </Link>
          <Link to="/rehearsal/history" className="dashboard-overview-card tone-peach">
            <span className="dashboard-card-top">
              <MessagesSquare size={18} />
              <span className="dashboard-card-arrow">
                <ArrowUpRight size={16} />
              </span>
            </span>
            <strong className="dashboard-card-number">
              {displayCount(data.interviews?.length ?? null)}
              <small> 场</small>
            </strong>
            <h3>参与模拟面试</h3>
            <p>
              {data.interviews
                ? `${data.interviews.filter((session) => session.status === 'COMPLETED').length} 场已完成 · 文字与语音面试`
                : loading
                  ? '正在读取面试记录'
                  : '面试记录暂不可用'}
            </p>
            {data.interviews ? (
              <MiniChart values={interviewWeek.map((point) => point.value)} bars />
            ) : (
              <div className="dashboard-mini-chart" />
            )}
            <span className="dashboard-card-foot">
              近 7 天参与 {data.interviews ? interviewWeek.reduce((sum, point) => sum + point.value, 0) : '—'}{' '}
              场
            </span>
          </Link>
          <section className="dashboard-focus-card" aria-labelledby="dashboard-focus-title">
            <div className="dashboard-offer-object" aria-hidden="true">
              <span className="dashboard-offer-sheet">
                <CircleCheck size={18} />
                <b>OFFER</b>
                <i />
                <i />
              </span>
              <span className="dashboard-offer-envelope" />
            </div>
            <span className="dashboard-focus-kicker">
              <Sparkles size={14} />
              让理想，再近一点
            </span>
            <h3 id="dashboard-focus-title">
              {data.plans === null
                ? loading
                  ? '正在读取学习方向…'
                  : '学习方向暂不可用'
                : activePlan?.title || '从今天，开始积累'}
            </h3>
            <p>{activePlan ? '保持自己的节奏，走好下一步。' : '定一个方向，把想学变成学会。'}</p>
            <div className="dashboard-focus-progress">
              <span>{activePlan ? '当前方向 · 今日进度' : '你的下一份 Offer，从准备开始'}</span>
              {activePlan && <strong>{data.today ? `${planDone} / ${planTasks.length}` : '—'}</strong>}
            </div>
            <div className="dashboard-focus-track" aria-hidden="true">
              <span style={{ width: `${percent(planDone, planTasks.length)}%` }} />
            </div>
            <Link
              to={data.plans === null ? '/plan' : activePlan ? '/drill' : '/intake'}
              className="dashboard-focus-cta"
            >
              {data.plans === null ? '查看学习计划' : activePlan ? '继续学习' : '创建学习方向'}
              <ArrowRight size={16} />
            </Link>
          </section>
        </section>
        <section className="dashboard-middle-grid">
          <div className="dashboard-panel dashboard-recent">
            <div className="dashboard-section-heading">
              <div>
                <h2>最近沉淀</h2>
                <span>把好问题，留给未来的自己</span>
              </div>
              <Link to="/capture" aria-label="查看全部沉淀卡片">
                全部
                <ArrowUpRight size={13} />
              </Link>
            </div>
            {data.cards === null ? (
              <EmptyState
                icon={Layers3}
                title={loading ? '正在读取卡片…' : '暂时无法读取卡片'}
                hint="你的知识积累会显示在这里"
              />
            ) : recentCards.length === 0 ? (
              <EmptyState
                icon={NotebookPen}
                title="留住第一个好问题"
                hint="在对话沉淀中，把问答保存为知识卡片。"
                to="/capture"
                action="去沉淀一张卡片"
              />
            ) : (
              <ul className="dashboard-recent-list">
                {recentCards.map((card, index) => (
                  <li key={card.id}>
                    <Link to="/capture" title="打开对话沉淀中的卡片列表">
                      <span className={`dashboard-record-icon record-${index}`}>
                        <BookOpen size={18} strokeWidth={1.5} />
                      </span>
                      <span className="dashboard-record-copy">
                        <strong>{card.question}</strong>
                        <small>
                          {new Date(card.createdAt).toLocaleDateString('zh-CN', {
                            month: 'long',
                            day: 'numeric',
                          })}
                          <span>·</span>
                          {card.reviewCount > 0 ? `已复习 ${card.reviewCount} 次` : '等待第一次复习'}
                        </small>
                      </span>
                      <ChevronRight size={15} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            <Link to="/capture" className="dashboard-add-card">
              <Plus size={14} />
              记录一个新收获
            </Link>
          </div>
          <div className="dashboard-panel dashboard-trend">
            <div className="dashboard-section-heading">
              <div>
                <h2>知识沉淀趋势</h2>
                <span>小小的积累，也值得被看见</span>
              </div>
              <div className="dashboard-period" aria-label="统计时间范围">
                {[14, 30].map((value) => (
                  <button
                    key={value}
                    type="button"
                    aria-pressed={days === value}
                    onClick={() => setDays(value)}
                  >
                    近 {value} 天
                  </button>
                ))}
              </div>
            </div>
            <div className="dashboard-trend-total">
              <strong>
                {displayCount(
                  data.cards === null ? null : activity.reduce((sum, point) => sum + point.value, 0),
                )}
                <small> 张新卡片</small>
              </strong>
              <span>
                <span className="dashboard-legend-dot" />
                知识积累
              </span>
            </div>
            <ActivityChart key={days} points={activity} available={data.cards !== null} loading={loading} />
          </div>
        </section>
        <div className="dashboard-plan-bar">
          <div>
            <span className="dashboard-plan-label">
              <Compass size={15} />
              当前学习方向
            </span>
            {plans.length > 1 ? (
              <PlanSwitcher plans={plans} activeId={activeId} onSwitch={switchPlan} />
            ) : (
              <strong>{activePlan?.title || (data.plans === null ? '暂未读取' : '尚未创建学习方向')}</strong>
            )}
          </div>
          <Link to="/intake">
            <Plus size={14} />
            新建方向
          </Link>
        </div>
        <section className="dashboard-bottom-grid">
          <div className="dashboard-panel">
            <div className="dashboard-section-heading">
              <div>
                <h2>接下来，学一点</h2>
                <span>当前方向的今日学习与复习</span>
              </div>
              <Link to="/drill">
                全部任务
                <ArrowUpRight size={13} />
              </Link>
            </div>
            {data.today === null ? (
              <EmptyState
                icon={CalendarDays}
                title={loading ? '正在安排今日任务…' : '暂时无法读取任务'}
                hint="刷新页面数据后重试"
              />
            ) : pendingTasks.length === 0 ? (
              <EmptyState
                icon={CheckCheck}
                title={planDone > 0 ? '今天的计划，已完成' : '按自己的节奏，开启今天'}
                hint={
                  planDone > 0 ? '把今天学到的，留在明天的记忆里。' : '继续学习，或创建一个感兴趣的方向。'
                }
                to={activePlan ? '/drill' : '/intake'}
                action={activePlan ? '继续探索' : '创建学习方向'}
              />
            ) : (
              <ul className="dashboard-task-list">
                {pendingTasks.slice(0, 3).map((task) => (
                  <li key={task.id}>
                    <Link to="/drill">
                      <span className={`dashboard-task-icon${task.kind === 'REVIEW' ? ' is-review' : ''}`}>
                        {task.kind === 'REVIEW' ? <RotateCcw size={16} /> : <BookOpen size={16} />}
                      </span>
                      <span className="dashboard-record-copy">
                        <strong>{task.conceptName}</strong>
                        <small>{task.subPoint || task.planTitle}</small>
                      </span>
                      <span className={`dashboard-task-label${task.kind === 'REVIEW' ? ' is-review' : ''}`}>
                        {task.kind === 'REVIEW' ? '复习' : '新学'}
                      </span>
                      <ArrowUpRight size={14} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="dashboard-panel">
            <div className="dashboard-section-heading">
              <div>
                <h2>留一点时间，内化复盘</h2>
                <span>当前方向 · {data.debt ? `${debts.length} 条待复盘` : '读取中'}</span>
              </div>
              <Link to="/notes">
                去复盘
                <ArrowUpRight size={13} />
              </Link>
            </div>
            {data.debt === null ? (
              <EmptyState
                icon={NotebookPen}
                title={loading ? '正在读取复盘记录…' : '暂时无法读取复盘'}
                hint="刷新页面数据后重试"
              />
            ) : debts.length === 0 ? (
              <EmptyState
                icon={Check}
                title="这个方向，暂时没有待复盘"
                hint="把每一次不确定，变成下一次的笃定。"
                to="/notes?tab=note"
                action="回看我的笔记"
              />
            ) : (
              <ul className="dashboard-task-list">
                {debts.slice(0, 3).map((debt) => (
                  <li key={debt.runId}>
                    <Link to={`/notes/review/${debt.runId}`}>
                      <span className="dashboard-task-icon is-peach">
                        <NotebookPen size={16} />
                      </span>
                      <span className="dashboard-record-copy">
                        <strong>{debt.stem}</strong>
                        <small>{debt.weakPoints?.[0] || '整理思路，巩固这次练习'}</small>
                      </span>
                      <span className="dashboard-score">
                        {Math.round(debt.rawScore)}
                        <small> 分</small>
                      </span>
                      <ArrowUpRight size={14} />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
        <footer className="dashboard-footer">
          <GraduationCap size={15} strokeWidth={1.5} />
          学习有方向，成长有回响。<span>ONE STEP CLOSER TO YOUR OFFER</span>
        </footer>
      </div>
    </div>
  );
}
