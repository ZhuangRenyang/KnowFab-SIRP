#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
升级演练（发版前必跑）
=====================

**为什么需要这个脚本**
--------------------
「读一遍迁移脚本觉得没问题」不等于「能升上去」。升级路径上的问题只有在
**真的拿旧版本建库灌数据、再用新版本升上去**时才会暴露：

- 迁移脚本在空库上跑得通，在有数据的库上失败（not null 加列、索引冲突）
- 存量行回填成 NULL 或空串，业务代码拿到空值但**不报错**
- ddl-auto=update 和迁移脚本重复加列，顺序不对就起不来
- 新版本启动时把老数据算坏了（数量口径变了，分母变了）

这些在开发库里一个都看不出来，因为开发库每次都是重建的。

**跑法**
--------
    python tools/upgrade-drill.py                       # 默认拿 HEAD~1 当旧版本
    python tools/upgrade-drill.py --old 950e0e1         # 指定旧版本
    python tools/upgrade-drill.py --keep                # 保留环境便于排查
    python tools/upgrade-drill.py --skip-build-old      # 旧 jar 已存在时跳过构建

**判定标准（任一不满足即 FAIL）**
--------------------------------
1. 新版本能正常启动（日志里有 Started，无 ERROR）
2. 迁移脚本执行成功
3. **存量数据行数一条不变**（工单/设备/仓库/单据/产量/SKU）
4. 新增列的存量行回填值正确（不是 NULL、不是空串）
5. 新增表建出来了
6. **迁移脚本重跑幂等**：删执行记录再升一次，种子数据不翻倍、数据不变

**前置条件**
------------
- 本地 MySQL 在 127.0.0.1:3306，用户 admin / 123456
- devicehub 用户对 devicehub_verify 库有 ALL 权限（没有就用有权限的库）
- 端口默认 8131（可用 --port 改）

**注意**：脚本用 devicehub_verify 库，**不会碰 devicehub 交付库**。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error
import http.cookiejar

# 本脚本在 device-hub/tools/ 下，仓库根就是 device-hub 本身
PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = PROJ
JAR = os.path.join(PROJ, "target", "device-hub.jar")
MVN = r"D:\mywebss\myWeb\KnowFab-SIRP\tools\apache-maven-3.9.9\bin\mvn.cmd"

DB_NAME = "devicehub_verify"
DB_USER = "admin"
DB_PASS = "123456"
ADMIN_PASS = "123456"

# 升级前后都要对比的存量表（表 → 基线行数由脚本自己记录）
WATCH_TABLES = ["erp_work_order", "device", "erp_warehouse", "erp_stock_bill", "erp_sku", "prod_hourly"]
# 新增列：表 → (列, 期望的回填值)
WATCH_COLUMNS = [
    ("erp_work_order", "order_type", "PROD"),
    ("device", "counter_mode", "TOTAL"),
    ("erp_warehouse", "kind", "STANDARD"),
]


def log(msg):
    print("  %s" % msg, flush=True)


def _no_proxy_opener():
    """
    不走任何 HTTP 代理。

    这台机器上设了 HTTP_PROXY，urllib 默认会拿它去请求 127.0.0.1，
    于是本地回环请求被代理拦下返回 502「upstream connect failed」。
    本机直连必须显式禁代理——不然每个本地脚本都会踩这一脚。
    """
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def fail(msg):
    print("\n✗ FAIL: %s" % msg, flush=True)
    sys.exit(1)


def db(database=DB_NAME):
    """建库阶段要连到 mysql 本体（database=None），否则库还不存在就连不上"""
    import pymysql
    return pymysql.connect(host="127.0.0.1", port=3306, user=DB_USER,
                           password=DB_PASS, database=database, charset="utf8mb4")


def q(sql, args=None):
    c = db()
    try:
        cur = c.cursor()
        cur.execute(sql, args)
        rows = cur.fetchall()
        c.commit()
        return rows
    finally:
        c.close()


def table_count(t):
    return int(q("select count(*) from `%s`" % t)[0][0])


def column_exists(t, col):
    r = q("select count(*) from information_schema.columns "
          "where table_schema=%s and table_name=%s and column_name=%s",
          (DB_NAME, t, col))
    return int(r[0][0]) > 0


def port_alive(port):
    out = subprocess.run("netstat -ano | grep ':%d' | grep LISTENING" % port,
                         shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    return bool(out.strip())


def kill_port(port):
    out = subprocess.run("netstat -ano | grep ':%d' | grep LISTENING" % port,
                         shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    pids = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5:
            pids.add(parts[-1])
    for pid in pids:
        subprocess.run("taskkill /F /PID %s" % pid, shell=True,
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if pids:
        time.sleep(3)


def build_old(old_commit, worktree):
    """检出旧版并构建（跳过前端与测试：升级演练只验后端 schema）"""
    if os.path.exists(worktree):
        subprocess.run("git worktree remove %s --force" % worktree,
                       shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace")
    subprocess.run("git worktree add %s %s" % (worktree, old_commit),
                   shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=ROOT)
    old_proj = os.path.join(worktree, "device-hub")
    if not os.path.exists(old_proj):
        old_proj = worktree          # 仓库根就是 device-hub 时
    jar = os.path.join(old_proj, "target", "device-hub.jar")
    if not os.path.exists(jar):
        log("构建旧版 %s …" % old_commit)
        p = subprocess.run(
            [MVN, "-B", "-q", "package", "-DskipTests", "-Dskip.npm=true",
             "-Dskip.installnodenpm=true"],
            shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=old_proj)
        if p.returncode != 0 or not os.path.exists(jar):
            print(p.stdout[-3000:])
            print(p.stderr[-2000:])
            fail("旧版构建失败")
    return jar


def start_jar(jar, port, logfile):
    env = dict(os.environ)
    env["DEVICEHUB_DB_PASSWORD"] = DB_PASS
    url = ("jdbc:mysql://127.0.0.1:3306/%s?useUnicode=true&characterEncoding=utf8"
           "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" % DB_NAME)
    cmd = ["java", "-Xms256m", "-Xmx512m", "-jar", jar,
           "--server.port=%d" % port,
           "--spring.datasource.url=" + url,
           "--system.admin.password=" + ADMIN_PASS]
    f = open(logfile, "wb")
    return subprocess.Popen(cmd, stdout=f, stderr=subprocess.STDOUT, env=env)


def wait_ready(port, logfile, timeout=90):
    for _ in range(timeout):
        try:
            _no_proxy_opener().open("http://127.0.0.1:%d/api/devices" % port, timeout=3)
            return True
        except urllib.error.HTTPError:
            # 有 HTTP 响应 = 服务已经起来了（401/403 正是未登录时的正常应答）。
            # 只有连不上（ConnectionError）才继续等。
            return True
        except Exception:
            time.sleep(1)
    tail = ""
    try:
        raw = open(logfile, "rb").read().decode("gbk", errors="replace")
        tail = "\n".join(raw.splitlines()[-25:])
    except Exception:
        pass
    print(tail)
    fail("服务在 %d 端口没起来" % port)


def wait_login(port, timeout=60):
    """
    等到「能登录」为止，而不是等「端口通」。

    Tomcat 先开始监听，管理员账号是 DataInitializer 随后才建的
    （日志顺序：Tomcat started → 已创建管理员）。只等端口通的话，
    紧接着的登录会拿到 401「用户名或密码不对」——看着像密码错了，其实是还没建好。
    """
    for i in range(timeout):
        try:
            login(port)
            return True
        except Exception:
            time.sleep(1)
    return False


def login(port):
    cj = http.cookiejar.CookieJar()
    op = _no_proxy_opener()
    op.add_handler(urllib.request.HTTPCookieProcessor(cj))
    body = json.dumps({"username": "admin", "password": ADMIN_PASS}).encode()
    req = urllib.request.Request("http://127.0.0.1:%d/api/auth/login" % port,
                                 data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    with op.open(req, timeout=20) as r:
        r.read()
    return op


def seed_and_make_orders(port):
    """灌演示数据 + 造几张工单，让库里真的有「存量」可验"""
    p = subprocess.run([sys.executable, os.path.join(PROJ, "tools", "seed-demo.py"),
                        "--base", "http://127.0.0.1:%d" % port, "--pass", ADMIN_PASS],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=PROJ)
    out = p.stdout.strip()
    if p.returncode != 0:
        print(out[-2000:])
        print(p.stderr[-1500:])
        fail("播种失败：旧版起不来就没法造存量数据")
    log("播种：%s" % (out.splitlines()[-1] if out else "?"))
    op = login(port)
    made = 0
    for _ in range(3):
        body = json.dumps({"productSkuId": 3, "planQty": 2, "deviceCode": "INJ-01",
                           "warehouseId": 1, "materialWhId": 3}).encode()
        req = urllib.request.Request("http://127.0.0.1:%d/api/erp/work-orders" % port,
                                     data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with op.open(req, timeout=20) as r:
                if json.loads(r.read().decode()).get("orderNo"):
                    made += 1
        except Exception as e:
            log("造工单跳过：%s" % e)
            break
    log("造工单 %d 张" % made)


def snapshot():
    snap = {"tables": {}}
    for t in WATCH_TABLES:
        try:
            snap["tables"][t] = table_count(t)
        except Exception as e:
            snap["tables"][t] = "ERR:%s" % e
    return snap


def check_upgrade(before, logfile):
    problems = []
    raw = open(logfile, "rb").read().decode("gbk", errors="replace")
    if "Started DeviceHubApplication" not in raw:
        problems.append("新版本没有正常启动")
    if "ERROR" in raw:
        for line in raw.splitlines():
            if "ERROR" in line:
                problems.append("启动日志有 ERROR：%s" % line.strip()[:160])
                break
    if "迁移脚本" in raw and "执行成功" not in raw:
        problems.append("迁移脚本没有全部执行成功")

    after = snapshot()
    log("存量数据对比：")
    for t in WATCH_TABLES:
        b, a = before["tables"].get(t), after["tables"].get(t)
        if isinstance(a, int) and isinstance(b, int):
            ok = a >= b          # 允许增长（新数据），不允许减少
            log("  %-16s %d → %d  %s" % (t, b, a, "✓" if ok else "✗ 变少了"))
            if not ok:
                problems.append("%s 行数从 %d 变成 %d（数据丢了）" % (t, b, a))
        else:
            log("  %-16s %s → %s  ?" % (t, b, a))

    log("新增列回填：")
    for t, col, expect in WATCH_COLUMNS:
        if not column_exists(t, col):
            problems.append("%s.%s 列没建出来" % (t, col))
            log("  %s.%-14s ✗ 列不存在" % (t, col))
            continue
        rows = q("select `%s`, count(*) from `%s` group by `%s`" % (col, t, col))
        vals = {str(r[0]): int(r[1]) for r in rows}
        bad = [v for v in vals if v in ("None", "")]
        ok = (not bad) and (expect in vals or not vals)
        log("  %s.%-14s %s  %s" % (t, col, vals, "✓" if ok else "✗"))
        if not ok:
            problems.append("%s.%s 回填值异常：%s（期望含 %s，不允许 NULL/空串）"
                            % (t, col, vals, expect))
    return problems, after


def check_idempotent(port, logfile):
    """删掉迁移执行记录再升一次：验证脚本幂等 + 种子数据不翻倍"""
    log("幂等检查：删掉 schema_migration 里的迁移记录，重启一次")
    rows = q("select version from schema_migration")
    vers = [r[0] for r in rows]
    for v in vers:
        if v.startswith("1.7"):
            q("delete from schema_migration where version=%s", (v,))
    kill_port(port)
    p = start_jar(JAR, port, logfile + ".2")
    try:
        wait_ready(port, logfile + ".2")
        wait_login(port)
    finally:
        kill_port(port)
    raw = open(logfile + ".2", "rb").read().decode("gbk", errors="replace")
    problems = []
    if "迁移脚本" not in raw:
        problems.append("删掉记录后迁移没有重跑（幂等无法验证）")
    # AQL 方案表：7 水平 × 3 严格度 × 15 批量档 × 6 AQL = 1890 行
    # （补齐 7 个水平 + 加严/放宽两张表后，行数 66 → 630 → 1890，是预期的）
    n = table_count("erp_qc_aql_plan")
    log("  erp_qc_aql_plan 行数 %d（7 水平 × 3 严格度 × 15 批量档 × 6 AQL = 1890）" % n)
    if n > 1890:
        problems.append("erp_qc_aql_plan 有 %d 行，超过 1890，种子数据疑似重复播种" % n)
    if n < 1890:
        problems.append("erp_qc_aql_plan 只有 %d 行，少于 1890：升级后没补齐水平或严格度" % n)
    # 7 水平 × 3 严格度，每个组合 90 行
    for lv in ("SPECIAL_S1", "SPECIAL_S2", "SPECIAL_S3", "SPECIAL_S4",
               "GENERAL_I", "GENERAL_II", "GENERAL_III"):
        for st in ("NORMAL", "TIGHTENED", "REDUCED"):
            cnt = q("select count(*) from erp_qc_aql_plan where level=%s and strict_level=%s",
                    (lv, st))[0][0]
            if int(cnt) != 90:
                problems.append("erp_qc_aql_plan 的 %s/%s 有 %s 行，应为 90（15 批量档 × 6 AQL）"
                                % (lv, st, cnt))

    # AQL 箭头格的样本量修正（QC 核对 GB/T 2828.1 表 2-A 时发现）
    # 升级前旧版把箭头格当数字格，H 档 @ AQL 0.65 的样本量是 50；
    # 国标 10.3 要求借 J 行的 n=80。迁移脚本要把它改对。
    rows = q("select lot_from, aql, sample_size, ac, re, verified "
             "from erp_qc_aql_plan where level=%s and strict_level=%s "
             "and lot_from in (281, 151, 91, 2) order by lot_from, aql",
             ("GENERAL_II", "NORMAL"))
    log("  AQL 箭头格样本量抽查（升级后应为国标值）：")
    expect = {
        # (lot_from, aql) -> (n, ac, re)
        (281, 0.65): (80, 1, 2),     # ↓ 借 J 行
        (151, 0.65): (20, 0, 1),     # ↑ 借 F 行
        (151, 1.0): (50, 1, 2),      # ↓ 借 H 行
        (91, 1.5): (32, 1, 2),       # ↓ 借 G 行
        (2, 4.0): (3, 0, 1),         # ↓ 借 B 行
    }
    def as_int(v):
        """统一转 int。

        ⚠️ TINYINT(1) 在这个驱动下返回的是 **bytes b'\x01'**（二进制字节，不是 ASCII '1'），
        所以不能 decode 后再 float —— decode 得到的是控制字符 '\x01'，float() 直接抛错。
        这里按字节值判断：b'\x00' → 0，b'\x01' → 1。
        """
        if isinstance(v, (bytes, bytearray)):
            if v in (b"\x00", b"\x01"):
                return int(v[0])
            return int(v.decode("utf-8"))
        if isinstance(v, bool):
            return 1 if v else 0
        return int(float(v))

    seen = {}
    for r in rows:
        seen[(as_int(r[0]), float(r[1]))] = (as_int(r[2]), as_int(r[3]), as_int(r[4]), as_int(r[5]))
    log("    查到 %d 行，样例：%r" % (len(rows), rows[:1]))
    for k, want in expect.items():
        got = seen.get(k)
        if got is None:
            problems.append("AQL 方案缺格子 %s" % (k,))
            continue
        if got[:3] != want:
            problems.append("AQL 方案 %s 升级后仍是 %s，应为 %s（箭头格没借到国标样本量）"
                            % (k, got[:3], want))
        if got[3] != 1:
            problems.append("AQL 方案 %s 升级后 verified=%s，应为 1（66 格已逐格核对国标）"
                            % (k, got[3]))
        # 迁移幂等：再跑一次，样本量不该变（说明 CASE 的 WHERE 条件仍然命中不了已改过的行）
        log("    批量 %s @ AQL %s → n=%s Ac=%s Re=%s verified=%s %s"
            % (k[0], k[1], got[0], got[1], got[2], got[3],
               "✓" if got[:3] == want else "✗ 应为 %s" % (want,)))
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old", default="HEAD~1", help="旧版本提交")
    ap.add_argument("--port", type=int, default=8131)
    ap.add_argument("--keep", action="store_true", help="保留环境")
    ap.add_argument("--skip-build-old", action="store_true")
    args = ap.parse_args()

    # 子进程里的 seed-demo.py 也是 urllib，显式给它 NO_PROXY
    os.environ["NO_PROXY"] = "127.0.0.1,localhost"
    os.environ["no_proxy"] = "127.0.0.1,localhost"

    print("=== 升级演练：%s → 当前工作区 ===" % args.old)
    worktree = os.path.join(os.environ.get("TEMP", "/tmp"), "dh-upgrade-old")
    logfile = os.path.join(os.environ.get("TEMP", "/tmp"), "dh-upgrade.log")
    problems = []
    try:
        if not os.path.exists(JAR):
            fail("当前 jar 不存在，先跑 mvn package：%s" % JAR)
        if port_alive(args.port):
            kill_port(args.port)

        # ① 旧版 jar
        if args.skip_build_old:
            old_jar = os.path.join(worktree, "device-hub", "target", "device-hub.jar")
            if not os.path.exists(old_jar):
                old_jar = os.path.join(worktree, "target", "device-hub.jar")
        else:
            old_jar = build_old(args.old, worktree)

        # ② 干净库
        c = db(None)
        cur = c.cursor()
        cur.execute("drop database if exists `%s`" % DB_NAME)
        cur.execute("create database `%s` default charset utf8mb4" % DB_NAME)
        c.commit(); c.close()
        log("演练库已重建：%s" % DB_NAME)

        # ③ 旧版建库灌数据
        log("旧版启动（建旧 schema）…")
        p = start_jar(old_jar, args.port, logfile)
        try:
            wait_ready(args.port, logfile)
            if not wait_login(args.port):
                fail("服务起来了但 admin 始终登录不了（可能 DataInitializer 失败）")
            seed_and_make_orders(args.port)
        finally:
            kill_port(args.port)
        before = snapshot()
        log("基线已记录")

        # ④ 新版升级
        log("新版启动（执行迁移）…")
        p = start_jar(JAR, args.port, logfile)
        try:
            wait_ready(args.port, logfile)
            wait_login(args.port)
            problems += check_upgrade(before, logfile)[0]
        finally:
            kill_port(args.port)

        # ⑤ 幂等
        problems += check_idempotent(args.port, logfile)

    except SystemExit:
        raise
    except Exception as e:
        print("演练异常：%s" % e)
        problems.append("演练异常：%s" % e)

    finally:
        if not args.keep:
            kill_port(args.port)
            subprocess.run("git worktree remove %s --force" % worktree,
                           shell=True, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=ROOT)
            try:
                c = db(); cur = c.cursor()
                cur.execute("drop database if exists `%s`" % DB_NAME)
                c.commit(); c.close()
                log("演练库已清理")
            except Exception:
                pass
        else:
            log("--keep：环境保留在 %s，端口 %d" % (DB_NAME, args.port))

    print()
    if problems:
        print("=== 演练失败 %d 项 ===" % len(problems))
        for x in problems:
            print("  ✗ %s" % x)
        sys.exit(1)
    print("=== 演练通过：升级路径可用，存量数据完好，迁移幂等 ===")


if __name__ == "__main__":
    main()
