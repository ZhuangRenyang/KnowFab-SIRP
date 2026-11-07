# 知造 SIRP · 撤销 PLC 网段别名（只删别名，主 IP 原样不动）
#
# 用法：双击 plc-off.bat（会自动请求管理员），或
#       powershell -ExecutionPolicy Bypass -File plc-off.ps1
#
# ===== 这个脚本做过一次语义变更（2026-11-07），现场手册要跟着改 =====
#   旧版：把以太网改回 DHCP（netsh set address source=dhcp）⇒ **主 IP 被换掉**。
#   新版：**只删 plc-on 加的 IP 别名**，主 IP 与 DHCP 一概不动。
#         ⇒ 主 IP 从来没被改过，所以「恢复上网」根本不需要这一步；
#           现在这一步的含义是「撤销我加的网段别名」。

param(
  [string]$Name = '以太网',
  # 别名清单文件（与 plc-on 共用同一个）
  [string]$AliasFile = (Join-Path $PSScriptRoot '.plc-aliases'),
  [switch]$KeepMetric   # 只删别名，不动跃点与共享绑定
)

$ErrorActionPreference = 'Stop'

function Say($m, $color = 'Gray') { Write-Host $m -ForegroundColor $color }
function Die($m) { Write-Host "失败：$m" -ForegroundColor Red; Read-Host '按回车退出' | Out-Null; exit 1 }

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  Die '需要管理员权限。请右键 plc-off.bat → 以管理员身份运行。'
}

$nic = Get-NetAdapter -Name $Name -ErrorAction SilentlyContinue
if (-not $nic) {
  $nic = Get-NetAdapter | Where-Object {
    -not $_.Virtual -and $_.InterfaceDescription -notmatch 'Wireless|Wi-Fi|WLAN|802\.11'
  } | Select-Object -First 1
}
if (-not $nic) { Die "找不到名为「$Name」的以太网卡。" }
if ($nic.InterfaceDescription -match 'Wireless|Wi-Fi|WLAN|802\.11') { Die '选中的是无线网卡，拒绝修改。' }

$name = $nic.Name
Say ""
Say ("目标网卡：{0}  （{1}）" -f $name, $nic.InterfaceDescription) 'Cyan'
Say ""

# ---- 1) 读别名清单 ----
Say '[1/3] 读取别名清单...'
$entries = @()
if (Test-Path $AliasFile) {
  $entries = @(Get-Content $AliasFile -ErrorAction SilentlyContinue | Where-Object { $_.Trim() })
}
if ($entries.Count -eq 0) {
  Say ("      清单为空或不存在（{0}）—— 没有别名需要删" -f $AliasFile) 'DarkGray'
} else {
  Say ("      清单 {0} 条" -f $entries.Count) 'Green'
}

# ---- 2) 删别名（只删清单里记的，不误删你手配的 IP）----
Say '[2/3] 删除别名与静态路由...' 'Cyan'
$removed = 0
foreach ($e in $entries) {
  $parts = $e -split '\|'
  if ($parts.Count -lt 2) { continue }
  $nicName = $parts[0]
  $rest = $parts[1]
  if ($nicName -ne $name) {
    Say ("      {0} 不属于本网卡，跳过" -f $rest) 'DarkGray'
    continue
  }
  if ($parts.Count -ge 4 -and $parts[1] -eq 'route') {
    # 静态路由（清单里记成 nic|route|网段|网关）
    $net = $parts[2]
    $gw  = $parts[3]
    $rt = Get-NetRoute -DestinationPrefix $net -InterfaceAlias $name -ErrorAction SilentlyContinue |
          Where-Object { $_.NextHop -eq $gw }
    if (-not $rt) {
      Say ("      路由 {0} via {1} 本来就不在，无需删" -f $net, $gw) 'DarkGray'
      continue
    }
    Remove-NetRoute -DestinationPrefix $net -NextHop $gw -InterfaceAlias $name -Confirm:$false -ErrorAction SilentlyContinue
    $removed++
    Say ("      已删除路由 {0} via {1}" -f $net, $gw) 'Green'
    continue
  }
  # IP 别名
  $seg = $rest
  $sp = $seg -split '/'
  $addr = $sp[0]
  $exists = Get-NetIPAddress -InterfaceAlias $name -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -eq $addr }
  if (-not $exists) {
    Say ("      {0} 本来就不在，无需删" -f $addr) 'DarkGray'
    continue
  }
  Remove-NetIPAddress -InterfaceAlias $name -IPAddress $addr -Confirm:$false -ErrorAction SilentlyContinue
  $removed++
  Say ("      已删除 {0}" -f $addr) 'Green'
}
Say ("      共删除 {0} 项（别名 + 路由）" -f $removed) 'Green'

# 清掉清单里属于本网卡、且已删掉的记录
$keep = @()
foreach ($e in $entries) {
  $parts = $e -split '\|'
  if ($parts.Count -ge 2 -and $parts[0] -eq $name) { continue }
  $keep += $e
}
if (Test-Path $AliasFile) {
  if ($keep.Count -eq 0) { Remove-Item $AliasFile -Force -ErrorAction SilentlyContinue }
  else { Set-Content -Path $AliasFile -Value $keep -Encoding UTF8 }
}

# ---- 3) 跃点与共享绑定 ----
if (-not $KeepMetric) {
  Say '[3/3] 接口跃点恢复自动 + 恢复文件共享 / 网络客户端绑定...' 'Cyan'
  Set-NetIPInterface -InterfaceAlias $name -AddressFamily IPv4 -AutomaticMetric Enabled
  Say '      跃点已恢复自动' 'Green'
  foreach ($cid in @('ms_server', 'ms_msclient')) {
    $b = Get-NetAdapterBinding -Name $name -ComponentID $cid -ErrorAction SilentlyContinue
    if ($b -and -not $b.Enabled) {
      Enable-NetAdapterBinding -Name $name -ComponentID $cid -ErrorAction SilentlyContinue
      Say ("      已启用 {0}" -f $cid) 'Green'
    }
  }
} else {
  Say '[3/3] 保留跃点与共享绑定（-KeepMetric）' 'DarkGray'
}

Say ''
Say '当前配置（主 IP 应与 plc-on 之前完全一致）：' 'Cyan'
Get-NetIPConfiguration -InterfaceAlias $name | ForEach-Object {
  Say ("  IP={0}  网关={1}" -f ($_.IPv4Address.IPAddress -join ','), (($_.IPv4DefaultGateway.NextHop) -join ','))
}
$wifi = Get-NetIPConfiguration | Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-Fi' -and $_.NetAdapter.Status -eq 'Up' }
if ($wifi) { Say ("  WiFi: {0}，上网不受影响" -f ($wifi.IPv4Address.IPAddress -join ',')) 'Green' }

Read-Host '按回车退出' | Out-Null
