# 知造 SIRP · 配置 PLC 网段可达（只追加 IP 别名，不动主 IP）
#
# 用法：双击 plc-on.bat（会自动请求管理员），或
#       powershell -ExecutionPolicy Bypass -File plc-on.ps1
#
# ===== 这个脚本做过一次语义变更（2026-11-07），现场手册要跟着改 =====
#   旧版：把网卡上**所有** IPv4 地址删掉、关掉 DHCP、改设一个静态 IP。
#         ⇒ 主 IP 被换掉 ⇒ 必须先 plc-off 才能恢复上网；
#           换 PLC 网段还要改脚本参数重来 —— 这就是「每次接 PLC 都要改本机 IP」的根因。
#   新版：**保留 DHCP 主 IP 不动**，只**追加**静态 IP 别名（可一次配多个网段）。
#         ⇒ 主 IP 没动过 ⇒ 上网不受影响、不需要来回切 plc-on/plc-off；
#           加设备/加网段时再跑一次本脚本即可（幂等，已存在的别名会跳过）。
#
# 做的事：
#   1) 在目标网卡上**追加** PLC 网段别名（每个网段一个），不删任何现有地址、
#      不关 DHCP、别名不带网关
#   2) 接口跃点调到 9000，让 WiFi 永远优先做默认路由（**不断网的关键**）
#   3) 关掉这块网卡上的「文件与打印机共享 / Microsoft 网络客户端」，
#      避免插线时 Windows 去探测局域网共享导致卡顿
#   4) 把本次加的别名记进 .plc-aliases（plc-off 靠它精确回滚，不误删你手配的 IP）
#   5) 最后 ping 一下各 PLC 验证
#
# ===== 为什么「1000 台设备也不需要 1000 个本机 IP」 =====
#   本机 IP 的数量 = **网段数量**，不是设备数量。
#   1000 台设备各用不同 IP（192.168.10.1 ~ 192.168.10.1000）只要在**同一网段**，
#   本机一个 192.168.10.100/24 就够连全部 1000 台 —— 系统按路由表自动选源地址。
#   ⇒ 设备变多不需要改本机 IP；只有**换了网段**才需要再加一个别名。

param(
  [string]$Name   = '以太网',
  # 第一个 PLC 网段别名（保留旧参数名，现场手册与脚本里在用）
  [string]$Ip     = '192.168.10.100',
  [int]   $Prefix = 24,
  # 第一个 PLC 的 IP（用于验证）
  [string]$PlcIp  = '192.168.10.1',
  # 额外的网段别名，形如 '192.168.20.100/24'（可多个；换网段时加这里）
  [string[]]$ExtraSegments = @(),
  # 额外的 PLC IP（用于验证，形如 192.168.20.1）
  [string[]]$ExtraPlcIps   = @(),
  [int]   $Metric = 9000,
  # 别名清单文件（plc-off 靠它精确回滚）
  [string]$AliasFile = (Join-Path $PSScriptRoot '.plc-aliases')
)

$ErrorActionPreference = 'Stop'

function Say($m, $color = 'Gray') { Write-Host $m -ForegroundColor $color }
function Die($m) { Write-Host "失败：$m" -ForegroundColor Red; Read-Host '按回车退出' | Out-Null; exit 1 }

# ---- 管理员检查 ----
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Die '需要管理员权限。请右键 plc-on.bat → 以管理员身份运行。'
}

# ---- 找网卡 ----
$nic = Get-NetAdapter -Name $Name -ErrorAction SilentlyContinue
if (-not $nic) {
  $nic = Get-NetAdapter | Where-Object {
    -not $_.Virtual -and $_.InterfaceDescription -notmatch 'Wireless|Wi-Fi|WLAN|802\.11'
  } | Select-Object -First 1
}
if (-not $nic) { Die "找不到名为「$Name」的以太网卡，也没有其它有线网卡。" }
if ($nic.InterfaceDescription -match 'Wireless|Wi-Fi|WLAN|802\.11') { Die '选中的是无线网卡，拒绝修改。' }

$name = $nic.Name
Say ""
Say ("目标网卡：{0}  （{1}）" -f $name, $nic.InterfaceDescription) 'Cyan'
Say ("当前状态：{0}" -f $nic.Status)
Say ""

# ---- 汇总要加的别名 ----
# ⚠️ 用 `-File` 调用时 PowerShell **不做参数解析**，`-ExtraSegments a,b` 会被当成**一个字符串**
#    "a,b" ⇒ 这里再按逗号拆一次，命令行与脚本内两种传法都能用。
function Expand-List($items) {
  $out = @()
  foreach ($raw in @($items)) {
    if (-not $raw) { continue }
    foreach ($x in ($raw -split ',')) {
      $x = $x.Trim()
      if ($x) { $out += $x }
    }
  }
  return $out
}

$segments = @()
# ⚠️ **默认段只在「一个都没传」时才用**（2026-11-07 真机实测发现）：
#   早先的写法是「默认段 + 用户传的 -ExtraSegments」**叠加** ⇒ 用户只想加 192.168.20.100/24，
#   结果脚本**顺手把默认的 192.168.10.100/24 也加了** —— 多出一个没要求的地址。
#   ⇒ 现在：传了就以传的为准，没传才用默认。
if ($PSBoundParameters.ContainsKey('Ip') -or (Expand-List $ExtraSegments).Count -eq 0) {
  $segments += ("{0}/{1}" -f $Ip, $Prefix)
}
$segments += Expand-List $ExtraSegments
foreach ($s in $segments) {
  if ($s -match '^\d+\.\d+\.\d+\.\d+/\d{1,2}$') { continue }
  Die "网段格式不对：'$s'（应形如 192.168.20.100/24）"
}
$segments = $segments | Select-Object -Unique

$plcIps = @()
if ($PSBoundParameters.ContainsKey('PlcIp') -or (Expand-List $ExtraPlcIps).Count -eq 0) {
  $plcIps += $PlcIp
}
$plcIps += Expand-List $ExtraPlcIps
$plcIps = $plcIps | Where-Object { $_ } | Select-Object -Unique

# ---- 1) 追加别名（不动现有地址、不关 DHCP）----
Say '[1/4] 追加 PLC 网段别名（保留主 IP 与 DHCP）...' 'Cyan'
$existing = @(Get-NetIPAddress -InterfaceAlias $name -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Select-Object -ExpandProperty IPAddress)
$added = @()
foreach ($seg in $segments) {
  $parts = $seg -split '/'
  $addr = $parts[0]
  $len  = [int]$parts[1]
  if ($existing -contains $addr) {
    Say ("      {0} 已存在，跳过" -f $addr) 'DarkGray'
    continue
  }
  # ⚠️ 不传 -DefaultGateway：别名**不带网关** ⇒ 不会抢默认路由
  # ⚠️ 不删现有地址、不关 DHCP ⇒ 主 IP 原样保留，上网不受影响
  New-NetIPAddress -InterfaceAlias $name -IPAddress $addr -PrefixLength $len -AddressFamily IPv4 -ErrorAction Stop | Out-Null
  $added += $seg
  Say ("      已追加 {0}/{1}（无网关）" -f $addr, $len) 'Green'
}
if ($added.Count -eq 0) { Say '      没有需要新增的别名（已是最新）' 'DarkGray' }

# ---- 2) 记录别名清单（plc-off 靠它精确回滚）----
Say '[2/4] 记录别名清单...' 'Cyan'
$existingLines = @()
if (Test-Path $AliasFile) {
  $existingLines = @(Get-Content $AliasFile -ErrorAction SilentlyContinue | Where-Object { $_.Trim() })
}
$lines = New-Object System.Collections.Generic.List[string]
foreach ($l in $existingLines) { [void]$lines.Add($l) }
foreach ($seg in $segments) {
  $entry = "$name|$seg"
  if (-not $existingLines.Contains($entry)) { [void]$lines.Add($entry) }
}
Set-Content -Path $AliasFile -Value $lines -Encoding UTF8
Say ("      清单：{0}（{1} 条）" -f $AliasFile, $lines.Count) 'Green'

# ---- 3) 跃点 + 关共享绑定 ----
Say '[3/4] 跃点设为 9000（WiFi 优先上网）+ 关闭共享相关绑定...' 'Cyan'
Set-NetIPInterface -InterfaceAlias $name -AddressFamily IPv4 -InterfaceMetric $Metric
Say ("      跃点 = {0}" -f $Metric) 'Green'
foreach ($cid in @('ms_server', 'ms_msclient')) {
  $b = Get-NetAdapterBinding -Name $name -ComponentID $cid -ErrorAction SilentlyContinue
  if ($b -and $b.Enabled) {
    Disable-NetAdapterBinding -Name $name -ComponentID $cid -ErrorAction SilentlyContinue
    Say ("      已关闭 {0}" -f $cid) 'Green'
  } else {
    Say ("      {0} 本来就是关闭的" -f $cid) 'DarkGray'
  }
}

# ---- 4) 验证 ----
Say '[4/4] 验证各 PLC 是否可达...' 'Cyan'
Start-Sleep -Seconds 2
foreach ($ip in $plcIps) {
  $ok = Test-Connection -ComputerName $ip -Count 2 -Quiet -ErrorAction SilentlyContinue
  if ($ok) { Say ("      {0} 可以 ping 通" -f $ip) 'Green' }
  else {
    Say ("      {0} ping 不通 —— 检查：网线是否插在 PLC 上 / PLC 是否上电 / 本机有没有该网段的别名" -f $ip) 'Yellow'
  }
}

Say ''
Say '当前生效配置：' 'Cyan'
Get-NetIPConfiguration -InterfaceAlias $name | ForEach-Object {
  Say ("  IP={0}  网关={1}  DNS={2}" -f `
      ($_.IPv4Address.IPAddress -join ','),
      (($_.IPv4DefaultGateway.NextHop) -join ','),
      ((Get-DnsClientServerAddress -InterfaceAlias $name -AddressFamily IPv4).ServerAddresses -join ','))
}
$wifi = Get-NetIPConfiguration | Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-Fi' -and $_.NetAdapter.Status -eq 'Up' }
if ($wifi) { Say ("  WiFi: {0} —— 上网不受影响" -f ($wifi.IPv4Address.IPAddress -join ',')) 'Green' }

Say ''
Say '要点：**主 IP 没动过**，所以不需要 plc-off 来回切；加设备/加网段时再跑一次本脚本即可。' 'Cyan'
Read-Host '按回车退出' | Out-Null
