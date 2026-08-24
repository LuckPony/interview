import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getStoredUserId, clearSession, setSession, apiFetch } from '../api/client';
import { login as apiLogin } from '../api/auth';
import type { LoginResp } from '../api/types';

interface AuthApi {
  userId: string | null;
  login: (email: string, password: string) => Promise<void>;
  /** 注册/验证成功后直接进入登录态（后端已签发 token）。 */
  completeAuth: (resp: LoginResp) => void;
  logout: () => void;
}

const Ctx = createContext<AuthApi>({
  userId: null,
  login: async () => {},
  completeAuth: () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<string | null>(() => getStoredUserId());

  // 任意接口返回 401（token 失效/无 token）→ 清登录态，RequireAuth 跳登录页
  useEffect(() => {
    const onLogout = () => setUserId(null);
    window.addEventListener('yan:logout', onLogout);
    return () => window.removeEventListener('yan:logout', onLogout);
  }, []);

  // 启动校验 token：localStorage 有 userId 时向鉴权接口发一次请求，
  // token 无效/过期会收到 401 → 触发 yan:logout → 跳登录页（而不是残留 id 闪现主界面）
  useEffect(() => {
    if (!getStoredUserId()) return;
    apiFetch('/study-plan').catch(() => { /* 401 已在 client 层广播 logout */ });
  }, []);

  const login = async (email: string, password: string) => {
    const resp = await apiLogin(email, password);
    setSession(resp.token, resp.userId);
    setUserId(resp.userId);
  };

  const completeAuth = (resp: LoginResp) => {
    setSession(resp.token, resp.userId);
    setUserId(resp.userId);
  };

  const logout = () => {
    clearSession();
    setUserId(null);
  };

  return <Ctx.Provider value={{ userId, login, completeAuth, logout }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthApi {
  return useContext(Ctx);
}
