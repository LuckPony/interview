// 本地桌面构建：把前端 API 地址固定为随 Electron 一起启动的本地 Spring Boot。
// 使用 Node 设置环境变量，兼容 Windows cmd、PowerShell、macOS 和 Linux。
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const DESKTOP = path.resolve(__dirname, '..');
const frontend = path.resolve(DESKTOP, '..', 'frontend');
const dist = path.join(frontend, 'dist');

const npmCommand = process.platform === 'win32' ? 'npm.cmd' : 'npm';
const build = spawnSync(npmCommand, ['run', 'build'], {
  cwd: frontend,
  stdio: 'inherit',
  shell: process.platform === 'win32',
  env: { ...process.env, VITE_API_BASE: 'http://127.0.0.1:23333' },
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
// config.json 存在时会被识别为云模式；本地构建必须确保它不存在。
fs.rmSync(path.join(DESKTOP, 'config.json'), { force: true });
console.log('本地 SPA 构建完成 → API http://127.0.0.1:23333');
