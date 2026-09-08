import { apiFetch, ApiError, getToken, getLlmKeyHeader, clearSession } from './client';
import type { KnowledgeCard, CasualNote } from './types';
import { createSseParser } from '../lib/sseParser';
import { withAttachments, type ChatAttachment } from '../lib/chatAttachments';

export interface ChatMsg { role: 'user' | 'ai'; content: string; stopped?: boolean; failed?: boolean; attachments?: ChatAttachment[] }
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
    conversation: ChatMsg[],
    onToken: (text: string) => void,
    onDone: () => void,
    onError: (msg?: string) => void,
    onStatus?: (text: string) => void,
    purpose?: 'library',
): AskStream {
    const controller = new AbortController();
    let cancelled = false;
    let finished = false;
    const fail = (message: string) => {
        if (cancelled || finished) return;
        finished = true;
        onError(message);
    };
    (async () => {
        let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
        try {
            const token = getToken();
            const llmKey = await getLlmKeyHeader();
            if (cancelled) return;
            const res = await fetch(`${API_BASE_SSE}/api/knowledge/ask`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    ...(token ? { Authorization: `Bearer ${token}` } : {}),
                    ...(llmKey ? { 'X-LLM-Key': llmKey } : {}),
                },
                body: JSON.stringify({
                    question,
                    purpose,
                    conversation: conversation
                        .filter((message, index) => !message.stopped && !message.failed
                            && !(message.role === 'user' && (conversation[index + 1]?.failed || conversation[index + 1]?.stopped)))
                        .slice(-12)
                        .map(({ role, content, attachments }) => ({ role, content: withAttachments(content, attachments) })),
                }),
                signal: controller.signal,
            });
            if (res.status === 401) {
                if (token === getToken()) { clearSession(); window.dispatchEvent(new Event('yan:logout')); }
                fail('登录已失效，请重新登录');
                return;
            }
            if (!res.ok || !res.body || !res.headers.get('content-type')?.includes('text/event-stream')) {
                let message = `请求失败（${res.status}），请检查模型设置后重试`;
                try {
                    const error = await res.json();
                    if (typeof error.message === 'string') message = error.message;
                } catch { /* 非 JSON 的代理错误使用安全提示。 */ }
                fail(message);
                return;
            }
            reader = res.body.getReader();
            const decoder = new TextDecoder();
            const parser = createSseParser(({ event, data }) => {
                if (finished || cancelled) return;
                let payload: { text?: string; message?: string } = {};
                try { payload = JSON.parse(data); } catch { /* 兼容纯文本 SSE。 */ }
                if (event === 'error') fail(payload.message || '模型生成失败，请重试');
                else if (event === 'done' || data === '[DONE]') { finished = true; onDone(); }
                else if (event === 'status') onStatus?.(payload.text || '正在处理…');
                else if (event === 'message') {
                    if (typeof payload.text === 'string') onToken(payload.text);
                    else if (data && !data.startsWith('{')) onToken(data);
                }
            });
            while (!finished && !cancelled) {
                const chunk = await reader.read();
                if (chunk.done) { parser.push(decoder.decode()); parser.finish(); break; }
                parser.push(decoder.decode(chunk.value, { stream: true }));
            }
            if (!finished && !cancelled) fail('连接意外断开，已保留问题和已生成内容，请重试。');
        } catch {
            fail('连接中断或网络异常，已保留问题和已生成内容，请检查网络后重试。');
        } finally {
            await reader?.cancel().catch(() => {});
            reader?.releaseLock();
        }
    })();
    return { cancel: () => { cancelled = true; controller.abort(); } };
}

export const knowledgeApi = {
    parseAttachment(file: File, signal?: AbortSignal): Promise<ChatAttachment> {
        const body = new FormData();
        body.append('file', file);
        return unwrap(apiFetch<Envelope<ChatAttachment>>('/knowledge/attachments/parse', { method: 'POST', body, signal }));
    },
    capture(conversation: ChatMsg[]): Promise<KnowledgeCard> {
        return unwrap(apiFetch<Envelope<KnowledgeCard>>('/knowledge/capture', {
            method: 'POST',
            body: JSON.stringify({
                conversation: conversation.map(({ role, content, attachments }) => ({ role, content: withAttachments(content, attachments) })),
            }),
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
