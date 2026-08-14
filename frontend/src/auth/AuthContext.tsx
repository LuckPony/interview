import { createContext, useContext, useState, type ReactNode } from 'react';
import { getStoredUserId, clearSession, setSession } from '../api/client';
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
