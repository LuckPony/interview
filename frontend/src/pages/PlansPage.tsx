import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Plus,
  Play,
  RefreshCw,
  BookOpen,
  Layers,
  ChevronRight,
  Pencil,
  Save,
  Trash2,
  X,
} from 'lucide-react';
import { studyPlan } from '../api/drill';
import { ApiError } from '../api/client';
import { Button, Card, Loading } from '../components/ui';
import type { PlanView, PlanConceptView } from '../api/types';
import './PlansPage.css';

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '加载失败';
}

/** 按 layer 分组概念 */
function groupByLayer(concepts: PlanConceptView[]): Record<number, PlanConceptView[]> {
  const map: Record<number, PlanConceptView[]> = {};
  for (const c of concepts) {
    (map[c.layer] ??= []).push(c);
  }
  return map;
}

const LAYER_LABEL: Record<number, string> = {
  1: 'L1 · 能认出',
  2: 'L2 · 能讲清',
  3: 'L3 · 能活用',
  4: 'L4 · 能串联',
  5: 'L5 · 能创造',
};

export function PlansPage() {
  const navigate = useNavigate();
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [activeIdx, setActiveIdx] = useState(0);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  // —— 编辑态：方向标题/目标 + 知识点增改删 ——
  const [editing, setEditing] = useState(false);
  const [editTitle, setEditTitle] = useState('');
  const [editGoal, setEditGoal] = useState('');
  const [editBusy, setEditBusy] = useState(false);
  const [editErr, setEditErr] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setPlans(await studyPlan.list());
    } catch (e) {
      setErr(msg(e));
    } finally {
      setLoading(false);
    }
  }, []);

  // 编辑后的静默刷新（不闪 loading）
  const reload = useCallback(async () => {
    try {
      setPlans(await studyPlan.list());
    } catch (e) {
      setErr(msg(e));
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const activePlan = plans[activeIdx] ?? null;

  const enterEdit = () => {
    if (!activePlan) return;
    setEditTitle(activePlan.title);
    setEditGoal(activePlan.goal ?? '');
    setEditErr('');
    setEditing(true);
  };

  const savePlan = async () => {
    if (!activePlan) return;
    if (!editTitle.trim()) { setEditErr('方向名称不能为空'); return; }
    setEditBusy(true);
    setEditErr('');
    try {
      await studyPlan.update(activePlan.id, { title: editTitle.trim(), goal: editGoal || null });
      await reload();
    } catch (e) {
      setEditErr(msg(e));
    } finally {
      setEditBusy(false);
    }
  };

  const deletePlan = async () => {
    if (!activePlan) return;
    if (!window.confirm(`确定删除学习方向「${activePlan.title}」？其下的知识点与掌握度记录会一并删除。`)) return;
    setEditBusy(true);
    setEditErr('');
    try {
      await studyPlan.remove(activePlan.id);
      setEditing(false);
      await load();
      setActiveIdx((i) => Math.max(0, Math.min(i, plans.length - 2)));
    } catch (e) {
      setEditErr(msg(e));
    } finally {
      setEditBusy(false);
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">学习计划</span>
        <h1>学习大纲</h1>
        <p>这里是你的知识蓝图。点「编辑」可以自行调整方向，或增改删知识点。</p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {loading ? (
        <Loading label="读取学习计划…" />
      ) : plans.length === 0 ? (
        <div className="empty">
          <h3>还没有学习方向</h3>
          <p>点下面「新建学习方向」，用对话告诉 AI 你想学什么。</p>
          <Button onClick={() => navigate('/intake')}>
            <Plus size={16} strokeWidth={1.6} /> 新建学习方向
          </Button>
        </div>
      ) : (
        <>
          {/* ====== 方向切换 tabs ====== */}
          <nav className="plan-tabs">
            {plans.map((p, i) => (
              <button
                key={p.id}
                className={'plan-tab' + (i === activeIdx ? ' active' : '')}
                onClick={() => setActiveIdx(i)}
              >
                <BookOpen size={14} strokeWidth={1.6} />
                {p.title}
              </button>
            ))}
            <button
              className="plan-tab plan-tab-new"
              onClick={() => navigate('/intake')}
            >
              <Plus size={14} strokeWidth={1.6} />
            </button>
          </nav>

          {activePlan && (
            <div className="plan-outline">
              {/* ====== 方向摘要卡片 ====== */}
              <Card className="plan-summary">
                <div className="plan-summary-top">
                  <div>
                    <h2>{activePlan.title}</h2>
                    {activePlan.goal && <p className="plan-goal">{activePlan.goal}</p>}
                  </div>
                  <div className="plan-summary-stats">
                    <div className="stat">
                      <span className="stat-num">{activePlan.masteredCount}</span>
                      <span className="stat-label">已掌握</span>
                    </div>
                    <div className="stat-divider" />
                    <div className="stat">
                      <span className="stat-num">{activePlan.totalCount}</span>
                      <span className="stat-label">总数</span>
                    </div>
                    <div className="stat-divider" />
                    <div className="stat">
                      <span className={'stat-num' + (activePlan.dueReviewCount > 0 ? ' warn' : '')}>
                        {activePlan.dueReviewCount}
                      </span>
                      <span className="stat-label">待复习</span>
                    </div>
                  </div>
                </div>
                {activePlan.corpusName && (
                  <div className="plan-corpus">
                    <BookOpen size={14} strokeWidth={1.6} />
                    <span>学习资料：{activePlan.corpusName}</span>
                  </div>
                )}
                {/* 操作按钮：开始练习 / 复习 / 编辑 */}
                <div className="plan-summary-actions">
                  <Button variant="primary" onClick={() => navigate('/drill', { state: { planId: activePlan.id, planMode: 'continue' }, replace: true })}>
                    <Play size={15} strokeWidth={1.8} /> 开始练习
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => navigate('/drill', { state: { planId: activePlan.id, planMode: 'review' }, replace: true })}
                    disabled={activePlan.dueReviewCount === 0}
                    title={activePlan.dueReviewCount === 0 ? '暂无到期复习项' : `有 ${activePlan.dueReviewCount} 个到期复习项`}
                  >
                    <RefreshCw size={15} strokeWidth={1.8} /> 复习
                    {activePlan.dueReviewCount > 0 && <span className="due-badge">{activePlan.dueReviewCount}</span>}
                  </Button>
                  <Button variant="ghost" onClick={() => (editing ? setEditing(false) : enterEdit())}>
                    {editing ? <><X size={15} strokeWidth={1.8} /> 完成</> : <><Pencil size={15} strokeWidth={1.8} /> 编辑</>}
                  </Button>
                </div>
              </Card>

              {editing ? (
                /* ====== 编辑模式：改方向 + 增改删知识点 ====== */
                <>
                  <Card className="plan-editor">
                    <span className="eyebrow">编辑方向</span>
                    {editErr && <div className="banner info">{editErr}</div>}
                    <label className="edit-field">
                      <span>名称</span>
                      <input value={editTitle} onChange={(e) => setEditTitle(e.target.value)} />
                    </label>
                    <label className="edit-field">
                      <span>目标</span>
                      <textarea
                        value={editGoal}
                        onChange={(e) => setEditGoal(e.target.value)}
                        rows={2}
                      />
                    </label>
                    <div className="edit-actions">
                      <Button onClick={savePlan} disabled={editBusy}>
                        <Save size={15} strokeWidth={1.8} /> 保存方向
                      </Button>
                      <Button variant="danger" onClick={deletePlan} disabled={editBusy}>
                        <Trash2 size={15} strokeWidth={1.8} /> 删除方向
                      </Button>
                    </div>
                  </Card>

                  <div className="concept-editor">
                    <span className="eyebrow">知识点</span>
                    {activePlan.concepts.map((c) => (
                      <ConceptRowEditor key={c.id} concept={c} onSaved={reload} />
                    ))}
                    <AddConceptForm planId={activePlan.id} onSaved={reload} />
                  </div>
                </>
              ) : (
                /* ====== 概念树：按 layer 分层展示 ====== */
                activePlan.concepts.length === 0 ? (
                  <div className="empty small">
                    <p>这个方向还没有概念。先去「开始练习」，系统会帮你选题。</p>
                  </div>
                ) : (
                  <div className="concept-tree">
                    {Object.entries(groupByLayer(activePlan.concepts))
                      .sort(([a], [b]) => Number(a) - Number(b))
                      .map(([layer, concepts]) => (
                        <div className="concept-layer" key={layer}>
                          <div className="layer-head">
                            <Layers size={15} strokeWidth={1.6} />
                            <span className="layer-label">{LAYER_LABEL[Number(layer)] ?? `L${layer}`}</span>
                            <span className="layer-count">
                              {concepts.filter(c => c.masteryLevel > 0).length}/{concepts.length} 掌握
                            </span>
                            <Button
                              variant="ghost"
                              className="layer-practice"
                              onClick={() => navigate('/drill', { state: { planId: activePlan.id, planMode: 'layer', layer: Number(layer) }, replace: true })}
                            >
                              <Play size={13} strokeWidth={1.8} /> 练这一层
                            </Button>
                          </div>
                          <div className="layer-chips">
                            {concepts.map((c) => (
                              <button
                                className={'concept-chip' + (c.masteryLevel > 0 ? ' mastered' : '')}
                                key={c.id}
                                onClick={() => navigate('/drill', { state: { conceptId: c.id }, replace: true })}
                              >
                                <span className="chip-name">{c.name}</span>
                                {c.masteryLevel > 0 && <span className="chip-check">✓</span>}
                                <ChevronRight size={12} strokeWidth={1.6} className="chip-arrow" />
                              </button>
                            ))}
                          </div>
                        </div>
                      ))}
                  </div>
                )
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ===== 单条知识点编辑行 =====
function ConceptRowEditor({ concept, onSaved }: {
  concept: PlanConceptView;
  onSaved: () => Promise<void>;
}) {
  const [name, setName] = useState(concept.name);
  const [layer, setLayer] = useState(String(concept.layer));
  const [note, setNote] = useState(concept.note ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  const save = async () => {
    if (!name.trim()) { setErr('名称不能为空'); return; }
    setBusy(true);
    setErr('');
    try {
      await studyPlan.updateConcept(concept.id, { name: name.trim(), layer: Number(layer), note: note || null });
      await onSaved();
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  const del = async () => {
    if (!window.confirm(`删除知识点「${concept.name}」？`)) return;
    setBusy(true);
    setErr('');
    try {
      await studyPlan.removeConcept(concept.id);
      await onSaved();
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="concept-edit-row">
      <input className="ce-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="知识点名称" />
      <select className="ce-layer" value={layer} onChange={(e) => setLayer(e.target.value)}>
        {[1, 2, 3, 4, 5].map((l) => <option key={l} value={l}>L{l}</option>)}
      </select>
      <input className="ce-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="一句话提示（可空）" />
      <div className="ce-actions">
        <Button variant="ghost" onClick={save} disabled={busy}>保存</Button>
        <Button variant="danger" onClick={del} disabled={busy}>删除</Button>
      </div>
      {err && <span className="ce-err">{err}</span>}
    </div>
  );
}

// ===== 新增知识点表单 =====
function AddConceptForm({ planId, onSaved }: { planId: number; onSaved: () => Promise<void> }) {
  const [name, setName] = useState('');
  const [layer, setLayer] = useState('1');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  const save = async () => {
    if (!name.trim()) { setErr('请填写知识点名称'); return; }
    setBusy(true);
    setErr('');
    try {
      await studyPlan.addConcept(planId, { name: name.trim(), layer: Number(layer), note: note || null });
      setName('');
      setNote('');
      setLayer('1');
      await onSaved();
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="concept-edit-row is-new">
      <input className="ce-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="新知识点名称" />
      <select className="ce-layer" value={layer} onChange={(e) => setLayer(e.target.value)}>
        {[1, 2, 3, 4, 5].map((l) => <option key={l} value={l}>L{l}</option>)}
      </select>
      <input className="ce-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="一句话提示（可空）" />
      <div className="ce-actions">
        <Button onClick={save} disabled={busy}>
          <Plus size={14} strokeWidth={1.8} /> 添加
        </Button>
      </div>
      {err && <span className="ce-err">{err}</span>}
    </div>
  );
}
