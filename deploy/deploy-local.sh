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
#   SSH_KEY    SSH 私钥路径             默认 ~/.ssh/id_rsa
#   DEPLOY_DIR 服务器部署目录           默认 /opt/mianba
#
# 流程：预检 SSH → gradle 构建 jar → vite 构建 web（相对 API）→
#       rsync 上传 3 件产物 → 服务器跑 deploy-prod.sh（先备份 PG，
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

# ---------- 0/5 预检 ----------
[ -f "$SSH_KEY" ] || { echo "❌ SSH 私钥不存在：$SSH_KEY"; exit 1; }
command -v rsync >/dev/null || { echo "❌ 缺少 rsync"; exit 1; }

step "0/5 预检服务器 SSH 连通性（${SSH_USER}@${SSH_HOST}:${SSH_PORT}）"
if ! ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 -p "$SSH_PORT" \
     "$SSH_USER@$SSH_HOST" 'echo SSH_OK' 2>/dev/null | grep -q SSH_OK; then
  echo "❌ 无法 SSH 到服务器（检查网络/密钥/端口）"
  exit 1
fi
echo "   ✓ SSH 连通"

# ---------- 1/5 构建后端 ----------
step "1/5 构建后端 jar（gradle）"
./gradlew :app:bootJar --console=plain
[ -f "$JAR" ] || { echo "❌ jar 构建失败：$JAR 不存在"; exit 1; }
echo "   ✓ jar: $(ls -la "$JAR" | awk '{print $5}') bytes"

# ---------- 2/5 构建 Web ----------
step "2/5 构建 Web SPA（相对 API，nginx 同源代理 /api）"
npm --prefix frontend run build:web >/dev/null 2>&1 || { echo "❌ web 构建失败"; exit 1; }
[ -f frontend/dist/index.html ] || { echo "❌ web 构建产物缺失"; exit 1; }
echo "   ✓ web: frontend/dist"

# ---------- 3/5 上传产物 ----------
step "3/5 上传产物到服务器"
RSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=no -p $SSH_PORT"
rsync -az -e "$RSH" "$JAR" "$SSH_USER@$SSH_HOST:$DEPLOY_DIR/backend/app.jar"
echo "   ✓ backend/app.jar"
rsync -az -e "$RSH" --delete frontend/dist/ "$SSH_USER@$SSH_HOST:$DEPLOY_DIR/web-image/web/"
echo "   ✓ web-image/web/"
rsync -az -e "$RSH" deploy/deploy-prod.sh "$SSH_USER@$SSH_HOST:$DEPLOY_DIR/deploy-prod.sh"
echo "   ✓ deploy-prod.sh"

# ---------- 4/5 服务器执行部署 ----------
step "4/5 服务器执行部署（PG 备份 → docker 构建/重启 → 健康检查，约 1~3 分钟）"
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ConnectTimeout=15 \
    -o ServerAliveInterval=30 -p "$SSH_PORT" "$SSH_USER@$SSH_HOST" \
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
