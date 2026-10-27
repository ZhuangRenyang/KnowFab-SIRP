#!/usr/bin/env bash
#
# 命令行升级 / 回滚（停机执行，带健康检查和自动回滚）
#
# 用法：
#   sudo ./bin/upgrade.sh /path/to/device-hub-1.1.0.zip    升级
#   sudo ./bin/upgrade.sh --apply                          应用网页上已上传的包
#   sudo ./bin/upgrade.sh --rollback                       回滚到上一个版本
#   sudo ./bin/upgrade.sh --status                         看当前版本和备份
#
# 和网页升级的区别：网页上传只是把包校验好放进 updates/staged，
# 真正替换 jar 必须有权限停服务。这个脚本就是干这件的，
# 而且它比 systemd 的 ExecStartPre 多一层：**起不来就自动退回旧版本**。
set -uo pipefail

DIR="${DIR:-/opt/device-hub}"
SVC="${SVC:-device-hub}"
PORT="${PORT:-8080}"
UPD="$DIR/updates"
BAK="$DIR/backups"
LOG="$DIR/logs/update.log"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  完成 %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  注意 %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  失败 %s\033[0m\n' "$*" >&2; exit 1; }
log()  { printf '%s [upgrade] %s\n' "$(date '+%F %T')" "$*" >> "$LOG"; }

[ "$(id -u)" -eq 0 ] || die "要用 root 运行：sudo $0 $*"
command -v systemctl >/dev/null || die "没有 systemd，这个脚本不适用"

version_now() {
  curl -fsS "http://127.0.0.1:$PORT/api/system/version" 2>/dev/null \
    | sed -nE 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' | head -1
}

health() {
  local i
  for i in $(seq 1 40); do
    if curl -fsS "http://127.0.0.1:$PORT/api/system/version" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

case "${1:-}" in
  --status)
    say "当前状态"
    echo "  版本：$(version_now || echo '服务未运行')"
    echo "  服务：$(systemctl is-active "$SVC" 2>/dev/null || echo unknown)"
    echo "  待应用：$([ -f "$UPD/pending.env" ] && echo 有 || echo 无)"
    echo "  备份："
    ls -1dt "$BAK"/apply-* 2>/dev/null | head -5 | sed 's/^/    /' || echo "    （无）"
    exit 0
    ;;
  --rollback)
    LAST="$(ls -1dt "$BAK"/apply-* 2>/dev/null | head -1)"
    [ -n "${LAST:-}" ] || die "没有可回滚的备份"
    [ -f "$LAST/device-hub.jar" ] || die "备份目录里没有 jar：$LAST"
    say "回滚到 $LAST"
    systemctl stop "$SVC"
    cp "$LAST/device-hub.jar" "$DIR/device-hub.jar"
    systemctl start "$SVC"
    if health; then
      ok "已回滚，版本：$(version_now)"
      log "手工回滚到 $LAST"
    else
      die "回滚后服务仍起不来，看日志：journalctl -u $SVC -n 100"
    fi
    exit 0
    ;;
  --apply|"")
    [ -f "$UPD/pending.env" ] || die "没有待应用的升级包（先用网页上传，或直接传包路径）"
    ;;
  *)
    PKG="$1"
    ;;
esac

# ---------- 传包路径时：解压到 staged，并做文件级校验 ----------
if [ -n "${PKG:-}" ]; then
  [ -f "$PKG" ] || die "找不到升级包：$PKG"
  command -v unzip >/dev/null || die "需要 unzip：apt install unzip"

  say "校验升级包 $PKG"
  TMP="$(mktemp -d)"
  unzip -q "$PKG" -d "$TMP" || die "解压失败，包可能损坏"

  # 验签：公钥在 $DIR/bin/update-pub.pem，没配就跳过（并明确警告）
  if [ -f "$DIR/bin/update-pub.pem" ] && [ -f "$TMP/manifest.sig" ] && command -v openssl >/dev/null; then
    if openssl dgst -sha256 -verify "$DIR/bin/update-pub.pem" \
        -signature "$TMP/manifest.sig" "$TMP/manifest.json" >/dev/null 2>&1; then
      ok "签名校验通过"
    else
      rm -rf "$TMP"
      die "签名校验失败：这个包不是我们签的，或者被改过。拒绝升级"
    fi
  else
    warn "没有公钥或 openssl，跳过签名校验（生产环境应当配上）"
  fi

  VER="$(sed -nE 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$TMP/manifest.json" | head -1)"
  [ -n "${VER:-}" ] || { rm -rf "$TMP"; die "manifest.json 里没有 version"; }

  DEST="$UPD/staged/$VER"
  rm -rf "$DEST"
  mkdir -p "$DEST"
  cp -r "$TMP"/. "$DEST"/

  SHA="$(sha256sum "$DEST/device-hub.jar" 2>/dev/null | awk '{print $1}')"
  [ -n "$SHA" ] || { rm -rf "$TMP"; die "升级包里没有 device-hub.jar"; }

  {
    echo "TO_VERSION=$VER"
    echo "FROM_VERSION=$(version_now || echo unknown)"
    echo "STAGED=$DEST"
    echo "SHA256=$SHA"
  } > "$UPD/pending.env"

  rm -rf "$TMP"
  ok "已解包并登记待升级：$VER"
fi

FROM="$(grep -E '^FROM_VERSION=' "$UPD/pending.env" 2>/dev/null | cut -d= -f2-)"
TO="$(grep -E '^TO_VERSION=' "$UPD/pending.env" 2>/dev/null | cut -d= -f2-)"

# ---------- 执行升级 ----------
say "升级 $FROM -> $TO（服务会中断约 10-30 秒）"
TS="$(date +%Y%m%d-%H%M%S)"
BK="$BAK/apply-$TS-${FROM:-old}"
mkdir -p "$BK"
if [ -f "$DIR/device-hub.jar" ]; then
  cp "$DIR/device-hub.jar" "$BK/device-hub.jar" || die "备份失败，放弃升级"
  ok "已备份到 $BK"
fi

systemctl stop "$SVC"

# 复用启动脚本的换包逻辑（它会再校验一次 sha256，失败就保持旧版本）
DIR="$DIR" bash "$DIR/bin/apply-pending.sh"
log "升级命令执行：$FROM -> $TO，备份 $BK"

systemctl start "$SVC"

say "健康检查"
if health; then
  ok "升级完成，当前版本：$(version_now)"
else
  warn "新版本 40 秒内没起来，自动回滚"
  systemctl stop "$SVC"
  if [ -f "$BK/device-hub.jar" ]; then
    cp "$BK/device-hub.jar" "$DIR/device-hub.jar"
    systemctl start "$SVC"
    if health; then
      log "升级失败已自动回滚到 $FROM"
      printf '\n\033[1;33m  已回滚到 %s。新版本起不来，原因看：journalctl -u %s -n 200\033[0m\n' "$FROM" "$SVC"
      exit 2
    fi
  fi
  die "回滚后仍然起不来，需要人工介入：journalctl -u $SVC -n 200"
fi
