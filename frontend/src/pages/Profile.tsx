import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { BookOpen, Layers, TrendingUp, Play, X } from 'lucide-react';
import { studyPlan } from '../api/drill';
import { ApiError } from '../api/client';
import { Card, Loading, Badge } from '../components/ui';
import { ACTIVE_PLAN_KEY, readActivePlanId } from '../lib/useActivePlan';
import type { PlanView, PlanConceptView } from '../api/types';
import './Profile.css';

const LAYERS = [1, 2, 3, 4, 5];

const LAYER_LABEL: Record<number, string> = {
  1: 'L1 · 能认出',
  2: 'L2 · 能讲清',
  3: 'L3 · 能活用',
  4: 'L4 · 能串联',
  5: 'L5 · 能创造',
};

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

function groupByLayer(concepts: PlanConceptView[]): Record<number, PlanConceptView[]> {
  const map: Record<number, PlanConceptView[]> = {};
  for (const c of concepts) {
    (map[c.layer] ??= []).push(c);
  }
  return map;
}

/** 掌握档位 → 徽标与文案（masteryLevel：0 未练 / 1 薄弱 / 2 达标 / 3 精熟） */
function masteryBadge(ml: number): { kind: 'good' | 'warn' | 'bad' | 'soft'; label: string } {
  if (ml >= 3) return { kind: 'good', label: '精熟' };
  if (ml === 2) return { kind: 'good', label: '达标' };
  if (ml === 1) return { kind: 'bad', label: '薄弱' };
  return { kind: 'soft', label: '未练' };
}

export function Profile() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  // 方向 tab 与「认知层模块」弹层由 URL 查询参数驱动（?plan=&layer=），支持浏览器前进/后退
  // 无 ?plan= 时默认跟随全局「当前学习方向」（首页/他页选择记忆），而不是固定第一个
  const storedIdx = (() => {
    const id = readActivePlanId();
    if (id == null) return 0;
    const i = plans.findIndex((p) => p.id === id);
    return i >= 0 ? i : 0;
  })();
  const activeIdx = Math.min(plans.length - 1, Math.max(0, Number(searchParams.get('plan') ?? storedIdx) || 0));
  const layerParam = searchParams.get('layer');
  const layerDetail = layerParam != null && Number.isInteger(Number(layerParam)) ? Number(layerParam) : null;

  /** 切换方向 tab：写 URL + 同步全局「当前学习方向」 */
  const switchPlanTab = (i: number, planId: number) => {
    try { localStorage.setItem(ACTIVE_PLAN_KEY, String(planId)); } catch { /* ignore */ }
    setSearchParams({ plan: String(i) });
  };

  useEffect(() => {
    let alive = true;
    studyPlan
      .list()
      .then((rows) => {
        if (alive) setPlans(rows);
      })
      .catch((e) => {
        if (alive) setErr(msg(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const plan = plans[activeIdx] ?? null;
  const masteredTotal = plan?.totalCount ? Math.round((plan.masteredCount / plan.totalCount) * 100) : 0;
  const detailConcepts =
    plan && layerDetail != null ? (groupByLayer(plan.concepts)[layerDetail] ?? []) : [];

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">掌握画像 · PROFILE</span>
        <h1>掌握画像</h1>
        <p>按学习方向切换，看每个认知层里你到底掌握了多少。</p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {loading ? (
        <Loading label="读取画像…" />
      ) : plans.length === 0 ? (
        <div className="empty">
          <h3>还没有学习方向</h3>
          <p>先去「学习计划」新建方向并练习，画像会跟着长出来。</p>
        </div>
      ) : (
        <>
          {/* ====== 方向切换 tabs ====== */}
          <nav className="profile-tabs">
            {plans.map((p, i) => (
              <button
                key={p.id}
                className={'profile-tab' + (i === activeIdx ? ' active' : '')}
                onClick={() => switchPlanTab(i, p.id)}
              >
                <BookOpen size={14} strokeWidth={1.6} />
                {p.title}
              </button>
            ))}
          </nav>

          {plan && (
            <div className="profile-body">
              {/* ====== 概览卡 ====== */}
              <Card className="profile-summary">
                <div className="profile-summary-top">
                  <div>
                    <h2>{plan.title}</h2>
                    {plan.goal && <p className="profile-goal">{plan.goal}</p>}
                  </div>
                  <div className="profile-total">
                    <span className="profile-total-num">
                      {plan.masteredCount}
                      <small>/{plan.totalCount}</small>
                    </span>
                    <span className="profile-total-label">已掌握</span>
                  </div>
                </div>
                <div className="progress-track" title={`掌握度 ${masteredTotal}%`}>
                  <div className="progress-fill" style={{ width: `${masteredTotal}%` }} />
                </div>
                <div className="profile-meta">
                  <span className="profile-pct">{masteredTotal}% 掌握</span>
                  {plan.dueReviewCount > 0 && (
                    <span className="profile-due">
                      <TrendingUp size={13} strokeWidth={1.8} /> {plan.dueReviewCount} 个到期，建议复习
                    </span>
                  )}
                </div>
              </Card>

              {/* ====== 按认知层分模块展示掌握程度 ====== */}
              {plan.concepts.length === 0 ? (
                <div className="empty small">
                  <p>这个方向还没有知识点，先去练习积累数据。</p>
                </div>
              ) : (
                <div className="profile-layers">
                  {LAYERS.map((L) => {
                    const concepts = groupByLayer(plan.concepts)[L];
                    if (!concepts || concepts.length === 0) return null;
                    const mastered = concepts.filter((c) => c.masteryLevel > 0).length;
                    const pct = Math.round((mastered / concepts.length) * 100);
                    return (
                      <Card className="profile-layer" key={L}>
                        <div className="profile-layer-head">
                          <Layers size={15} strokeWidth={1.6} />
                          <span className="profile-layer-label">{LAYER_LABEL[L]}</span>
                          <span className={'profile-layer-count' + (pct === 100 ? ' done' : '')}>
                            {mastered}/{concepts.length} 掌握
                          </span>
                          <div className="progress-track small">
                            <div className="progress-fill" style={{ width: `${pct}%` }} />
                          </div>
                        </div>
                        <div className="profile-chips">
                          {concepts.map((c) => (
                            <button
                              key={c.id}
                              className="profile-chip"
                              onClick={() => setSearchParams({ plan: String(activeIdx), layer: String(c.layer) })}
                              title="查看这一层模块的内容"
                            >
                              <span className="chip-name">{c.name}</span>
                              {c.note && <span className="chip-note">{c.note}</span>}
                              <Badge kind={masteryBadge(c.masteryLevel).kind}>
                                {masteryBadge(c.masteryLevel).label}
                              </Badge>
                            </button>
                          ))}
                        </div>
                      </Card>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </>
      )}

      {plan && plan.concepts.length > 0 && (
        <div className="profile-foot">
          <button className="profile-practice" onClick={() => navigate('/drill', { state: { planId: plan.id, planMode: 'continue' }, replace: true })}>
            <Play size={14} strokeWidth={1.8} /> 继续练「{plan.title}」
          </button>
        </div>
      )}

      {/* ====== 认知层模块内容弹层：点击知识点后查看该层包含什么（?layer= 驱动，可后退关闭）====== */}
      {plan && layerDetail != null && (
        <div className="module-backdrop" onClick={() => setSearchParams({ plan: String(activeIdx) })}>
          <div className="module-modal" onClick={(e) => e.stopPropagation()}>
            <button className="module-close" onClick={() => setSearchParams({ plan: String(activeIdx) })} aria-label="关闭">
              <X size={16} strokeWidth={1.8} />
            </button>
            <div className="module-head">
              <Layers size={16} strokeWidth={1.6} />
              <span className="module-title">{LAYER_LABEL[layerDetail] ?? `L${layerDetail}`} · 模块内容</span>
              <span className="module-count">
                {detailConcepts.filter((c) => c.masteryLevel > 0).length}/{detailConcepts.length} 掌握
              </span>
            </div>
            <div className="module-list">
              {detailConcepts.length === 0 ? (
                <p className="module-empty">这一层还没有知识点。</p>
              ) : (
                detailConcepts.map((c) => (
                  <div className="module-item" key={c.id}>
                    <div className="module-item-head">
                      <span className="module-item-name">{c.name}</span>
                      <Badge kind={masteryBadge(c.masteryLevel).kind}>
                        {masteryBadge(c.masteryLevel).label}
                      </Badge>
                    </div>
                    {c.note && <p className="module-item-note">{c.note}</p>}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
