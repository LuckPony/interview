import { apiFetch, ApiError } from './client';

interface Envelope<T> {
  code: number;
  message: string;
  data: T;
}

export interface UserProfile {
  id: number;
  email: string;
  username: string | null;
  nickname: string | null;
  gender: 'M' | 'F' | 'OTHER' | null;
  phone: string | null;
  birthday: string | null;
}

export interface UpdateUserProfile {
  username: string;
  nickname: string;
  gender: UserProfile['gender'];
  phone: string;
  birthday: string | null;
}

async function unwrap<T>(request: Promise<Envelope<T>>): Promise<T> {
  const envelope = await request;
  if (envelope.code !== 200) {
    throw new ApiError(envelope.code, envelope.message || '操作失败');
  }
  return envelope.data;
}

export const userProfileApi = {
  get: () => unwrap(apiFetch<Envelope<UserProfile>>('/user/profile')),
  update: (profile: UpdateUserProfile) => unwrap(apiFetch<Envelope<UserProfile>>('/user/profile', {
    method: 'PUT',
    body: JSON.stringify(profile),
  })),
};
