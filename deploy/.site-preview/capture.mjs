import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const outputDir = path.resolve('official-site/assets/screenshots');
const profileDir = path.resolve('deploy/.site-preview/chrome-profile');
await mkdir(outputDir, { recursive: true });
await mkdir(profileDir, { recursive: true });

const chrome = spawn(chromePath, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  '--remote-debugging-port=9222',
  `--user-data-dir=${profileDir}`,
  '--window-size=1600,900',
  'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let endpoint;
for (let i = 0; i < 50; i += 1) {
  try {
    const pages = await fetch('http://127.0.0.1:9222/json/list').then((response) => response.json());
    endpoint = pages.find((entry) => entry.type === 'page')?.webSocketDebuggerUrl;
    if (endpoint) break;
  } catch {}
  await sleep(100);
}
if (!endpoint) throw new Error('Chrome DevTools endpoint unavailable');

const socket = new WebSocket(endpoint);
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});

let messageId = 0;
const pending = new Map();
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data);
  if (!message.id || !pending.has(message.id)) return;
  const { resolve, reject } = pending.get(message.id);
  pending.delete(message.id);
  if (message.error) reject(new Error(message.error.message));
  else resolve(message.result);
});

function send(method, params = {}) {
  const id = ++messageId;
  socket.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 900, deviceScaleFactor: 1, mobile: false });

async function navigate(url) {
  await send('Page.navigate', { url });
  await sleep(2600);
}

async function evaluate(expression) {
  return send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
}

async function capture(name) {
  await evaluate('window.scrollTo(0, 0)');
  await sleep(250);
  const { data } = await send('Page.captureScreenshot', { format: 'png', fromSurface: true, captureBeyondViewport: false });
  await writeFile(path.join(outputDir, name), Buffer.from(data, 'base64'));
  console.log(`CAPTURED ${name}`);
}

const base = 'http://127.0.0.1:5173/';
await navigate(base);
await evaluate("localStorage.clear(); sessionStorage.clear();");
await send('Page.reload', { ignoreCache: true });
await sleep(3800);
await capture('login-latest.png');

const messages = [
  { role: 'user', content: '这段 Java 代码为什么可能出现重复消费？请结合 Redis Stream 的 Pending 机制分析。' },
  { role: 'ai', content: '关键问题是消息处理成功后没有可靠 ACK，消费者重启后消息仍留在 Pending List。可以把“业务落库”和“幂等校验”作为可靠性边界：\n\n```java\nif (taskRepository.markProcessing(messageId)) {\n  taskService.execute(payload);\n  streamOperations.acknowledge(group, record);\n}\n```\n\n同时定时使用 `XAUTOCLAIM` 回收超时消息，并把最大重试次数写入任务状态机。' },
  { role: 'user', content: '如果任务执行到一半进程退出，怎样避免副作用重复？' },
  { role: 'ai', content: '使用业务幂等键和唯一约束兜底。消费者收到消息后先尝试占有任务，只有状态迁移成功的实例才执行；外部调用还需要保存请求号，并让下游支持幂等。' },
];
await evaluate(`localStorage.setItem('yan.token:same-origin','preview-token'); localStorage.setItem('yan.userId:same-origin','1'); sessionStorage.setItem('yan.capture.msgs', ${JSON.stringify(JSON.stringify(messages))}); sessionStorage.removeItem('yan.capture.cards'); sessionStorage.removeItem('yan.interview.sessionId');`);

await send('Page.reload', { ignoreCache: true });
await sleep(3800);
await capture('dashboard-latest.png');

const shots = [
  ['#/capture','capture-latest.png'],
  ['#/knowledge-base','knowledge-library-latest.png'],
  ['#/knowledge-base/301','knowledge-detail-latest.png'],
  ['#/knowledge-base/tools?corpus=301&section=401','knowledge-tools-latest.png'],
  ['#/rehearsal?corpus=301','interview-latest.png'],
  ['#/settings','settings-latest.png'],
];
for (const [route, file] of shots) {
  await navigate(`${base}${route}`);
  await capture(file);
}

socket.close();
chrome.kill();
