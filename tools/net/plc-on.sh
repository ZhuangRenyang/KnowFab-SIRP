#!/usr/bin/env bash
# 知造 SIRP · 配置 PLC 网段可达（只追加 IP 别名，不动主 IP）
#
# 用法：sudo ./plc-on.sh
#       sudo ./plc-on.sh -i eth0 -s 192.168.10.100/24 -s 192.168.20.100/24 -p 192.168.10.1 -p 192.168.20.1
#
# ===== 这个脚本与 Windows 版 plc-on.ps1 语义一致（2026-11-07）=====
#   旧做法：改主 IP（或者干脆让每台设备一个网段）⇒ 每次接 PLC 都要改本机网络配置。
#   新做法：**保留主 IP 不动**，只**追加**静态 IP 别名（可一次配多个网段）。
#         ⇒ 加设备/加网段不需要改本机 IP；主 IP 没动过 ⇒ 不会抢默认路由。
#
#   为什么「1000 台设备也不需要 1000 个本机 IP」：
#     本机 IP 数量 = **网段数量**，不是设备数量。
#     1000 台设备各用不同 IP（192.168.10.1 ~ .1000）只要在**同一网段**，
#     本机一个 192.168.10.100/24 就够连全部 1000 台 —— 系统按路由表自动选源地址。
#
# ⚠️ **Linux 上的已知限制**：手工加的 IP 别名在
#    `systemctl restart NetworkManager` / `systemctl restart systemd-networkd`
#    或网卡重新 up 之后**可能被清掉**。
#    ⇒ 所以要长期生效，请把它做成一个 oneshot 服务（见本文件末尾）。

set -euo pipefail

IFACE="eth0"
SEGMENTS=()        # 形如 192.168.10.100/24
PLC_IPS=()         # 用于验证
ALIAS_FILE="$(cd "$(dirname "$0")" && pwd)/.plc-aliases"

usage() {
  cat <<'EOF'
用法: sudo ./plc-on.sh [选项]
  -i IFACE        网卡名（默认 eth0）
  -s SEGMENT      网段别名，形如 192.168.10.100/24（可多次）
  -p PLC_IP       用于 ping 验证的 PLC 地址（可多次）
  -m METRIC       默认路由 metric，默认 9000（越大越不优先，保证 WiFi 优先）
  -h              本帮助
默认会加 192.168.10.100/24 并验证 192.168.10.1
EOF
}

SEGMENTS=()
PLC_IPS=()
METRIC=9000

while getopts "i:s:p:m:h" opt; do
  case "$opt" in
    i) IFACE="$OPTARG" ;;
    s) SEGMENTS+=("$OPTARG") ;;
    p) PLC_IPS+=("$OPTARG") ;;
    m) METRIC="$OPTARG" ;;
    h) usage; exit 0 ;;
    *) usage; exit 1 ;;
  esac
done

# ⚠️ **默认值只在「一个都没传」时才用**（2026-11-07 真机实测发现）：
#   早先的写法是「默认段 + 用户传的 -s」**叠加** ⇒ 用户只想加 192.168.20.100/24，
#   结果脚本**顺手把默认的 192.168.10.100/24 也加了** —— 多出一个没要求的地址。
#   ⇒ 现在：传了 -s 就完全以 -s 为准，没传才用默认。
if [ ${#SEGMENTS[@]} -eq 0 ]; then SEGMENTS=("192.168.10.100/24"); fi
if [ ${#PLC_IPS[@]} -eq 0 ]; then PLC_IPS=("192.168.10.1"); fi

[ "$(id -u)" -eq 0 ] || { echo "失败：需要 root（sudo）"; exit 1; }
command -v ip >/dev/null 2>&1 || { echo "失败：找不到 ip 命令（iproute2）"; exit 1; }
ip link show "$IFACE" >/dev/null 2>&1 || { echo "失败：网卡 $IFACE 不存在（用 ip link 看有哪些）"; exit 1; }

say() { printf '%s\n' "$*"; }

say "目标网卡：$IFACE"
say "当前地址："
ip -4 addr show dev "$IFACE" | sed 's/^/    /'
say ""

# ---- 1) 追加别名（不动主 IP、不带网关）----
say "[1/3] 追加 PLC 网段别名（保留主 IP，不带网关）..."
added=0
for seg in "${SEGMENTS[@]}"; do
  if ! [[ "$seg" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]{1,2}$ ]]; then
    echo "失败：网段格式不对 '$seg'（应形如 192.168.20.100/24）"; exit 1
  fi
  addr="${seg%%/*}"
  if ip -4 addr show dev "$IFACE" | grep -qE "inet ${addr}/"; then
    say "    $addr 已存在，跳过"
    continue
  fi
  # ⚠️ 不加 via ⇒ 别名不带网关 ⇒ 不会抢默认路由；主 IP 原样保留
  ip addr add "$seg" dev "$IFACE"
  added=$((added + 1))
  say "    已追加 $seg（无网关）"
done
[ "$added" -eq 0 ] && say "    没有需要新增的别名（已是最新）"

# ---- 2) 记录清单（plc-off 靠它精确回滚）----
say "[2/3] 记录别名清单..."
touch "$ALIAS_FILE"
for seg in "${SEGMENTS[@]}"; do
  grep -qxF "$IFACE|$seg" "$ALIAS_FILE" || echo "$IFACE|$seg" >> "$ALIAS_FILE"
done
say "    清单：$ALIAS_FILE（$(wc -l < "$ALIAS_FILE") 条）"

# ---- 3) 默认路由 metric 调高（WiFi 优先）+ 验证 ----
say "[3/3] 把 $IFACE 的默认路由 metric 调到 $METRIC（保证 WiFi 优先上网）..."
GW="$(ip route show dev "$IFACE" default 2>/dev/null | awk 'NR==1{print $3}')"
if [ -n "$GW" ]; then
  ip route replace default via "$GW" dev "$IFACE" metric "$METRIC"
  say "    已设置 default via $GW dev $IFACE metric $METRIC"
else
  say "    $IFACE 上没有默认路由（正常：这正是不断网的前提）"
fi

sleep 2
for ip_addr in "${PLC_IPS[@]}"; do
  if ping -c 2 -W 2 "$ip_addr" >/dev/null 2>&1; then
    say "    $ip_addr 可以 ping 通"
  else
    say "    $ip_addr ping 不通 —— 检查：网线是否插在 PLC 上 / PLC 是否上电 / 本机有没有该网段的别名"
  fi
done

say ""
say "当前地址："
ip -4 addr show dev "$IFACE" | sed 's/^/    /'
say ""
say "默认路由："
ip route show default | sed 's/^/    /'
say ""
say "要点：**主 IP 没动过**，加设备/加网段时再跑一次本脚本即可（幂等）。"
say ""
say "⚠️ Linux 上手工别名在重启网络服务后可能被清掉。要长期生效，请做成 oneshot 服务："
cat <<EOF
    sudo tee /etc/systemd/system/plc-net.service >/dev/null <<'UNIT'
    [Unit]
    Description=知造 SIRP · PLC 网段 IP 别名
    After=network-online.target
    Wants=network-online.target

    [Service]
    Type=oneshot
    RemainAfterExit=yes
    ExecStart=$(pwd)/plc-on.sh -i $IFACE ${SEGMENTS[*]/#/-s }
    ExecStop=$(pwd)/plc-off.sh -i $IFACE

    [Install]
    WantedBy=multi-user.target
    UNIT
    sudo systemctl enable --now plc-net.service
EOF
