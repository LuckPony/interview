#!/usr/bin/env bash
# 构建 Windows 自包含 exe（本地模式，内嵌 Windows JRE + fat jar）
# 用法：bash scripts/build-win.sh
# 产物：dist-electron/面霸 Setup 0.1.0.exe（NSIS）+ 面霸-0.1.0-win.zip（自动更新）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# —— 0. 定位 Mac 上的 JDK 21 jlink（Gradle 工具链）——
JLINK="$(ls -d "$HOME"/.gradle/jdks/eclipse_adoptium-21-*/jdk-21*/Contents/Home/bin/jlink 2>/dev/null | head -1)"
[ -n "$JLINK" ] || { echo "❌ 找不到 JDK 21 的 jlink（Gradle 工具链未就绪）"; exit 1; }

# —— 1. Windows x64 JDK 21（首次自动下载，之后复用缓存）——
WIN_JDK_VER="21.0.12"
WIN_JDK_HOME="$HOME/.gradle/jdks/win/jdk-${WIN_JDK_VER}+8"
if [ ! -d "$WIN_JDK_HOME/jmods" ]; then
  echo "→ 下载 Windows x64 JDK 21 ..."
  mkdir -p "$HOME/.gradle/jdks/win"
  curl -L --retry 3 -o "$HOME/.gradle/jdks/win/OpenJDK21U-jdk_x64_windows_hotspot_${WIN_JDK_VER}_8.zip" \
    "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
  unzip -q -o "$HOME/.gradle/jdks/win/OpenJDK21U-jdk_x64_windows_hotspot_${WIN_JDK_VER}_8.zip" -d "$HOME/.gradle/jdks/win"
fi

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

# —— 4. 前端（本地模式）+ 交叉构建 exe ——
echo "→ 构建前端 + NSIS 安装包 ..."
(cd interview-desktop && npm run sync-spa && npx electron-builder --win --x64 --config electron-builder.win.yml)

echo "✅ 完成：interview-desktop/dist-electron/面霸 Setup 0.1.0.exe"
