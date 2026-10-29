#!/usr/bin/env bash
# 停止便携版：先停应用，再停自带数据库（数据保留在 runtime/data）
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RT="$HERE/runtime"

if [ -f "$RT/app.pid" ]; then
  PID="$(cat "$RT/app.pid")"
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    sleep 2
    kill -9 "$PID" 2>/dev/null || true
    echo "已停止应用（PID $PID）"
  else
    echo "应用未在运行"
  fi
  rm -f "$RT/app.pid"
else
  echo "没有 app.pid，跳过应用"
fi

if [ -x "$RT/mariadb/bin/mariadb-admin" ] && [ -S "$RT/mysql.sock" ]; then
  "$RT/mariadb/bin/mariadb-admin" --no-defaults -u root --socket="$RT/mysql.sock" shutdown 2>/dev/null \
    && echo "已停止数据库" || echo "数据库停止失败（可手动 kill）"
elif [ -f "$RT/mysql.pid" ]; then
  PID="$(cat "$RT/mysql.pid")"
  kill "$PID" 2>/dev/null && echo "已停止数据库（PID $PID）" || true
  rm -f "$RT/mysql.pid"
else
  echo "没有数据库进程记录，跳过"
fi

echo "数据保留在 $RT/data；彻底卸载=删整个目录"
