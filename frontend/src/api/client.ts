// 统一的 fetch 包装：Bearer 鉴权 + 错误归一化 + 401 自动清登录。
// 桌面端（Electron）下由 .env 注入 http://127.0.0.1:8080；网页态留空走 dev 代理（相对 /api）。
// 桌面端用户可把 LLM key 存在本机（Electron userData），每次请求带 X-LLM-Key 头，后端「只用不存」——
// 服务器不落库、也不共享任何默认 key。
const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/$/, '');

const TOKEN_KEY = 'yan.token';
const USER_KEY = 'yan.userId';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

/** 桌面端本地 key（Electron 桥）；浏览器/非桌面端返回空串。 */
export async function getLlmKeyHeader(): Promise<string> {
  try {
    if (window.electronAPI?.getLlmKey) {
      const k = await window.electronAPI.getLlmKey();
      return k ?? '';
    }
  } catch {
    /* 拿不到就当没有 */
  }
  return '';
}

/** 统一鉴权头：Bearer + 桌面端本机 LLM key。 */
export async function buildAuthHeaders(): Promise<Record<string, string>> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const llmKey = await getLlmKeyHeader();
  if (llmKey) headers['X-LLM-Key'] = llmKey;
  return headers;
}
export function setSession(token: string, userId: string): void {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, userId);
}
export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}
export function getStoredUserId(): string | null {
  return localStorage.getItem(USER_KEY);
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
    this.name = 'ApiError';
  }
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (init?.body != null && !(init.body instanceof FormData)) {
    // FormData 由浏览器自动带 multipart boundary，不能手动设 Content-Type
    headers['Content-Type'] = 'application/json';
  }
  Object.assign(headers, await buildAuthHeaders());

  const res = await fetch(`${API_BASE}/api${path}`, { ...init, headers });

  if (res.status === 401) {
    clearSession();
    throw new ApiError(401, '登录已失效，请重新登录');
  }
  if (!res.ok) {
    let msg = `请求失败（${res.status}）`;
    try {
      const t = await res.text();
      if (t) {
        // 后端错误是 { code, message, data } 包装，取 message 给用户看
        try {
          const j = JSON.parse(t) as { message?: string };
          msg = typeof j.message === 'string' && j.message ? j.message : t;
        } catch {
          msg = t;
        }
      }
    } catch {
      /* 忽略读取错误 */
    }
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return undefined as T;

  const ct = res.headers.get('content-type') ?? '';
  if (!ct.includes('application/json')) return (await res.text()) as unknown as T;
  return res.json() as Promise<T>;
}
