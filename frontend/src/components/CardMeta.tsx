import type { KnowledgeCard } from '../api/types';

/** 下次复习时间的统一展示：今天(红) / 明天 / N天后(绿) */
export function formatDue(dueAt: string | null): { text: string; urgent: boolean } | null {
    if (!dueAt) return null;
    const now = new Date();
    const due = new Date(dueAt);
    const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const startDue = new Date(due.getFullYear(), due.getMonth(), due.getDate()).getTime();
    const days = Math.round((startDue - startToday) / 86400000);
    if (days <= 0) return { text: '今天', urgent: true }; // 已到期 / 今天就该复习
    if (days === 1) return { text: '明天', urgent: false };
    return { text: `${days}天后`, urgent: false };
}

/** 知识卡片复习元信息：已复习 N 次 + 下次复习时间（对话沉淀与内化复盘通用；内化复盘可不显示时间） */
export function CardMeta({ card, showDue = true }: { card: KnowledgeCard; showDue?: boolean }) {
    const due = showDue ? formatDue(card.dueAt) : null;
    return (
        <div className="card-meta">
            <span className="card-meta-item">
                已复习 {card.reviewCount} 次
            </span>
            {due && (
                <span className={'card-meta-item card-meta-due' + (due.urgent ? ' urgent' : '')}>
                    下次复习：{due.text}
                </span>
            )}
        </div>
    );
}
