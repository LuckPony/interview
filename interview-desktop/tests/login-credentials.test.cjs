const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { createCredentialStore, trustedCredentialSender } = require('../src/login-credentials');

function fixture(t, options = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mianba-login-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const key = crypto.randomBytes(32);
  // 单元测试使用独立随机 AES 密钥模拟系统 API，不触及真实用户凭据。
  const safeStorage = {
    isEncryptionAvailable: () => true,
    encryptString(value) {
      const iv = crypto.randomBytes(12);
      const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
      const data = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()]);
      return Buffer.concat([iv, cipher.getAuthTag(), data]);
    },
    decryptString(buffer) {
      const cipher = crypto.createDecipheriv('aes-256-gcm', key, buffer.subarray(0, 12));
      cipher.setAuthTag(buffer.subarray(12, 28));
      return Buffer.concat([cipher.update(buffer.subarray(28)), cipher.final()]).toString('utf8');
    },
  };
  const config = { directory, scope: 'local:http://127.0.0.1:23333', safeStorage, ...options };
  return { directory, config, store: createCredentialStore(config) };
}
const account = { email: 'fixture@example.test', password: 'Fixture-secret-123' };

test('凭据以密文落盘，重新创建存储实例后可填充，清除只影响当前后端', t => {
  const { config, directory, store } = fixture(t);
  assert.equal(store.read().credentials, null);
  store.save(account);
  const raw = fs.readFileSync(path.join(directory, fs.readdirSync(directory)[0]), 'utf8');
  assert.ok(!raw.includes(account.email) && !raw.includes(account.password));
  assert.deepEqual(createCredentialStore(config).read().credentials, account);
  const cloud = createCredentialStore({ ...config, scope: 'cloud:https://example.test' });
  assert.equal(cloud.read().credentials, null);
  cloud.save({ ...account, password: 'Cloud-secret-456' });
  store.clear();
  assert.equal(store.read().credentials, null);
  assert.equal(cloud.read().credentials.password, 'Cloud-secret-456');
});

test('系统加密不可用或 Linux basic_text 时拒绝保存，绝不回退明文', t => {
  const { config, directory } = fixture(t);
  for (const safeStorage of [
    { ...config.safeStorage, isEncryptionAvailable: () => false },
    { ...config.safeStorage, getSelectedStorageBackend: () => 'basic_text' },
  ]) {
    const store = createCredentialStore({ ...config, safeStorage, platform: 'linux' });
    assert.equal(store.read().available, false);
    assert.throws(() => store.save(account), /不会明文/);
  }
  assert.deepEqual(fs.readdirSync(directory), []);
});

test('改密只更新已记住的同一账号；损坏凭据不泄露、可清除', t => {
  const { directory, store } = fixture(t);
  store.save(account);
  store.updatePassword('different@example.test', 'Other-secret-123');
  assert.equal(store.read().credentials.password, account.password);
  store.updatePassword(account.email, 'New-secret-123');
  assert.equal(store.read().credentials.password, 'New-secret-123');
  fs.writeFileSync(path.join(directory, fs.readdirSync(directory)[0]), '{}');
  assert.equal(store.read().credentials, null);
  assert.match(store.read().message, /无法解密/);
  store.clear();
  assert.equal(store.read().message, undefined);
});

test('只允许可信主窗口的顶层本地 SPA 访问凭据，拒绝外站与子 frame', () => {
  const appPath = path.resolve('fixture-app');
  const frame = { url: pathToFileURL(path.join(appPath, 'app-dist', 'index.html')).href + '#/login' };
  const contents = { mainFrame: frame };
  const win = { webContents: contents, isDestroyed: () => false };
  const event = { sender: contents, senderFrame: frame };
  assert.equal(trustedCredentialSender(event, win, appPath, true), true);
  assert.equal(trustedCredentialSender({ ...event, senderFrame: { ...frame } }, win, appPath, true), false);
  frame.url = 'https://example.test/#/login';
  assert.equal(trustedCredentialSender(event, win, appPath, false), false);
  frame.url = 'http://localhost:5173/#/login';
  assert.equal(trustedCredentialSender(event, win, appPath, false), true);
  assert.equal(trustedCredentialSender(event, win, appPath, true), false);
});
