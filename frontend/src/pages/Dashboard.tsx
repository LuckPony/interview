import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PenLine, MessagesSquare, Network, ArrowUpRight, NotebookPen } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { drill, studyPlan } from '../api/drill';
import { Card, Badge, Loading } from '../components/ui';
import { PlanSwitcher } from '../components/PlanSwitcher';
import { useActivePlan } from '../lib/useActivePlan';
import { ApiError } from '../api/client';
import type { DebtView, TopicProfile, PlanView } from '../api/types';
import './Dashboard.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

export function Dashboard() {
  const { userId } = useAuth();
  const navigate = useNavigate();
  const [debt, setDebt] = useState<DebtView[] | null>(null);
  const [profile, setProfile] = useState<TopicProfile[] | null>(null);
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [err, setErr] = useState('');

  useEffect(() => {
    drill.debt().then(setDebt).catch((e) => setErr(msg(e)));
    drill.profile().then(setProfile).catch(() => undefined);
    studyPlan.list().then(setPlans).catch(() => undefined);
  }, []);

  // 当前学习方向：只在首页切换，其他页面（问答记录/复盘/练习）都按它过滤
  const { activeId, switchPlan } = useActivePlan(plans);
  const debtActive = debt ? debt.filter((d) => d.planId === activeId) : [];

  const topics = profile?.length ?? 0;
  const mastered = profile?.filter((t) => t.masteredLayer >= 3).length ?? 0;
  const debtCount = debtActive.length;

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">面霸 · 今日</span>
        <h1>面霸 #{userId}</h1>
        <p>闭卷、动脑、内化。挑一条路开始。</p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {/* 学习方向切换（只在此处切换，其他页面都跟随）+ 新建 */}
      <div className="dash-direction">
        <PlanSwitcher plans={plans} activeId={activeId} onSwitch={switchPlan} />
        <button className="dash-new-plan" onClick={() => navigate('/intake')}>
          <span>新建学习方向</span>
        </button>
      </div>

      <section className="dash-actions">
        <button className="dash-primary" onClick={() => navigate('/drill')}>
          <span className="dash-primary-ico">
            <PenLine size={22} strokeWidth={1.6} />
          </span>
          <span className="dash-primary-body">
            <span className="dash-kicker">练习</span>
            <strong>继续学习</strong>
            <small>
              {debtCount > 0 ? `有 ${debtCount} 条复盘待写，先去消化` : '服务端已为你选好下一题'}
            </small>
          </span>
          <ArrowUpRight size={20} strokeWidth={1.6} className="dash-go" />
        </button>

        <div className="dash-secondary">
          <button className="dash-sec" onClick={() => navigate('/rehearsal')}>
            <MessagesSquare size={18} strokeWidth={1.6} />
            <span>模拟面试</span>
          </button>
          <button className="dash-sec" onClick={() => navigate('/profile')}>
            <Network size={18} strokeWidth={1.6} />
            <span>掌握画像</span>
          </button>
        </div>
      </section>

      <section className="dash-stats">
        <Stat label="知识主题" value={topics} tone="soft" />
        <Stat label="已精熟主题" value={mastered} tone="good" />
        <Stat label="待复盘" value={debtCount} tone={debtCount > 0 ? 'bad' : 'soft'} />
      </section>

      <section className="dash-debt">
        <div className="dash-debt-head">
          <h2>内化欠账</h2>
          <button className="dash-link" onClick={() => navigate('/notes')}>
            全部复盘 <NotebookPen size={14} strokeWidth={1.6} />
          </button>
        </div>

        {debt === null ? (
          <Loading label="读取欠账…" />
        ) : debtActive.length === 0 ? (
          <div className="empty">
            <h3>这个方向暂无欠账</h3>
            <p>答错的题都已复盘，保持住。</p>
          </div>
        ) : (
          <ul className="debt-list">
            {debtActive.map((d) => (
              <li key={d.runId} className="debt-item" onClick={() => navigate('/notes')}>
                <span className="debt-stem">{d.stem}</span>
                <Badge kind="bad">{Math.round(d.rawScore)} 分</Badge>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone: 'soft' | 'good' | 'bad' }) {
  return (
    <Card className="stat">
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
      {tone !== 'soft' && <span className={`dot is-${tone}`} />}
    </Card>
  );
}
