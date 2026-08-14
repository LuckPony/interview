import { apiFetch } from './client';
import type { LoginResp } from './types';

// 真实账号体系：邮箱 + 密码，注册可选邮箱验证。成功后后端签发 JWT。
// setSession 由 AuthContext / 登录页在拿到 resp 后统一调用（注册未验证时不入库）。
export async function login(email: string, password: string): Promise<LoginResp> {
  return apiFetch<LoginResp>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function register(email: string, password: string): Promise<LoginResp> {
  return apiFetch<LoginResp>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function verify(email: string, code: string): Promise<LoginResp> {
  return apiFetch<LoginResp>('/auth/verify', {
    method: 'POST',
    body: JSON.stringify({ email, code }),
  });
}
