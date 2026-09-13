import { CheckCircle2, CircleMinus, XCircle } from 'lucide-react';
import type { QuestionEvaluation, ScoringPointStatus } from '../api/interview';

interface InterviewScoringBreakdownProps {
  evaluation?: QuestionEvaluation | null;
}

const STATUS_VIEW: Record<ScoringPointStatus, { label: string; icon: typeof CheckCircle2 }> = {
  ACHIEVED: { label: '已达成', icon: CheckCircle2 },
  PARTIAL: { label: '部分达成', icon: CircleMinus },
  MISSED: { label: '未达成', icon: XCircle },
};

export function InterviewScoringBreakdown({ evaluation }: InterviewScoringBreakdownProps) {
  const points = evaluation?.scoringPoints ?? [];
  if (points.length === 0) return null;

  return (
    <div className="iv-rubric">
      <div className="iv-rubric-head">
        <span>详细评分点</span>
        <span>{evaluation?.score ?? 0} / 100</span>
      </div>
      <div className="iv-rubric-list">
        {points.map((point, index) => {
          const view = STATUS_VIEW[point.status] ?? STATUS_VIEW.PARTIAL;
          const StatusIcon = view.icon;
          return (
            <div className={`iv-rubric-item ${point.status.toLowerCase()}`} key={`${point.criterion}-${index}`}>
              <div className="iv-rubric-item-head">
                <span className="iv-rubric-status">
                  <StatusIcon size={15} strokeWidth={2} /> {view.label}
                </span>
                <strong>{point.awardedScore} / {point.maxScore} 分</strong>
              </div>
              <div className="iv-rubric-criterion">{point.criterion}</div>
              <div className="iv-rubric-detail">
                <span>回答依据</span>
                <p>{point.evidence}</p>
              </div>
              <div className="iv-rubric-detail">
                <span>评分说明</span>
                <p>{point.reason}</p>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
