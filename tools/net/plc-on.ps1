# 知造 SIRP · 切到 PLC 直连模式（只动「以太网」，绝不碰 WLAN）
#
# 用法：双击 plc-on.bat（会自动请求管理员），或
#       powershell -ExecutionPolicy Bypass -File plc-on.ps1 -Ip 192.168.0.100 -PlcIp 192.168.0.1
#
# 做的事：
#   1) 以太网设静态 IP + 掩码，**不设网关、不设 DNS**（这是不断网的关键）
#   2) 接口跃点调到 9000，让 WiFi 永远优先做默认路由
#   3) 关掉这块网卡上的「文件与打印机共享 / Microsoft 网络客户端」，
#      避免插线时 Windows 去探测局域网共享导致卡顿
#   4) 最后 ping 一下 PLC 验证

param(
  [string]$Name   = '以太网',
  [string]$Ip     = '192.168.0.100',
  [int]   $Prefix = 24,
  [int]   $Metric = 9000,
  [string]$PlcIp  = '192.168.0.1'
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

# ---- 1) 静态 IP，无网关 ----
Say '[1/4] 设置静态地址（不填网关、不填 DNS）...'
Get-NetIPAddress -InterfaceAlias $name -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Remove-NetIPAddress -Confirm:$false -ErrorAction SilentlyContinue
Get-NetRoute -InterfaceAlias $name -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
  Remove-NetRoute -Confirm:$false -ErrorAction SilentlyContinue
Set-NetIPInterface -InterfaceAlias $name -Dhcp Disabled -ErrorAction SilentlyContinue
New-NetIPAddress -InterfaceAlias $name -IPAddress $Ip -PrefixLength $Prefix -AddressFamily IPv4 | Out-Null
Set-DnsClientServerAddress -InterfaceAlias $name -ResetServerAddresses -ErrorAction SilentlyContinue
Say ("      已设为 {0}/{1}，网关与 DNS 均为空" -f $Ip, $Prefix) 'Green'

# ---- 2) 跃点 ----
Say '[2/4] 接口跃点设为 9000（WiFi 优先上网）...'
Set-NetIPInterface -InterfaceAlias $name -AddressFamily IPv4 -InterfaceMetric $Metric
Say ("      跃点 = {0}" -f $Metric) 'Green'

# ---- 3) 关掉共享相关绑定 ----
Say '[3/4] 关闭该网卡的文件共享 / 网络客户端绑定（不影响 PLC 通信）...'
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
Say '[4/4] 验证...'
Start-Sleep -Seconds 2
$ok = Test-Connection -ComputerName $PlcIp -Count 2 -Quiet
if ($ok) { Say ("      PLC {0} 可以 ping 通" -f $PlcIp) 'Green' }
else { Say ("      PLC {0} 暂时 ping 不通 —— 检查网线是否插在 PLC 上、PLC 是否上电" -f $PlcIp) 'Yellow' }

Say ""
Say '当前生效配置：' 'Cyan'
Get-NetIPConfiguration -InterfaceAlias $name | ForEach-Object {
  Say ("  IP={0}  网关={1}  DNS={2}" -f `
      ($_.IPv4Address.IPAddress -join ','),
      (($_.IPv4DefaultGateway.NextHop) -join ','),
      ((Get-DnsClientServerAddress -InterfaceAlias $name -AddressFamily IPv4).ServerAddresses -join ','))
}
Say ""
Say '提示：需要恢复上网时，双击 plc-off.bat。' 'Cyan'
Read-Host '按回车退出' | Out-Null
