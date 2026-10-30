# 知造 SIRP · PLC 联网诊断（只读，不改任何配置）
#
# 用法：双击 plc-diag.bat（无需管理员），或
#       powershell -ExecutionPolicy Bypass -File plc-diag.ps1 -PlcIp 192.168.0.1
#
# 输出：屏幕打印 + 同目录生成 plc-diag-<时间>.txt（把这个文件发给工程师即可）

param(
  [string]$PlcIp = '192.168.0.1',
  [string]$LogDir = $PSScriptRoot
)

$ErrorActionPreference = 'SilentlyContinue'
$lines = New-Object System.Collections.Generic.List[string]
function W($t = '') { Write-Host $t; [void]$lines.Add([string]$t) }

W "=== PLC 联网诊断  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="
W ""

# ---------- 1. 网卡状态 ----------
W '【1】物理网卡（Status=Up 才表示网线插好 / 无线已连）'
$nics = Get-NetAdapter | Where-Object { -not $_.Virtual } | Sort-Object Name
foreach ($n in $nics) {
  W ("  {0,-14} {1,-10} {2}" -f $n.Name, $n.Status, $n.LinkSpeed)
}
$eth = $nics | Where-Object { $_.Name -eq '以太网' } | Select-Object -First 1
if (-not $eth) {
  $eth = $nics | Where-Object { $_.InterfaceDescription -notmatch 'Wireless|Wi-Fi|WLAN|802\.11' } |
         Select-Object -First 1
}
W ''

# ---------- 2. IP / 网关 / DNS ----------
W '【2】IPv4 地址、默认网关、DNS'
foreach ($cfg in (Get-NetIPConfiguration | Where-Object { $_.NetAdapter.Status -eq 'Up' })) {
  $ips = ($cfg.IPv4Address.IPAddress) -join ', '
  $gw = ($cfg.IPv4DefaultGateway.NextHop) -join ', '
  W ("  {0,-14} IP={1,-18} 网关={2}" -f $cfg.InterfaceAlias, $ips, $gw)
}
W ''
foreach ($d in (Get-DnsClientServerAddress -AddressFamily IPv4 | Where-Object { $_.ServerAddresses })) {
  W ("  DNS {0,-12} {1}" -f $d.InterfaceAlias, ($d.ServerAddresses -join ', '))
}
W ''

# ---------- 3. 默认路由 ----------
W '【3】默认路由 0.0.0.0/0（正常只有一条，指向能上网的那块网卡）'
$defRoutes = Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric
if (-not $defRoutes) {
  W '  （没有默认路由 —— 一定上不了网）'
} else {
  foreach ($r in $defRoutes) {
    W ("  ifIndex={0,-4} {1,-14} 下一跳={2,-16} 路由跃点={3} 接口跃点={4}" -f `
        $r.ifIndex, $r.InterfaceAlias, $r.NextHop, $r.RouteMetric, $r.InterfaceMetric)
  }
}
W ''

# ---------- 4. 接口跃点 ----------
W '【4】接口跃点（越小越优先；给 PLC 的网卡建议设成 9000）'
foreach ($i in (Get-NetIPInterface -AddressFamily IPv4 | Where-Object { $_.ConnectionState -eq 'Connected' })) {
  W ("  {0,-14} 跃点={1,-6} DHCP={2}" -f $i.InterfaceAlias, $i.InterfaceMetric, $i.Dhcp)
}
W ''

# ---------- 5. 连通性 ----------
W '【5】连通性'
function Test-Ping($target, $label) {
  $r = Test-Connection -ComputerName $target -Count 2 -Quiet
  if ($r) { W ("  {0,-22} {1,-16} 通" -f $label, $target) } else { W ("  {0,-22} {1,-16} 不通" -f $label, $target) }
  return $r
}
$okPlc = Test-Ping $PlcIp "PLC"
# 两个公网目标任一通即视为"公网可达"（有些网络会过滤 ICMP）
$okPub1 = Test-Ping '114.114.114.114' "公网IP①"
$okPub2 = Test-Ping '223.5.5.5' "公网IP②"
$okDns = ($okPub1 -or $okPub2)
$router = (Get-NetIPConfiguration | Where-Object { $_.NetAdapter.Status -eq 'Up' -and $_.IPv4DefaultGateway }).IPv4DefaultGateway.NextHop |
          Select-Object -First 1
if ($router) { $null = Test-Ping $router "上网路由器/网关" }
W ''

# ---------- 6. DNS ----------
W '【6】域名解析'
$resolve = $null
try {
  $resolve = Resolve-DnsName 'www.baidu.com' -ErrorAction Stop | Select-Object -First 1
} catch { }
if ($resolve) { W '  www.baidu.com 解析成功' } else { W '  www.baidu.com 解析失败（DNS 有问题，表现为打不开网页）' }
W ''

# ---------- 7. 代理 / 加速器 ----------
W '【7】系统代理与代理类软件（TUN 模式会抢路由，插新网卡就断网）'
$is = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
W ("  系统代理: 启用={0}  服务器={1}  自动配置脚本={2}" -f $is.ProxyEnable, $is.ProxyServer, $is.AutoConfigURL)
$names = 'clash', 'mihomo', 'v2ray', 'xray', 'sing-box', 'verge', 'nekoray', 'shadowsocks', 'trojan'
$found = @()
foreach ($p in (Get-Process | Where-Object { $_.ProcessName })) {
  foreach ($k in $names) {
    if ($p.ProcessName.ToLower().Contains($k)) { $found += ("{0}({1})" -f $p.ProcessName, $p.Id); break }
  }
}
if ($found.Count -gt 0) { W ("  正在运行: " + (($found | Select-Object -Unique) -join ', ')) }
else { W '  未发现常见代理进程' }
W ''

# ---------- 8. 结论 ----------
W '【8】结论'
if (-not $eth -or $eth.Status -ne 'Up') {
  W '  网线没插 / 以太网未连接 —— 先把线插好再跑一次本诊断。'
  W '  注意：要判断“插线后断网”，必须在插着线、且断网发生的那一刻运行本脚本。'
} else {
  $ethCfg = Get-NetIPConfiguration -InterfaceAlias $eth.Name
  $ethGw = ($ethCfg.IPv4DefaultGateway.NextHop) -join ','
  if ($ethGw) {
    W ("  [A] 以太网冒出了默认网关 {0} —— 所有上网流量被送进 PLC 网段，必然断网。" -f $ethGw)
    W '      修法：清空这块网卡的网关（只留 IP + 掩码）。跑 plc-on.bat 即可。'
  } elseif ($defRoutes.Count -gt 1) {
    W '  [A] 存在多条默认路由 —— 两个网关打架。跑 plc-on.bat 把 PLC 网卡降到最低优先级。'
  } elseif (-not $okDns -and $okPlc) {
    W '  [C] PLC 能通、公网 IP 不通 —— 默认路由/NAT 被抢。跑 plc-on.bat。'
  } elseif ($okDns -and -not $resolve) {
    W '  [C] 公网 IP 能通但域名解析失败 —— DNS 被 PLC 网卡抢答。跑 plc-on.bat 清空其 DNS。'
  } elseif ($found.Count -gt 0) {
    W '  [D] 检测到代理/加速器在运行 —— 若它开了 TUN/透明代理，插新网卡会抢路由。'
    W '      先退出它，插线再试；能上网就说明是它的问题。'
  } else {
    W '  未命中已知模式。请把本文件（plc-diag-*.txt）发给工程师分析。'
    W ("  提示：以太网 IP={0}  PLC={1}  公网={2}" -f $ethCfg.IPv4Address.IPAddress, $okPlc, $okDns)
  }
}
W ''

# ---------- 保存 ----------
$out = Join-Path $LogDir ("plc-diag-{0}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
try {
  $lines | Set-Content -Path $out -Encoding UTF8
  Write-Host ""
  Write-Host ("报告已保存：{0}" -f $out) -ForegroundColor Green
} catch {
  Write-Host ("报告保存失败：{0}" -f $_.Exception.Message) -ForegroundColor Yellow
}
Write-Host '按任意键关闭窗口...'
try { $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown') } catch { }
