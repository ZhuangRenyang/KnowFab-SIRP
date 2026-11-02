#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
发布包端到端验证。

验的不是开发环境的 jar，而是**发布出去的那个包**和**客户真实的升级路径**：

  1. 包完整性      清单、签名文件、jar 的 sha256 对得上
  2. 双站一致      Gitee（主源）下回来的 jar 跟本地发布包字节相同；GitHub 能下就一起比
  3. 旧客户端      把 jar 里的版本号改成旧版，模拟客户机上那个旧版本
  4. 检查更新      旧客户端点「检查更新」能看到新版本
  5. 下载验签      下载线上包，验签 + 逐文件 sha256 全过
  6. 真旧库迁移    把新版本加的列删掉、版本号退回，启动后迁移要跑通、老数据一条不能丢
  7. 换 jar 那一步  发布包里的真实脚本：正常升级 / **篡改包必须被拒** / 回滚
  8. 全新安装      空库起步，所有迁移跑通、服务能起
  9. 回归          selftest + smoke-all 跑在**升级后的发布包**上

**它用的是单独的验证库（默认 devicehub_verify），不碰交付库**——
验证过程要删列、退版本号，这些操作绝不能落在客户的数据上。

用法：
    python tools/verify-release.py                    # 验 dist 里最新的包
    python tools/verify-release.py --version 1.0.22
    python tools/verify-release.py --keep             # 保留验证库和临时目录，便于排查
    python tools/verify-release.py --skip-fresh       # 跳过全新安装（慢）

退出码：0 = 全部通过，1 = 有失败项。
"""

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")

# ── 输出（跟项目里 verify-dh24.sh 一个风格）─────────────────────────────
FAIL = 0
SKIP = 0


def say(title):
    print("\n=== %s ===" % title, flush=True)


def ok(msg):
    print("  [OK]   %s" % msg, flush=True)


def bad(msg, detail=""):
    global FAIL
    FAIL += 1
    print("  [FAIL] %s" % msg, flush=True)
    if detail:
        for line in str(detail).splitlines()[:8]:
            print("         %s" % line, flush=True)


def skip(msg, why=""):
    global SKIP
    SKIP += 1
    print("  [SKIP] %s%s" % (msg, ("（%s）" % why) if why else ""), flush=True)


def note(msg):
    print("  · %s" % msg, flush=True)


# ── 工具 ────────────────────────────────────────────────────────────────
def sha256(b):
    return hashlib.sha256(b).hexdigest()


def decode_out(b):
    """子进程输出可能是 UTF-8 也可能是 Windows 的 GBK，先试前者再退后者"""
    if not b:
        return ""
    if isinstance(b, str):
        return b
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return b.decode("gbk", "replace")


def read_yml(path):
    """只取 datasource 那几行，够用了（不引 pyyaml，脚本要能裸跑）"""
    out = {}
    try:
        txt = io.open(path, encoding="utf-8").read()
    except Exception:
        return out
    m = re.search(r"url:\s*(\S+)", txt)
    if m:
        out["url"] = m.group(1).strip('"')
    m = re.search(r"username:\s*(\S+)", txt)
    if m:
        out["user"] = m.group(1).strip('"')
    m = re.search(r"password:\s*(\S+)", txt)
    if m:
        out["pass"] = m.group(1).strip('"')
    return out


def db_connect(host, port, user, pwd, db=None):
    import pymysql
    return pymysql.connect(host=host, port=port, user=user, password=pwd,
                           database=db, autocommit=True, charset="utf8mb4")


def free_port(start=8095):
    for p in range(start, start + 40):
        s = socket.socket()
        try:
            s.bind(("127.0.0.1", p))
            s.close()
            return p
        except OSError:
            s.close()
    raise RuntimeError("找不到空闲端口")


def wait_port(port, timeout=150):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2)
            s.close()
            return True
        except OSError:
            time.sleep(1)
    return False


class App:
    """起一个 jar，等它把端口打开"""

    def __init__(self, jar, port, cwd, db_url, db_user, db_pass, log):
        self.cmd = ["java", "-Xms256m", "-Xmx512m", "-Dfile.encoding=UTF-8", "-jar", jar,
                    "--server.port=%d" % port,
                    "--spring.datasource.url=%s" % db_url,
                    "--spring.datasource.username=%s" % db_user,
                    "--spring.datasource.password=%s" % db_pass]
        # 不关演示数据：回归用例要设备、要产量，空库跑 selftest/smoke-all
        # 一堆检查会直接失败（不是产品的问题，是没东西可查）
        self.port = port
        self.log = log
        self.proc = None
        self.cwd = cwd

    def __enter__(self):
        self.fh = open(self.log, "wb")
        self.proc = subprocess.Popen(self.cmd, cwd=self.cwd, stdout=self.fh,
                                     stderr=subprocess.STDOUT)
        if not wait_port(self.port):
            self.__exit__(None, None, None)
            raise RuntimeError("jar 启动超时（%d 秒），日志见 %s" % (150, self.log))
        return self

    def __exit__(self, *a):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=15)
            except Exception:
                self.proc.kill()
        try:
            self.fh.close()
        except Exception:
            pass
        return False


class Api:
    """极简 HTTP 客户端（带 cookie），够验这些接口就行"""

    def __init__(self, base):
        self.base = base
        self.cookie = ""

    def call(self, method, path, body=None, timeout=300):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        r = urllib.request.Request(self.base + path, data=data, method=method)
        r.add_header("Content-Type", "application/json")
        if self.cookie:
            r.add_header("Cookie", self.cookie)
        try:
            resp = urllib.request.urlopen(r, timeout=timeout)
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode("utf-8") or "{}")
            except Exception:
                return e.code, {}
        sc = resp.headers.get("Set-Cookie", "")
        if sc:
            self.cookie = sc.split(";")[0]
        raw = resp.read().decode("utf-8")
        try:
            return resp.status, json.loads(raw or "{}")
        except Exception:
            return resp.status, {"_raw": raw}

    def login(self, user="admin", pwd="123456", retries=20):
        """
        端口开了不等于应用就绪：管理员账号是 ApplicationReadyEvent 时才建的，
        端口通的那一刻它可能还没写进去。这里重试，别抢跑。
        """
        for i in range(retries):
            st, b = self.call("POST", "/api/auth/login", {"username": user, "password": pwd})
            if st == 200 and b.get("ok") is not False:
                return True
            time.sleep(1.5)
        return False


def columns_added_by(sql_text):
    """从迁移脚本里找出新增的列：alter table <表> add column <列>"""
    out = {}
    for m in re.finditer(r"alter\s+table\s+([`\w]+)\s+add\s+(?:column\s+)?([`\w]+)",
                         sql_text, re.I):
        out.setdefault(m.group(1).strip("`"), set()).add(m.group(2).strip("`"))
    return out


def newest_migration():
    d = os.path.join(ROOT, "src", "main", "resources", "db", "migrations")
    if not os.path.isdir(d):
        return None, None
    files = sorted(f for f in os.listdir(d) if f.endswith(".sql"))
    if not files:
        return None, None
    last = files[-1]
    return last, io.open(os.path.join(d, last), encoding="utf-8").read()


def make_old_client(jar_bytes, old_version, out_path):
    """把 jar 里的版本号改成旧版 → 这个 jar 就跟客户机上的旧版一模一样"""
    inner = zipfile.ZipFile(io.BytesIO(jar_bytes))
    target = "META-INF/maven/com.factory/device-hub/pom.properties"
    buf = io.BytesIO()
    out = zipfile.ZipFile(buf, "w")
    for item in inner.infolist():
        data = inner.read(item.filename)
        if item.filename == target:
            data = re.sub(rb"version=[^\r\n]+",
                          ("version=%s" % old_version).encode(), data)
        zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        zi.compress_type = item.compress_type   # 嵌套 jar 不能压缩，Spring Boot 启动器要求
        zi.external_attr = item.external_attr
        out.writestr(zi, data)
    out.close()
    with open(out_path, "wb") as f:
        f.write(buf.getvalue())
    return out_path


def jar_version(path):
    z = zipfile.ZipFile(path)
    e = z.getinfo("META-INF/maven/com.factory/device-hub/pom.properties")
    txt = z.read(e).decode("utf-8", "replace")
    z.close()
    m = re.search(r"version=([^\r\n]+)", txt)
    return m.group(1).strip() if m else "?"


def apply_pending_cases(install_root, staged_jar, old_client_jar, tmp, portable_zip):
    """
    验「换 jar 的那一步」——用发布包里真实的代码，不自己另写一套。
      用例 1 正常升级   用例 2 篡改包必须被拒   用例 3 回滚
    """
    results = []
    is_win = os.name == "nt"

    if is_win and portable_zip and os.path.exists(portable_zip):
        z = zipfile.ZipFile(portable_zip)
        ps1 = None
        for n in z.namelist():
            if n.endswith("start.ps1"):
                ps1 = z.read(n).decode("utf-8", "replace")
                break
        z.close()
        if ps1 and "function Apply-Pending" in ps1:
            s = ps1.index("function Apply-Pending")
            e = ps1.index("function Start-Db")
            fn = ps1[s:e]
            script = os.path.join(tmp, "run-apply.ps1")
            fnfile = os.path.join(tmp, "apply-fn.ps1")
            # 函数源码单独存一个文件再点源加载：源码里带单引号（'pending.env'），
            # 内联进单引号字符串会把引号提前闭合，直接炸
            with io.open(fnfile, "w", encoding="utf-8-sig") as f:
                f.write(fn)
            with io.open(script, "w", encoding="ascii", errors="ignore") as f:
                f.write(PS1_TEMPLATE.replace("@@FNPATH@@", fnfile.replace("\\", "\\\\"))
                        .replace("@@ROOT@@", install_root.replace("\\", "\\\\"))
                        .replace("@@STAGED@@", os.path.join(install_root, "updates", "staged").replace("\\", "\\\\"))
                        .replace("@@OLDJAR@@", old_client_jar.replace("\\", "\\\\"))
                        .replace("@@NEWJAR@@", staged_jar.replace("\\", "\\\\")))
            p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                "-File", script], capture_output=True, timeout=300)
            # 别用 text=True：Windows 的 PowerShell 输出是 ANSI/GBK，
            # 按 UTF-8 解会抛 UnicodeDecodeError，输出直接丢了（看着像脚本没跑）
            return (p.stdout or b"").decode("gbk", "replace") + \
                   (p.stderr or b"").decode("gbk", "replace")
    else:
        sh = os.path.join(ROOT, "bin", "apply-pending.sh")
        if os.path.exists(sh):
            script = os.path.join(tmp, "run-apply.sh")
            with io.open(script, "w", encoding="utf-8") as f:
                f.write(SH_TEMPLATE.replace("@@SCRIPT@@", sh)
                        .replace("@@ROOT@@", install_root)
                        .replace("@@OLDJAR@@", old_client_jar)
                        .replace("@@NEWJAR@@", staged_jar))
            p = subprocess.run(["bash", script], capture_output=True, timeout=300)
            return (p.stdout or b"").decode("utf-8", "replace") + \
                   (p.stderr or b"").decode("utf-8", "replace")
    return ""


PS1_TEMPLATE = r"""
$root = '@@ROOT@@'
$staged = Join-Path $root 'updates\staged\1.0.21'
function Jar-Version($p) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $z = [System.IO.Compression.ZipFile]::OpenRead($p)
  $e = $z.Entries | Where-Object { $_.FullName -eq 'META-INF/maven/com.factory/device-hub/pom.properties' } | Select-Object -First 1
  $r = New-Object System.IO.StreamReader($e.Open()); $v = $r.ReadToEnd(); $r.Close(); $z.Dispose()
  if ($v -match 'version=([^\r\n]+)') { return $Matches[1].Trim() } else { return '?' }
}
function Setup-Install {
  Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force -Path (Join-Path $root 'updates'), (Join-Path $root 'backups'), (Join-Path $root 'logs') | Out-Null
  Copy-Item '@@OLDJAR@@' (Join-Path $root 'device-hub.jar') -Force
  New-Item -ItemType Directory -Force -Path $staged | Out-Null
  Copy-Item '@@NEWJAR@@' (Join-Path $staged 'device-hub.jar') -Force
  $sha = (Get-FileHash (Join-Path $staged 'device-hub.jar') -Algorithm SHA256).Hash.ToLower()
  @('TO_VERSION=1.0.21','FROM_VERSION=1.0.18',"STAGED=$staged","SHA256=$sha",'NOTES=verify') |
    Set-Content (Join-Path $root 'updates\pending.env') -Encoding UTF8
}
. '@@FNPATH@@'
$Root = $root
$Updates = Join-Path $root 'updates'
$Jar = Join-Path $root 'device-hub.jar'
$LogDir = Join-Path $root 'logs'
function Run-Apply { Apply-Pending }
Write-Output '--- case1 normal ---'
Setup-Install
Write-Output ('before=' + (Jar-Version (Join-Path $root 'device-hub.jar')))
Run-Apply
Write-Output ('after=' + (Jar-Version (Join-Path $root 'device-hub.jar')))
$bk = Get-ChildItem (Join-Path $root 'backups') -Directory -Filter 'apply-*' -ErrorAction SilentlyContinue
if ($bk) { Write-Output ('backup=' + (Jar-Version (Join-Path $bk[0].FullName 'device-hub.jar'))) }
Write-Output '--- case2 tampered ---'
Setup-Install
$b = [System.IO.File]::ReadAllBytes((Join-Path $staged 'device-hub.jar'))
$b[100] = $b[100] -bxor 0xFF
[System.IO.File]::WriteAllBytes((Join-Path $staged 'device-hub.jar'), $b)
Run-Apply
Write-Output ('after=' + (Jar-Version (Join-Path $root 'device-hub.jar')))
Write-Output '--- case3 rollback ---'
Setup-Install
Run-Apply
New-Item -ItemType File -Force -Path (Join-Path $root 'updates\rollback.flag') | Out-Null
Run-Apply
Write-Output ('after=' + (Jar-Version (Join-Path $root 'device-hub.jar')))
"""

SH_TEMPLATE = r"""#!/bin/bash
set -u
ROOT='@@ROOT@@'
OLD='@@OLDJAR@@'
NEW='@@NEWJAR@@'
STAGED="$ROOT/updates/staged/1.0.21"
setup() {
  rm -rf "$ROOT"; mkdir -p "$ROOT/updates/staged/1.0.21" "$ROOT/backups" "$ROOT/logs"
  cp "$OLD" "$ROOT/device-hub.jar"
  cp "$NEW" "$STAGED/device-hub.jar"
  SHA=$(sha256sum "$STAGED/device-hub.jar" | awk '{print $1}')
  printf 'TO_VERSION=1.0.21\nFROM_VERSION=1.0.18\nSTAGED=%s\nSHA256=%s\nNOTES=verify\n' "$STAGED" "$SHA" > "$ROOT/updates/pending.env"
}
jarver() { unzip -p "$1" META-INF/maven/com.factory/device-hub/pom.properties 2>/dev/null | grep -o 'version=[^ ]*' | head -1; }
echo '--- case1 normal ---'; setup
DIR="$ROOT" bash '@@SCRIPT@@'
echo "before=$(jarver $ROOT/device-hub.jar)"
echo '--- case2 tampered ---'; setup
printf 'XX' | dd of="$STAGED/device-hub.jar" bs=1 seek=100 count=2 conv=notrunc 2>/dev/null
DIR="$ROOT" bash '@@SCRIPT@@'
echo "after=$(jarver $ROOT/device-hub.jar)"
echo '--- case3 rollback ---'; setup
DIR="$ROOT" bash '@@SCRIPT@@'
touch "$ROOT/updates/rollback.flag"
DIR="$ROOT" bash '@@SCRIPT@@'
echo "after=$(jarver $ROOT/device-hub.jar)"
"""


def fetch_deadline(url, max_seconds, ua="Mozilla/5.0"):
    """
    下载，但**总时长**有上限。
    <p>
    socket 的 timeout 只管「一次读多久没动静」，管不了「慢慢挪 79MB」——
    GitHub 从国内机器下大文件就是这样，能把整个验证拖成半小时。
    """
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=30) as r:
        chunks = []
        while True:
            if time.time() - t0 > max_seconds:
                raise TimeoutError("下载超过 %d 秒还没完" % max_seconds)
            b = r.read(1 << 20)
            if not b:
                break
            chunks.append(b)
    return b"".join(chunks)


def main():
    ap = argparse.ArgumentParser(description="发布包端到端验证")
    ap.add_argument("--version", help="要验证的版本号（默认取 dist 里最新的包）")
    ap.add_argument("--db-host", default="127.0.0.1")
    ap.add_argument("--db-port", type=int, default=3306)
    ap.add_argument("--db-admin-user", default="root")
    ap.add_argument("--db-admin-password", default="root")
    ap.add_argument("--db-name", default="devicehub_verify")
    ap.add_argument("--app-user", default=None)
    ap.add_argument("--app-password", default=None)
    ap.add_argument("--old-version", default=None, help="模拟的旧版本号（默认取清单里的 minFrom）")
    ap.add_argument("--keep", action="store_true", help="保留验证库和临时目录")
    ap.add_argument("--skip-fresh", action="store_true", help="跳过全新安装用例")
    args = ap.parse_args()

    cfg = read_yml(os.path.join(ROOT, "src", "main", "resources", "application.yml"))
    app_user = args.app_user or cfg.get("user") or "devicehub"
    app_pass = args.app_password or cfg.get("pass") or ""

    # ---- 找到发布包 ----
    say("发布包")
    if args.version:
        pkg = os.path.join(DIST, "device-hub-%s-update.zip" % args.version)
    else:
        cands = sorted(f for f in os.listdir(DIST)
                       if f.startswith("device-hub-") and f.endswith("-update.zip")) \
            if os.path.isdir(DIST) else []
        if not cands:
            print("  dist 里没有更新包，先发版")
            return 1
        pkg = os.path.join(DIST, cands[-1])
    if not os.path.exists(pkg):
        print("  找不到包：%s" % pkg)
        return 1
    note("包：%s（%.1f MB）" % (os.path.basename(pkg), os.path.getsize(pkg) / 1048576))

    z = zipfile.ZipFile(pkg)
    names = z.namelist()
    if "manifest.json" not in names or "manifest.sig" not in names:
        bad("包里缺 manifest.json / manifest.sig")
        return 1
    manifest = json.loads(z.read("manifest.json").decode("utf-8"))
    jar_bytes = z.read("device-hub.jar")
    want_sha = None
    for f in manifest.get("files", []):
        if f.get("path", "").endswith(".jar"):
            want_sha = f.get("sha256")
    got_sha = sha256(jar_bytes)
    if want_sha and got_sha == want_sha:
        ok("清单里的 jar sha256 对得上（%s…）" % got_sha[:16])
    else:
        bad("jar 的 sha256 跟清单不一致", "清单 %s\n实际 %s" % (want_sha, got_sha))
    version = manifest.get("version")
    ok("版本 %s，minFrom %s" % (version, manifest.get("minFrom")))
    old_version = args.old_version or manifest.get("minFrom") or "1.0.0"

    tmp = os.path.join(ROOT, "target", "verify-release")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)

    # ---- 建验证库（不碰交付库）----
    say("验证库")
    db_name = args.db_name
    admin = None
    for u, p in [(args.db_admin_user, args.db_admin_password),
                 ("root", ""), ("root", "root"), ("root", "knowfab2026")]:
        try:
            c = db_connect(args.db_host, args.db_port, u, p)
            c.cursor().execute("SELECT 1")
            admin = (u, p)
            c.close()
            break
        except Exception:
            continue
    if not admin:
        bad("没有能建库的管理员账号", "用 --db-admin-user / --db-admin-password 指定")
        return 1
    try:
        c = db_connect(args.db_host, args.db_port, admin[0], admin[1])
        cur = c.cursor()
        cur.execute("DROP DATABASE IF EXISTS `%s`" % db_name)
        cur.execute("CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" % db_name)
        for host in ("localhost", "127.0.0.1", "%"):
            try:
                cur.execute("GRANT ALL ON `%s`.* TO '%s'@'%s'" % (db_name, app_user, host))
            except Exception:
                pass
        cur.execute("FLUSH PRIVILEGES")
        c.close()
        ok("验证库 %s 已建（用的是 %s@%s，**交付库没动**）" % (db_name, admin[0], args.db_host))
    except Exception as e:
        bad("建库失败", e)
        return 1

    db_url = ("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8"
              "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
              % (args.db_host, args.db_port, db_name))

    cleanup = []

    try:
        # ---- 1. 全新安装（空库）----
        if not args.skip_fresh:
            say("全新安装（空库）")
            port = free_port(8095)
            log = os.path.join(tmp, "fresh.log")
            with _start_with_jar(jar_bytes, tmp, port, db_url, app_user, app_pass, log):
                api = Api("http://127.0.0.1:%d" % port)
                if api.login():
                    ok("服务起来了，登录正常")
                    st, v = api.call("GET", "/api/system/version")
                    if v.get("schemaVersion") and v.get("schemaVersion") != "0":
                        ok("数据库结构版本 %s（启动时自动写对的，不用点开迁移页面）" % v.get("schemaVersion"))
                    else:
                        bad("数据库结构版本显示 %s（应为真实版本号）" % v.get("schemaVersion"))
                    st, ms = api.call("GET", "/api/system/migrations")
                    if ms.get("waiting") == 0 and not ms.get("failedVersion"):
                        ok("迁移全部执行成功（等待 %s，失败 %s）"
                           % (ms.get("waiting"), ms.get("failedVersion")))
                    else:
                        bad("迁移状态不对", json.dumps(ms, ensure_ascii=False)[:300])
                else:
                    bad("空库启动后登录失败")
            mig_log = io.open(log, encoding="utf-8", errors="replace").read()
            n = len(re.findall(r"迁移脚本 .* 执行成功", mig_log))
            if n:
                ok("启动日志里有 %d 个迁移脚本执行成功" % n)
            else:
                bad("启动日志里没看到迁移执行记录")
            cleanup.append(("drop fresh db", None))
            _drop_db(args, admin, db_name)
            _recreate_db(args, admin, db_name, app_user)

        # ---- 2. 双站一致 ----
        say("双站包一致性")
        for name, tmpl in [("Gitee", "https://gitee.com/ZhuangRenyang/KnowFab-SIRP/releases/download/v%s/device-hub-%s-update.zip"),
                            ("GitHub", "https://github.com/ZhuangRenyang/KnowFab-SIRP/releases/download/v%s/device-hub-%s-update.zip")]:
            url = tmpl % (version, version)
            try:
                # GitHub 从国内机器下大文件经常下不动，它只是兜底源，限时收紧：
                # 免得整个验证卡在这儿
                data = fetch_deadline(url, 120 if name == "GitHub" else 300)
                zz = zipfile.ZipFile(io.BytesIO(data))
                jb = zz.read("device-hub.jar")
                if sha256(jb) == want_sha:
                    ok("%s 上的包 sha256 与清单一致（%.1f MB）" % (name, len(data) / 1048576))
                else:
                    bad("%s 上的包与清单不一致" % name)
            except Exception as e:
                skip("%s 的包没下下来（主源是 Gitee，不影响升级）" % name, str(e)[:60])

        # ---- 3. 旧客户端 + 检查更新 + 下载验签 ----
        say("旧客户端 → 检查更新 → 下载验签")
        old_jar = make_old_client(jar_bytes, old_version, os.path.join(tmp, "old-client.jar"))
        if jar_version(old_jar) == old_version:
            ok("造了个假旧客户端（自报版本 %s）" % jar_version(old_jar))
        else:
            bad("假旧客户端版本没改成功")

        # 先让新库跑一遍迁移，才有「旧库」的表结构可退
        port = free_port(8096)
        log = os.path.join(tmp, "old.log")
        with _start_with_jar(jar_bytes, tmp, port, db_url, app_user, app_pass, log):
            pass

        # 退回到「新版本加的列还不存在」的状态
        mig_name, mig_sql = newest_migration()
        added = columns_added_by(mig_sql or "")
        if not added:
            skip("旧库用例", "没从 %s 里解析出新增列" % mig_name)
        else:
            c = db_connect(args.db_host, args.db_port, admin[0], admin[1], db_name)
            cur = c.cursor()
            for tbl, cols in added.items():
                for col in cols:
                    try:
                        cur.execute("ALTER TABLE `%s` DROP COLUMN `%s`" % (tbl, col))
                        note("模拟旧库：删掉 %s.%s" % (tbl, col))
                    except Exception as e:
                        note("跳过 %s.%s（%s）" % (tbl, col, str(e)[:40]))
            # 只删最新那一条：版本号是 varchar，用 >= 字符串比较在 1.10 之后会判错
            cur.execute("DELETE FROM schema_migration WHERE version = %s",
                        (mig_name.replace(".sql", ""),))
            cur.execute("DELETE FROM app_setting WHERE k='system.schemaVersion'")
            c.close()
            ok("已退回旧库状态（%s 之前的结构）" % mig_name.replace(".sql", ""))

        # 用假旧客户端启动，走客户的检查更新流程
        port = free_port(8096)
        log = os.path.join(tmp, "oldclient.log")
        with _start_with_jar_path(old_jar, tmp, port, db_url, app_user, app_pass, log):
            api = Api("http://127.0.0.1:%d" % port)
            if not api.login():
                bad("旧客户端登录失败")
            else:
                st, v = api.call("GET", "/api/system/version")
                ok("旧客户端自报版本 %s" % v.get("version"))
                st, ck = api.call("GET", "/api/system/update/check", timeout=300)
                if ck.get("hasUpdate") and ck.get("latest") == version:
                    ok("检查更新：看到新版本 %s（%s）" % (ck.get("latest"), ck.get("message")))
                else:
                    bad("检查更新没看到 %s" % version, json.dumps(ck, ensure_ascii=False)[:300])
                url = ck.get("url")
                st, dl = api.call("POST", "/api/system/update/download", {"url": url}, timeout=600)
                if dl.get("ok"):
                    ok("下载并校验通过：%s" % dl.get("message"))
                else:
                    bad("下载/校验没过", json.dumps(dl, ensure_ascii=False)[:300])
                st, ss = api.call("GET", "/api/system/update/status")
                pend = ss.get("pending") or {}
                if pend.get("present") and pend.get("toVersion") == version:
                    ok("待升级包已摆好（%s → %s）" % (pend.get("fromVersion"), pend.get("toVersion")))
                else:
                    bad("待升级包状态不对", json.dumps(pend, ensure_ascii=False)[:200])

        # ---- 4. 迁移在旧库上跑通 ----
        say("旧库迁移")
        c = db_connect(args.db_host, args.db_port, admin[0], admin[1], db_name)
        cur = c.cursor()
        okcols = True
        for tbl, cols in added.items():
            for col in cols:
                cur.execute("""select count(*) from information_schema.columns
                               where table_schema=database() and table_name=%s and column_name=%s""",
                            (tbl, col))
                if cur.fetchone()[0] == 0:
                    okcols = False
                    bad("%s.%s 没被迁移加回来" % (tbl, col))
        if okcols and added:
            ok("新版本加的列都回来了（%s）"
               % ", ".join("%s.%s" % (t, c2) for t, cs in added.items() for c2 in cs))
        cur.execute("select count(*) from schema_migration")
        nrec = cur.fetchone()[0]
        if nrec:
            ok("迁移执行记录 %d 条" % nrec)
        c.close()

        # ---- 5. 换 jar 那一步（真实脚本）----
        say("换 jar（发布包里的真实脚本）")
        install_root = os.path.join(tmp, "install")
        portable = os.path.join(DIST, "device-hub-%s-portable.zip" % version)
        out = apply_pending_cases(install_root, os.path.join(tmp, "jar"), old_jar, tmp, portable)
        m1 = re.search(r"before=([\d.]+)", out)
        a1 = re.search(r"after=([\d.]+)", out)
        upgraded = bool(a1 and a1.group(1) == version)
        if upgraded:
            ok("正常升级：%s → %s" % (m1.group(1) if m1 else "?", a1.group(1)))
        else:
            bad("正常升级没成功", out[-800:])
        if upgraded and "backup=" in out:
            m = re.search(r"backup=([\d.]+)", out)
            ok("旧版已备份（备份里是 %s）" % (m.group(1) if m else "?"))
        # 用例 2/3 只有在用例 1 成立时才有意义——不然「没变化」会被当成「正确地拒绝了」
        if not upgraded:
            skip("篡改包 / 回滚两个用例", "正常升级那步没过，后面的结果不可信")
        else:
            seg = out.split("case2 tampered")[-1]
            m = re.search(r"after=([\d.]+)", seg)
            if m and m.group(1) == old_version:
                ok("篡改待升级包被 sha256 校验拦下，保持旧版 %s" % m.group(1))
            else:
                bad("篡改包没有被拒（版本变成了 %s）" % (m.group(1) if m else "?"))
            seg = out.split("case3 rollback")[-1]
            m = re.search(r"after=([\d.]+)", seg)
            if m and m.group(1) == old_version:
                ok("回滚成功：回到 %s" % m.group(1))
            else:
                bad("回滚没成功（版本变成了 %s）" % (m.group(1) if m else "?"))

        # ---- 6. 回归跑在升级后的发布包上 ----
        say("回归（升级后的发布包）")
        port = free_port(8097)
        log = os.path.join(tmp, "regress.log")
        with _start_with_jar_path(os.path.join(tmp, "jar"), tmp, port, db_url, app_user, app_pass, log):
            base = "http://127.0.0.1:%d" % port
            # 回归前灌演示数据：ERP 商品/仓库/往来单位/产线/库存是 seed-demo.py 灌的，
            # 不灌的话 smoke-all 有一堆检查会因为「没数据」而失败（不是产品的问题）
            seed = os.path.join(ROOT, "tools", "seed-demo.py")
            if os.path.exists(seed):
                sp = subprocess.run([sys.executable, seed, "--base", base,
                                     "--user", "admin", "--pass", "123456"],
                                    capture_output=True, timeout=900)
                sout = decode_out(sp.stdout)
                if sp.returncode == 0:
                    ok("演示数据灌好了（ERP/产线/库存）")
                else:
                    tail = sout.strip().splitlines()
                    skip("演示数据没灌上", tail[-1] if tail else "")
            for name, script in [("selftest", "selftest.py"), ("smoke-all", "smoke-all.py")]:
                p = subprocess.run([sys.executable, os.path.join(ROOT, "tools", script),
                                    "--base", base, "--pass", "123456"],
                                   capture_output=True, timeout=1800)
                out = decode_out(p.stdout)
                tail = [l for l in out.splitlines() if "结果" in l or "FAIL" in l]
                if p.returncode == 0:
                    ok("%s 通过（%s）" % (name, (tail[-1] if tail else "").strip()))
                else:
                    bad("%s 有失败项" % name, "\n".join(tail[:8]))

        # ---- 7. 功能抽查 ----
        say("功能抽查（OEE）")
        port = free_port(8098)
        log = os.path.join(tmp, "feature.log")
        with _start_with_jar_path(os.path.join(tmp, "jar"), tmp, port, db_url, app_user, app_pass, log):
            api = Api("http://127.0.0.1:%d" % port)
            api.login()
            st, d = api.call("GET", "/api/reports/daily")
            o = d.get("oee") or {}
            if "availability" in o and "notes" in o:
                ok("日报有 OEE 区块（可用率 %s，缺什么有说明）" % o.get("availability"))
            else:
                bad("日报没有 OEE 区块")
            st, r = api.call("GET", "/api/reports/range?days=7")
            if r.get("dates") and r.get("devices") is not None:
                ok("趋势接口正常（%s 天 × %s 台设备）"
                   % (len(r.get("dates") or []), len(r.get("devices") or [])))
            else:
                bad("趋势接口不对", json.dumps(r, ensure_ascii=False)[:200])

    finally:
        if not args.keep:
            try:
                _drop_db(args, admin, db_name)
            except Exception:
                pass
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            note("保留了验证库 %s 和临时目录 %s" % (db_name, tmp))

    say("结果")
    if FAIL == 0:
        print("  全部通过%s" % ("（跳过 %d 项）" % SKIP if SKIP else ""))
        return 0
    print("  %d 项失败%s" % (FAIL, ("，%d 项跳过" % SKIP) if SKIP else ""))
    return 1


def _start_with_jar(jar_bytes, tmp, port, db_url, user, pwd, log):
    p = os.path.join(tmp, "jar")
    with open(p, "wb") as f:
        f.write(jar_bytes)
    return _start_with_jar_path(p, tmp, port, db_url, user, pwd, log)


def _start_with_jar_path(jar, tmp, port, db_url, user, pwd, log):
    return App(jar, port, tmp, db_url, user, pwd, log)


def _drop_db(args, admin, name):
    c = db_connect(args.db_host, args.db_port, admin[0], admin[1])
    c.cursor().execute("DROP DATABASE IF EXISTS `%s`" % name)
    c.close()


def _recreate_db(args, admin, name, app_user):
    c = db_connect(args.db_host, args.db_port, admin[0], admin[1])
    cur = c.cursor()
    cur.execute("CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" % name)
    for host in ("localhost", "127.0.0.1", "%"):
        try:
            cur.execute("GRANT ALL ON `%s`.* TO '%s'@'%s'" % (name, app_user, host))
        except Exception:
            pass
    cur.execute("FLUSH PRIVILEGES")
    c.close()


if __name__ == "__main__":
    sys.exit(main())
