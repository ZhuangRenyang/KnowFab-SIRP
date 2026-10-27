#!/usr/bin/env bash
#
# 启动前应用待升级包（systemd ExecStartPre 调用，也可以手工跑）。
#
# 设计原则只有一条：**这个脚本永远不返回失败**。
# 它是服务启动链路上的一环，一旦返回非 0，systemd 就不会启动 java，
# 等于"升级动作把系统搞挂了"。所以任何异常都退回旧版本继续启动，
# 把失败原因写进日志，人上去看，而不是让产线停着。
#
# 它做的判断顺序：
#   1. 有 rollback.flag  → 恢复最近一次备份，删 flag
#   2. 有 pending.env    → 备份当前 jar → 校验新 jar 的 sha256 → 换上去
#   3. 都没有            → 什么都不做
set -uo pipefail

DIR="${DIR:-/opt/device-hub}"
UPD="$DIR/updates"
BAK="$DIR/backups"
LOG="$DIR/logs/update.log"
SVC="device-hub"

mkdir -p "$UPD" "$BAK" "$DIR/logs"

log() {
  printf '%s [apply-pending] %s\n' "$(date '+%F %T')" "$*" >> "$LOG"
}

# 从 pending.env 里取一个键（不 source，避免执行到奇怪内容）
kv() {
  local key="$1" file="$2"
  [ -f "$file" ] || return 0
  grep -E "^${key}=" "$file" 2>/dev/null | head -1 | cut -d= -f2-
}

health_ok() {
  # 这个脚本不负责健康检查（那是 upgrade.sh 的事），这里只需要文件级正确
  [ -f "$DIR/device-hub.jar" ]
}

# ---------- 回滚 ----------
if [ -f "$UPD/rollback.flag" ]; then
  LAST="$(ls -1dt "$BAK"/apply-* 2>/dev/null | head -1)"
  if [ -n "${LAST:-}" ] && [ -f "$LAST/device-hub.jar" ]; then
    cp "$DIR/device-hub.jar" "$BAK/rolledback-$(date +%Y%m%d-%H%M%S).jar" 2>/dev/null || true
    if cp "$LAST/device-hub.jar" "$DIR/device-hub.jar"; then
      log "已回滚到 $LAST"
    else
      log "回滚失败（复制出错），保持当前版本"
    fi
  else
    log "要求回滚，但没找到备份包，保持当前版本"
  fi
  rm -f "$UPD/rollback.flag"
  exit 0
fi

# ---------- 应用升级 ----------
PENDING="$UPD/pending.env"
if [ ! -f "$PENDING" ]; then
  exit 0
fi

TO="$(kv TO_VERSION "$PENDING")"
FROM="$(kv FROM_VERSION "$PENDING")"
STAGED="$(kv STAGED "$PENDING")"
SHA="$(kv SHA256 "$PENDING")"
NOTES="$(kv NOTES "$PENDING")"

log "发现待应用升级包：$FROM -> ${TO:-未知}，目录 $STAGED"

NEW_JAR="$STAGED/device-hub.jar"
if [ ! -f "$NEW_JAR" ]; then
  log "待升级包里没有 device-hub.jar（$NEW_JAR），跳过，保留当前版本"
  mv "$PENDING" "$UPD/pending.failed-$(date +%Y%m%d-%H%M%S).env" 2>/dev/null
  exit 0
fi

# 上传时已经验过签名和 sha256，这里再算一遍：防的是"上传之后文件被改过"
# （有人手工动了 updates/staged 目录，或者磁盘出错）。多花两秒，换一个确定性。
if [ -n "${SHA:-}" ]; then
  ACTUAL="$(sha256sum "$NEW_JAR" | awk '{print $1}')"
  if [ "$ACTUAL" != "$SHA" ]; then
    log "新 jar 的 sha256 不匹配（期望 $SHA，实际 $ACTUAL），放弃升级，保留当前版本"
    mv "$PENDING" "$UPD/pending.failed-$(date +%Y%m%d-%H%M%S).env" 2>/dev/null
    exit 0
  fi
  log "sha256 校验通过"
fi

TS="$(date +%Y%m%d-%H%M%S)"
B="$(kv BACKUP_DIR "$PENDING")"
[ -n "${B:-}" ] || B="$BAK/apply-$TS-${FROM:-old}"
mkdir -p "$B"
if [ -f "$DIR/device-hub.jar" ]; then
  cp "$DIR/device-hub.jar" "$B/device-hub.jar" || {
    log "备份当前 jar 失败，放弃升级（不能在没有备份的情况下替换）"
    exit 0
  }
  log "当前版本已备份到 $B"
fi

if cp "$NEW_JAR" "$DIR/device-hub.jar.new" && mv -f "$DIR/device-hub.jar.new" "$DIR/device-hub.jar"; then
  log "已替换为新版本 ${TO:-未知}"

  # 数据库脚本放在这里给"启动时"执行：这个脚本本身不连库。
  # 不在这里跑 SQL 的原因：工控机/服务器不一定装了 mysql 客户端，
  # H2 更没法用 sqlite3 那种通用命令操作；而且换 jar 和改表必须一起成功，
  # 让同一个 JVM 在起来的那一刻按顺序做，出错也只有一个地方要查。
  MIG_SRC="$STAGED/migration.sql"
  if [ -f "$MIG_SRC" ]; then
    mkdir -p "$DIR/migrations"
    if cp "$MIG_SRC" "$DIR/migrations/${TO}.sql"; then
      log "已放入数据库脚本 migrations/${TO}.sql（服务启动时在 Hibernate 之前执行）"
    else
      log "数据库脚本复制失败，需人工把 $MIG_SRC 放到 $DIR/migrations/${TO}.sql"
    fi
  fi
  {
    echo "TO_VERSION=${TO:-}"
    echo "FROM_VERSION=${FROM:-}"
    echo "APPLIED_AT=$(date +%s)000"
    echo "BACKUP_DIR=$B"
  } > "$UPD/applied.env"
  mv "$PENDING" "$UPD/pending.applied-$TS.env" 2>/dev/null || rm -f "$PENDING"
  # 备份目录里留一份说明，半年后回来看得懂这是什么
  {
    echo "从 ${FROM:-未知} 升级到 ${TO:-未知}"
    echo "时间：$(date '+%F %T')"
    echo "说明：${NOTES:-（无）}"
  } > "$B/README.txt"
else
  log "替换 jar 失败（可能磁盘满或权限不对），保留当前版本"
  rm -f "$DIR/device-hub.jar.new"
fi

exit 0
