import { useEffect, useRef, useState } from 'react';
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

export function CapturePage() {
    const [msgs, setMsgs] = useState<ChatMsg[]>([]);
    const [cards, setCards] = useState<KnowledgeCard[]>([]);
    const [input, setInput] = useState('');
    const [streaming, setStreaming] = useState(false);
    const [auto, setAuto] = useState(false);
    const [err, setErr] = useState('');
    const timer = useRef<number>();
    const streamRef = useRef<{ cancel: () => void } | null>(null);

    useEffect(() => {
        knowledgeApi.list().then(setCards).catch(() => {});
        return () => streamRef.current?.cancel();
    }, []);

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

            <div className="capture-input">
                <input
                    value={input}
                    onChange={e => setInput(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && ask(input)}
                    placeholder="随便问点什么…"
                    disabled={streaming}
                />
                <Button onClick={() => ask(input)} disabled={streaming || !input.trim()}>提问</Button>
                <Button onClick={settle} disabled={msgs.length === 0 || streaming} variant="primary">存成卡片</Button>
            </div>

            <section className="capture-cards">
                <h2>知识卡片</h2>
                {cards.length === 0 ? (
                    <div className="empty">还没有卡片，试着提问并保存一次吧</div>
                ) : (
                    cards.map(c => (
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
                                onClick={() => knowledgeApi.remove(c.id).then(() => setCards(cs => cs.filter(x => x.id !== c.id)))}
                            >
                                删除
                            </button>
                        </Card>
                    ))
                )}
            </section>
        </div>
    );
}
