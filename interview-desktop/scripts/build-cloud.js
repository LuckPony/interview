// 云端桌面构建：要求 MIANBA_SERVER 环境变量（后端服务器地址）。
// 构建前端时把 VITE_API_BASE 烘焙为服务器地址，并把地址写进 config.json，
// 让 main.js 在启动时识别为「云模式」（不拉本地后端）。
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const DESKTOP = path.resolve(__dirname, '..');
const server = (process.env.MIANBA_SERVER || '').trim();

if (!server) {
  console.error(
    '缺少 MIANBA_SERVER 环境变量，例如：\n  MIANBA_SERVER=https://api.example.com npm run dist:cloud'
  );
  process.exit(1);
}

const frontend = path.resolve(DESKTOP, '..', 'frontend');
const dist = path.join(frontend, 'dist');

const build = spawnSync('npm', ['--prefix', frontend, 'run', 'build'], {
  stdio: 'inherit',
  env: { ...process.env, VITE_API_BASE: server },
});
if (build.status !== 0) process.exit(build.status);

fs.rmSync(path.join(DESKTOP, 'app-dist'), { recursive: true, force: true });
fs.cpSync(dist, path.join(DESKTOP, 'app-dist'), { recursive: true });
fs.writeFileSync(
  path.join(DESKTOP, 'config.json'),
  JSON.stringify({ serverUrl: server }, null, 2)
);
console.log('云构建完成 → config.json = ' + server);
