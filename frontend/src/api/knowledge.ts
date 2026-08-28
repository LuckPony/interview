import { apiFetch, ApiError, getToken, getLlmKeyHeader } from './client';
import type { KnowledgeCard, CasualNote } from './types';

export interface ChatMsg { role: 'user' | 'ai'; content: string }
export interface AskStream { cancel: () => void }

/** 桌面端构建时 VITE_API_BASE 烘焙为后端地址；网页态为空走 dev 代理（相对 /api）。 */
const API_BASE_SSE: string = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '');

/**
 * Knowledge 模块后端统一返回 Result<{code, message, data}> 包装
 * （与 Drill 模块直接返回原始数据不同），且业务失败时 HTTP 仍是 200、只把 code 置为非 200。
 * 这里统一解包取 data；code != 200 时抛 ApiError 透出后端 message。
 */
interface Envelope<T> { code: number; message: string; data: T }

/** 后端 tags 存的是逗号分隔字符串，前端契约是 string[]，这里统一转成数组（空/缺失 → []）。 */
function normalizeCard(raw: unknown): KnowledgeCard {
    const c = (raw ?? {}) as Partial<KnowledgeCard> & { tags?: unknown };
    const t: unknown = c.tags;
    let tags: string[];
    if (Array.isArray(t)) {
        tags = t.filter((x): x is string => typeof x === 'string');
    } else if (typeof t === 'string') {
        tags = t.trim() ? t.split(',').map((s) => s.trim()).filter(Boolean) : [];
    } else {
        tags = [];
    }
    return { ...c, tags } as KnowledgeCard;
}

async function unwrap<T>(p: Promise<Envelope<T>>): Promise<T> {
    const env = await p;
    if (env && typeof env === 'object' && 'code' in env && env.code !== 200) {
        throw new ApiError(env.code, env.message || '操作失败');
    }
    return env?.data as T;
}

/** 流式自由问答：逐 token 回调，done 结束。事件格式对齐后端 /api/knowledge/ask。 */
export function askStream(
    question: string,
    onToken: (text: string) => void,
    onDone: () => void,
    onError: (msg?: string) => void,
): AskStream {
    const controller = new AbortController();
    let cancelled = false;

    (async () => {
        try {
            // 桌面端本机 LLM key：随 SSE 请求临时带给后端（与 drill.ts 的 openSse 一致），
            // 后端按 请求头 X-LLM-Key > 用户设置 > 启动配置 解析，只用不存。
            const token = getToken();
            const llmKey = await getLlmKeyHeader();
            const headers: Record<string, string> = {
                'Content-Type': 'application/json',
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
                ...(llmKey ? { 'X-LLM-Key': llmKey } : {}),
            };
            const res = await fetch(`${API_BASE_SSE}/api/knowledge/ask`, {
                method: 'POST',
                headers,
                body: JSON.stringify({ question }),
                signal: controller.signal,
            });
            if (!res.ok || !res.body) { onError(`请求失败（${res.status}）`); return; }

            const reader = res.body.getReader();
            const decoder = new TextDecoder();
            let buf = '';
            let currentEvent: string | null = null;
            const currentData: string[] = [];

            const dispatch = () => {
                const payload = currentData.join('\n');
                if (currentEvent === 'error') {
                    try { const p = JSON.parse(payload); onError(p.message); } catch { onError(payload); }
                } else if (currentEvent === 'done') {
                    onDone();
                } else {
                    try {
                        const p = JSON.parse(payload);
                        if (typeof p.text === 'string') onToken(p.text);
                    } catch { if (payload && !payload.startsWith('{')) onToken(payload); }
                }
                currentEvent = null;
                currentData.length = 0;
            };

            const handleLine = (line: string) => {
                if (line === '') dispatch();
                else if (line.startsWith('event:')) currentEvent = line.slice(6).trim();
                else if (line.startsWith('data:')) currentData.push(line.slice(5).trim());
            };

            for (;;) {
                const { done, value } = await reader.read();
                if (done || cancelled) break;
                buf += decoder.decode(value, { stream: true });
                let idx;
                while ((idx = buf.indexOf('\n')) >= 0) {
                    const line = buf.slice(0, idx).trim();
                    buf = buf.slice(idx + 1);
                    handleLine(line);
                    await new Promise((r) => setTimeout(r, 0));
                }
            }
        } catch {
            if (!cancelled) onError();
        }
    })();

    return { cancel: () => { cancelled = true; controller.abort(); } };
}

export const knowledgeApi = {
    capture(conversation: ChatMsg[]): Promise<KnowledgeCard> {
        return unwrap(apiFetch<Envelope<KnowledgeCard>>('/knowledge/capture', {
            method: 'POST', body: JSON.stringify({ conversation }),
        })).then(normalizeCard);
    },
    list(planId?: number): Promise<KnowledgeCard[]> {
        return unwrap(apiFetch<Envelope<KnowledgeCard[]>>(`/knowledge/cards${planId ? `?planId=${planId}` : ''}`))
            .then(cs => (cs ?? []).map(normalizeCard));
    },
    due(): Promise<KnowledgeCard[]> {
        return unwrap(apiFetch<Envelope<KnowledgeCard[]>>('/knowledge/cards/due'))
            .then(cs => (cs ?? []).map(normalizeCard));
    },
    review(id: number, mastered: boolean): Promise<KnowledgeCard> {
        return unwrap(apiFetch<Envelope<KnowledgeCard>>(`/knowledge/cards/${id}/review`, {
            method: 'POST', body: JSON.stringify({ mastered }),
        })).then(normalizeCard);
    },
    /** 编辑卡片（question / answer / detail / tags，tags 传逗号分隔字符串）。保存后同步到内化复盘（同一张表）。 */
    update(id: number, req: { question: string; answer: string; tags: string; detail: string }): Promise<KnowledgeCard> {
        return unwrap(apiFetch<Envelope<KnowledgeCard>>(`/knowledge/cards/${id}`, {
            method: 'PUT', body: JSON.stringify(req),
        })).then(normalizeCard);
    },
    remove(id: number): Promise<void> {
        return apiFetch<void>(`/knowledge/cards/${id}`, { method: 'DELETE' });
    },
    // ----- 随手记 (Casual Note) -----
    listNotes(): Promise<CasualNote[]> {
        return unwrap(apiFetch<Envelope<CasualNote[]>>('/knowledge/notes'));
    },
    createNote(req: { title: string; content: string; conceptId?: number; chatId?: number }): Promise<CasualNote> {
        return unwrap(apiFetch<Envelope<CasualNote>>('/knowledge/notes', { method: 'POST', body: JSON.stringify(req) }));
    },
    updateNote(id: number, req: { title: string; content: string }): Promise<CasualNote> {
        return unwrap(apiFetch<Envelope<CasualNote>>(`/knowledge/notes/${id}`, { method: 'PUT', body: JSON.stringify(req) }));
    },
    deleteNote(id: number): Promise<void> {
        return apiFetch<void>(`/knowledge/notes/${id}`, { method: 'DELETE' });
    },
};
