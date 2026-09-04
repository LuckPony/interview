import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { BookmarkPlus, Check, ChevronDown, ChevronUp, EyeOff, FileText, PencilLine, RotateCcw, Search, Send, Sparkles, Square, X } from 'lucide-react';
import { knowledgeApi, askStream, type ChatMsg } from '../api/knowledge';
import { Button, Card, Badge } from '../components/ui';
import { Markdown } from '../components/Markdown';
import { CardMeta } from '../components/CardMeta';
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

/** 会话级持久化：切换路由再返回时恢复对话与卡片（同一标签页内有效） */
const SESSION_MSGS = 'yan.capture.msgs';
const SESSION_CARDS = 'yan.capture.cards';
const SESSION_SAVED_TURNS = 'yan.capture.saved-turns';

/** 模块级工具：从会话存储读取；实际读取在 useState 惰性初始化里（每次组件挂载都执行，SPA 路由回来也能拿到最新值） */
function loadSession<T>(key: string): T | null {
    try {
        const raw = sessionStorage.getItem(key);
        return raw ? JSON.parse(raw) as T : null;
    } catch { return null; }
}

function initialMsgs(): ChatMsg[] {
    const m = loadSession<ChatMsg[]>(SESSION_MSGS);
    if (!m) return [];
    let list = m;
    // 去掉尾部未完成的空 AI 消息（流中断残留，避免恢复后卡在「思考中」）
    while (list.length > 0 && list[list.length - 1].role === 'ai' && !list[list.length - 1].content.trim()) list = list.slice(0, -1);
    return list;
}

function initialSavedTurns(): Set<number> {
    const saved = loadSession<number[]>(SESSION_SAVED_TURNS) ?? [];
    return new Set(saved.filter(Number.isSafeInteger));
}

/** 空状态示例问题（兜底：没有卡片/标签时用）；有标签时按标签频次动态推荐 */
const DEFAULT_SUGGESTIONS = [
    '什么是 CAP 定理？',
    'Java 内存模型 JMM 讲一下',
    'Spring 循环依赖怎么解决？',
    'Redis 缓存穿透怎么办？',
];

/** 按高频标签生成推荐问题的模板（与 top4 一一对应，避免每次都是同一个句式） */
const SUGGEST_TEMPLATES = [
    (t: string) => `再深入讲讲「${t}」？`,
    (t: string) => `「${t}」常见的坑有哪些？`,
    (t: string) => `工作中怎么用好「${t}」？`,
    (t: string) => `「${t}」和哪些知识点容易混？`,
];

/** 卡片标签块：默认最多两行；若第二行还要换行，就在右下角显示带箭头的「展开」，点击展开全部 */
function CardTags({ tags }: { tags: string[] }) {
    const [expanded, setExpanded] = useState(false);
    const [overflow, setOverflow] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    // useLayoutEffect：测量类副作用必须在布局阶段同步执行，避免 useEffect 异步时序导致误判。
    // 溢出判断用 scrollHeight（内容全高，不受 max-height/transition 裁剪影响），可靠稳定。
    useLayoutEffect(() => {
        const el = ref.current;
        if (!el) return;
        const check = () => {
            const first = el.querySelector('.badge');
            const rowH = first ? first.getBoundingClientRect().height : 22;
            const gap = 8; // var(--s-2)
            const twoRows = rowH * 2 + gap;
            el.style.maxHeight = expanded ? '' : `${twoRows.toFixed(1)}px`;
            setOverflow(el.scrollHeight > twoRows + 1);
        };
        check();
        const ro = new ResizeObserver(check);
        ro.observe(el);
        return () => ro.disconnect();
    }, [tags, expanded]);

    return (
        <div className={'card-tags-wrap' + (overflow ? ' has-toggle' : '')}>
            <div ref={ref} className={'card-tags' + (expanded ? ' expanded' : '')}>
                {tags.map(t => <Badge key={t}>{t}</Badge>)}
            </div>
            {(overflow || expanded) && (
                <button className="card-tags-toggle" onClick={() => setExpanded(v => !v)}>
                    {expanded ? <><ChevronUp size={12} strokeWidth={2} /> 收起</> : <><ChevronDown size={12} strokeWidth={2} /> 展开</>}
                </button>
            )}
        </div>
    );
}

/** 正在编辑的卡片表单 */
interface EditForm { question: string; answer: string; tags: string; detail: string }

export function CapturePage() {
    const [msgs, setMsgs] = useState<ChatMsg[]>(initialMsgs);
    const [cards, setCards] = useState<KnowledgeCard[]>(() => loadSession<KnowledgeCard[]>(SESSION_CARDS) ?? []);
    const [input, setInput] = useState('');
    const [streaming, setStreaming] = useState(false);
    const [auto, setAuto] = useState(false);
    const [err, setErr] = useState('');
    const [savedTurns, setSavedTurns] = useState<Set<number>>(initialSavedTurns);
    const [savingTurns, setSavingTurns] = useState<Set<number>>(new Set());
    const [page, setPage] = useState(1);
    const [filterTag, setFilterTag] = useState<string | null>(null);
    const [search, setSearch] = useState('');
    // 筛选标签栏：默认最多两行，溢出时显示「展开全部标签」
    const [filtersExpanded, setFiltersExpanded] = useState(false);
    const [filtersOverflow, setFiltersOverflow] = useState(false);
    const filtersRef = useRef<HTMLDivElement>(null);
    const [editing, setEditing] = useState<KnowledgeCard | null>(null);
    const [editForm, setEditForm] = useState<EditForm>({ question: '', answer: '', tags: '', detail: '' });
    const [editErr, setEditErr] = useState('');
    const [editBusy, setEditBusy] = useState(false);
    // 对话沉淀页：默认显示摘要答案，点「查看详细答案」再展开 AI 完整回复
    const [showDetail, setShowDetail] = useState<Map<number, boolean>>(new Map());
    const timer = useRef<number>();
    const streamRef = useRef<{ cancel: () => void } | null>(null);
    const composingRef = useRef(false);
    const chatRef = useRef<HTMLDivElement>(null);

    const hadSessionCards = useRef(cards.length > 0);
    useEffect(() => {
        // 每次进入都从后端刷新卡片（复习次数/下次复习时间等以服务端为准；快照仅用于首帧渲染）
        if (hadSessionCards.current) {
            knowledgeApi.list().then(cs => { if (cs.length) setCards(cs); }).catch(() => {});
        } else {
            knowledgeApi.list().then(setCards).catch(() => {});
        }
        return () => streamRef.current?.cancel();
    }, []);

    // 变更即写回会话存储（msgs 为空等常态也写，保证与真实状态一致）
    useEffect(() => { sessionStorage.setItem(SESSION_MSGS, JSON.stringify(msgs)); }, [msgs]);
    useEffect(() => { sessionStorage.setItem(SESSION_CARDS, JSON.stringify(cards)); }, [cards]);
    useEffect(() => {
        sessionStorage.setItem(SESSION_SAVED_TURNS, JSON.stringify([...savedTurns]));
    }, [savedTurns]);

    // 流式输出会持续改变最后一条消息的高度；每个 token 更新后跟随到底部。
    useLayoutEffect(() => {
        if (!streaming || !chatRef.current) return;
        chatRef.current.scrollTop = chatRef.current.scrollHeight;
    }, [msgs, streaming]);

    // ===== 卡片筛选 + 分页（本地计算，翻页/筛选零延迟） =====
    const allTags = useMemo(() => {
        const s = new Set<string>();
        cards.forEach(c => c.tags.forEach(t => s.add(t)));
        return [...s].sort((a, b) => a.localeCompare(b, 'zh'));
    }, [cards]);

    // 筛选标签栏两行检测：内容全高 > 两行高度即溢出（useLayoutEffect 布局阶段同步测量）
    useLayoutEffect(() => {
        const el = filtersRef.current;
        if (!el) return;
        const check = () => {
            const first = el.querySelector('.capture-filter');
            const rowH = first ? first.getBoundingClientRect().height : 30;
            const gap = 8;
            const twoRows = rowH * 2 + gap;
            el.style.maxHeight = filtersExpanded ? '' : `${twoRows.toFixed(1)}px`;
            setFiltersOverflow(el.scrollHeight > twoRows + 1);
        };
        check();
        const ro = new ResizeObserver(check);
        ro.observe(el);
        return () => ro.disconnect();
    }, [filtersExpanded, allTags]);
    const tagCounts = useMemo(() => {
        const m = new Map<string, number>();
        cards.forEach(c => c.tags.forEach(t => m.set(t, (m.get(t) ?? 0) + 1)));
        return m;
    }, [cards]);
    // 空状态推荐问题：按标签出现频次取 top4 生成；无标签时回退到默认示例
    const suggestions = useMemo(() => {
        if (cards.length === 0) return DEFAULT_SUGGESTIONS;
        const top = [...tagCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 4);
        if (top.length === 0) return DEFAULT_SUGGESTIONS;
        return top.map(([tag], i) => SUGGEST_TEMPLATES[i % SUGGEST_TEMPLATES.length](tag));
    }, [cards, tagCounts]);
    const filtered = useMemo(() => {
        let list = filterTag ? cards.filter(c => c.tags.includes(filterTag)) : cards;
        const kw = search.trim().toLowerCase();
        if (kw) {
            list = list.filter(c =>
                c.question.toLowerCase().includes(kw) ||
                c.tags.some(t => t.toLowerCase().includes(kw)));
        }
        return list;
    }, [cards, filterTag, search]);
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
            msgs,
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

    const stopAnswer = () => {
        if (!streaming) return;
        streamRef.current?.cancel();
        streamRef.current = null;
        setStreaming(false);
        setMsgs(current => current.map((item, index) => {
            if (index !== current.length - 1 || item.role !== 'ai') return item;
            return {
                ...item,
                stopped: true,
                content: item.content.trim()
                    ? `${item.content}\n\n> 已停止生成`
                    : '（已停止生成）',
            };
        }));
    };

    const saveTurn = async (answerIndex: number) => {
        const question = msgs[answerIndex - 1];
        const answer = msgs[answerIndex];
        if (!question || question.role !== 'user' || !answer || answer.role !== 'ai'
            || !answer.content.trim() || answer.stopped || savingTurns.has(answerIndex)) return;

        setErr('');
        setSavingTurns(current => new Set(current).add(answerIndex));
        try {
            const card = await knowledgeApi.capture([question, answer]);
            setCards(current => [card, ...current]);
            setSavedTurns(current => new Set(current).add(answerIndex));
            setPage(1);
        } catch (e) {
            setErr(msg(e));
        } finally {
            setSavingTurns(current => {
                const next = new Set(current);
                next.delete(answerIndex);
                return next;
            });
        }
    };

    const settle = async () => {
        if (msgs.length === 0 || streaming) return;
        setErr('');
        try {
            const card = await knowledgeApi.capture(msgs);
            setCards(c => [card, ...c]);
            setMsgs([]);
            setSavedTurns(new Set());
            setPage(1); // 新卡在列表头部，回到第一页让用户看到
        } catch (e) {
            // 无价值对话 / 未配置 key / LLM 失败都会走到这里：明确提示，不清空对话，方便用户重试
            setErr(msg(e));
        }
    };

    /** 不存卡、直接开新对话：清空当前对话（持久化随之写回空） */
    const newChat = () => {
        if (msgs.length === 0 || streaming) return;
        if (!window.confirm('当前对话还没有保存成卡片，确定开始新对话吗？')) return;
        streamRef.current?.cancel();
        setMsgs([]);
        setSavedTurns(new Set());
        setErr('');
    };

    useEffect(() => {
        if (!auto || msgs.length === 0 || streaming || msgs[msgs.length - 1]?.stopped) return;
        timer.current = window.setTimeout(settle, 10000);
        return () => window.clearTimeout(timer.current);
    }, [msgs, auto, streaming]);

    // ===== 卡片查看：默认显示摘要，点「查看详细答案」展开 AI 完整回复 =====
    const toggleDetail = (id: number) => {
        setShowDetail(prev => {
            const n = new Map(prev);
            n.set(id, !(n.get(id) ?? false));
            return n;
        });
    };

    // ===== 卡片编辑 =====
    const openEdit = (c: KnowledgeCard) => {
        setEditing(c);
        setEditForm({ question: c.question, answer: c.answer ?? '', tags: c.tags.join(','), detail: c.detail ?? '' });
        setEditErr('');
        setEditBusy(false);
    };
    const saveEdit = async () => {
        if (!editing) return;
        if (!editForm.question.trim()) { setEditErr('问题不能为空'); return; }
        setEditBusy(true);
        setEditErr('');
        try {
            const updated = await knowledgeApi.update(editing.id, {
                question: editForm.question.trim(),
                answer: editForm.answer.trim(),
                tags: editForm.tags,
                detail: editForm.detail,
            });
            // 同步本地列表（含当前筛选/分页视图）
            setCards(cs => cs.map(c => (c.id === updated.id ? updated : c)));
            setEditing(null);
        } catch (e) {
            setEditErr(msg(e));
        } finally {
            setEditBusy(false);
        }
    };

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

            <Card ref={chatRef} className="capture-chat">
                {msgs.length === 0 ? (
                    <div className="capture-empty">
                        <Sparkles size={30} strokeWidth={1.4} className="capture-empty-icon" />
                        <p>想到什么问什么，有价值的对话会沉淀成知识卡片。</p>
                        <div className="capture-suggestions">
                            {suggestions.map(s => (
                                <button key={s} className="capture-suggestion" onClick={() => ask(s)} disabled={streaming}>
                                    {s}
                                </button>
                            ))}
                        </div>
                    </div>
                ) : (
                    msgs.map((m, i) => (
                        <div key={i} className={'msg ' + m.role}>
                            {m.role === 'user' ? (
                                m.content
                            ) : m.content ? (
                                <>
                                    <Markdown>{m.content}</Markdown>
                                    {i > 0 && msgs[i - 1].role === 'user' && !m.stopped
                                        && !(streaming && i === msgs.length - 1) && (
                                        <div className="capture-turn-actions">
                                            <button
                                                type="button"
                                                className="capture-turn-save"
                                                onClick={() => saveTurn(i)}
                                                disabled={savingTurns.has(i) || savedTurns.has(i)}
                                                title="只把这一组问题和回答存成卡片"
                                            >
                                                {savedTurns.has(i)
                                                    ? <Check size={14} />
                                                    : <BookmarkPlus size={14} />}
                                                {savingTurns.has(i)
                                                    ? '保存中…'
                                                    : savedTurns.has(i)
                                                        ? '已存卡片'
                                                        : '存卡片'}
                                            </button>
                                        </div>
                                    )}
                                </>
                            ) : (
                                <span className="thinking">
                                    思考中<span className="thinking-dots">…</span>
                                    <span className="cursor-blink">▍</span>
                                </span>
                            )}
                        </div>
                    ))
                )}
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
                        <Button variant="ghost" onClick={newChat} disabled={msgs.length === 0 || streaming}>
                            <RotateCcw size={15} strokeWidth={1.6} /> 新对话
                        </Button>
                        {streaming ? (
                            <Button onClick={stopAnswer} variant="danger">
                                <Square size={14} fill="currentColor" /> 停止
                            </Button>
                        ) : (
                            <Button onClick={() => ask(input)} disabled={!input.trim()}>
                                <Send size={16} strokeWidth={1.6} /> 提问
                            </Button>
                        )}
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
                        {/* 搜索框：按标签名 / 问题内容模糊查询 */}
                        <div className="capture-search">
                            <Search size={15} strokeWidth={1.8} className="capture-search-icon" />
                            <input
                                className="capture-search-input"
                                value={search}
                                onChange={e => { setSearch(e.target.value); setPage(1); }}
                                placeholder="搜索标签或问题…"
                            />
                            {search && (
                                <button className="capture-search-clear" onClick={() => { setSearch(''); setPage(1); }} aria-label="清空搜索">
                                    <X size={14} strokeWidth={1.8} />
                                </button>
                            )}
                        </div>

                        {/* 标签筛选 chips（默认最多两行，多出显示「展开全部标签」） */}
                        <div className="capture-filters-wrap">
                            <div ref={filtersRef} className={'capture-filters' + (filtersExpanded ? ' expanded' : '')}>
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
                            {(filtersOverflow || filtersExpanded) && (
                                <button className="capture-filters-toggle" onClick={() => setFiltersExpanded(v => !v)}>
                                    {filtersExpanded
                                        ? <><ChevronUp size={12} strokeWidth={2} /> 收起</>
                                        : <><ChevronDown size={12} strokeWidth={2} /> 展开全部标签（{allTags.length + 1}）</>}
                                </button>
                            )}
                        </div>

                        {pageCards.length === 0 ? (
                            <div className="empty">{search.trim() ? '没有匹配的卡片' : '这个标签下还没有卡片'}</div>
                        ) : (
                            pageCards.map(c => {
                                const detailOpen = showDetail.get(c.id) ?? false;
                                return (
                                    <Card key={c.id} className="card-item">
                                        <div className="card-q">{c.question}</div>
                                        {c.answer && <div className="card-a"><Markdown>{c.answer}</Markdown></div>}
                                        {detailOpen && (
                                            c.detail ? (
                                                <div className="card-detail">
                                                    <span className="card-detail-label">AI 完整回答</span>
                                                    <Markdown>{c.detail}</Markdown>
                                                </div>
                                            ) : (
                                                <div className="card-a">（没有保存详细回答）</div>
                                            )
                                        )}
                                        {c.tags.length > 0 && <CardTags tags={c.tags} />}
                                        <CardMeta card={c} />
                                        <div className="card-ops">
                                            {c.detail && (
                                                <button className="card-view" onClick={() => toggleDetail(c.id)}>
                                                    {detailOpen
                                                        ? <><EyeOff size={13} strokeWidth={1.8} /> 收起</>
                                                        : <><FileText size={13} strokeWidth={1.8} /> 查看详细答案</>}
                                                </button>
                                            )}
                                            <button
                                                className="card-edit"
                                                onClick={() => openEdit(c)}
                                                title="编辑卡片（会同步到内化复盘）"
                                            >
                                                <PencilLine size={13} strokeWidth={1.8} /> 编辑
                                            </button>
                                            <button
                                                className="card-del"
                                                onClick={() => knowledgeApi.remove(c.id).then(() => removeCard(c.id))}
                                            >
                                                删除
                                            </button>
                                        </div>
                                    </Card>
                                );
                            })
                        )}

                        {/* 分页器（数据超过一页才显示） */}
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

            {/* 编辑卡片弹窗（含完整内容：摘要 + 详细回答） */}
            {editing && (
                <div className="card-edit-backdrop" onClick={() => setEditing(null)}>
                    <div className="card-edit-dialog" role="dialog" aria-modal="true" onClick={e => e.stopPropagation()}>
                        <header className="card-edit-head">
                            <span className="eyebrow">编辑知识卡片 · CARD</span>
                            <button className="card-edit-close" onClick={() => setEditing(null)} aria-label="关闭">
                                <X size={16} strokeWidth={1.8} />
                            </button>
                        </header>
                        {editErr && <div className="banner warn">{editErr}</div>}
                        <label className="field">
                            <span className="field-label">问题 / 要点</span>
                            <textarea
                                className="note-area"
                                rows={2}
                                value={editForm.question}
                                onChange={e => setEditForm(f => ({ ...f, question: e.target.value }))}
                            />
                        </label>
                        <label className="field">
                            <span className="field-label">答案摘要（查看答案时显示，支持 Markdown）</span>
                            <textarea
                                className="note-area"
                                rows={4}
                                value={editForm.answer}
                                onChange={e => setEditForm(f => ({ ...f, answer: e.target.value }))}
                            />
                        </label>
                        <label className="field">
                            <span className="field-label">详细答案（查看详细答案时显示，AI 当时回复的完整内容）</span>
                            <textarea
                                className="note-area"
                                rows={8}
                                value={editForm.detail}
                                onChange={e => setEditForm(f => ({ ...f, detail: e.target.value }))}
                            />
                        </label>
                        <label className="field">
                            <span className="field-label">标签（逗号分隔）</span>
                            <input
                                className="note-input"
                                placeholder="如：java,并发"
                                value={editForm.tags}
                                onChange={e => setEditForm(f => ({ ...f, tags: e.target.value }))}
                            />
                        </label>
                        <div className="card-edit-actions">
                            <Button variant="ghost" onClick={() => setEditing(null)}>取消</Button>
                            <Button onClick={saveEdit} disabled={editBusy}>
                                {editBusy ? '保存中…' : '保存'}
                            </Button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
