import { apiFetch, ApiError } from './client';
import type { CorpusDetail } from './types';

async function unwrap<T>(request: Promise<{ code: number; message: string; data: T }>): Promise<T> {
  const result = await request;
  if (result.code !== 200) throw new ApiError(result.code, result.message);
  return result.data;
}
export const libraryApi = {
  detail: (id: number) => unwrap(apiFetch<{ code: number; message: string; data: CorpusDetail }>(`/corpus/${id}`)),
  text: (id: number, sectionId?: number) => unwrap(apiFetch<{ code: number; message: string; data: { text: string; totalChars: number; truncated: boolean } }>(`/corpus/${id}/text${sectionId ? `?sectionId=${sectionId}` : ''}`)),
  reindex: (id: number) => unwrap(apiFetch<{ code: number; message: string; data: null }>(`/corpus/${id}/reindex`, { method: 'POST' })),
  async openOriginal(id: number, parsedText = false) {
    // 先在点击事件里开窗，避免异步鉴权完成后被浏览器拦截弹窗。
    const desktop = window.electronAPI?.openKnowledgeOriginal;
    const tab = desktop ? null : window.open('about:blank', '_blank');
    if (!desktop && !tab) throw new Error('浏览器阻止了新窗口，请允许弹窗后重试');
    if (tab) tab.opener = null;
    try {
      const data = await unwrap(apiFetch<{ code: number; message: string; data: { path: string } }>(`/corpus/${id}/${parsedText ? 'parsed-link' : 'original-link'}`, { method: 'POST' }));
      const base = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '');
      const url = new URL(base + data.path, window.location.href).href;
      if (desktop) await desktop(url);
      else if (tab) tab.location.href = url;
    } catch (error) { tab?.close(); throw error; }
  },
};

export const INDEX_LABELS: Record<string, string> = {
  READY: 'AI 索引就绪', BASIC: '基础索引', PENDING: '等待索引', RUNNING: '正在整理', FAILED: '索引待重试',
};
export const LIBRARY_ACCEPT = '.pdf,.docx,.txt,.md,.markdown,.mdx';
export const libraryError = (e: unknown) => e instanceof Error ? e.message : '操作失败，请稍后重试';
