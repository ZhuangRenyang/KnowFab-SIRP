# 一条命令安装并启动（Windows）
#
#   powershell -ExecutionPolicy Bypass -Command "irm https://gitee.com/ZhuangRenyang/KnowFab-SIRP/raw/main/device-hub/tools/portable/install.ps1 | iex"
#
# 做三件事：找最新发行版里的 portable 包 → 下载解压到 %LOCALAPPDATA%\KnowFabSIRP
#          → 启动（后续首次运行会自动下 JRE 与 MariaDB，见 start.ps1）
# 重复执行会覆盖程序文件，但不会动 runtime\data（业务数据安全）。

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Owner = 'ZhuangRenyang'
$Repo = 'KnowFab-SIRP'
$Dest = Join-Path $env:LOCALAPPDATA 'KnowFabSIRP'

function Say($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Ok($m) { Write-Host "   $m" -ForegroundColor Green }

function Get-PortableUrl {
  # 主源 Gitee（国内快），失败兜底 GitHub
  $apis = @(
    "https://gitee.com/api/v5/repos/$Owner/$Repo/releases/latest",
    "https://api.github.com/repos/$Owner/$Repo/releases/latest"
  )
  foreach ($api in $apis) {
    try {
      $r = Invoke-RestMethod -Uri $api -TimeoutSec 20 -Headers @{ 'User-Agent' = 'knowfab-installer' }
      $a = $r.assets | Where-Object { $_.name -like '*-portable.zip' } | Select-Object -First 1
      if ($a) { Ok "找到便携包：$($a.name)（$($api.Split('/')[2])）"; return $a.browser_download_url }
    } catch { }
  }
  return $null
}

Say '查找最新便携包'
$url = Get-PortableUrl
if (-not $url) {
  Write-Host '没找到 portable 包：请确认发行版已上传 device-hub-<版本>-portable.zip' -ForegroundColor Red
  exit 1
}

Say '下载'
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$zip = Join-Path $env:TEMP ([IO.Path]::GetFileName($url))
& curl.exe -L --ssl-no-revoke --retry 3 -o $zip $url
if ($LASTEXITCODE -ne 0) { throw "下载失败：$url" }
Ok ("{0:N0} MB" -f ((Get-Item $zip).Length / 1MB))

Say '解压'
# 先解到临时目录再替换程序文件，避免覆盖被占用的 jar
$tmp = Join-Path $env:TEMP ("knowfab-" + [Guid]::NewGuid().ToString('N'))
Expand-Archive -Path $zip -DestinationPath $tmp -Force
$sub = Get-ChildItem $tmp -Directory -Filter 'device-hub-*-portable' | Select-Object -First 1
if (-not $sub) { throw '压缩包结构不对（找不到 device-hub-*-portable 目录）' }
# 保留已有 runtime（数据库）与 config，只覆盖程序与脚本
Copy-Item -Path (Join-Path $sub.FullName '*') -Destination $Dest -Recurse -Force
[IO.File]::Delete($zip)
if (Test-Path $tmp) { [IO.Directory]::Delete($tmp, $true) }
Ok "已安装到 $Dest"

Say '启动'
$bat = Join-Path $Dest 'start.bat'
if (-not (Test-Path $bat)) { throw "缺少 start.bat：$bat" }
Start-Process -FilePath $bat -WorkingDirectory $Dest
Write-Host "`n已启动。浏览器访问 http://localhost:8080/app/ ，管理员密码在 $Dest\data\initial-admin.txt" -ForegroundColor Green
