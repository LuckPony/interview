// 统一的 fetch 包装：Bearer 鉴权 + 错误归一化 + 401 自动清登录。

// 桌面端（Electron）下由 .env 注入 http://127.0.0.1:8080；网页态留空走 dev 代理（相对 /api）。
const API_BASE = import.meta.env.VITE_API_BASE ?? '';

const TOKEN_KEY = 'yan.token';
const USER_KEY = 'yan.userId';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
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
  const token = getToken();
  const headers: Record<string, string> = {
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (init?.body != null) {
    headers['Content-Type'] = 'application/json';
  }
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${API_BASE}/api${path}`, { ...init, headers });

  if (res.status === 401) {
    clearSession();
    throw new ApiError(401, '登录已失效，请重新登录');
  }
  if (!res.ok) {
    let msg = `请求失败（${res.status}）`;
    try {
      const t = await res.text();
      if (t) msg = t;
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
