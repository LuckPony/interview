#!/usr/bin/env bash
# 在 macOS 上一键构建并发布【云模式】桌面版：Windows exe/zip + macOS dmg/zip。
# 正式发布通道：桌面端只打包「Electron 壳 + 前端 SPA」，后端部署在服务器（MIANBA_SERVER）。
# Windows 包在 macOS 上交叉编译（无需 Windows 机器）。
#
# 前置：
#   MIANBA_SERVER  后端服务器地址（含协议，如 https://api.example.com；缺协议时自动补 http://）
#   GH_TOKEN       GitHub 仓库 contents:write 权限的 token（发布到 GitHub Release）
#   Node/npm
#
# 用法：
#   MIANBA_SERVER=http://103.236.92.40:23333 GH_TOKEN=xxx npm run release
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

[ "$(uname -s)" = "Darwin" ] || { echo "❌ 云发布需在 macOS 上运行（Windows 包在此交叉编译）"; exit 1; }
[ -n "${MIANBA_SERVER:-}" ] || { echo "❌ 缺少 MIANBA_SERVER（后端服务器地址，例如 https://api.example.com）"; exit 1; }
[ -n "${GH_TOKEN:-}" ] || { echo "❌ 缺少 GH_TOKEN（需要仓库 contents:write 权限）"; exit 1; }
command -v node >/dev/null || { echo "❌ 缺少 Node.js"; exit 1; }
command -v npm >/dev/null || { echo "❌ 缺少 npm"; exit 1; }

# 缺协议时自动补 http://（VITE_API_BASE 必须是带协议的绝对地址，fetch 才能拼出合法 URL）
case "$MIANBA_SERVER" in
  http://*|https://*) ;;
  *) MIANBA_SERVER="http://${MIANBA_SERVER}" ;;
esac
export MIANBA_SERVER

VERSION="$(node -p "require('./interview-desktop/package.json').version")"
echo "==> 发布面霸 v${VERSION}（云端模式，后端 ${MIANBA_SERVER}）"

# 安装可复现依赖。
npm --prefix frontend ci
npm --prefix interview-desktop ci

# 构建云端 SPA：VITE_API_BASE 烘焙为 MIANBA_SERVER，config.json 写入 serverUrl
#（main.js 启动时识别为云模式：不拉本地后端，直接连远程）。
echo "==> 构建云端 SPA（config.json = ${MIANBA_SERVER}）"
(
  cd interview-desktop
  node scripts/build-cloud.js
)

case "$(uname -m)" in
  arm64) MAC_ARCH="arm64" ;;
  x86_64) MAC_ARCH="x64" ;;
  *) echo "❌ 不支持的 Mac 架构：$(uname -m)"; exit 1 ;;
esac

# Windows x64（云模式：只含前端）
echo "==> Windows x64：构建并上传 exe/zip"
(
  cd interview-desktop
  npx electron-builder --win nsis zip --x64 --config electron-builder.cloud.yml --publish always
)

# macOS（云模式：只含前端）
echo "==> macOS ${MAC_ARCH}：构建并上传 dmg/zip"
(
  cd interview-desktop
  npx electron-builder --mac dmg zip --"$MAC_ARCH" --config electron-builder.cloud.yml --publish always
)

echo "✅ v${VERSION}（云端模式）已构建并上传 GitHub Release"
ls -lh interview-desktop/dist-electron-cloud/mianba-"$VERSION"-cloud-* 2>/dev/null || true
