#!/usr/bin/env bash
# ===== 面霸 · 桌面端一键发布（GitHub Releases）=====
# 用户桌面端启动时自动检查 GitHub Releases 的 latest*.yml → 发现新版本 → 下载 → 重启安装。
#
# 用法：
#   export GH_TOKEN=github_pat_xxx        # 需有 LuckPony/interview 的 contents:write 权限
#   ./scripts/upload-update.sh
#
# 流程：改 interview-desktop/package.json 的 version（必须递增，否则不会触发更新）
#   → 重跑本脚本（内部：构建前端 → electron-builder 打包 + 发布到 GitHub Releases）。
# 说明：
#   - Windows：NSIS 安装包自动更新（未签名首次安装有 SmartScreen 提示，点"仍要运行"即可）
#   - macOS：自动更新需要 Apple 开发者签名（$99/年 + 公证）；未签名时 mac 用户只能手动下载
#     GitHub Release 里的 zip 覆盖安装（main.js 已自动跳过自动更新）。
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "缺少 GH_TOKEN 环境变量（GitHub 个人令牌，需 contents:write 权限）。"
  exit 1
fi

echo "==> 构建前端 + 打包发布 Windows (NSIS x64)…"
npx electron-builder --win nsis --x64 --publish always

echo "==> 打包发布 macOS (zip, arm64)…"
npx electron-builder --mac zip --arm64 --publish always || {
  echo "⚠️ macOS 打包/发布失败（常见原因：未配置签名证书）。Windows 更新已发布，可忽略此步。"
}

echo ""
echo "✅ 已发布到 GitHub Releases。用户下次启动桌面端将自动检查更新（版本号变了才触发）。"
echo "   发布新版本：改 package.json 的 version → 重跑本脚本。"
