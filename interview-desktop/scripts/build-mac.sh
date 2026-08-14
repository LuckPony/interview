#!/usr/bin/env bash
# ===== 面霸 · macOS 打包脚本 =====
# 产出：dist-electron/mac-arm64/面霸.app（已签名）+ zip + dmg + latest-mac.yml
#
# 签名说明：
#   - electron-builder 自动用本机可用的签名身份签（现在这台机器是测试证书，结构有效、
#     能正常启动，但 Gatekeeper 不信任 → 用户首次需「右键 → 打开」放行一次）。
#   - 买 Apple Developer 账号（$99/年）后：钥匙串里有 Developer ID 证书时本脚本自动用真证书签；
#     再配 .env 的 APPLE_ID / APPLE_APP_SPECIFIC_PASSWORD，electron-builder 会自动公证
#     （notarization）→ 警告消失 + macOS 自动更新可用。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> 打包并签名（electron-builder --mac zip dmg，自动处理签名/zip/dmg 完整性）"
npx electron-builder --mac zip dmg --arm64 --publish always

echo "==> 验证产物签名"
codesign -v --deep --strict "dist-electron/mac-arm64/面霸.app" && echo "   ✓ app 签名结构有效"

echo ""
echo "✅ 完成："
ls -lh dist-electron/面霸-0.1.0-arm64-mac.zip dist-electron/面霸-0.1.0-arm64.dmg
