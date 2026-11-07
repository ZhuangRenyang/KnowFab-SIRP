#!/usr/bin/env bash
# 知造 SIRP · PLC 联网诊断（只读，不改任何配置）
#
# 用法：./plc-diag.sh
#       ./plc-diag.sh -p 192.168.10.1 -p 192.168.20.1
#
# 输出：屏幕打印 + 同目录生成 plc-diag-<时间>.txt（发这个文件给工程师即可）
#
# ⚠️ 只读：本脚本**不修改任何网络配置**，可以放心在生产机上跑。
#
# 与 Windows 版 plc-diag.ps1 语义对齐（2026-11-07）：
#   新增「本机网段清单与目标可达性」一节 —— 把「本机到底有没有到那个网段的地址」
#   直接摆出来。以前这件事只能靠试错（改 IP → 连 → 不行 → 再改）。
#
# ⚠️ **本脚本踩过的两个坑（别再踩回去）**：
#   ① 双引号字符串**内部**的 $( ) 里若再出现双引号，外层引号会被**提前闭合**
#      ⇒ 后面的内容被当成 sed 的参数 ⇒ 实测报「unterminated `s' command」，
#        打印出来的命令**少了 IP**（给的是 `-s /24`）—— 那等于没给出建议。
#      ⇒ 凡是要在提示里插 IP，一律用纯参数展开 `${t%.*}.100`，不写 `$( ... )`。
#   ② 「有路由」≠「能直连」：本机有默认路由时，内核会把任意目标都路由到网关，
#      `ip route get` 照样 exit 0 ⇒ 只看「有没有路由」会把**直连不通**误判成可达。
#      ⇒ 判据是**输出里含不含 `via`**：含 = 经网关（不是直连），不含 = 直连。

set -u

PLC_IPS=()
IFACE=""
LOG_DIR="$(cd "$(dirname "$0")" && pwd)"

while getopts "p:i:h" opt; do
  case "$opt" in
    p) PLC_IPS+=("$OPTARG") ;;
    i) IFACE="$OPTARG" ;;
    h) echo "用法: ./plc-diag.sh [-p PLC_IP ...] [-i IFACE]"; exit 0 ;;
    *) echo "用法: ./plc-diag.sh [-p PLC_IP ...] [-i IFACE]"; exit 1 ;;
  esac
done
if [ ${#PLC_IPS[@]} -eq 0 ]; then PLC_IPS=("192.168.10.1"); fi

LINES=()
W() { printf '%s\n' "$*"; LINES+=("$*"); }

# 合法 IPv4：四段十进制，每段 0-255
# ⚠️ 只用正则 ^[0-9]{1,3}\.…$ **不够**：999.999.999.999 也能匹配上，
#   但内核会拒绝它 ⇒ 那样会把它当成一台「ping 不通的设备」报出来，**误导**。
is_valid_ipv4() {
  local ip="$1" p
  local -a parts
  IFS='.' read -r -a parts <<< "$ip"
  [ ${#parts[@]} -eq 4 ] || return 1
  for p in "${parts[@]}"; do
    case "$p" in ''|*[!0-9]*) return 1 ;; esac
    # 去掉前导零后仍要 <= 255；带前导零的写法（010）本身也可疑，直接拒
    [ "${#p}" -le 3 ] || return 1
    [ "$p" -le 255 ] || return 1
  done
  return 0
}

W "=== PLC 联网诊断  $(date '+%Y-%m-%d %H:%M:%S') ==="
W ""

# ---------- 1. 网卡状态 ----------
W "【1】物理网卡（state UP 才表示网线插好 / 无线已连）"
ip -o link show 2>/dev/null | awk '{print "  " $2, $(NF-2), $(NF-1), $NF}' | head -20
W ""

# ---------- 2. IP / 网关 / DNS ----------
W "【2】IPv4 地址、默认网关、DNS"
if [ -f /etc/resolv.conf ]; then
  grep -E "^nameserver" /etc/resolv.conf | sed 's/^/  DNS /'
fi
ip -4 addr show 2>/dev/null | awk '
  /^[0-9]+:/ { dev=$2 }
  /inet / { split($2,a,"/"); printf "  %-14s IP=%-18s 掩码=/%s\n", dev, a[1], a[2] }
'
W ""

# ---------- 2b. 本机网段清单 + 目标可达性 ----------
W "【2b】本机网段清单与目标可达性（本机 IP 数量 = 网段数量，不是设备数量）"
seg_bits="$(ip -4 addr show 2>/dev/null | awk '/inet /{split($2,a,"/"); print a[2]}' | sort -u | tr '\n' ' ')"
if [ -z "${seg_bits// /}" ]; then
  # ⚠️ **「读不到」不等于「没有」** —— 这里必须说清是哪一种，
  #   不能让「读不到」冒充「不可达」，否则人会被引到错误的结论上。
  W "  **读不到本机 IPv4 地址** —— 这一节无法判定。"
  W "  可能原因：(1) 没有权限 (2) 网卡未连接 (3) 这台机器确实没配地址"
else
  W "  本机已配置的掩码位数：${seg_bits}"
  for t in "${PLC_IPS[@]}"; do
    if ! is_valid_ipv4 "$t"; then
      W "  目标 $t -> 不是合法的 IPv4 地址（某段超过 255 或格式不对），**跳过**"
      continue
    fi
    # 纯参数展开取「同网段的本机地址」：192.168.10.1 -> 192.168.10.100
    seg_addr="${t%.*}.100"
    route="$(ip route get "$t" 2>&1)"
    rc=$?
    if [ $rc -eq 0 ]; then
      src="$(printf '%s' "$route" | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')"
      dev="$(printf '%s' "$route" | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')"
      if printf '%s' "$route" | grep -q 'via '; then
        # ⚠️ **有路由 ≠ 能直连**：走的是默认网关（下一跳是路由器），
        #   能不能真连上取决于**现场有没有三层路由**（路由器做网段互转）。
        #   ⇒ 这不是「不可达」，是「不是直连」—— 两者处理动作完全不同。
        W "  目标 $t -> **不是直连**：会经网关出去（源地址 $src，经 $dev）"
        W "      能不能连取决于现场有没有三层路由；本机没有这个网段的地址。"
        W "      稳妥做法 A（网段少）：给本机加该网段的别名"
        W "        sudo bash plc-on.sh -i ${IFACE:-eth0} -s ${seg_addr}/24"
        W "      稳妥做法 B（网段几十上百个）：加**静态路由**（一条覆盖一片，设备表也不用改 IP）"
        W "        sudo bash plc-on.sh -i ${IFACE:-eth0} -r 192.168.0.0/16 -g <网关IP>"
        W "        前提：网关做的是**三层路由**（不是 NAT —— NAT 是一台设备一个映射，上千台配不过来）"
      else
        W "  目标 $t -> **直连可达**（本机有该网段的地址，源地址 $src，经 $dev）"
      fi
    else
      case "$route" in
        *unreachable*|*"Network is unreachable"*)
          W "  目标 $t -> **本机没有到该网段的路由** => 直连不通"
          W "      处理：跑 plc-on.sh 追加该网段的别名（可一次配多个网段）"
          W "      例：sudo ./plc-on.sh -i ${IFACE:-eth0} -s ${seg_addr}/24"
          ;;
        *)
          W "  目标 $t -> 路由查询失败（**不是**「不可达」的结论）：$route"
          W "      ⇒ 这一项无法判定，别据此改配置。"
          ;;
      esac
    fi
  done
fi
W ""

# ---------- 3. 默认路由 ----------
W "【3】默认路由 0.0.0.0/0（正常只有一条，指向能上网的那块网卡）"
ip route show default 2>/dev/null | sed 's/^/  /'
if ! ip route show default 2>/dev/null | grep -q .; then
  W "  **没有默认路由** —— 这台机器上不了网（但直连 PLC 可能仍然通）"
fi
W ""

# ---------- 4. 连通性 ----------
W "【4】连通性"
for t in "${PLC_IPS[@]}"; do
  if ! is_valid_ipv4 "$t"; then
    continue
  fi
  if ping -c 2 -W 2 "$t" >/dev/null 2>&1; then
    W "  $t ping 通"
  else
    W "  $t ping 不通 —— 检查：网线是否插在 PLC 上 / PLC 是否上电 / 本机有没有该网段的别名"
  fi
done
W ""

# ---------- 5. 结论 ----------
W "【5】结论"
n_direct=0; n_via=0; n_unreach=0; n_unknown=0
for t in "${PLC_IPS[@]}"; do
  if ! is_valid_ipv4 "$t"; then
    n_unknown=$((n_unknown + 1)); continue
  fi
  route="$(ip route get "$t" 2>&1)"
  if [ $? -ne 0 ]; then
    n_unknown=$((n_unknown + 1))
  elif printf '%s' "$route" | grep -q 'via '; then
    n_via=$((n_via + 1))
  else
    n_direct=$((n_direct + 1))
  fi
done
if [ $n_direct -gt 0 ]; then
  W "  直连可达 $n_direct 个：这些是本机有同网段地址的，路由没问题。"
fi
if [ $n_via -gt 0 ]; then
  W "  **经网关（非直连）$n_via 个**：本机没有这些网段的地址，"
  W "  能不能连取决于现场有没有三层路由（路由器做网段互转）。"
  W "  => 想稳，就给这些网段各加一个别名：sudo ./plc-on.sh -i ${IFACE:-eth0} -s <网段>/24"
fi
if [ $n_unreach -gt 0 ]; then
  W "  **完全无路由 $n_unreach 个**：必须加别名，否则连不上。"
fi
if [ $n_unknown -gt 0 ]; then
  W "  **无法判定 $n_unknown 个**（地址非法或路由查询失败）—— 先把地址写对。"
fi
if [ $n_direct -eq 0 ] && [ $n_via -eq 0 ] && [ $n_unreach -eq 0 ] && [ $n_unknown -eq 0 ]; then
  W "  没有可判定的目标。"
fi
W ""
W "  ⚠️ 无论上面结论如何：**别改本机主 IP**。"
W "     加别名就行（主 IP 改掉会影响你现在的上网，而且要切回来才恢复）。"

# ---------- 保存 ----------
OUT="$LOG_DIR/plc-diag-$(date '+%Y%m%d-%H%M%S').txt"
if printf '%s\n' "${LINES[@]}" > "$OUT" 2>/dev/null; then
  echo ""
  echo "报告已保存：$OUT"
else
  echo ""
  echo "报告保存失败（目录不可写？）"
fi
