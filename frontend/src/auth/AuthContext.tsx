import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { getStoredUserId, getToken, clearSession, setSession } from '../api/client';
import { login as apiLogin } from '../api/auth';
import type { LoginResp } from '../api/types';
import { userProfileApi, type UserProfile } from '../api/user';

interface AuthApi {
  authReady: boolean;
  userId: string | null;
  profile: UserProfile | null;
  login: (email: string, password: string) => Promise<void>;
  /** 注册/验证成功后直接进入登录态（后端已签发 token）。 */
  completeAuth: (resp: LoginResp) => void;
  refreshProfile: () => Promise<UserProfile | null>;
  logout: () => void;
}

const Ctx = createContext<AuthApi>({
  authReady: false,
  userId: null,
  profile: null,
  login: async () => {},
  completeAuth: () => {},
  refreshProfile: async () => null,
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authReady, setAuthReady] = useState(false);
  // 不能直接信任 localStorage 中的 userId。必须先由后端验证 token，
  // 否则应用会短暂进入主页并提前发起依赖用户信息的请求。
  const [userId, setUserId] = useState<string | null>(null);
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
      setAuthReady(true);
    };
    window.addEventListener('yan:logout', onLogout);
    return () => window.removeEventListener('yan:logout', onLogout);
  }, []);

  // 启动时先验证完整登录态。验证结束前 App 不渲染任何业务页面，
  // 因而首次启动或旧登录态失效时会稳定地落到登录页。
  useEffect(() => {
    const storedUserId = getStoredUserId();
    if (!storedUserId || !getToken()) {
      clearSession();
      setAuthReady(true);
      return;
    }

    let active = true;
    userProfileApi.get()
      .then((next) => {
        if (!active) return;
        setProfile(next);
        setUserId(storedUserId);
        setAuthReady(true);
      })
      .catch(() => {
        if (!active) return;
        clearSession();
        setProfile(null);
        setUserId(null);
        setAuthReady(true);
      });

    return () => {
      active = false;
    };
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
    setAuthReady(true);
  };

  const completeAuth = (resp: LoginResp) => {
    setSession(resp.token, resp.userId);
    setProfile(null);
    setUserId(resp.userId);
    setAuthReady(true);
  };

  const logout = () => {
    clearSession();
    setProfile(null);
    setUserId(null);
    setAuthReady(true);
  };

  return <Ctx.Provider value={{ authReady, userId, profile, login, completeAuth, refreshProfile, logout }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthApi {
  return useContext(Ctx);
}
