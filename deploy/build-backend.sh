#!/usr/bin/env bash
# 可单独运行：只构建并检查后端，不连接服务器。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
build_log="$(mktemp)"
trap 'rm -f -- "$build_log"' EXIT

if [ -f gradlew.bat ] && command -v cmd.exe >/dev/null 2>&1; then
  # Git Bash 会把 /c 当成路径转换，导致 cmd 只打开命令行而不执行 Gradle。
  # 禁用本次调用的参数路径转换，并将 CMD 命令整体传入。
  MSYS_NO_PATHCONV=1 cmd.exe /d /s /c "call gradlew.bat :app:bootJar :app:verifyDeploymentJar --console=plain --no-daemon" | tee "$build_log"
else
  ./gradlew :app:bootJar :app:verifyDeploymentJar --console=plain --no-daemon | tee "$build_log"
fi

# 不只检查旧 JAR 是否存在：必须实际执行了本次 Gradle 内容校验。
grep -qx 'DEPLOY_JAR_VERIFIED' "$build_log" || grep -qx $'DEPLOY_JAR_VERIFIED\r' "$build_log" || {
  echo 'ERROR: 本次未完成后端构建校验，停止部署，禁止上传历史 JAR。' >&2
  exit 1
}
