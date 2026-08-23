import { GRADE_LABEL, VERDICT_LABEL, verdictClass } from '../lib/labels';
import type { ConversationView, ConversationRun, ConversationTurn, ByConcept } from '../api/types';
import { Markdown } from './Markdown';
import './ConversationStream.css';

function parseByConcept(json?: string | null): ByConcept[] {
  if (!json) return [];
  try {
    return JSON.parse(json) as ByConcept[];
  } catch {
    return [];
  }
}

interface Props {
  conv: ConversationView;
  /** 底部动作条（Drill 传继续追问 + 写笔记，History 传继续练习） */
  footer?: React.ReactNode;
  /** 是否显示题目作为开场气泡（Drill 顶部已固定题目，传 false 避免重复） */
  showStem?: boolean;
}

/**
 * 聊天气泡形态：AI 题目 → Me 回答 → AI 判分 → (追问) AI 提问 → Me 答 → AI 判分 …
 * 不再按 run 分块；所有轮次按时间顺序串起来，左右气泡对齐。
 */
export function ConversationStream({ conv, footer, showStem = true }: Props) {
  // 扁平化所有 run 的所有 turn，按时间顺序
  const flat: { run: ConversationRun; turn: ConversationTurn }[] = [];
  for (const run of conv.runs) {
    for (const t of run.turns) flat.push({ run, turn: t });
  }

  return (
    <div className="chat-stream">
      {showStem && (
        <ChatRow side="ai">
          <Bubble side="ai" isStem>
            <Markdown>{conv.stem}</Markdown>
          </Bubble>
        </ChatRow>
      )}

      {flat.map(({ run, turn }) => (
        <TurnBubbles
          key={`${run.runId}:${turn.round}`}
          run={run}
          turn={turn}
        />
      ))}

      {footer && <div className="chat-footer">{footer}</div>}
    </div>
  );
}

function TurnBubbles({ run, turn }: { run: ConversationRun; turn: ConversationTurn }) {
  // REHEARSAL 的 round >= 1 才有独立追问题干。
  // LEARN 聊天的每个 turn.stem 都只是数据库为判分保存的原始主问题，不能重复渲染成“追问”。
  const showAsk = run.mode === 'REHEARSAL' && turn.round > 0;
  // 评分只在真正判过分的轮次渲染（判分会把 byConceptJson 写回 turn）。
  // 已判分记录「继续对话」追加的纯提问轮没有判分数据，不渲染空判分面板。
  const showVerdict = run.mode === 'LEARN' && turn.byConceptJson != null;
  // REHEARSAL 的 round 0 是原题占位 stem，不是用户作答轮，不能显示"（未作答）"
  const showAnswer = !(run.mode === 'REHEARSAL' && turn.round === 0);

  return (
    <>
      {showAsk && (
        <ChatRow side="ai">
          <Bubble side="ai">
            <span className="chat-round-tag">追问 R{turn.round}</span>
            <Markdown>{turn.stem}</Markdown>
          </Bubble>
        </ChatRow>
      )}
      {showAnswer && (
        <ChatRow side="me">
          <Bubble side="me">{turn.rawAnswer ?? '（未作答）'}</Bubble>
        </ChatRow>
      )}
      {showVerdict && (
        <ChatRow side="ai">
          <Bubble side="ai" isVerdict>
            <VerdictPanel run={run} turn={turn} />
          </Bubble>
        </ChatRow>
      )}
      {turn.tutorText && (
        <ChatRow side="ai">
          <Bubble side="ai" isTutor>
            <div className="tutor-text">
              {/* markdown 渲染：代码块 / 列表 / 加粗等结构正常呈现 */}
              <Markdown>{turn.tutorText}</Markdown>
            </div>
          </Bubble>
        </ChatRow>
      )}
    </>
  );
}

function ChatRow({
  side,
  children,
}: {
  side: 'ai' | 'me';
  children: React.ReactNode;
}) {
  return (
    <div className={`chat-row chat-row-${side}`}>
      {side === 'ai' && <Avatar side="ai" />}
      {children}
      {side === 'me' && <Avatar side="me" />}
    </div>
  );
}

function Avatar({ side }: { side: 'ai' | 'me' }) {
  return (
    <div className={`chat-avatar chat-avatar-${side}`}>
      <span>{side === 'ai' ? 'AI' : '我'}</span>
    </div>
  );
}

function Bubble({
  side,
  isStem,
  isVerdict,
  isTutor,
  children,
}: {
  side: 'ai' | 'me';
  isStem?: boolean;
  isVerdict?: boolean;
  isTutor?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      className={`chat-bubble chat-bubble-${side}${isStem ? ' is-stem' : ''}${isVerdict ? ' is-verdict' : ''}${isTutor ? ' is-tutor' : ''}`}
    >
      {children}
    </div>
  );
}

export function VerdictPanel({ run, turn }: { run: ConversationRun; turn: ConversationTurn }) {
  const data = parseByConcept(turn.byConceptJson);
  // 未考察（NA）的评分点 = 没被实际问到的追问内容：不计分，也不列入评分细则展示
  const visible = data
    .map((c) => ({ ...c, pointResults: c.pointResults.filter((p) => p.verdict?.toUpperCase() !== 'NA') }))
    .filter((c) => c.pointResults.length > 0);
  const pointCount = visible.reduce((n, c) => n + c.pointResults.length, 0);
  return (
    <div className="verdict-panel">
      <div className="verdict-summary">
        <span className="verdict-score">{turn.rawScore.toFixed(0)}</span>
        <small>分</small>
        <span className={`verdict-badge ${passedClass(turn.passed)}`}>
          {passedLabel(turn.passed)}
        </span>
        {run.turns.length > 0 &&
          turn.round === Math.max(...run.turns.map((t) => t.round)) &&
          run.rawScore != null && (
            <span className="verdict-grade">run 总评 · {runGradeLabel(run)}</span>
          )}
      </div>
      {pointCount > 0 && (
        <details className="verdict-details">
          <summary className="verdict-details-summary">
            <span className="verdict-details-icon">▾</span>
            评分标准（{pointCount} 条）
          </summary>
          <ul className="verdict-points">
            {visible.flatMap((c) =>
              c.pointResults.map((p, i) => (
                <li key={`${c.conceptId}-${i}`}>
                  <span className={`verdict-dot ${verdictClass(p.verdict)}`} />
                  <div className="point-body">
                    <span className={`verdict-tag ${verdictClass(p.verdict)}`}>
                      {VERDICT_LABEL[p.verdict] ?? p.verdict}
                    </span>
                    {/* 评分点（讲解）与佐证（证据）均为 AI 生成，可能含加粗 / 代码 / 列表，统一走 markdown 渲染 */}
                    <Markdown className="point-md">{p.point}</Markdown>
                    {p.evidence && <Markdown className="evidence-md">{p.evidence}</Markdown>}
                  </div>
                </li>
              )),
            )}
          </ul>
        </details>
      )}
    </div>
  );
}

function passedClass(p: boolean | null): string {
  if (p === true) return 'pass';
  if (p === false) return 'fail';
  return '';
}
function passedLabel(p: boolean | null): string {
  if (p === true) return '通过';
  if (p === false) return '未中';
  return '—';
}
function runGradeLabel(run: ConversationRun): string {
  return GRADE_LABEL[run.grade ?? ''] ?? run.grade ?? '—';
}
