/** 未设置用户名时，在本机为每个账号保留同一个友好昵称。 */
export function fallbackUsername(userId: string | null): string {
  if (!userId) return '霸仔';
  const storageKey = `yan.fallback-username.${userId}`;
  try {
    const saved = localStorage.getItem(storageKey);
    if (saved && /^霸仔[a-z0-9]{3}$/.test(saved)) return saved;
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    const random = new Uint32Array(3);
    crypto.getRandomValues(random);
    const generated = `霸仔${[...random].map((value) => chars[value % chars.length]).join('')}`;
    localStorage.setItem(storageKey, generated);
    return generated;
  } catch {
    return `霸仔${String(userId).padStart(3, '0').slice(-3)}`;
  }
}
