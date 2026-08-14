import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, BookOpen, Target, PenLine, Trash2 } from 'lucide-react';
import { drill, studyPlan } from '../api/drill';
import { Card, Button, Badge, Loading } from '../components/ui';
import { useActivePlan } from '../lib/useActivePlan';
import { ApiError } from '../api/client';
import type { DebtView, PlanView } from '../api/types';
import './Notes.css';

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

      {debt === null ? (
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
      )}
    </div>
  );
}
