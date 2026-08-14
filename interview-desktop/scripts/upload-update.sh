#!/usr/bin/env bash
# ===== 面霸 · 桌面端一键推送更新 =====
# 用法：
#   ./scripts/upload-update.sh user@your-server.com:/var/www/mianba/updates
# 把 dist-electron 里的「安装包 + 校验元数据」传到你的更新服务器目录，
# 用户桌面端启动时自动检查 latest*.yml → 发现新版本 → 下载 → 重启安装。
#
# 服务器侧（首次部署，任选一种）：
#   A. nginx 静态目录（推荐）：
#      server { location /updates/ { root /var/www/mianba; autoindex off; } }
#   B. GitHub Releases：electron-builder.yml 的 publish 换成
#      provider: github / owner: LuckPony / repo: interview（仓库需公开），
#      然后 GH_TOKEN=xxx npx electron-builder --win nsis --x64 --publish always 自动上传。
set -euo pipefail

TARGET="${1:?用法: $0 user@host:/path/to/updates}"

cd "$(dirname "$0")/.."

# Windows 用户（自动更新走 NSIS 安装包）
WIN_FILES=(
  "dist-electron/面霸 Setup 0.1.0.exe"
  "dist-electron/面霸 Setup 0.1.0.exe.blockmap"
  "dist-electron/latest.yml"
)
# macOS 用户（需已签名才能自动更新；未签名时这些文件供手动下载）
MAC_FILES=(
  "dist-electron/面霸-0.1.0-arm64-mac.zip"
  "dist-electron/面霸-0.1.0-arm64-mac.zip.blockmap"
  "dist-electron/latest-mac.yml"
)

echo "==> 上传 Windows 更新文件到 $TARGET"
scp "${WIN_FILES[@]}" "$TARGET/"
echo "==> 上传 macOS 更新文件到 $TARGET"
scp "${MAC_FILES[@]}" "$TARGET/"

echo ""
echo "✅ 更新已推送。版本号变了才会触发更新（下次用户启动应用时检查）。"
echo "   发布新版本：改 interview-desktop/package.json 的 version → 重新打包 → 重跑本脚本。"
