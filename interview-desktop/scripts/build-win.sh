#!/usr/bin/env bash
# 构建 Windows 自包含 exe（本地模式，内嵌 Windows JRE + fat jar）
# 用法：
#   npm run dist:win:local             # 仅构建
#   npm run release:win:local          # 构建并上传 GitHub Release（需 GH_TOKEN）
# 产物：dist-electron/mianba-${version}-x64.exe + zip
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# —— 0. 定位 Mac 上的 JDK 21 jlink（Gradle 工具链）——
JLINK="$(ls -d "$HOME"/.gradle/jdks/eclipse_adoptium-21-*/jdk-21*/Contents/Home/bin/jlink 2>/dev/null | head -1)"
[ -n "$JLINK" ] || { echo "❌ 找不到 JDK 21 的 jlink（Gradle 工具链未就绪）"; exit 1; }

# —— 1. Windows x64 JDK 21（首次自动下载，之后复用缓存）——
WIN_JDK_ROOT="$HOME/.gradle/jdks/win"
WIN_JDK_HOME="$(find "$WIN_JDK_ROOT" -maxdepth 1 -type d -name 'jdk-21*' -print 2>/dev/null | head -1)"
if [ -z "$WIN_JDK_HOME" ] || [ ! -d "$WIN_JDK_HOME/jmods" ]; then
  echo "→ 下载 Windows x64 JDK 21 ..."
  mkdir -p "$WIN_JDK_ROOT"
  WIN_JDK_ZIP="$WIN_JDK_ROOT/OpenJDK21U-jdk_x64_windows_hotspot.zip"
  curl -fL --retry 3 -o "$WIN_JDK_ZIP" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
  unzip -q -o "$WIN_JDK_ZIP" -d "$WIN_JDK_ROOT"
  WIN_JDK_HOME="$(find "$WIN_JDK_ROOT" -maxdepth 1 -type d -name 'jdk-21*' -print | head -1)"
fi
[ -n "$WIN_JDK_HOME" ] && [ -d "$WIN_JDK_HOME/jmods" ] || { echo "❌ Windows JDK 21 下载或解压失败"; exit 1; }

# —— 2. jlink 裁 Windows JRE ——
# 与 macOS 版同一份模块清单；jmods 是 Windows 二进制，输出即 Windows 运行时。
MODS="java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.smartcardio,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jfr,jdk.management,jdk.management.agent,jdk.net,jdk.nio.mapmode,jdk.unsupported,jdk.zipfs"
echo "→ jlink 裁剪 Windows JRE ..."
rm -rf runtime-win/jre
"$JLINK" --module-path "$WIN_JDK_HOME/jmods" --add-modules "$MODS" --output runtime-win/jre \
  --strip-debug --no-header-files --no-man-pages --compress=2

# —— 3. fat jar（跨平台，Mac/Windows 通用）——
echo "→ 构建 fat jar ..."
./gradlew :app:bootJar --console=plain -q
cp app/build/libs/app-0.0.1-SNAPSHOT.jar runtime-win/app.jar

# —— 4. 前端（本地模式）+ 交叉构建 exe/zip ——
echo "→ 构建前端 + Windows NSIS/ZIP ..."
PUBLISH_ARGS=()
if [ "${PUBLISH:-never}" = "always" ]; then
  [ -n "${GH_TOKEN:-}" ] || { echo "❌ 发布需要 GH_TOKEN（contents:write）"; exit 1; }
  PUBLISH_ARGS=(--publish always)
fi
(
  cd interview-desktop
  npm run sync-spa
  npx electron-builder --win nsis zip --x64 --config electron-builder.win.yml "${PUBLISH_ARGS[@]}"
)

echo "✅ Windows 构建完成："
ls -lh interview-desktop/dist-electron/mianba-*-x64.exe interview-desktop/dist-electron/mianba-*-x64.zip
