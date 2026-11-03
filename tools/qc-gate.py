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
MYSQL_USER = "devicehub"
MYSQL_PASS = "DeviceHub2026"
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

        print()
        print("======== 门禁结果 ========")
        print("  selftest rc=%d（0=全过）" % selftest_rc)
        print("  smoke-all rc=%d（0=全过）" % smoke_rc)
        return 0 if (selftest_rc == 0 and smoke_rc == 0) else 1
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
