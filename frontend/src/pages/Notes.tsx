import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, BookOpen, Target, PenLine, Trash2, Eye, EyeOff, Layers, FileText, RefreshCw, Check, MessageCircle, Plus } from 'lucide-react';
import { drill, studyPlan } from '../api/drill';
import { Card, Button, Badge, Loading } from '../components/ui';
import { Markdown } from '../components/Markdown';
import { CasualNoteDialog } from '../components/CasualNoteDialog';
import { CardMeta, daysUntilDue } from '../components/CardMeta';
import { useActivePlan } from '../lib/useActivePlan';
import { ApiError } from '../api/client';
import type {DebtView, KnowledgeCard, PlanView, CasualNote} from '../api/types';
import './Notes.css';
import {knowledgeApi} from "../api/knowledge.ts";

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '操作失败';
}

/** 达标线：判分 ≥ 此分数即视为达标，不再进入内化复盘（与后端 GradeScale.PASS_LINE 一致） */
const PASS_LINE = 60;

/** 按学习方向分组欠账（planId 为空的归「全局」） */
function groupDebt(
  debt: DebtView[],
  plans: PlanView[],
): { planId: number | null; title: string; items: DebtView[] }[] {
  const titleOf = new Map(plans.map((p) => [p.id, p.title]));
  const groups = new Map<number | null, DebtView[]>();
  for (const d of debt) {
    const k = d.planId;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k)!.push(d);
  }
  return [...groups.entries()]
    .map(([planId, items]) => ({
      planId,
      title: planId != null ? titleOf.get(planId) ?? '未命名方向' : '全局 / 未归类',
      items,
    }))
    .sort((a, b) => (a.planId ?? -1) - (b.planId ?? -1));
}

export function Notes() {
  const navigate = useNavigate();
  const [debt, setDebt] = useState<DebtView[] | null>(null);
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [err, setErr] = useState('');
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const [tab, setTab] = useState<'debt' | 'card' | 'note'>('debt');
  const [notes, setNotes] = useState<CasualNote[] | null>(null);
  const [noteSearch, setNoteSearch] = useState('');
  const [noteConceptId, setNoteConceptId] = useState<number | null>(null);
  const [showNoteDialog, setShowNoteDialog] = useState(false);
  const [dueCards, setDueCards] = useState<KnowledgeCard[]>([]);
  // 复习反馈后自动消失的轻提示（无需确认）
  const [toast, setToast] = useState<{ kind: 'good' | 'bad'; text: string } | null>(null);
  const toastTimer = useRef<number>();
  // 复习前默认只显示标题；查看状态：hidden → summary(摘要) → detail(AI 完整回答)
  const [reveal, setReveal] = useState<Map<number, 'summary' | 'detail'>>(new Map());

  // 卡片：切到卡片 tab 时拉取到期待复习卡片
  const loadDue = () => knowledgeApi.due().then(setDueCards).catch(() => {});
  useEffect(() => {
    if (tab === 'card') {
      setReveal(new Map());
      loadDue();
    }
  }, [tab]);

  // 随手记：切到 note tab 时加载笔记列表
  useEffect(() => {
    if (tab === 'note') {
      knowledgeApi.listNotes().then(setNotes).catch(() => setNotes([]));
    }
  }, [tab]);

  const setCardView = (id: number, v: 'hidden' | 'summary' | 'detail') => {
    setReveal(prev => {
      const next = new Map(prev);
      if (v === 'hidden') next.delete(id); else next.set(id, v);
      return next;
    });
  };

  const showToast = (kind: 'good' | 'bad', text: string) => {
    setToast({ kind, text });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 1500);
  };

  // 卡片操作：复习后立即从待复习列表移除（卡片不再占用待复习），并弹出「下次复习时间」轻提示
  const reviewCard = async (id: number, mastered: boolean) => {
    setErr('');
    try {
      const updated = await knowledgeApi.review(id, mastered);
      setDueCards(prev => prev.filter(c => c.id !== id));
      const days = daysUntilDue(updated.dueAt);
      showToast(
        mastered ? 'good' : 'bad',
        mastered ? `已掌握，${days} 天后再复习` : '没掌握，明天继续复习',
      );
    } catch (e) {
      setErr(msg(e));
    }
  };

  useEffect(() => {
    let alive = true;
    Promise.all([drill.debt(), studyPlan.list()])
      .then(([d, p]) => {
        if (alive) {
          setDebt(d);
          setPlans(p);
        }
      })
      .catch((e) => {
        if (alive) setErr(msg(e));
      });
    return () => {
      alive = false;
    };
  }, []);

  /** 复盘：跳转到复盘子页面（题目 + AI 复盘报告 + 自省笔记） */
  const review = (d: DebtView) => navigate(`/notes/review/${d.runId}`);

  /** 判断自测：对该概念生成一道新题（非原题），进练习聊天作答 */
  const selfTest = (d: DebtView) => {
    if (d.conceptId == null) return;
    navigate('/drill', { state: { conceptId: d.conceptId }, replace: true });
  };

  /** 删除这条欠账记录（级联删除该 run 的作答/判分/复盘），删除前二次确认 */
  const del = async (d: DebtView) => {
    const stemShort = d.stem.length > 36 ? d.stem.slice(0, 36) + '…' : d.stem;
    if (!window.confirm(
      `确定删除这条欠账记录？\n\n「${stemShort}」\n\n该题的作答、判分与 AI 复盘数据都会被删除，且不可恢复。`,
    )) return;
    setDeletingId(d.runId);
    setErr('');
    try {
      await drill.deleteRun(d.runId);
      setDebt((prev) => (prev ? prev.filter((x) => x.runId !== d.runId) : prev));
    } catch (e) {
      setErr(msg(e));
    } finally {
      setDeletingId(null);
    }
  };

    // 删除随手记
  const handleNoteDelete = async (id: number) => {
    if (!window.confirm('确定删除这条随手记吗？此操作不可恢复。')) return;
    setErr('');
    try {
      await knowledgeApi.deleteNote(id);
      setNotes((prev) => (prev ? prev.filter((n) => n.id !== id) : prev));
    } catch (e) {
      setErr(msg(e));
    }
  };

  // 当前学习方向（切换需确认）：复盘按方向过滤
  const { activeId } = useActivePlan(plans);
  const debtActive = debt ? debt.filter((d) => d.planId === activeId) : [];
  const groups = debtActive.length ? groupDebt(debtActive, plans) : [];

  return (
    <div className="page">
      <header className="page-head">
        <span className="eyebrow">内化复盘 · NOTE</span>
        <h1>内化复盘</h1>
        <p>
          判分低于达标线（{PASS_LINE} 分）的题会留在这里。点「复盘」看 AI 生成的
          欠缺/思路/口诀并写自省，或点「判断自测」出新题再练一遍；达标的题不会进来。
        </p>
      </header>

      {err && <div className="banner info">{err}</div>}

      {/* ===== 顶部：tab 切换「学习中 / 知识卡片」（与学习计划页同款下划线式） ===== */}
      <nav className="notes-tabs">
        <button
          className={'notes-tab' + (tab === 'debt' ? ' active' : '')}
          onClick={() => setTab('debt')}
        >
          <BookOpen size={14} strokeWidth={1.6} />
          学习中
        </button>
        <button
          className={'notes-tab' + (tab === 'card' ? ' active' : '')}
          onClick={() => setTab('card')}
        >
          <Layers size={14} strokeWidth={1.6} />
          知识卡片
          {dueCards.length > 0 && <span className="notes-tab-badge">{dueCards.length}</span>}
        </button>
        <button
          className={'notes-tab' + (tab === 'note' ? ' active' : '')}
          onClick={() => setTab('note')}
        >
          <MessageCircle size={14} strokeWidth={1.6} />
          随手记
        </button>
      </nav>

      {/* 复习反馈轻提示：无确认、自动消失 */}
      {toast && (
        <div className={'notes-toast notes-toast-' + toast.kind}>
          {toast.kind === 'good'
            ? <Check size={15} strokeWidth={2} />
            : <RefreshCw size={15} strokeWidth={2} />}
          {toast.text}
        </div>
      )}

      {/* ===== Tab 1：练习欠账（现有逻辑） ===== */}
      {tab === 'debt' && (
          debt === null ? (
              <Loading label="读取欠账…" />
          ) : debtActive.length === 0 ? (
              <div className="empty">
                <h3>这个方向没有待复盘</h3>
                <p>所有低于 {PASS_LINE} 分达标线的题都已复盘，这一关你守住了。</p>
              </div>
          ) : (
              <div className="notes-waterfall">
                {groups.map((g) => (
                    <section className="notes-group" key={g.planId ?? 'global'}>
                      <div className="notes-group-head">
                        <BookOpen size={14} strokeWidth={1.6} />
                        <span className="notes-group-title">{g.title}</span>
                        <span className="notes-group-count">{g.items.length} 条</span>
                      </div>
                      <div className="notes-masonry">
                        {g.items.map((d) => (
                            <Card className="debt-card" key={d.runId}>
                              <div className="debt-card-top">
                                <span className="debt-card-stem">{d.stem}</span>
                                <Badge kind="bad">{Math.round(d.rawScore)} 分</Badge>
                              </div>
                              {(d.weakPoints?.length ?? 0) > 0 && (
                                  <div className="debt-card-weak" title={d.weakPoints.join('；')}>
                                    <AlertTriangle size={12} strokeWidth={1.8} />
                                    {(d.weakPoints ?? []).slice(0, 2).join('、')}
                                    {(d.weakPoints?.length ?? 0) > 2 && ` 等 ${d.weakPoints.length} 处`}
                                  </div>
                              )}
                              <div className="debt-card-foot">
                                <Button
                                    variant="danger"
                                    className="debt-card-del"
                                    onClick={() => del(d)}
                                    disabled={deletingId === d.runId}
                                    title="删除这条欠账记录（含作答/判分/复盘数据）"
                                >
                                  <Trash2 size={14} strokeWidth={1.8} /> 删除
                                </Button>
                                <Button variant="ghost" onClick={() => review(d)}>
                                  <PenLine size={14} strokeWidth={1.8} /> 复盘
                                </Button>
                                <Button
                                    variant="primary"
                                    onClick={() => selfTest(d)}
                                    disabled={d.conceptId == null}
                                    title={d.conceptId == null ? '该题没有关联概念，无法自测' : '对该概念出一道新题再练'}
                                >
                                  <Target size={14} strokeWidth={1.8} /> 判断自测
                                </Button>
                              </div>
                            </Card>
                        ))}
                      </div>
                    </section>
                ))}
              </div>
          )
      )}

      {/* ===== Tab 2：知识卡片（复习前只显示标题；查看答案→摘要；查看详细答案→AI 完整回答） ===== */}
      {tab === 'card' && (
          dueCards.length === 0 ? (
              <div className="empty">
                <h3>没有到期待复习的卡片</h3>
                <p>去「对话沉淀」随手提问并存成卡片，到期后会出现在这里。</p>
              </div>
          ) : (
              <div className="notes-waterfall">
                {dueCards.map((c) => {
                  const view = reveal.get(c.id) ?? 'hidden';
                  return (
                    <Card className="review-card" key={c.id}>
                      <div className="review-card-title">{c.question}</div>
                      <CardMeta card={c} />
                      {view === 'summary' && c.answer && (
                          <div className="review-card-body">
                            <Markdown>{c.answer}</Markdown>
                          </div>
                      )}
                      {view === 'detail' && (
                          <>
                            {c.answer && (
                                <div className="review-card-body">
                                  <Markdown>{c.answer}</Markdown>
                                </div>
                            )}
                            {c.detail ? (
                                <div className="review-card-body review-card-detail">
                                  <span className="card-detail-label">AI 完整回答</span>
                                  <Markdown>{c.detail}</Markdown>
                                </div>
                            ) : (
                                <div className="review-card-body">（没有保存详细回答）</div>
                            )}
                          </>
                      )}
                      <div className="review-card-foot">
                        <div className="review-card-actions">
                          {view === 'hidden' ? (
                              <Button variant="ghost" onClick={() => setCardView(c.id, 'summary')}>
                                <Eye size={14} strokeWidth={1.8} /> 查看答案
                              </Button>
                          ) : (
                              <>
                                {view === 'summary' && c.detail && (
                                    <Button variant="ghost" onClick={() => setCardView(c.id, 'detail')}>
                                      <FileText size={14} strokeWidth={1.8} /> 查看详细答案
                                    </Button>
                                )}
                                <Button variant="ghost" onClick={() => setCardView(c.id, 'hidden')}>
                                  <EyeOff size={14} strokeWidth={1.8} /> 收起
                                </Button>
                              </>
                          )}
                        </div>
                        <div className="review-card-actions">
                          <button className="review-btn review-btn-no" onClick={() => reviewCard(c.id, false)}>
                            <RefreshCw size={14} strokeWidth={1.8} /> 没掌握
                          </button>
                          <button className="review-btn review-btn-yes" onClick={() => reviewCard(c.id, true)}>
                            <Check size={14} strokeWidth={1.8} /> 掌握了
                          </button>
                        </div>
                      </div>
                    </Card>
                  );
                })}
              </div>
          )
      )}
      {/* ===== Tab 3：随手记 ===== */}
      {tab === 'note' && (
        notes === null ? (
          <Loading label="读取随手记…" />
        ) : (
          <>
            <div className="note-toolbar">
              <input
                className="note-search"
                placeholder="搜索标题…"
                value={noteSearch}
                onChange={(e) => setNoteSearch(e.target.value)}
              />
              <select
                className="note-select"
                value={noteConceptId ?? ''}
                onChange={(e) => setNoteConceptId(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">所有知识点</option>
                {(() => {
                  const map = new Map<number, string>();
                  notes?.forEach((n) => {
                    if (n.conceptId && n.conceptName) map.set(n.conceptId, n.conceptName);
                  });
                  return [...map.entries()].map(([id, name]) => (
                    <option key={id} value={id}>{name}</option>
                  ));
                })()}
              </select>
              <Button className="note-new-btn" onClick={() => setShowNoteDialog(true)}>
                <Plus size={15} strokeWidth={2} /> 新建
              </Button>
            </div>
            {(() => {
              const filtered = (notes ?? []).filter((n) => {
                const matchTitle = !noteSearch || n.title.toLowerCase().includes(noteSearch.toLowerCase());
                const matchConcept = noteConceptId == null || n.conceptId === noteConceptId;
                return matchTitle && matchConcept;
              });
              if (filtered.length === 0) {
                return <div className="empty"><h3>没有匹配的随手记</h3></div>;
              }
              return (
                <div className="notes-waterfall">
                  {filtered.map((n) => {
                    const raw = (n.content || '').split('\n')[0].trim();
                    const snippet = raw.length > 40 ? raw.slice(0, 40) + '…' : raw;
                    return (
                      <Card className="casual-note-card" key={n.id}>
                        <h3 className="note-title">{n.title}</h3>
                        <p className="note-snippet">{snippet || '（暂无内容）'}</p>
                        {n.conceptName && <span className="note-concept">{n.conceptName}</span>}
                        <div className="note-card-actions">
                          <Button variant="danger" onClick={() => handleNoteDelete(n.id)}>删除</Button>
                        </div>
                      </Card>
                    );
                  })}
                </div>
              );
            })()}
          </>
        )
      )}
      {showNoteDialog && (
        <CasualNoteDialog
          runId={null}
          onClose={() => setShowNoteDialog(false)}
          onSaved={() => {
            knowledgeApi.listNotes().then(setNotes).catch(() => undefined);
          }}
        />
      )}
    </div>
  );


}
