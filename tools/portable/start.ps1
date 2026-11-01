# 知造 SIRP 便携启动器（Windows）
#
# 双击 start.bat，或在 PowerShell 里执行： powershell -ExecutionPolicy Bypass -File start.ps1
#
# 它会自动完成：Java 17（缺则下载）→ MariaDB 便携版（缺则下载并初始化到 runtime\data）
#            → 生成 config\application.yml → 启动服务 → 打开浏览器
#
# 目录约定：
#   runtime\jre        自带 Java（没有就用系统 Java 17+）
#   runtime\mariadb    便携 MariaDB（固定 127.0.0.1:3307，避免和本机 3306 冲突）
#   runtime\data       数据库文件（备份就拷这个目录）
#   config\            应用配置（数据库密码在这里，首次自动生成随机密码）
#
# 全部数据都在本目录内，卸载=删目录；不需要 Docker / MySQL / Python / Git。

param(
  [int]$Port = 8080,
  [int]$DbPort = 3307,
  [switch]$NoBrowser,
  [switch]$NoWait,
  [switch]$ResetDb,
  [switch]$ForceBundledJre
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path $Root 'device-hub.jar'
$Runtime = Join-Path $Root 'runtime'
$JreDir = Join-Path $Runtime 'jre'
$DbDir = Join-Path $Runtime 'mariadb'
$DataDir = Join-Path $Runtime 'data'
$LogDir = Join-Path $Runtime 'logs'
$ConfDir = Join-Path $Root 'config'
$ConfFile = Join-Path $ConfDir 'application.yml'
$Updates  = Join-Path $Root 'updates'
$StopFlag = Join-Path $Runtime 'stop.flag'

# 国内镜像（默认清华/阿里云），海外或镜像不可用时改这里
$JreUrl = 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/windows/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip'
$DbUrl = 'https://mirrors.aliyun.com/mariadb/mariadb-11.4.13/winx64-packages/mariadb-11.4.13-winx64.zip'

function Say($m) { Write-Host "`n== $m" -ForegroundColor Cyan }
function Ok($m)  { Write-Host "   $m" -ForegroundColor Green }
function Warn($m){ Write-Host "   $m" -ForegroundColor Yellow }
function Die($m) {
  Write-Host "`n失败：$m" -ForegroundColor Red
  if (-not $NoWait) { try { Read-Host '按回车退出' | Out-Null } catch { } }
  exit 1
}

# 有的机器同时存在 HTTPS_PROXY / https_proxy（大小写两份），
# Start-Process 组环境字典时会因重复键报错；启动 Java/数据库前统一清掉。
# 这两个进程只访问本机与局域网，不需要代理。
function Clear-ProxyEnv {
  foreach ($k in @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy','NO_PROXY','no_proxy')) {
    if (Test-Path "Env:$k") { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
  }
}

function Download($url, $out) {
  Say "下载 $([IO.Path]::GetFileName($out))"
  $tmp = "$out.part"
  # --ssl-no-revoke：公司代理常见证书吊销检查失败，跳过（只影响本次下载）
  & curl.exe -L --ssl-no-revoke --retry 3 --connect-timeout 20 -o $tmp $url
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp)) { Die "下载失败：$url（可手动下载后放到 $out）" }
  Move-Item -Force $tmp $out
  Ok ("{0:N0} MB" -f ((Get-Item $out).Length / 1MB))
}

# ---------- 1. Java ----------
function Resolve-Java {
  if (Test-Path (Join-Path $JreDir 'bin\java.exe')) { return (Join-Path $JreDir 'bin\java.exe') }
  if (-not $ForceBundledJre) {
    $sys = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($sys) {
      # java -version 走 stderr，必须整体捕获再正则，不能依赖 $LASTEXITCODE
      $out = (& $sys.Source -version 2>&1 | Out-String)
      if ($out -match 'version "(\d+)') {
        $v = [int]$Matches[1]
        if ($v -ge 17) { Ok "使用系统 Java：$($sys.Source)"; return $sys.Source }
      }
    }
  }
  Say '本机没有 Java 17，下载便携 JRE（约 45MB，只需一次）'
  New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
  $zip = Join-Path $Runtime 'jre.zip'
  Download $JreUrl $zip
  # 用 PowerShell 原生 Expand-Archive：不能调 tar.exe——客户机装了 Git Bash 时
  # PATH 里的 MSYS tar 会把 D:\ 当远程主机（"Cannot connect to D: resolve failed"）
  try { Expand-Archive -Path $zip -DestinationPath $Runtime -Force }
  catch { Die 'JRE 解压失败（可手动解压并把目录改名为 runtime\jre）' }
  [IO.File]::Delete($zip)
  $inner = Get-ChildItem $Runtime -Directory | Where-Object { $_.Name -like '*jre*' -and $_.Name -ne 'jre' } | Select-Object -First 1
  if ($inner -and -not (Test-Path $JreDir)) { Rename-Item $inner.FullName 'jre' }
  if (-not (Test-Path (Join-Path $JreDir 'bin\java.exe'))) { Die 'JRE 解压后找不到 bin\java.exe' }
  Ok "JRE 就绪：$JreDir"
  return (Join-Path $JreDir 'bin\java.exe')
}

# ---------- 2. MariaDB 便携版 ----------
function Ensure-MariaDb {
  if (-not (Test-Path (Join-Path $DbDir 'bin\mariadbd.exe'))) {
    Say '首次运行：下载便携 MariaDB（约 80MB，只需一次）'
    New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
    $zip = Join-Path $Runtime 'mariadb.zip'
    Download $DbUrl $zip
    try { Expand-Archive -Path $zip -DestinationPath $Runtime -Force }
    catch { Die 'MariaDB 解压失败' }
    [IO.File]::Delete($zip)
    $inner = Get-ChildItem $Runtime -Directory | Where-Object { $_.Name -like 'mariadb-*' } | Select-Object -First 1
    if (-not $inner) { Die 'MariaDB 解压失败' }
    if (-not (Test-Path $DbDir)) { Rename-Item $inner.FullName 'mariadb' }
    Ok "MariaDB 就绪：$DbDir"
  }

  if ($ResetDb -and (Test-Path $DataDir)) {
    Warn '按参数要求重置数据库（原有业务数据会删除）'
    Remove-Item -Recurse -Force $DataDir
  }

  if (-not (Test-Path (Join-Path $DataDir 'mysql'))) {
    Say '初始化数据库（第一次约 20 秒）'
    New-Item -ItemType Directory -Force -Path $DataDir, $LogDir | Out-Null
    # Windows 版初始化工具是 mysql_install_db.exe，只认 -d/-p/-P 这几个短参数
    # （不是 Linux 的 mariadb-install-db 脚本，别用 --basedir/--auth-root-...）
    $rootPwd = New-DbPassword
    & (Join-Path $DbDir 'bin\mariadb-install-db.exe') -d "$DataDir" -p "$rootPwd" -P $DbPort `
      2>&1 | Tee-Object -FilePath (Join-Path $LogDir 'db-init.log') | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $DataDir 'mysql'))) {
      Die "数据库初始化失败，见 runtime\logs\db-init.log"
    }
    [IO.File]::WriteAllText((Join-Path $Runtime 'root.pwd'), $rootPwd)
    Ok '数据库初始化完成（root 密码保存在 runtime\root.pwd）'
  }
}

function Test-Port([int]$p) {
  try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $p); $c.Close(); return $true } catch { return $false }
}

# ---------- 启动前应用待升级（对应 Linux 的 bin/apply-pending.sh） ----------
# 原则同 Linux 版：永远不阻断启动。出任何问题都退回旧版本继续跑，原因写进 logs\update.log。
function Apply-Pending {
  $pending  = Join-Path $Updates 'pending.env'
  $rollback = Join-Path $Updates 'rollback.flag'
  $bak      = Join-Path $Root 'backups'
  $ulogPath = Join-Path $LogDir 'update.log'
  New-Item -ItemType Directory -Force -Path $Updates, $bak, $LogDir | Out-Null
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $ulog = { param($m) Add-Content -Path $ulogPath -Value "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [apply-pending] $m" -ErrorAction SilentlyContinue }

  try {
    # 1) 回滚优先
    if (Test-Path $rollback) {
      $last = Get-ChildItem $bak -Directory -Filter 'apply-*' -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending | Select-Object -First 1
      if ($last -and (Test-Path (Join-Path $last.FullName 'device-hub.jar'))) {
        Copy-Item $Jar (Join-Path $bak "rolledback-$stamp.jar") -Force -ErrorAction SilentlyContinue
        Copy-Item (Join-Path $last.FullName 'device-hub.jar') $Jar -Force
        & $ulog "已回滚到 $($last.Name)"
      } else { & $ulog '收到回滚请求，但没有可用备份' }
      Remove-Item $rollback -Force -ErrorAction SilentlyContinue
      return
    }

    # 2) 应用升级
    if (-not (Test-Path $pending)) { return }
    $kv = @{}
    Get-Content $pending -ErrorAction SilentlyContinue | Where-Object { $_.Contains('=') } | ForEach-Object {
      $i = $_.IndexOf('='); $kv[$_.Substring(0, $i).Trim()] = $_.Substring($i + 1).Trim()
    }
    $staged  = $kv['STAGED']
    $wantSha = $kv['SHA256']
    $toVer   = $kv['TO_VERSION']
    $newJar  = if ($staged) { Join-Path $staged 'device-hub.jar' } else { $null }
    if (-not $newJar -or -not (Test-Path $newJar)) {
      & $ulog "升级文件缺失（STAGED=$staged），跳过本次升级"
      Remove-Item $pending -Force -ErrorAction SilentlyContinue
      return
    }
    if ($wantSha) {
      $sha = (Get-FileHash $newJar -Algorithm SHA256).Hash.ToLower()
      if ($sha -ne $wantSha.ToLower()) {
        & $ulog "新 jar 校验不通过（期望 $wantSha，实际 $sha），放弃升级"
        Remove-Item $pending -Force -ErrorAction SilentlyContinue
        return
      }
    }
    $bdir = Join-Path $bak "apply-$stamp"
    New-Item -ItemType Directory -Force -Path $bdir | Out-Null
    Copy-Item $Jar (Join-Path $bdir 'device-hub.jar') -Force
    Copy-Item $newJar $Jar -Force
    Remove-Item $pending -Force -ErrorAction SilentlyContinue
    & $ulog "已应用 v$toVer（旧版备份在 backups\$stamp）"
  } catch {
    & $ulog "应用升级出错（保持当前版本继续启动）：$($_.Exception.Message)"
  }
}

function Start-Db {
  if (Test-Port $DbPort) { Ok "数据库已在 $DbPort 运行"; return }
  Say "启动数据库（127.0.0.1:$DbPort）"
  Clear-ProxyEnv
  $args = @('--no-defaults', "--basedir=$DbDir", "--datadir=$DataDir",
            "--port=$DbPort", '--bind-address=127.0.0.1', '--skip-name-resolve',
            '--character-set-server=utf8mb4', '--collation-server=utf8mb4_general_ci',
            "--log-error=$LogDir\db.err.log")
  # 不用 Start-Process 的 stdout 重定向：某些环境下重定向句柄会跟着父进程一起被回收，
  # 数据库会"启动成功然后秒退"。用 --log-error 自己写日志最稳。
  Start-Process -FilePath (Join-Path $DbDir 'bin\mariadbd.exe') -ArgumentList $args `
    -WorkingDirectory $Root -WindowStyle Hidden
  for ($i = 0; $i -lt 60; $i++) { Start-Sleep -Milliseconds 500; if (Test-Port $DbPort) { break } }
  if (-not (Test-Port $DbPort)) { Die "数据库没有起来，见 runtime\logs\db.err.log" }
  Ok '数据库已就绪'
}

# ---------- 3. 配置 ----------
function New-DbPassword {
  $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  -join (1..16 | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

function Ensure-Config {
  if (Test-Path $ConfFile) { Ok "沿用现有配置：$ConfFile"; return }
  Say '生成配置文件（随机数据库密码，仅本机可见）'
  New-Item -ItemType Directory -Force -Path $ConfDir | Out-Null
  $pwd = New-DbPassword
  $yml = @"
# 由便携启动器自动生成（首次运行）。改端口/密码可以直接改这里。
server:
  port: $Port
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:$DbPort/devicehub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true
    username: devicehub
    password: "$pwd"
  jpa:
    # 便携版固定用 MariaDB 11.x：显式指定方言，避免 Hibernate 自动探测失败
    # （"Unable to determine Dialect without JDBC metadata"）
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MariaDBDialect
"@
  [IO.File]::WriteAllText($ConfFile, $yml, (New-Object Text.UTF8Encoding($false)))
  Ok "已写入 $ConfFile"
}

function Ensure-Database {
  $pwd = (Select-String -Path $ConfFile -Pattern 'password:\s*"([^"]+)"').Matches[0].Groups[1].Value
  # 用 ALTER USER 而不是 CREATE USER IF NOT EXISTS：配置被重新生成时密码会变，
  # IF NOT EXISTS 只在用户不存在时生效，会导致 Access denied（踩过）
  $sql = "CREATE DATABASE IF NOT EXISTS devicehub DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci; " +
         "CREATE USER IF NOT EXISTS 'devicehub'@'127.0.0.1' IDENTIFIED BY '$pwd'; " +
         "CREATE USER IF NOT EXISTS 'devicehub'@'localhost' IDENTIFIED BY '$pwd'; " +
         "ALTER USER 'devicehub'@'127.0.0.1' IDENTIFIED BY '$pwd'; " +
         "ALTER USER 'devicehub'@'localhost' IDENTIFIED BY '$pwd'; " +
         "GRANT ALL PRIVILEGES ON devicehub.* TO 'devicehub'@'127.0.0.1'; " +
         "GRANT ALL PRIVILEGES ON devicehub.* TO 'devicehub'@'localhost'; FLUSH PRIVILEGES;"
  Say '确保数据库与账号存在'
  $rootPwd = ''
  $rootFile = Join-Path $Runtime 'root.pwd'
  if (Test-Path $rootFile) { $rootPwd = (Get-Content $rootFile -Raw).Trim() }
  & (Join-Path $DbDir 'bin\mariadb.exe') --no-defaults -u root "-p$rootPwd" -h 127.0.0.1 -P $DbPort -e $sql
  if ($LASTEXITCODE -ne 0) { Die '创建数据库失败（端口被别的程序占用，或 datadir 损坏；可加 -ResetDb 重来，注意会清数据）' }
  Ok '数据库就绪'
}

# ---------- 4. 启动应用 ----------
function Start-App($java) {
  Say "启动知造 SIRP（端口 $Port）"
  Clear-ProxyEnv
  $log = Join-Path $LogDir 'app.log'
  $env:FILE_ENCODING = 'UTF-8'
  # 关键：工作目录必须是程序目录，Spring Boot 才会读到 ./config/application.yml；
  # 否则会回落到 jar 内置默认库（3306），出现"连到别的数据库"的怪现象
  $p = Start-Process -FilePath $java -PassThru -WindowStyle Hidden -WorkingDirectory $Root `
    -ArgumentList @('-Xms256m', '-Xmx512m', '-Dfile.encoding=UTF-8', '-jar', "`"$Jar`"",
                    "--server.port=$Port") `
    -RedirectStandardOutput $log -RedirectStandardError (Join-Path $LogDir 'app.err.log')
  $p.Id | Set-Content (Join-Path $Runtime 'app.pid')
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 1
    try {
      $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://127.0.0.1:$Port/app/" -ErrorAction Stop
      if ($r.StatusCode -eq 200) { Ok '服务已启动'; return $p }
    } catch { }
    if ($p.HasExited) { Die "应用启动失败，见 runtime\logs\app.err.log" }
  }
  Die '等待启动超时，见 runtime\logs\app.log'
}

# ---------- 主流程 ----------
Write-Host "`n知造 SIRP 便携版" -ForegroundColor White
Write-Host "目录：$Root"

if (-not (Test-Path $Jar)) { Die "找不到 device-hub.jar（请把本脚本放在解压后的目录里）" }
New-Item -ItemType Directory -Force -Path $Runtime, $LogDir | Out-Null

if (Test-Port $Port) { Die "端口 $Port 已被占用，换一个：start.bat -Port 8090" }
$java = Resolve-Java
Ensure-MariaDb
Start-Db
Ensure-Config
Ensure-Database
Apply-Pending   # 首次启动前先应用待升级
$proc = Start-App $java

$url = "http://localhost:$Port/app/"
Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host "  已启动： $url" -ForegroundColor Green
Write-Host "  登录：   admin / 123456（首次登录后请立刻改密码）" -ForegroundColor Green
Write-Host "  数据：   $DataDir" -ForegroundColor Green
Write-Host "  停止：   运行 stop.bat" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
if (-not $NoBrowser) { Start-Process $url }

if ($NoWait) { Write-Host "`n-NoWait：启动器退出，服务继续在后台运行"; exit 0 }

# ---------- 守护循环：崩溃自愈 + 网页升级自动重启 ----------
Write-Host "`n守护已开启：服务退出后自动拉起（含网页一键升级重启）；彻底停止请运行 stop.bat"
Write-Host "日志：runtime\logs\app.log"
$crash = 0
while ($true) {
  # 每轮启动前都先应用待升级（幂等：没有 pending/rollback 时立即返回），
  # 避免上一轮 java 退出瞬间的时序竞态漏掉 pending
  Apply-Pending
  $proc = Start-App $java
  Wait-Process -Id $proc.Id -ErrorAction SilentlyContinue
  $code = if ($proc.HasExited) { $proc.ExitCode } else { 0 }

  if (Test-Path $StopFlag) {
    Remove-Item $StopFlag -Force -ErrorAction SilentlyContinue
    Write-Host "`n已按 stop.bat 停止（守护退出）"
    break
  }
  if (Test-Path (Join-Path $Updates 'pending.env')) {
    Apply-Pending
    $crash = 0
    Write-Host "`n检测到升级包，已应用，正在重启…"
    continue
  }
  if ($code -eq 0) { Write-Host "`n服务正常退出，守护结束"; break }
  $crash++
  if ($crash -ge 5) {
    Write-Host "`n连续崩溃 $crash 次，已停止自动拉起。请查看 runtime\logs\app.err.log" -ForegroundColor Red
    break
  }
  Write-Host "`n服务异常退出（code=$code），5 秒后第 $crash 次自动重启…" -ForegroundColor Yellow
  Start-Sleep -Seconds 5
}
