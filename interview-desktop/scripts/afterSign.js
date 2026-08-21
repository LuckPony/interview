// electron-builder afterSign 钩子：mac 包补 ad-hoc 签名。
//
// 背景：CI（GitHub Actions macOS runner）没有 Apple 证书，electron-builder 会产出「未签名」的
// .app。macOS Gatekeeper 对「未签名 + 隔离属性（从网上下载）」的包会直接报「包含恶意软件」，
// 且右键也不一定给「打开」入口。这里用 `codesign --sign -` 做 ad-hoc 签名后，Gatekeeper 会降级为
// 「无法验证开发者」，用户右键 → 打开（或 xattr -cr 清隔离）即可，一次通过、长期有效。
// ad-hoc 签名免费，不依赖任何开发者账号，本地与 CI 行为一致。
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

exports.default = async function afterSign(context) {
  const appOutDir = context.appOutDir;
  if (!appOutDir) return;

  const appName = fs.readdirSync(appOutDir).find((f) => f.endsWith('.app'));
  if (!appName) {
    console.warn(`[afterSign] ${appOutDir} 下未找到 .app，跳过 ad-hoc 签名`);
    return;
  }

  const appPath = path.join(appOutDir, appName);
  console.log(`[afterSign] ad-hoc 签名: ${appPath}`);
  execSync(`codesign --force --deep --sign - "${appPath}"`, { stdio: 'inherit' });
};
