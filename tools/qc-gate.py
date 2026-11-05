# -*- coding: utf-8 -*-
"""AC-11 门禁验证：在隔离库上真跑 selftest 的 Q 组与 smoke-all 的质检段。

背景：质检模块这一轮新做的，但一直没进门禁（selftest / smoke-all 都没碰过它）。
本脚本：
  1. 建隔离库 → 起服务（后台）→ 播种演示数据
  2. 真跑 tools/selftest.py（含新增 Q 组）与 tools/smoke-all.py（含新增 sec_qc）
  3. 打印两者的失败项；自动清理

不碰交付库 devicehub —— 只用 devicehub_verify。
"""
import argparse
import json
import os
import subprocess
import glob
import sys
import time
import urllib.error
import urllib.request
import http.cookiejar

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB = "devicehub_verify"
JAR = os.path.join(ROOT, "target", "device-hub.jar")
JAVA = "java"
MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 3306
MYSQL_USER = "admin"
MYSQL_PASS = "123456"
ADMIN_PASS = "123456"


def mysql(sql, db=None, fetch=False):
    import pymysql
    conn = pymysql.connect(host=MYSQL_HOST, port=MYSQL_PORT,
                            user=MYSQL_USER, password=MYSQL_PASS, database=db)
    cur = conn.cursor()
    cur.execute(sql)
    rows = cur.fetchall() if fetch else None
    conn.commit()
    cur.close()
    conn.close()
    return rows


def wait_ready(port, timeout=180):
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    cj = http.cookiejar.CookieJar()
    op.add_handler(urllib.request.HTTPCookieProcessor(cj))
    for _ in range(timeout):
        try:
            data = json.dumps({"username": "admin", "password": ADMIN_PASS}).encode()
            r = urllib.request.Request("http://127.0.0.1:%d/api/auth/login" % port,
                                       data=data, method="POST",
                                       headers={"Content-Type": "application/json"})
            with op.open(r, timeout=10) as resp:
                if json.loads(resp.read().decode()).get("ok"):
                    return True
        except Exception:
            pass
        time.sleep(1)
    return False


def run(cmd, timeout=900):
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, timeout=timeout)
    raw = (p.stdout or b"") + (p.stderr or b"")
    # ⚠️ 编码：子进程在 Windows 上重定向时可能是 UTF-8（Python 3.7+ 在
    # UTF-8 模式/部分终端下如此），按 GBK 解会满屏乱码。
    # 先试 UTF-8，失败再退 GBK —— 判据用「能否解出中文」，不靠猜。
    out = None
    for enc in ("utf-8", "gbk"):
        try:
            cand = raw.decode(enc)
            if "\ufffd" not in cand:
                out = cand
                break
        except UnicodeDecodeError:
            continue
    if out is None:
        out = raw.decode("gbk", errors="replace")
    return p.returncode, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8180)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    port = args.port

    print("=== ① 建隔离库 %s ===" % DB)
    mysql("drop database if exists %s" % DB)
    mysql("create database %s default charset utf8mb4" % DB)
    print("   库已建")

    print("=== ② 起服务（端口 %d）===" % port)
    url = ("jdbc:mysql://127.0.0.1:3306/%s?useUnicode=true&characterEncoding=utf8"
           "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" % DB)
    env = dict(os.environ)
    env["DEVICEHUB_DB_PASSWORD"] = MYSQL_PASS
    env["NO_PROXY"] = "127.0.0.1,localhost"
    logf = open(os.path.join(ROOT, "target", "qc-gate.log"), "wb")
    proc = subprocess.Popen(
        [JAVA, "-Xms256m", "-Xmx512m", "-jar", JAR,
         "--server.port=%d" % port, "--spring.datasource.url=" + url,
         "--system.admin.password=" + ADMIN_PASS],
        stdout=logf, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
    try:
        if not wait_ready(port):
            print("   服务没起来，看 target/qc-gate.log")
            return 2
        print("   服务就绪")

        print("=== ③ 播种演示数据 ===")
        rc, out = run([sys.executable, os.path.join(ROOT, "tools", "seed-demo.py"),
                       "--base", "http://127.0.0.1:%d" % port, "--pass", ADMIN_PASS])
        print("   seed-demo rc=%d（部分失败不算门禁失败，冒烟会自己判定）" % rc)

        print("=== ④ selftest.py（含新增 Q 组）===")
        rc, out = run([sys.executable, os.path.join(ROOT, "tools", "selftest.py"),
                       "--base", "http://127.0.0.1:%d" % port, "--pass", ADMIN_PASS])
        lines = out.splitlines()
        for l in lines:
            if l.startswith("FAIL ") or "FAIL:" in l or "结果:" in l:
                print("   " + l.strip()[:160])
        q_lines = [l for l in lines if l.startswith("PASS Q") or l.startswith("FAIL Q")]
        print("   Q 组：%d 条通过，%d 条失败"
              % (len([l for l in q_lines if l.startswith("PASS")]),
                 len([l for l in q_lines if l.startswith("FAIL")])))
        selftest_rc = rc

        print("=== ⑤ smoke-all.py（含新增 sec_qc）===")
        rc2, out2 = run([sys.executable, os.path.join(ROOT, "tools", "smoke-all.py"),
                         "--base", "http://127.0.0.1:%d" % port, "--pass", ADMIN_PASS])
        with open(os.path.join(ROOT, "target", "smoke-out.txt"), "w", encoding="utf-8") as f:
            f.write(out2)
        print("   原始输出 %d 字符，已存 target/smoke-out.txt" % len(out2))
        # smoke-all 的报告用 ✗ 标记失败（不是 "FAIL" 字样），末尾还有失败项清单
        for l in out2.splitlines():
            if "\u2717" in l or l.strip().startswith("\u2717"):
                print("   " + l.strip()[:170])
        for l in out2.splitlines():
            if l.startswith("\u7ed3\u679c") or "过 /" in l:
                print("   " + l.strip()[:120])
        smoke_rc = rc2

        # ---------- ⑥ 发行包内容自检（单一真相来源：tools/dist-manifest.txt）----------
        # 为什么必须有这一段：2026-11-04 真机实测发现 **install.sh 的复制清单漏了
        # bin/backup.sh 与 bin/restore.sh** → 客户机上备份功能根本不存在，
        # 而 247 单测全绿、打包审计 0 阻断，一个都没拦住。
        # 根因：「包里该有什么」散落在 make-dist / install.sh / 冒烟脚本三处各写一遍。
        # 现在统一到 tools/dist-manifest.txt，这里核对它与源码、与刚构建出的包一致。
        print("=== ⑥ 发行包内容自检 ===")
        manifest_path = os.path.join(ROOT, "tools", "dist-manifest.txt")
        req_files = []
        with open(manifest_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip().replace("\r", "")
                if line and not line.startswith("#"):
                    req_files.append(line)
        def src_of(rel):
            # device-hub.jar 是构建产物（在 target/），其余是源码文件（项目根目录）
            return os.path.join(ROOT, "target", rel) if rel == "device-hub.jar" \
                else os.path.join(ROOT, rel)
        missing_src = [r for r in req_files if not os.path.exists(src_of(r))]
        if missing_src:
            print("   ✗ 发行清单要求的文件在源码里不存在：%s" % "、".join(missing_src))
        else:
            print("   · 发行清单 %d 个文件，源码齐全" % len(req_files))

        # 核对 dist 里最新的包（如果刚构建过）
        zips = sorted(glob.glob(os.path.join(ROOT, "dist", "device-hub-*.zip")))
        zips = [z for z in zips if "update" not in z and "portable" not in z]
        if not zips:
            print("   · dist/ 里没有安装包（还没构建）→ 跳过包内容核对")
        else:
            zp = zips[-1]
            import zipfile
            with zipfile.ZipFile(zp) as z:
                names = set(z.namelist())
                rootdir = os.path.basename(zp)[:-4]   # device-hub-1.0.27.zip → device-hub-1.0.27
                miss = [r for r in req_files
                        if ("%s/%s" % (rootdir, r)) not in names]
                if miss:
                    print("   ✗ %s 里缺：%s" % (os.path.basename(zp), "、".join(miss)))
                else:
                    print("   ✓ %s 内容与发行清单一致（%d 个文件）"
                          % (os.path.basename(zp), len(req_files)))
                    # 权限位（zip 的 external_attr）——客户 unzip 常常不还原，
                    # 所以 install.sh 自己 chmod bin/；这里只保证包里写对了。
                    badmode = []
                    for r in req_files:
                        if not r.endswith(".sh"):
                            continue
                        info = next((i for i in z.infolist()
                                     if i.filename == "%s/%s" % (rootdir, r)), None)
                        if info is None:
                            continue
                        mode = (info.external_attr >> 16) & 0o777
                        if mode and not (mode & 0o111):
                            badmode.append(r)
                    if badmode:
                        print("   ✗ 包内权限位不是 755：%s" % "、".join(badmode))
                    else:
                        print("   ✓ 包内脚本权限位都是 755")
            dist_rc = 0 if not missing_src else 1
        dist_rc = 0 if not missing_src else 1

        print()
        print("======== 门禁结果 ========")
        print("  selftest rc=%d（0=全过）" % selftest_rc)
        print("  smoke-all rc=%d（0=全过）" % smoke_rc)
        print("  发行包内容 rc=%d（0=全过）" % dist_rc)
        return 0 if (selftest_rc == 0 and smoke_rc == 0 and dist_rc == 0) else 1
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=20)
        except Exception:
            proc.kill()
        logf.close()
        if not args.keep:
            mysql("drop database if exists %s" % DB)
            print("   隔离库已清理")


if __name__ == "__main__":
    sys.exit(main())
