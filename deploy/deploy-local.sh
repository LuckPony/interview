#!/usr/bin/env bash
# ============================================================================
# 本地一键部署：构建后端 jar + Web SPA → 上传服务器 → 重启服务 → 健康检查
#
# 背景：服务器机房（陕西电信云基地/XIAOTEYUN）限制境外入站，GitHub Actions
#       直连部署不可用；本机（国内 IP）SSH 可达，故用本脚本完成部署。
#
# 用法（在仓库根目录任意位置执行）：
#   bash deploy/deploy-local.sh
#
# 可配置环境变量（均有默认值）：
#   SSH_HOST   服务器 IP                默认 103.236.92.40
#   SSH_PORT   服务器 SSH 端口（机房 NAT→22） 默认 37777
#   SSH_USER   SSH 用户名               默认 root
#   SSH_KEY    SSH 私钥路径             默认 ~/.ssh/id_ed25519
#   DEPLOY_DIR 服务器部署目录           默认 /opt/mianba
#
# 流程：预检 SSH → gradle 构建 jar → vite 构建 web（相对 API）→
#       scp/tar 上传产物 → 服务器跑 deploy-prod.sh（先备份 PG，
#       再 docker 缓存构建 + 重启 + 健康检查）→ 本地复核后端健康。
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SSH_HOST="${SSH_HOST:-103.236.92.40}"
SSH_PORT="${SSH_PORT:-37777}"
SSH_USER="${SSH_USER:-root}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_rsa}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/mianba}"
JAR="app/build/libs/app-0.0.1-SNAPSHOT.jar"

step() { echo; echo "==> $1"; }

# Git Bash 下混用其它发行版的 rsync.exe 与 ssh.exe 会触发
# "dup() in/out/err failed"。统一使用 Git 自带的 ssh/scp，并用 tar
# 传输目录，避免 Windows 上的 rsync 运行时兼容问题。
SSH=(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 -p "$SSH_PORT")
SCP=(scp -i "$SSH_KEY" -o StrictHostKeyChecking=no -P "$SSH_PORT")
REMOTE="$SSH_USER@$SSH_HOST"

upload_tree() {
  local local_dir="$1"
  local remote_target="$2"
  local remote_parent="${remote_target%/*}"
  local remote_name="${remote_target##*/}"
  local remote_stage="$remote_parent/.${remote_name}.uploading"
  local remote_previous="$remote_parent/.${remote_name}.previous"

  "${SSH[@]}" "$REMOTE" \
    "set -e; rm -rf -- '$remote_stage'; mkdir -p '$remote_stage'"
  tar -C "$local_dir" -czf - . | \
    "${SSH[@]}" "$REMOTE" "tar -xzf - -C '$remote_stage'"
  "${SSH[@]}" "$REMOTE" \
    "set -e; rm -rf -- '$remote_previous'; \
     if [ -e '$remote_target' ]; then mv '$remote_target' '$remote_previous'; fi; \
     mv '$remote_stage' '$remote_target'"
}

# ---------- 0/5 预检 ----------
[ -f "$SSH_KEY" ] || { echo "❌ SSH 私钥不存在：$SSH_KEY"; exit 1; }
[[ "$DEPLOY_DIR" =~ ^/[A-Za-z0-9._/-]+$ && "$DEPLOY_DIR" != "/" ]] || {
  echo "❌ DEPLOY_DIR 必须是安全的绝对路径且不能为根目录：$DEPLOY_DIR"
  exit 1
}
command -v ssh >/dev/null || { echo "❌ 缺少 ssh"; exit 1; }
command -v scp >/dev/null || { echo "❌ 缺少 scp"; exit 1; }
command -v tar >/dev/null || { echo "❌ 缺少 tar"; exit 1; }

step "0/5 预检服务器 SSH 连通性（${SSH_USER}@${SSH_HOST}:${SSH_PORT}）"
if ! "${SSH[@]}" "$REMOTE" 'command -v tar >/dev/null && echo SSH_OK' \
     2>/dev/null | grep -q SSH_OK; then
  echo "❌ 无法 SSH 到服务器（检查网络/密钥/端口）"
  exit 1
fi
echo "   ✓ SSH 连通"

# ---------- 1/5 构建后端 ----------
step "1/5 构建后端 jar（gradle）"
# Windows 主机（含 WSL/Git Bash）：JDK 只有 java.exe，Unix 的 ./gradlew 找不到 bin/java，
# 且经 WSL interop 调 Windows java 无法解析 /mnt/... 路径（报 "Unable to access jarfile"）。
# 因此优先用 gradlew.bat（经 cmd.exe，用 Windows 路径解析 JAVA_HOME\bin\java.exe）。
if [ -f gradlew.bat ] && command -v cmd.exe >/dev/null 2>&1; then
  GRADLE_LAUNCHER="cmd.exe /c call gradlew.bat"
else
  GRADLE_LAUNCHER="./gradlew"
fi
$GRADLE_LAUNCHER :app:bootJar --console=plain
[ -f "$JAR" ] || { echo "❌ jar 构建失败：$JAR 不存在"; exit 1; }
echo "   ✓ jar: $(ls -la "$JAR" | awk '{print $5}') bytes"

# ---------- 2/5 构建 Web（官网落地页 + /app SPA）----------
step "2/5 构建 Web（官方站点 + SPA 打包至 /app）"
npm --prefix frontend run build:web >/dev/null 2>&1 || { echo "❌ web 构建失败"; exit 1; }
[ -f frontend/dist/index.html ] || { echo "❌ web 构建产物缺失"; exit 1; }

# 有官网源码时组装完整 nginx 根目录；没有时仅更新服务器 /app，
# 保留服务器当前的官网根目录，避免本地缺文件导致发布中断或误删官网。
WEB_STAGE="$ROOT/deploy/web-build/web"
rm -rf "$WEB_STAGE" && mkdir -p "$WEB_STAGE"
if [ -f "$ROOT/official-site/index.html" ]; then
  cp -R "$ROOT/official-site/." "$WEB_STAGE/"
  mkdir -p "$WEB_STAGE/app"
  cp -R "$ROOT/frontend/dist/." "$WEB_STAGE/app/"
  [ -f "$WEB_STAGE/app/index.html" ] || { echo "❌ SPA 产物缺失"; exit 1; }
  chmod -R a+rX "$WEB_STAGE"
  WEB_UPLOAD_SOURCE="$WEB_STAGE"
  WEB_UPLOAD_TARGET="$DEPLOY_DIR/web-image/web"
  echo "   ✓ web: official-site (/) + SPA (/app)"
else
  WEB_UPLOAD_SOURCE="$ROOT/frontend/dist"
  WEB_UPLOAD_TARGET="$DEPLOY_DIR/web-image/web/app"
  echo "   ⚠ official-site/index.html 不存在：本次仅更新 /app，保留服务器现有官网"
fi

# ---------- 3/5 上传产物 ----------
step "3/5 上传产物到服务器"
"${SSH[@]}" "$REMOTE" "mkdir -p '$DEPLOY_DIR/backend' '$DEPLOY_DIR/web-image/web'"
"${SCP[@]}" "$JAR" "$REMOTE:$DEPLOY_DIR/backend/app.jar.uploading"
"${SSH[@]}" "$REMOTE" \
  "set -e; if [ -f '$DEPLOY_DIR/backend/app.jar' ]; then \
     mv -f '$DEPLOY_DIR/backend/app.jar' '$DEPLOY_DIR/backend/app.jar.previous'; fi; \
   mv -f '$DEPLOY_DIR/backend/app.jar.uploading' '$DEPLOY_DIR/backend/app.jar'"
echo "   ✓ backend/app.jar"
upload_tree "$WEB_UPLOAD_SOURCE" "$WEB_UPLOAD_TARGET"
echo "   ✓ $WEB_UPLOAD_TARGET"
"${SCP[@]}" deploy/nginx.conf "$REMOTE:$DEPLOY_DIR/web-image/nginx.conf.uploading"
"${SSH[@]}" "$REMOTE" \
  "mv -f '$DEPLOY_DIR/web-image/nginx.conf.uploading' '$DEPLOY_DIR/web-image/nginx.conf'"
echo "   ✓ web-image/nginx.conf"
"${SCP[@]}" deploy/deploy-prod.sh "$REMOTE:$DEPLOY_DIR/deploy-prod.sh.uploading"
"${SSH[@]}" "$REMOTE" \
  "mv -f '$DEPLOY_DIR/deploy-prod.sh.uploading' '$DEPLOY_DIR/deploy-prod.sh'"
echo "   ✓ deploy-prod.sh"

# ---------- 4/5 服务器执行部署 ----------
step "4/5 服务器执行部署（PG 备份 → docker 构建/重启 → 健康检查，约 1~3 分钟）"
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
    -o ServerAliveInterval=30 -p "$SSH_PORT" "$REMOTE" \
  "export DEPLOY_DIR='$DEPLOY_DIR' COMPOSE_FILE='$DEPLOY_DIR/docker-compose.yml'; \
   chmod +x '$DEPLOY_DIR/deploy-prod.sh'; bash '$DEPLOY_DIR/deploy-prod.sh'"

# ---------- 5/5 健康检查 ----------
step "5/5 复核后端健康（http://$SSH_HOST:23333/actuator/health）"
sleep 3
HEALTH="$(curl -s --max-time 20 "http://$SSH_HOST:23333/actuator/health" 2>/dev/null || true)"
if echo "$HEALTH" | grep -q '"status":"UP"'; then
  echo "✅ 部署成功，服务器后端健康：$HEALTH"
else
  echo "⚠️ 健康检查未通过：$HEALTH"
  exit 1
fi
