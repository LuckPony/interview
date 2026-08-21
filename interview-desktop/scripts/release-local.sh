#!/usr/bin/env bash
# 在 macOS 一键构建并发布本地版：Windows exe/zip + macOS dmg/zip。
# 前置：JDK 21、Node/npm、GH_TOKEN；首次运行会下载 Windows JDK 21。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

[ "$(uname -s)" = "Darwin" ] || { echo "❌ release:local 必须在 macOS 上运行"; exit 1; }
[ -n "${GH_TOKEN:-}" ] || { echo "❌ 缺少 GH_TOKEN（需要仓库 contents:write 权限）"; exit 1; }
command -v node >/dev/null || { echo "❌ 缺少 Node.js"; exit 1; }
command -v npm >/dev/null || { echo "❌ 缺少 npm"; exit 1; }

JAVA21_HOME="${JAVA_HOME:-}"
if [ ! -x "$JAVA21_HOME/bin/jlink" ]; then
  JAVA21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
[ -x "$JAVA21_HOME/bin/jlink" ] || { echo "❌ 找不到 macOS JDK 21/jlink"; exit 1; }
export JAVA_HOME="$JAVA21_HOME"

VERSION="$(node -p "require('./interview-desktop/package.json').version")"
echo "==> 发布面霸 v${VERSION}"

# 安装可复现依赖。
npm --prefix frontend ci
npm --prefix interview-desktop ci

# build-win.sh 会构建 fat jar、Windows JRE，并发布 exe/zip。
echo "==> Windows x64：构建并上传 exe/zip"
PUBLISH=always bash interview-desktop/scripts/build-win.sh

# 生成与当前 Mac 架构一致的 JRE。
echo "==> macOS：生成内嵌 JRE"
MODS="java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.smartcardio,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.management,jdk.management.agent,jdk.net,jdk.nio.mapmode,jdk.unsupported,jdk.zipfs"
rm -rf runtime
mkdir -p runtime
"$JAVA_HOME/bin/jlink" --module-path "$JAVA_HOME/jmods" --add-modules "$MODS" --output runtime/jre \
  --strip-debug --no-header-files --no-man-pages --compress=2
cp app/build/libs/app-0.0.1-SNAPSHOT.jar runtime/app.jar

case "$(uname -m)" in
  arm64) MAC_ARCH="arm64" ;;
  x86_64) MAC_ARCH="x64" ;;
  *) echo "❌ 不支持的 Mac 架构：$(uname -m)"; exit 1 ;;
esac

echo "==> macOS ${MAC_ARCH}：构建并上传 dmg/zip"
(
  cd interview-desktop
  npm run sync-spa
  npx electron-builder --mac dmg zip --"$MAC_ARCH" --config electron-builder.yml --publish always
)

echo "✅ v${VERSION} 已构建并上传 GitHub Release"
ls -lh interview-desktop/dist-electron/mianba-"$VERSION"-* 2>/dev/null || true
