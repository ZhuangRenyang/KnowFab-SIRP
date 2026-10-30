# 知造 SIRP · 恢复上网模式（把「以太网」改回自动获取，只动有线网卡）
#
# 用法：双击 plc-off.bat（会自动请求管理员）
#
# 做的事：
#   1) 以太网改回 DHCP（自动获取 IP / DNS）
#   2) 接口跃点恢复「自动」
#   3) 重新打开文件共享 / 网络客户端绑定
#   4) 打印结果，并提示 WiFi 是否正常

param([string]$Name = '以太网')

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

Say '[1/3] 改回自动获取 IP 与 DNS...'
netsh interface ipv4 set address name="$name" source=dhcp | Out-Null
netsh interface ipv4 set dnsservers name="$name" source=dhcp | Out-Null
Say '      已切回 DHCP' 'Green'

Say '[2/3] 接口跃点恢复自动...'
Set-NetIPInterface -InterfaceAlias $name -AddressFamily IPv4 -AutomaticMetric Enabled
Say '      已恢复' 'Green'

Say '[3/3] 恢复文件共享 / 网络客户端绑定...'
foreach ($cid in @('ms_server', 'ms_msclient')) {
  $b = Get-NetAdapterBinding -Name $name -ComponentID $cid -ErrorAction SilentlyContinue
  if ($b -and -not $b.Enabled) {
    Enable-NetAdapterBinding -Name $name -ComponentID $cid -ErrorAction SilentlyContinue
    Say ("      已启用 {0}" -f $cid) 'Green'
  }
}

Say ''
Say '当前配置：' 'Cyan'
Get-NetIPConfiguration -InterfaceAlias $name | ForEach-Object {
  Say ("  IP={0}  网关={1}" -f ($_.IPv4Address.IPAddress -join ','), (($_.IPv4DefaultGateway.NextHop) -join ','))
}
$wifi = Get-NetIPConfiguration | Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-Fi' -and $_.NetAdapter.Status -eq 'Up' }
if ($wifi) { Say ("  WiFi: {0}，上网不受影响" -f ($wifi.IPv4Address.IPAddress -join ',')) 'Green' }

Read-Host '按回车退出' | Out-Null
