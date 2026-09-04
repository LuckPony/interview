import { apiFetch } from './client';
import type { LoginResp } from './types';

// 真实账号体系：邮箱 + 密码 + 邮箱验证码。
// 注册拆两步：① sendRegisterCode（滑块通过后发码，不建账号）② register（输码验证通过才入库）。
export async function login(email: string, password: string): Promise<LoginResp> {
  return apiFetch<LoginResp>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

/** 注册：提交邮箱 + 密码 + 收到的验证码，验证通过后端才创建账号并签发 JWT。 */
export async function register(
    email: string,
    password: string,
    code?: string,
): Promise<LoginResp> {
  return apiFetch<LoginResp>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, code }),
  });
}

/** 注册前置发码：滑块通过后调用，向邮箱发送验证码（后端此时不创建账号）。 */
export async function sendRegisterCode(
    email: string,
    captchaToken?: string,
): Promise<void> {
  return apiFetch<void>('/auth/send-register-code', {
    method: 'POST',
    body: JSON.stringify({ email, captchaToken }),
  });
}

/** 环境开关：captchaRequired=注册是否需要滑块；emailVerifyRequired=是否需要邮箱验证码。 */
export async function getAuthConfig(): Promise<{
  captchaRequired: boolean;
  emailVerifyRequired: boolean;
}> {
  return apiFetch('/auth/config');
}

/** 给遗留的未验证账号补验邮箱（新注册流程已不再使用）。 */
export async function verify(email: string, code: string): Promise<LoginResp> {
  return apiFetch<LoginResp>('/auth/verify', {
    method: 'POST',
    body: JSON.stringify({ email, code }),
  });
}
