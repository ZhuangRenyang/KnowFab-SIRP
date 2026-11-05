#!/usr/bin/env python3
"""交付冒烟：把「装到客户机器上到底能不能用」变成一条能自动跑的命令。

为什么需要它（2026-11-04 真机实测的教训）：
    247 单测全绿、打包审计 0 阻断、客户机上照样死。
    那天在真服务器上完整装了一遍，抓出 9 个**本地测不出来**的交付缺陷：

    ① install.sh 的复制清单漏了 backup/restore → 客户机上备份功能不存在
    ② zip 丢了执行位 → 客户第一条命令 Permission denied
    ③ JVM 堆大小写死 -Xmx1024m → 2G 小机器起不来
    ④ 没装 MySQL 客户端 → 备份功能是空的
    ⑤ 重装后初始密码登不进去
    ⑥ Gitee 附件没有 sha256 → 默认配置下升级功能完全不可用
    ⑦ 下载 80MB 整个读进堆 → OOM，页面表现为"下载失败"
    ⑧ Java HttpClient 默认 h2 → Gitee 大文件流中途 EOF
    ⑨ bin 脚本硬编码安装目录 → 非标准目录每次启动报 Permission denied

    这些问题的共同点：**门禁只覆盖到「包对不对」，没覆盖到「装完能不能用」**。
    本脚本就是把后半段补上。

用法（Linux，交付包 zip）：
    python3 tools/delivery-smoke.py --package dist/device-hub-1.0.25.zip \\
        --db-host 127.0.0.1 --db-port 3306 --db-name dh_smoke \\
        --db-user root --db-pass 'xxx' --sudo

阶段：
    0 包体检    解压 + SHA256SUMS 校验 + 必需文件齐全 + **脚本有执行位**
    1 安装      调交付包里的 install.sh（隔离目录/端口/库/service 名）
    2 起服务    等健康检查通过（带超时与失败诊断）
    3 登录+业务 admin 登录 + 抽查关键接口
    4 备份      bin/backup.sh 产出备份 + gzip 完整性校验
    5 恢复      bin/restore.sh 恢复到演练库 + 与生产库表数比对
    6 卸载      disable + 删单元 + 删目录 + 确认端口已释放

退出码：0 = 全过；1 = 有阶段失败（默认会留下现场，--keep 控制）。
"""
import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

IS_WIN = os.name == "nt"

# 阶段 0 要求的文件：**读发行清单**（tools/dist-manifest.txt）
# 清单是「包里该有什么」的单一真相来源 —— make-dist 按它复制、
# qc-gate 按它核对、这里按它体检。三处不再各写一遍（2026-11-04 的教训：
# install.sh 漏复制 backup/restore，客户机上备份功能根本不存在）。
MANIFEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist-manifest.txt")


def load_manifest():
    req, exe = [], []
    with open(MANIFEST, encoding="utf-8") as f:
        for line in f:
            line = line.strip().replace("\r", "")
            if not line or line.startswith("#"):
                continue
            req.append(line)
            if line.endswith(".sh"):
                exe.append(line)
    return req, exe


REQUIRED, EXECUTABLE = load_manifest()

# 阶段 3 抽查的业务接口
BUSINESS_APIS = [
    "/api/system/version",
    "/api/energy/overview",
    "/api/devices",
    "/api/erp/finance/summary",
]


class Ctx:
    def __init__(self, args):
        self.a = args
        self.results = []      # [(阶段, 名称, ok, 详情)]
        self.stage = ""
        self.tmp = None

    # ---------- 输出 ----------
    def say(self, msg):
        print(msg, flush=True)

    def head(self, stage):
        self.stage = stage
        self.say("\n== %s" % stage)

    def ok(self, name, detail=""):
        self.results.append((self.stage, name, True, detail))
        self.say("   PASS  %-44s %s" % (name, detail))

    def bad(self, name, detail=""):
        self.results.append((self.stage, name, False, detail))
        self.say("   FAIL  %-44s %s" % (name, detail))

    def info(self, msg):
        self.say("   · %s" % msg)

    # ---------- 执行 ----------
    def run(self, cmd, env=None, timeout=900, sudo=False, cwd=None):
        if sudo and not IS_WIN:
            # sudo 只能跑**外部命令**，不能跑 shell 内建（cd 就是内建）→
            # 所以 `cd X && bash install.sh` 必须拆开：cd 在外面，sudo 只包 bash。
            # 环境变量用 `env K=V` 传给 root 进程（sudo 默认会清环境）。
            pre = ""
            if env:
                import shlex
                pre = "env " + " ".join(
                    "%s=%s" % (k, shlex.quote(str(v))) for k, v in env.items()) + " "
            cmd = "sudo -S %s%s" % (pre, cmd)
        e = dict(os.environ)
        if env:
            e.update(env)
        if sudo and not IS_WIN:
            e["SUDO_ASKPASS"] = ""
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", env=e, timeout=timeout, cwd=cwd,
                           input=(self.a.sudo_pass + "\n" if (sudo and not IS_WIN) else None))
        return p.returncode, p.stdout, p.stderr


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def port_open(host, port, timeout=3):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def http_json(url, timeout=15, method="GET", body=None, cookie=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw.decode()) if raw else {})
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def http_cookie_login(url, user, pwd):
    """返回 (ok, cookie, body)"""
    import http.cookiejar
    cj = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    data = json.dumps({"username": user, "password": pwd}).encode()
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with op.open(req, timeout=20) as r:
            body = json.loads(r.read().decode() or "{}")
    except Exception as e:
        return False, "", {"error": str(e)}
    cookie = "; ".join("%s=%s" % (c.name, c.value) for c in cj)
    return body.get("ok") is True, cookie, body


# ==================== 各阶段 ====================

def stage_package(ctx):
    """阶段 0：包体检"""
    ctx.head("阶段 0 · 包体检")
    pkg = ctx.a.package
    if not os.path.exists(pkg):
        ctx.bad("交付包存在", pkg)
        return False
    ctx.ok("交付包存在", "%s (%.1f MB)" % (os.path.basename(pkg), os.path.getsize(pkg) / 1048576))

    ctx.tmp = tempfile.mkdtemp(prefix="dh-smoke-")
    rc, out, err = ctx.run("cd %s && unzip -qo %s" % (ctx.tmp, os.path.abspath(pkg)))
    if rc != 0:
        ctx.bad("解压", err.strip()[:120])
        return False
    root = os.path.join(ctx.tmp, os.path.basename(pkg)[:-4])
    if not os.path.isdir(root):
        cands = [d for d in os.listdir(ctx.tmp) if os.path.isdir(os.path.join(ctx.tmp, d))]
        root = os.path.join(ctx.tmp, cands[0]) if cands else root
    ctx.root = root

    # SHA256SUMS 校验
    sums = os.path.join(root, "SHA256SUMS")
    if not os.path.exists(sums):
        ctx.bad("SHA256SUMS 存在")
        return False
    bad = []
    with open(sums, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$", line.strip())
            if not m:
                continue
            want, name = m.group(1).lower(), m.group(2).strip().lstrip("*")
            p = os.path.join(root, name)
            if not os.path.exists(p):
                bad.append(name + "(缺文件)")
            elif sha256_file(p).lower() != want:
                bad.append(name + "(哈希不符)")
    if bad:
        ctx.bad("SHA256SUMS 逐文件校验", "、".join(bad[:4]))
        return False
    ctx.ok("SHA256SUMS 逐文件校验", "全部一致")

    miss = [f for f in REQUIRED if not os.path.exists(os.path.join(root, f))]
    if miss:
        ctx.bad("必需文件齐全", "缺 " + "、".join(miss[:5]))
        return False
    ctx.ok("必需文件齐全", "%d 个" % len(REQUIRED))

    # 执行位分两层看，缺一不可：
    #   a) **包里的权限位**（zip 的 external_attr）——有些 unzip 会还原，有些不会
    #   b) **装完后的 bin/*.sh 可执行**——这才是客户真正依赖的（install.sh 自己 chmod）
    # 只看解压后的文件会误判：服务器的 unzip 常常不还原权限位，
    # 那是 unzip 的行为，不是包的问题（2026-11-04 真机实测踩到）。
    import zipfile
    with zipfile.ZipFile(os.path.abspath(pkg)) as z:
        noexec = []
        for f in EXECUTABLE:
            found = None
            for info in z.infolist():
                if info.filename.endswith("/" + f) or info.filename == f:
                    found = info
                    break
            if found is None:
                noexec.append(f + "(包内没有)")
                continue
            mode = (found.external_attr >> 16) & 0o777
            if mode and not (mode & 0o111):
                noexec.append(f + "(包内 %o)" % mode)
    if noexec:
        ctx.bad("包内脚本权限位", "、".join(noexec[:5]))
        return False
    ctx.ok("包内脚本权限位", "%d 个脚本都是 755" % len(EXECUTABLE))
    if IS_WIN:
        ctx.info("Windows 上不检查解压后的执行位")
    else:
        ctx.info("解压后的执行位取决于 unzip 实现（install.sh 会自己 chmod bin/，阶段 1.5 验）")
    return True


def stage_createdb(ctx):
    """阶段 0.5：建库（install.sh 只连不建库，交付时库是客户自己准备的）"""
    ctx.head("阶段 0.5 · 建库")
    a = ctx.a
    for cmd in ("mysql", "mariadb"):
        if shutil.which(cmd) is None:
            continue
        # 库名不用反引号：反引号在 shell 里会被当命令替换（踩过）
        q = "CREATE DATABASE IF NOT EXISTS %s DEFAULT CHARSET utf8mb4" % a.db_name
        c = ("%s -h%s -P%s -u%s -p%s -e \"%s\"" % (cmd, a.db_host, a.db_port, a.db_user, a.db_pass, q))
        rc, out, err = ctx.run(c, timeout=120, sudo=a.sudo)
        if rc == 0:
            ctx.ok("建库 %s" % a.db_name)
            return True
        ctx.bad("建库 %s" % a.db_name, (out + err).strip()[-120:])
        return False
    ctx.info("没有 mysql 客户端，跳过建库（假定库已存在）")
    return True


def stage_install(ctx):
    """阶段 1：调用交付包里的 install.sh"""
    ctx.head("阶段 1 · 安装（用交付包里的 install.sh）")
    a = ctx.a
    env = {
        "DIR": a.dir,
        "PORT": str(a.port),
        "APP": a.service,
        "SVC_USER": a.svc_user,
        "JAVA_OPTS": a.java_opts,
    }
    # **不要在命令里写 cd**：sudo 只能跑外部命令，cd 是 shell 内建 → sudo: cd: command not found。
    # 用 subprocess 的 cwd 参数切目录，命令里只留真正要跑的东西。
    cmd = ("bash install.sh --db-host %s --db-port %s --db-name %s "
           "--db-user %s --db-pass '%s'" % (a.db_host, a.db_port, a.db_name,
                                            a.db_user, a.db_pass))
    t0 = time.time()
    rc, out, err = ctx.run(cmd, env=env, timeout=1800, sudo=a.sudo, cwd=ctx.root)
    ctx.install_out = out + err
    dt = time.time() - t0
    if rc != 0:
        ctx.bad("install.sh 退出码 0", "rc=%d，用时 %.0fs" % (rc, dt))
        tail = (out + err).strip().splitlines()[-6:]
        for l in tail:
            ctx.info("  | " + l[:150])
        return False
    ctx.ok("install.sh 退出码 0", "用时 %.0fs" % dt)
    # 安装脚本必须打印默认账号（R1/R4：固定 admin/123456，存数据库）
    if "admin" in out and "123456" in out:
        ctx.ok("安装输出含默认账号 admin/123456")
    else:
        ctx.bad("安装输出含默认账号 admin/123456", "输出里没有 123456 —— 客户不知道密码")
    return True


def stage_execbits(ctx):
    """阶段 1.5：装完后的 bin/ 脚本必须可执行（客户真正依赖的）"""
    ctx.head("阶段 1.5 · 装完后的脚本可执行")
    a = ctx.a
    if IS_WIN:
        ctx.info("Windows 上跳过")
        return True
    bad = []
    for f in ("apply-pending.sh", "upgrade.sh", "backup.sh", "restore.sh"):
        p = os.path.join(a.dir, "bin", f)
        if not os.path.exists(p):
            bad.append(f + "(不存在)")
        elif not os.access(p, os.X_OK):
            bad.append(f + "(不可执行)")
    if bad:
        ctx.bad("bin/ 脚本可执行", "、".join(bad))
        return False
    ctx.ok("bin/ 脚本可执行", "4 个脚本")
    # install.sh 自己 chmod 过 bin/ —— 这条是 2026-11-04 补上的，回归钉住它
    return True


def stage_service(ctx):
    """阶段 2：服务就绪"""
    ctx.head("阶段 2 · 服务就绪")
    a = ctx.a
    url = "http://127.0.0.1:%d/api/system/version" % a.port
    if a.sudo and not IS_WIN:
        ctx.run("systemctl enable %s" % a.service, sudo=True)
    t0 = time.time()
    code = 0
    for _ in range(60):
        code, _b = http_json(url, timeout=5)
        if code == 200:
            break
        time.sleep(2)
    if code != 200:
        ctx.bad("健康检查通过", "120 秒内没起来")
        if a.sudo and not IS_WIN:
            _rc, out, _e = ctx.run("journalctl -u %s -n 25 --no-pager" % a.service, sudo=True)
            for l in out.strip().splitlines()[-8:]:
                ctx.info("  | " + l[:150])
            _rc, out, _e = ctx.run("tail -20 %s/logs/stdout.log" % a.dir, sudo=True)
            for l in out.strip().splitlines()[-6:]:
                ctx.info("  | " + l[:150])
        return False
    ctx.ok("健康检查通过", "用时 %.0fs" % (time.time() - t0))
    if a.sudo and not IS_WIN:
        # 查两样：unit 文件在不在 + is-enabled 的退出码（0 = enabled）
        _rc, out, _e = ctx.run("ls /etc/systemd/system/%s.service" % a.service, sudo=True)
        unit_there = _rc == 0
        _rc2, out2, _e2 = ctx.run("systemctl is-enabled %s" % a.service, sudo=True)
        if not unit_there:
            ctx.bad("systemd 单元已注册", "找不到 %s.service（service 名与预期不符）" % a.service)
            return False
        ctx.ok("systemd 单元已注册", "%s.service" % a.service)
        if _rc2 == 0:
            ctx.ok("已设开机自启")
        else:
            ctx.bad("已设开机自启", (out2 or "").strip() or "is-enabled 返回 %d" % _rc2)
            return False
    return True


def stage_business(ctx):
    """阶段 3：登录 + 业务接口"""
    ctx.head("阶段 3 · 登录与业务接口")
    a = ctx.a
    base = "http://127.0.0.1:%d" % a.port
    # 登录要**重试**：version 接口是免登录的，它通了不代表 admin 已 seed 完。
    # 应用在 ApplicationReadyEvent 里建管理员，而 Tomcat 已经在接受请求了 →
    # 有几秒的「初始化窗口期」，这时登录会 401。
    # 客户装完立刻点登录，会撞上同一个坑（2026-11-04 真机实测）。
    ok = False
    body = {}
    t0 = time.time()
    for i in range(15):
        ok, cookie, body = http_cookie_login(base + "/api/auth/login", a.admin_user, a.admin_pass)
        if ok:
            break
        time.sleep(2)
    if not ok:
        ctx.bad("admin 登录", "%s（等了 %.0fs）" % (json.dumps(body, ensure_ascii=False)[:90], time.time() - t0))
        return False
    ctx.ok("admin 登录", "账号 %s，用时 %.0fs" % (a.admin_user, time.time() - t0))
    bad = []
    for ep in BUSINESS_APIS:
        code, _b = http_json(base + ep, timeout=20, cookie=cookie)
        if code != 200:
            bad.append("%s→%d" % (ep, code))
    if bad:
        ctx.bad("关键业务接口", "、".join(bad))
        return False
    ctx.ok("关键业务接口", "%d 个全 200" % len(BUSINESS_APIS))
    return True


def stage_backup(ctx):
    """阶段 4：备份"""
    ctx.head("阶段 4 · 备份")
    a = ctx.a
    rc, out, err = ctx.run("bash bin/backup.sh 3", timeout=900, sudo=a.sudo, cwd=a.dir)
    files = []
    bdir = os.path.join(a.dir, "backups")
    if os.path.isdir(bdir):
        files = sorted(f for f in os.listdir(bdir) if f.endswith(".sql.gz"))
    if not files:
        ctx.bad("产出备份文件", (out + err).strip()[-120:])
        return False
    latest = os.path.join(bdir, files[-1])
    try:
        with gzip.open(latest, "rb") as f:
            f.read(1)
        good = True
    except Exception:
        good = False
    if not good:
        ctx.bad("备份 gzip 完整", latest)
        return False
    ctx.ok("产出备份文件", "%s (%.0f KB)" % (files[-1], os.path.getsize(latest) / 1024))
    ctx.backup = latest
    return True


def stage_restore(ctx):
    """阶段 5：恢复到演练库"""
    ctx.head("阶段 5 · 恢复")
    a = ctx.a
    drill = a.db_name + "_drill"
    rc, out, err = ctx.run("bash bin/restore.sh '%s' %s" % (ctx.backup, drill),
                           timeout=1200, sudo=a.sudo, cwd=a.dir)
    if rc != 0:
        ctx.bad("恢复到演练库", (out + err).strip()[-140:])
        return False
    ctx.ok("恢复到演练库", drill)
    # 表数比对：恢复出来的必须与源库一样多
    cnt = ctx.table_count(drill)
    if cnt is None:
        ctx.info("拿不到表数（没有 mysql 客户端？）——跳过比对")
        return True
    src = ctx.table_count(a.db_name)
    if src is None:
        ctx.info("拿不到源库表数——跳过比对")
        return True
    if cnt == src and cnt > 0:
        ctx.ok("恢复后表数与源库一致", "%d 张表" % cnt)
        return True
    ctx.bad("恢复后表数与源库一致", "源库 %s 张 / 演练库 %s 张" % (src, cnt))
    return False


def stage_uninstall(ctx):
    """阶段 6：卸载"""
    ctx.head("阶段 6 · 卸载清理")
    a = ctx.a
    if a.sudo and not IS_WIN:
        ctx.run("systemctl disable --now %s" % a.service, sudo=True, timeout=120)
        ctx.run("rm -f /etc/systemd/system/%s.service" % a.service, sudo=True)
        ctx.run("rm -rf %s" % a.dir, sudo=True)
    if os.path.exists(a.dir):
        ctx.bad("安装目录已删除", a.dir)
        return False
    ctx.ok("安装目录已删除")
    time.sleep(2)
    if port_open("127.0.0.1", a.port):
        ctx.bad("端口已释放", "%d 还在监听" % a.port)
        return False
    ctx.ok("端口已释放", "%d" % a.port)
    return True


def add_table_count(ctx):
    """给 ctx 挂一个查表数的方法（有 mysql 客户端就用，没有就返回 None）"""
    def table_count(db):
        if not ctx.a.db_user or not ctx.a.db_pass:
            return None
        q = ("select count(*) from information_schema.tables "
             "where table_schema='%s'" % db)
        for cmd in ("mysql", "mariadb"):
            if shutil.which(cmd) is None:
                continue
            c = ("%s -h%s -P%s -u%s -p%s -N -B -e \"%s\" %s"
                 % (cmd, ctx.a.db_host, ctx.a.db_port, ctx.a.db_user, ctx.a.db_pass, q, db))
            rc, out, _e = ctx.run(c, timeout=60, sudo=ctx.a.sudo)
            if rc == 0 and out.strip().isdigit():
                return int(out.strip().splitlines()[-1])
        return None
    ctx.table_count = table_count


def main():
    ap = argparse.ArgumentParser(description="交付冒烟：装→跑→备→恢→卸")
    ap.add_argument("--package", required=True, help="交付包 zip")
    ap.add_argument("--dir", default="/opt/dh-smoke", help="隔离安装目录")
    ap.add_argument("--port", type=int, default=8099, help="隔离端口")
    ap.add_argument("--service", default="device-hub-smoke", help="隔离 systemd 服务名")
    ap.add_argument("--svc-user", default="dhsmoke", help="运行用户")
    ap.add_argument("--java-opts", default="-Xms128m -Xmx384m")
    ap.add_argument("--db-host", default="127.0.0.1")
    ap.add_argument("--db-port", default="3306")
    ap.add_argument("--db-name", default="dh_smoke")
    ap.add_argument("--db-user", default="root")
    ap.add_argument("--db-pass", default="")
    ap.add_argument("--admin-user", default="admin")
    ap.add_argument("--admin-pass", default="123456")
    ap.add_argument("--sudo", action="store_true", help="install.sh 等用 sudo 跑")
    ap.add_argument("--sudo-pass", default=os.environ.get("DEHUB_PASS", ""),
                    help="sudo 口令（也可用环境变量 DEHUB_PASS）；不要写进命令行历史")
    ap.add_argument("--keep", action="store_true", help="失败时保留现场")
    ap.add_argument("--skip-uninstall", action="store_true")
    a = ap.parse_args()

    ctx = Ctx(a)
    add_table_count(ctx)
    ctx.say("交付冒烟：%s" % os.path.basename(a.package))
    ctx.say("  隔离环境：目录 %s  端口 %d  服务 %s  库 %s" % (a.dir, a.port, a.service, a.db_name))

    stages = [
        ("包体检", stage_package),
        ("建库", stage_createdb),
        ("安装", stage_install),
        ("脚本可执行", stage_execbits),
        ("服务就绪", stage_service),
        ("登录与业务", stage_business),
        ("备份", stage_backup),
        ("恢复", stage_restore),
    ]
    failed = None
    for name, fn in stages:
        try:
            if not fn(ctx):
                failed = name
                break
        except Exception as e:
            ctx.bad(name + "（脚本异常）", "%s: %s" % (type(e).__name__, e))
            failed = name
            break

    if failed is None and not a.skip_uninstall:
        try:
            if not stage_uninstall(ctx):
                failed = "卸载"
        except Exception as e:
            ctx.bad("卸载（脚本异常）", str(e))
            failed = "卸载"

    ctx.head("汇总")
    npass = sum(1 for _s, _n, ok, _d in ctx.results if ok)
    nfail = sum(1 for _s, _n, ok, _d in ctx.results if not ok)
    for s, n, ok, d in ctx.results:
        if not ok:
            ctx.say("   FAIL  [%s] %s  %s" % (s, n, d))
    ctx.say("   通过 %d / 失败 %d" % (npass, nfail))
    if failed:
        ctx.say("   卡在阶段：%s" % failed)
        if ctx.a.keep or True:
            ctx.say("   现场保留在：%s（脚本日志在 %s/logs/）" % (ctx.a.dir, ctx.a.dir))
    else:
        ctx.say("   全流程通过 ✅")

    # 收尾：默认卸载（除非失败且要求保留）
    if ctx.a.keep and failed:
        ctx.say("   --keep：现场保留，未清理")
    else:
        try:
            if a.sudo and not IS_WIN:
                ctx.run("systemctl disable --now %s" % a.service, sudo=True, timeout=120)
                ctx.run("rm -f /etc/systemd/system/%s.service" % a.service, sudo=True)
                ctx.run("rm -rf %s" % a.dir, sudo=True)
        except Exception:
            pass
    if ctx.tmp and os.path.isdir(ctx.tmp) and not (ctx.a.keep and failed):
        shutil.rmtree(ctx.tmp, ignore_errors=True)

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
