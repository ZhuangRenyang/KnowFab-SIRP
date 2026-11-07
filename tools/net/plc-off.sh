#!/usr/bin/env bash
# 知造 SIRP · 撤销 PLC 网段别名（只删别名，主 IP 原样不动）
#
# 用法：sudo ./plc-off.sh
#       sudo ./plc-off.sh -i eth0
#
# 与 Windows 版 plc-off.ps1 语义一致：
#   **只删 plc-on 加的 IP 别名**，主 IP 与路由一概不动。
#   （旧做法是改回 DHCP，那会把主 IP 换掉 —— 现在主 IP 从来没被改过。）

set -euo pipefail

IFACE="eth0"
KEEP_ROUTE=0
ALIAS_FILE="$(cd "$(dirname "$0")" && pwd)/.plc-aliases"

while getopts "i:k h" opt 2>/dev/null; do
  case "$opt" in
    i) IFACE="$OPTARG" ;;
    k) KEEP_ROUTE=1 ;;
    h) echo "用法: sudo ./plc-off.sh [-i IFACE] [-k]   (-k 保留默认路由 metric)"; exit 0 ;;
    *) ;;
  esac
done

[ "$(id -u)" -eq 0 ] || { echo "失败：需要 root（sudo）"; exit 1; }
command -v ip >/dev/null 2>&1 || { echo "失败：找不到 ip 命令（iproute2）"; exit 1; }

say() { printf '%s\n' "$*"; }

say "目标网卡：$IFACE"
say ""

# ---- 1) 读清单 ----
say "[1/2] 读取别名清单..."
if [ ! -f "$ALIAS_FILE" ]; then
  say "    清单不存在（$ALIAS_FILE）—— 没有别名需要删"
  ENTRIES=()
else
  ENTRIES=()
  while IFS= read -r line; do
    [ -n "$line" ] && ENTRIES+=("$line")
  done < "$ALIAS_FILE"
  say "    清单 $((${#ENTRIES[@]})) 条"
fi

# ---- 2) 删别名 ----
say "[2/2] 删除别名（只删清单里记的，不误删你手配的 IP）..."
removed=0
kept=()
for e in "${ENTRIES[@]:-}"; do
  [ -z "$e" ] && continue
  nic="${e%%|*}"
  seg="${e#*|}"
  if [ "$nic" != "$IFACE" ]; then
    kept+=("$e")
    say "    $seg 不属于本网卡，跳过"
    continue
  fi
  addr="${seg%%/*}"
  if ip -4 addr show dev "$IFACE" | grep -qE "inet ${addr}/"; then
    ip addr del "$seg" dev "$IFACE"
    removed=$((removed + 1))
    say "    已删除 $addr"
  else
    say "    $addr 本来就不在，无需删"
  fi
done
say "    共删除 $removed 个别名"

# 更新清单（去掉本网卡的记录）
if [ -f "$ALIAS_FILE" ]; then
  if [ ${#kept[@]} -eq 0 ]; then
    rm -f "$ALIAS_FILE"
  else
    printf '%s\n' "${kept[@]}" > "$ALIAS_FILE"
  fi
fi

# ---- 3) 恢复默认路由 metric（可选）----
if [ "$KEEP_ROUTE" -eq 0 ]; then
  GW="$(ip route show dev "$IFACE" default 2>/dev/null | awk 'NR==1{print $3}')"
  if [ -n "$GW" ]; then
    ip route replace default via "$GW" dev "$IFACE" metric 100
    say "    已把 default via $GW 的 metric 恢复为 100"
  fi
fi

say ""
say "当前地址（主 IP 应与 plc-on 之前完全一致）："
ip -4 addr show dev "$IFACE" | sed 's/^/    /'
say ""
say "默认路由："
ip route show default | sed 's/^/    /'
