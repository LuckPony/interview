import { createContext, useContext, useState, type ReactNode } from 'react';
import { getStoredUserId, clearSession } from '../api/client';
import { login as doLogin } from '../api/auth';

interface AuthApi {
  userId: string | null;
  login: (id: string) => Promise<void>;
  logout: () => void;
}

const Ctx = createContext<AuthApi>({
  userId: null,
  login: async () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<string | null>(() => getStoredUserId());

  const login = async (id: string) => {
    await doLogin(id);
    setUserId(id);
  };
  const logout = () => {
    clearSession();
    setUserId(null);
  };

  return <Ctx.Provider value={{ userId, login, logout }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthApi {
  return useContext(Ctx);
}
