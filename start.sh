#!/usr/bin/env bash
# 启动后端，并把根目录 .env 里的变量导出为环境变量。
#
# 为什么需要这个脚本：
#   Spring Boot 默认【不读】.env 文件；而且手写 .env 常出现 `KEY = value`（等号两侧有空格）
#   或「键一行、值一行」这类写法，直接 `source .env` 会被 bash 当成命令执行而报错。
#   这里用兼容解析器处理这些非标准写法，再 bootRun，使 application.yml 里的
#   ${API_KEY} / ${MODEL_NAME} 能正确解析到你在 .env 写的值。
#
# 用法：  ./start.sh      （建议替代直接 ./gradlew bootRun）

set -a
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [ -f "$ENV_FILE" ]; then
  while IFS= read -r raw || [ -n "$raw" ]; do
    # 去掉首尾空白
    line="$(printf '%s' "$raw" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [ -z "$line" ] && continue
    case "$line" in \#*) continue;; esac

    if [[ "$line" == *"="* ]]; then
      key="${line%%=*}"
      val="${line#*=}"
      key="$(printf '%s' "$key" | xargs)"   # 去空格
      val="$(printf '%s' "$val" | xargs)"   # 去空格 + 去引号
      [ -n "$key" ] && export "$key=$val"
    else
      # 兼容「键单独一行、值下一行」的写法
      bare="$line"
      IFS= read -r nxt || nxt=""
      nxt="$(printf '%s' "$nxt" | xargs)"
      [ -n "$bare" ] && export "$bare=$nxt"
    fi
  done < "$ENV_FILE"
fi
set +a

cd "$SCRIPT_DIR"

# 关闭从系统/终端继承来的 SOCKS 代理：macOS 上的 Java 默认会读取系统代理配置，
# 把 localhost 的数据库连接（postgres/redis/minio）也路由到代理，导致
# UnknownHostException: localhost。本地开发不需要代理，这里显式清掉并禁用自动探测。
unset JAVA_OPTS
unset _JAVA_OPTIONS
export JAVA_TOOL_OPTIONS="-Djava.net.useSystemProxies=false -DsocksProxyHost= -Dhttp.nonProxyHosts=localhost|127.0.0.1|*.local -DsocksNonProxyHosts=localhost|127.0.0.1|*.local"

# 杀掉可能残留的旧 Gradle 守护进程（它若带着旧代理环境被复用，上面的清理会失效）
./gradlew --stop 2>/dev/null || true

exec ./gradlew :app:bootRun
