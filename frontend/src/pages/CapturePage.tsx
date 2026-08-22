import { useEffect, useMemo, useRef, useState } from 'react';
import { Send } from 'lucide-react';
import { knowledgeApi, askStream, type ChatMsg } from '../api/knowledge';
import { Button, Card, Badge } from '../components/ui';
import { Markdown } from '../components/Markdown';
import type { KnowledgeCard } from '../api/types';
import { ApiError } from '../api/client';
import './CapturePage.css';

function msg(e: unknown): string {
    if (e instanceof ApiError) return e.message;
    if (e instanceof Error && e.message) return e.message;
    return '操作失败，请重试';
}

/** 卡片每页条数 */
const PAGE_SIZE = 8;

export function CapturePage() {
    const [msgs, setMsgs] = useState<ChatMsg[]>([]);
    const [cards, setCards] = useState<KnowledgeCard[]>([]);
    const [input, setInput] = useState('');
    const [streaming, setStreaming] = useState(false);
    const [auto, setAuto] = useState(false);
    const [err, setErr] = useState('');
    const [page, setPage] = useState(1);
    const [filterTag, setFilterTag] = useState<string | null>(null);
    const timer = useRef<number>();
    const streamRef = useRef<{ cancel: () => void } | null>(null);
    const composingRef = useRef(false);

    useEffect(() => {
        knowledgeApi.list().then(setCards).catch(() => {});
        return () => streamRef.current?.cancel();
    }, []);

    // ===== 卡片筛选 + 分页（本地计算，翻页/筛选零延迟） =====
    const allTags = useMemo(() => {
        const s = new Set<string>();
        cards.forEach(c => c.tags.forEach(t => s.add(t)));
        return [...s].sort((a, b) => a.localeCompare(b, 'zh'));
    }, [cards]);
    const tagCounts = useMemo(() => {
        const m = new Map<string, number>();
        cards.forEach(c => c.tags.forEach(t => m.set(t, (m.get(t) ?? 0) + 1)));
        return m;
    }, [cards]);
    const filtered = useMemo(() =>
        filterTag ? cards.filter(c => c.tags.includes(filterTag)) : cards,
        [cards, filterTag]);
    const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
    const safePage = Math.min(page, totalPages);
    const pageCards = useMemo(() =>
        filtered.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE),
        [filtered, safePage]);

    const pickTag = (t: string | null) => { setFilterTag(t); setPage(1); };

    const removeCard = (id: number) => {
        setCards(cs => cs.filter(x => x.id !== id));
        // 删到当前页空了（且不是第一页）时回退一页
        setPage(p => Math.min(p, Math.max(1, Math.ceil((filtered.length - 1) / PAGE_SIZE))));
    };

    const ask = (q: string) => {
        if (!q.trim() || streaming) return;
        setInput('');
        setErr('');
        setStreaming(true);
        setMsgs(m => [...m, { role: 'user', content: q }, { role: 'ai', content: '' }]);

        const stream = askStream(
            q.trim(),
            (token) => setMsgs(m => m.map((x, i) => i === m.length - 1 ? { ...x, content: x.content + token } : x)),
            () => { setStreaming(false); streamRef.current = null; },
            (e) => {
                setErr(e ?? '回答失败，请检查 AI 设置或稍后重试');
                setMsgs(m => m.map((x, i) => i === m.length - 1 ? { ...x, content: '（回答失败）' } : x));
                setStreaming(false); streamRef.current = null;
            },
        );
        streamRef.current = stream;
    };

    const settle = async () => {
        if (msgs.length === 0 || streaming) return;
        setErr('');
        try {
            const card = await knowledgeApi.capture(msgs);
            setCards(c => [card, ...c]);
            setMsgs([]);
            setPage(1); // 新卡在列表头部，回到第一页让用户看到
        } catch (e) {
            // 无价值对话 / 未配置 key / LLM 失败都会走到这里：明确提示，不清空对话，方便用户重试
            setErr(msg(e));
        }
    };

    useEffect(() => {
        if (!auto || msgs.length === 0 || streaming) return;
        timer.current = window.setTimeout(settle, 10000);
        return () => window.clearTimeout(timer.current);
    }, [msgs, auto, streaming]);

    return (
        <div className="page capture">
            <header className="page-head">
                <span className="eyebrow">对话沉淀 · CAPTURE</span>
                <h1>随手记</h1>
                <p>随便问，AI 答。有价值的对话一键存成知识卡片，进入内化复盘。</p>
                <label className="capture-auto">
                    <input type="checkbox" checked={auto} onChange={e => setAuto(e.target.checked)} />
                    停顿 10 秒自动成卡
                </label>
            </header>

            {err && <div className="banner warn">{err}</div>}

            <Card className="capture-chat">
                {msgs.map((m, i) => (
                    <div key={i} className={'msg ' + m.role}>
                        {m.role === 'user' ? (
                            m.content
                        ) : (
                            <Markdown>{m.content || ' '}</Markdown>
                        )}
                    </div>
                ))}
                {streaming && <div className="msg ai cursor-blink">▍</div>}
            </Card>

            <Card className="capture-composer">
                <textarea
                    className="capture-composer-textarea"
                    value={input}
                    onChange={e => setInput(e.target.value)}
                    onCompositionStart={() => { composingRef.current = true; }}
                    onCompositionEnd={() => { composingRef.current = false; }}
                    onKeyDown={(e) => {
                        // 输入法组字（composition）期间按回车只是确认候选词，不能当成发送
                        if (composingRef.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
                        if (e.key === 'Enter' && !e.shiftKey) {
                            e.preventDefault();
                            ask(input);
                        }
                    }}
                    placeholder="随便问点什么…（Enter 发送，Shift+Enter 换行）"
                    rows={2}
                    disabled={streaming}
                />
                <div className="capture-composer-foot">
                    <span className="capture-composer-hint">
                        {streaming
                            ? '回答生成中…'
                            : auto
                                ? '已开启自动成卡：停顿 10 秒自动保存'
                                : '有价值的对话可一键存成知识卡片'}
                    </span>
                    <div className="capture-composer-actions">
                        <Button onClick={() => ask(input)} disabled={streaming || !input.trim()}>
                            <Send size={16} strokeWidth={1.6} /> 提问
                        </Button>
                        <Button onClick={settle} disabled={msgs.length === 0 || streaming} variant="primary">存成卡片</Button>
                    </div>
                </div>
            </Card>

            <section className="capture-cards">
                <h2>知识卡片</h2>

                {cards.length === 0 ? (
                    <div className="empty">还没有卡片，试着提问并保存一次吧</div>
                ) : (
                    <>
                        {/* 标签筛选 chips */}
                        <div className="capture-filters">
                            <button
                                className={'capture-filter' + (filterTag === null ? ' active' : '')}
                                onClick={() => pickTag(null)}
                            >
                                全部
                                <span className="capture-filter-count">{cards.length}</span>
                            </button>
                            {allTags.map(t => (
                                <button
                                    key={t}
                                    className={'capture-filter' + (filterTag === t ? ' active' : '')}
                                    onClick={() => pickTag(t)}
                                >
                                    {t}
                                    <span className="capture-filter-count">{tagCounts.get(t)}</span>
                                </button>
                            ))}
                        </div>

                        {pageCards.length === 0 ? (
                            <div className="empty">这个标签下还没有卡片</div>
                        ) : (
                            pageCards.map(c => (
                                <Card key={c.id} className="card-item">
                                    <div className="card-q">{c.question}</div>
                                    {c.answer && <div className="card-a">{c.answer}</div>}
                                    {c.tags.length > 0 && (
                                        <div className="card-tags">
                                            {c.tags.map(t => <Badge key={t}>{t}</Badge>)}
                                        </div>
                                    )}
                                    <button
                                        className="card-del"
                                        onClick={() => knowledgeApi.remove(c.id).then(() => removeCard(c.id))}
                                    >
                                        删除
                                    </button>
                                </Card>
                            ))
                        )}

                        {/* 分页器 */}
                        {totalPages > 1 && (
                            <div className="capture-pager">
                                <button
                                    className="capture-pager-btn"
                                    disabled={safePage <= 1}
                                    onClick={() => setPage(safePage - 1)}
                                >
                                    ‹ 上一页
                                </button>
                                <span className="capture-pager-info">
                                    第 {safePage} / {totalPages} 页 · 共 {filtered.length} 张
                                </span>
                                <button
                                    className="capture-pager-btn"
                                    disabled={safePage >= totalPages}
                                    onClick={() => setPage(safePage + 1)}
                                >
                                    下一页 ›
                                </button>
                            </div>
                        )}
                    </>
                )}
            </section>
        </div>
    );
}
