import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { getStoredUserId, clearSession, setSession, apiFetch } from '../api/client';
import { login as apiLogin } from '../api/auth';
import type { LoginResp } from '../api/types';
import { userProfileApi, type UserProfile } from '../api/user';

interface AuthApi {
  userId: string | null;
  profile: UserProfile | null;
  login: (email: string, password: string) => Promise<void>;
  /** 注册/验证成功后直接进入登录态（后端已签发 token）。 */
  completeAuth: (resp: LoginResp) => void;
  refreshProfile: () => Promise<UserProfile | null>;
  logout: () => void;
}

const Ctx = createContext<AuthApi>({
  userId: null,
  profile: null,
  login: async () => {},
  completeAuth: () => {},
  refreshProfile: async () => null,
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<string | null>(() => getStoredUserId());
  const [profile, setProfile] = useState<UserProfile | null>(null);

  const refreshProfile = useCallback(async () => {
    if (!getStoredUserId()) {
      setProfile(null);
      return null;
    }
    try {
      const next = await userProfileApi.get();
      setProfile(next);
      return next;
    } catch {
      return null;
    }
  }, []);

  // 任意接口返回 401（token 失效/无 token）→ 清登录态，RequireAuth 跳登录页
  useEffect(() => {
    const onLogout = () => {
      setUserId(null);
      setProfile(null);
    };
    window.addEventListener('yan:logout', onLogout);
    return () => window.removeEventListener('yan:logout', onLogout);
  }, []);

  // 启动校验 token：localStorage 有 userId 时向鉴权接口发一次请求，
  // token 无效/过期会收到 401 → 触发 yan:logout → 跳登录页（而不是残留 id 闪现主界面）
  useEffect(() => {
    if (!getStoredUserId()) return;
    apiFetch('/study-plan').catch(() => { /* 401 已在 client 层广播 logout */ });
  }, []);

  useEffect(() => {
    if (userId) void refreshProfile();
    else setProfile(null);
  }, [refreshProfile, userId]);

  const login = async (email: string, password: string) => {
    const resp = await apiLogin(email, password);
    setSession(resp.token, resp.userId);
    setProfile(null);
    setUserId(resp.userId);
  };

  const completeAuth = (resp: LoginResp) => {
    setSession(resp.token, resp.userId);
    setProfile(null);
    setUserId(resp.userId);
  };

  const logout = () => {
    clearSession();
    setProfile(null);
    setUserId(null);
  };

  return <Ctx.Provider value={{ userId, profile, login, completeAuth, refreshProfile, logout }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthApi {
  return useContext(Ctx);
}
