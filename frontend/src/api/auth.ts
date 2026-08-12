import { apiFetch, setSession } from './client';
import type { LoginResp } from './types';

// 后端为演示用：POST /api/auth/login?userId= 直接签发 JWT，无注册流程。
export async function login(userId: string): Promise<LoginResp> {
  const resp = await apiFetch<LoginResp>(`/auth/login?userId=${encodeURIComponent(userId)}`, {
    method: 'POST',
  });
  setSession(resp.token, resp.userId);
  return resp;
}
