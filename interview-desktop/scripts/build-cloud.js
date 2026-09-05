// 云端桌面构建：要求 MIANBA_SERVER 环境变量（后端服务器地址）。
// 构建前端时把 VITE_API_BASE 烘焙为服务器地址，并把桌面模式标记写进
// app-dist/desktop-config.json。app-dist 已明确纳入 electron-builder，避免根目录下
// 被 .gitignore 排除的生成文件没有进入 app.asar。
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const DESKTOP = path.resolve(__dirname, '..');
const rawServer = (process.env.MIANBA_SERVER || '').trim();

if (!rawServer) {
  console.error(
    '缺少 MIANBA_SERVER 环境变量，例如：\n  MIANBA_SERVER=https://api.example.com npm run dist:cloud'
  );
  process.exit(1);
}

// Windows 用户常直接填写 IP:端口。没有协议时 URL 会在 Electron 的 file:// 页面中
// 被当成相对路径，所有 API 请求都会变成网络错误。统一补 http://，并在构建前校验，
// 避免生成一个能打开界面、却永远无法登录的安装包。
const serverWithProtocol = /^https?:\/\//i.test(rawServer)
  ? rawServer
  : `http://${rawServer}`;
let server;
try {
  const parsed = new URL(serverWithProtocol);
  if (!parsed.hostname || !['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('只支持 http 或 https 地址');
  }
  server = parsed.toString().replace(/\/$/, '');
} catch (error) {
  console.error(`MIANBA_SERVER 不是有效的服务器地址：${rawServer}`);
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}

const frontend = path.resolve(DESKTOP, '..', 'frontend');
const dist = path.join(frontend, 'dist');

const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const build = spawnSync(npmCommand, ['run', 'build'], {
  cwd: frontend,
  stdio: 'inherit',
  shell: process.platform === 'win32',
  env: { ...process.env, VITE_API_BASE: server },
});
if (build.error) {
  console.error(`无法执行 ${npmCommand}: ${build.error.message}`);
  process.exit(1);
}
if (build.status !== 0) {
  console.error(`前端构建失败，退出码: ${build.status ?? 'unknown'}`);
  process.exit(build.status ?? 1);
}

fs.rmSync(path.join(DESKTOP, 'app-dist'), { recursive: true, force: true });
fs.cpSync(dist, path.join(DESKTOP, 'app-dist'), { recursive: true });
fs.writeFileSync(
  path.join(DESKTOP, 'app-dist', 'desktop-config.json'),
  JSON.stringify({ serverUrl: server }, null, 2)
);
// 清理旧版根目录标记，防止源码模式受到历史构建残留影响。
fs.rmSync(path.join(DESKTOP, 'config.json'), { force: true });
console.log('云构建完成 → app-dist/desktop-config.json = ' + server);
