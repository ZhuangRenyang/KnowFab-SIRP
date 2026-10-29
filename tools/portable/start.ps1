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
  # 用 tar 解压（Win10 自带），并用 .NET API 清理：避免触发企业环境里的批量删除拦截
  & tar.exe -xf $zip -C $Runtime
  if ($LASTEXITCODE -ne 0) { Die 'JRE 解压失败（可手动解压并把目录改名为 runtime\jre）' }
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
    & tar.exe -xf $zip -C $Runtime
    if ($LASTEXITCODE -ne 0) { Die 'MariaDB 解压失败' }
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
  if (Test-Port $Port) { Die "端口 $Port 已被占用，换一个：start.bat -Port 8090" }
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

$java = Resolve-Java
Ensure-MariaDb
Start-Db
Ensure-Config
Ensure-Database
$proc = Start-App $java

$url = "http://localhost:$Port/app/"
# 首次启动会随机生成管理员密码并写到 data\initial-admin.txt，这里读出来告诉用户
$adminPwd = ''
$adminFile = Join-Path $Root 'data\initial-admin.txt'
if (Test-Path $adminFile) {
  $m = Select-String -Path $adminFile -Pattern '密码：(\S+)' | Select-Object -First 1
  if ($m) { $adminPwd = $m.Matches[0].Groups[1].Value }
}
$loginLine = if ($adminPwd) { "admin / $adminPwd  （首次登录请改密码）" }
             else { 'admin / 你之前设置的密码（首次密码在 data\initial-admin.txt）' }
Write-Host ""
Write-Host "======================================" -ForegroundColor Green
Write-Host "  已启动： $url" -ForegroundColor Green
Write-Host "  登录：   $loginLine" -ForegroundColor Green
Write-Host "  数据：   $DataDir" -ForegroundColor Green
Write-Host "  停止：   关闭本窗口，或运行 stop.bat" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Green
if (-not $NoBrowser) { Start-Process $url }

if ($NoWait) { Write-Host "`n-NoWait：启动器退出，服务继续在后台运行"; exit 0 }
Write-Host "`n日志：runtime\logs\app.log（Ctrl+C 只退出监视，服务仍在后台）"
try {
  Wait-Process -Id $proc.Id
} catch {
  Write-Host '应用已退出，查看 runtime\logs\app.err.log'
}
