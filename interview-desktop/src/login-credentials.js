// 登录凭据只使用系统密钥加密保存；绝不降级成明文或 localStorage。
const fs = require('fs');
const path = require('path');
const { createHash } = require('crypto');
const { pathToFileURL } = require('url');

function trustedCredentialSender(event, win, appPath, packaged) {
  if (!win || win.isDestroyed() || event.sender !== win.webContents
      || event.senderFrame !== win.webContents.mainFrame) return false;
  try {
    const url = new URL(event.senderFrame.url);
    url.hash = '';
    if (url.href === pathToFileURL(path.join(appPath, 'app-dist', 'index.html')).href) return true;
    return !packaged && url.origin === 'http://localhost:5173' && url.pathname === '/' && !url.search;
  } catch { return false; }
}

function createCredentialStore({ directory, scope, safeStorage, platform = process.platform }) {
  if (typeof scope !== 'string' || !scope) throw new Error('当前桌面端缺少后端地址，无法隔离保存登录密码');
  const file = path.join(directory, `login-${createHash('sha256').update(scope).digest('hex')}.json`);
  const available = () => safeStorage.isEncryptionAvailable()
    && !(platform === 'linux' && safeStorage.getSelectedStorageBackend?.() === 'basic_text');
  const valid = data => data && typeof data.email === 'string' && data.email.length <= 254
    && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email)
    && typeof data.password === 'string' && data.password.length >= 6 && data.password.length <= 64;
  const clear = () => {
    // 仅删除当前后端对应的凭据文件，不影响其他服务器、AI Key 或用户资料。
    try { fs.unlinkSync(file); } catch (error) { if (error.code !== 'ENOENT') throw new Error('无法清除本机保存的登录信息，请检查文件权限'); }
  };
  const read = () => {
    if (!available()) return { available: false, credentials: null, message: '系统加密服务不可用，暂不能记住密码' };
    try {
      if (fs.statSync(file).size > 16384) throw new Error('invalid size');
      const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (saved.version !== 1 || typeof saved.ciphertext !== 'string') throw new Error('invalid format');
      const data = JSON.parse(safeStorage.decryptString(Buffer.from(saved.ciphertext, 'base64')));
      if (data.scope !== scope || !valid(data)) throw new Error('invalid credentials');
      return { available: true, credentials: { email: data.email, password: data.password } };
    } catch (error) {
      return { available: true, credentials: null,
        ...(error.code === 'ENOENT' ? {} : { message: '保存的登录信息暂时无法解密，请重新输入后保存或清除' }) };
    }
  };
  const save = data => {
    if (!available()) throw new Error('系统加密服务不可用，不会明文保存密码');
    if (!valid(data)) throw new Error('登录信息格式不正确');
    try {
      const ciphertext = safeStorage.encryptString(JSON.stringify({ scope, email: data.email.trim(), password: data.password })).toString('base64');
      fs.mkdirSync(directory, { recursive: true });
      fs.writeFileSync(file + '.tmp', JSON.stringify({ version: 1, ciphertext }), { mode: 0o600 });
      fs.renameSync(file + '.tmp', file);
    } catch {
      throw new Error('本机登录信息保存失败，请取消记住密码后重试');
    }
  };
  const updatePassword = (email, password) => {
    const saved = read().credentials;
    if (!saved || typeof email !== 'string' || saved.email.toLowerCase() !== email.trim().toLowerCase()) return;
    try { save({ email: saved.email, password }); }
    catch (error) { clear(); throw error; } // 改密成功但本机更新失败，不继续保留旧密码。
  };
  return { read, save, clear, updatePassword };
}

module.exports = { createCredentialStore, trustedCredentialSender };
