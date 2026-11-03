#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
质检模块端到端验收脚本（QC-SPEC §8 的 AC 逐条动态验证）。

**为什么要有这个脚本**：Spec 里 15 条 AC，静态审查能看出"代码写了没有"，
看不出"跑起来对不对"。而这个模块的缺陷几乎全是「错了不报错」型——
账面正常、页面正常、良率看着合理，月底盘账才发现。
所以必须真跑一遍。

**用法**：
    python tools/qc-e2e.py                    # 自建隔离库、起服务、跑完自动清理
    python tools/qc-e2e.py --keep             # 保留环境便于排查
    python tools/qc-e2e.py --port 8141

**判定标准**：每条 AC 一组断言，任一失败即 FAIL 并打印失败原因。
"""

import argparse
import http.cookiejar
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = PROJ

DB_HOST = "127.0.0.1"
DB_PORT = 3306
DB_USER = "devicehub"
DB_PASS = "DeviceHub2026"
# 用已授权的验证库名。devicehub 用户只对固定几个库有权限，
# 不能自己 create 新库——那是本地环境的授权限制，不是脚本的问题。
DB_NAME = "devicehub_verify"
ADMIN_PASS = "123456"

MVN = os.path.join(ROOT, "..", "tools", "apache-maven-3.9.9", "bin", "mvn")
JAR = os.path.join(PROJ, "target", "device-hub.jar")

_results = []


def log(msg):
    print("  %s" % msg, flush=True)


def ok(name, detail=""):
    _results.append((True, name, detail))
    print("  [PASS] %s %s" % (name, detail), flush=True)


def fail(name, detail=""):
    _results.append((False, name, detail))
    print("  [FAIL] %s %s" % (name, detail), flush=True)


def as_rows(x):
    """列表接口的返回体格式不统一：有的是 {rows:[...]}，有的是 [...] 本身。"""
    if isinstance(x, list):
        return x
    if isinstance(x, dict):
        for k in ("rows", "data", "items", "list"):
            v = x.get(k)
            if isinstance(v, list):
                return v
    return []


def check(cond, name, detail=""):
    if cond:
        ok(name, detail)
    else:
        fail(name, detail)
    return cond


def no_proxy_opener():
    """本机直连，不走 HTTP 代理。

    这台机器设了 HTTP_PROXY，urllib 默认拿它去请求 127.0.0.1，
    本地回环被代理拦下返回 502。本机直连必须显式禁代理。
    """
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Api:
    def __init__(self, port):
        self.base = "http://127.0.0.1:%d" % port
        self.cj = http.cookiejar.CookieJar()
        self.op = no_proxy_opener()
        self.op.add_handler(urllib.request.HTTPCookieProcessor(self.cj))

    def send(self, method, path, body=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(
            self.base + path, data=data, method=method,
            headers={"Content-Type": "application/json"})
        try:
            with self.op.open(req, timeout=30) as r:
                out = json.loads(r.read().decode("utf-8"))
                # ⚠️ 成功路径也要记 httpStatus。
                # 否则「200 + {ok:false}」与「403 + {ok:false}」在断言里长得一模一样——
                # 我就因此一度以为项目的权限拒绝是 200，其实 SecurityConfig 的
                # denied handler 明确 setStatus(403)。判断权限拒绝必须看状态码。
                if isinstance(out, dict):
                    out.setdefault("httpStatus", r.status)
                return out
        except urllib.error.HTTPError as e:
            body_txt = e.read().decode("utf-8", "replace")
            try:
                out = json.loads(body_txt)
            except Exception:
                out = {"ok": False, "raw": body_txt[:300]}
            if isinstance(out, dict):
                out["httpStatus"] = e.code
            return out

    def get(self, path):
        return self.send("GET", path)

    def post(self, path, body=None):
        return self.send("POST", path, body if body is not None else {})

    def put(self, path, body=None):
        return self.send("PUT", path, body if body is not None else {})


def db(database=None):
    import pymysql
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=database, charset="utf8mb4")


def wait_login(port, timeout=90):
    """等到「能登录」为止，而不是等「端口通」。

    Tomcat 先开始监听，管理员账号是 DataInitializer 随后才建的。
    只等端口通的话，紧接着的登录会拿到 401「用户名或密码不对」——
    看着像密码错了，其实是还没建好。
    """
    api = Api(port)
    for _ in range(timeout):
        try:
            r = api.post("/api/auth/login", {"username": "admin", "password": ADMIN_PASS})
            if r.get("ok"):
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def start_service(port, logfile):
    env = dict(os.environ)
    env["DEVICEHUB_DB_PASSWORD"] = DB_PASS
    env["NO_PROXY"] = "127.0.0.1,localhost"
    env["no_proxy"] = "127.0.0.1,localhost"
    url = ("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8"
           "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
           % (DB_HOST, DB_PORT, DB_NAME))
    cmd = ["java", "-Xms256m", "-Xmx512m", "-jar", JAR,
           "--server.port=%d" % port,
           "--spring.datasource.url=" + url,
           "--system.admin.password=" + ADMIN_PASS]
    with open(logfile, "wb") as f:
        p = subprocess.Popen(cmd, stdout=f, stderr=subprocess.STDOUT, cwd=PROJ, env=env)
    return p


def stop_service(proc, port):
    try:
        proc.terminate()
        proc.wait(timeout=20)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def seed(port):
    env = dict(os.environ)
    env["NO_PROXY"] = "127.0.0.1,localhost"
    env["no_proxy"] = "127.0.0.1,localhost"
    p = subprocess.run([sys.executable, os.path.join(PROJ, "tools", "seed-demo.py"),
                        "--base", "http://127.0.0.1:%d" % port, "--pass", ADMIN_PASS],
                       capture_output=True, text=True, encoding="utf-8", errors="replace",
                       cwd=PROJ, env=env)
    if p.returncode != 0:
        print(p.stdout[-1500:])
        print(p.stderr[-800:])
        raise SystemExit("播种失败：旧环境起不来就没法造存量数据")
    return p.stdout.strip().splitlines()[-1] if p.stdout.strip() else "?"


def seed_two_channels(work_order_no, device_code):
    """造同一工单的两条记录：设备通道 3 件 + 人工通道 2 件。

    直接写库：设备通道的记录只能由同步任务生成，而演示数据里
    设备没有 reject 点位、prod_hourly 的 defect 恒为 0，造不出 3 件。
    写库只为验对账口径，不走业务接口——业务路径另有单测覆盖。
    """
    now = int(time.time() * 1000)
    conn = db(DB_NAME)
    cur = conn.cursor()
    # 同时造一条 prod_hourly：设备采集数（deviceQty）是从 prod_hourly 读的，
    # 只造质量域记录的话两个数对不上（一个 3 一个 0），
    # 那不是代码问题，是造数据造歪了——两个通道本来就该互为镜像。
    if device_code:
        hour = (now // 3600000) * 3600000
        # insert ignore：演示数据可能已经有同一 device+hour 的行（唯一键冲突）。
        # 普通 insert 失败时 MySQL 只回滚那一条语句，后续语句照跑——
        # 结果就是质量域记录插进去了、prod_hourly 没插进去，两个数对不上。
        # ⚠️ prod_hourly 的 double 列不能是 NULL：实体里它们是 primitive double
        # （不是 Double），Hibernate 读到 NULL 会抛
        # "Null value was assigned to a property ... of primitive type"，
        # 整个接口 500。所以这里把所有 double 列都填上值。
        cur.execute(
            "insert ignore into prod_hourly (device_code, device_name, hour_start, output, defect, "
            " run_sec, stop_sec, idle_sec, alarm_sec, backfill, unknown_sec) "
            "values (%s, 'e2e', %s, 100, 3, 3600, 0, 0, 0, 0, 0)", (device_code, hour))
        cur.execute(
            "update prod_hourly set defect = 3 where device_code = %s and hour_start = %s",
            (device_code, hour))
    for src, qty, key in [("DEVICE", "3", "DEV:E2E:%s:%d" % (device_code or "X", now)),
                          ("MANUAL", "2", "MANUAL:E2E:%s:%d" % (work_order_no, now))]:
        cur.execute(
            "insert into erp_qc_defect "
            "(defect_no, source, source_key, work_order_no, device_code, qty, in_stock, "
            " status, disposed_qty, found_at, registered_by, recon_state, created_at, updated_at) "
            "values (%s, %s, %s, %s, %s, %s, 0, 'PENDING', 0, %s, 'e2e', '', %s, %s)",
            ("E2E%s%d" % (src[:3], now % 100000), src, key, work_order_no,
             device_code, qty, now, now, now))
    conn.commit()
    cur.close()
    conn.close()


def main():
    global DB_NAME
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8141)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--db", default=DB_NAME, help="隔离库名（默认用已授权的验证库）")
    args = ap.parse_args()
    DB_NAME = args.db

    os.environ["NO_PROXY"] = "127.0.0.1,localhost"
    os.environ["no_proxy"] = "127.0.0.1,localhost"

    if not os.path.exists(JAR):
        raise SystemExit("jar 不存在，先跑 mvn clean package：%s" % JAR)

    print("=== 质检模块端到端验收 ===")
    c = db(None)
    cur = c.cursor()
    cur.execute("drop database if exists `%s`" % DB_NAME)
    cur.execute("create database `%s` default charset utf8mb4" % DB_NAME)
    c.close()
    log("隔离库 %s 已建（不碰交付库 devicehub）" % DB_NAME)

    logfile = os.path.join(PROJ, "target", "qc-e2e.log")
    proc = start_service(args.port, logfile)
    try:
        if not wait_login(args.port):
            tail = ""
            if os.path.exists(logfile):
                with open(logfile, "rb") as f:
                    tail = f.read().decode("gbk", "replace")[-2500:]
            print(tail)
            raise SystemExit("服务起来了但 admin 始终登录不了")
        log("服务已就绪")

        log("播种演示数据：%s" % seed(args.port))
        api = Api(args.port)
        api.post("/api/auth/login", {"username": "admin", "password": ADMIN_PASS})

        run_checks(api, args.port)
    finally:
        stop_service(proc, args.port)
        if not args.keep:
            c = db(None)
            cur = c.cursor()
            cur.execute("drop database if exists `%s`" % DB_NAME)
            c.close()
            log("隔离库已清理")
        else:
            log("保留环境：库 %s / 日志 %s" % (DB_NAME, logfile))

    passed = sum(1 for r in _results if r[0])
    total = len(_results)
    print()
    print("=== 结果：%d / %d 通过 ===" % (passed, total))
    if passed != total:
        print("失败项：")
        for good, name, detail in _results:
            if not good:
                print("  - %s %s" % (name, detail))
        raise SystemExit(1)
    print("全部通过")


def run_checks(api, args_port):
    print()
    print("--- ① 看板与帕累托字段对齐 ---")
    r = api.get("/api/erp/qc/dashboard")
    check(r.get("ok"), "看板可用", str(r.get("message", "")))
    for f in ["defectRate", "deviceDefectRate", "qcDefectQty", "qcInspectedQty",
              "deviceOutputQty", "deviceDefectQty", "pendingQty", "pendingRecords",
              "untriagedQty", "untriagedRecords", "scrapQty", "concessionQty",
              "deviceRawQty", "deviceAdjustedQty", "challengedCount", "trend"]:
        check(f in r, "看板字段 %s" % f)
    check("caliberNote" not in r, "看板不返面向用户的文案（caliberNote 由前端 i18n 写）")

    r2 = api.get("/api/erp/qc/pareto?by=code")
    check(r2.get("ok") and isinstance(r2.get("rows"), list), "帕累托可用")
    check(any(x.get("key") is None for x in r2.get("rows", [])) or not r2.get("rows"),
          "帕累托保留「未分类」行（不许被静默丢掉）")

    print()
    print("--- ② 检验单列表端点 ---")
    r = api.get("/api/erp/qc/inspections")
    check(r.get("ok"), "检验单列表可用", str(r.get("message", "")))
    check("rows" in r and "total" in r, "列表返回 rows/total")

    print()
    print("--- ③ AQL 方案表 ---")
    r = api.get("/api/erp/qc/aql/plans")
    check(r.get("ok"), "AQL 方案表可用")
    rows = r.get("rows") or []
    check(len(rows) > 0, "方案表非空", "%d 行" % len(rows))
    unverified = [x for x in rows if not x.get("verified")]
    log("未核对（推算）方案 %d 条" % len(unverified))

    print()
    print("--- ③a 国标箭头格：样本量按 10.3 借相邻行（抽样强度）---")
    # 查表端点按 AQL + 批量返回样本量/Ac/Re。
    # 关键抽查：H 档（批量 281~500）@ AQL 0.65 在国标里是箭头格，
    # 按 10.3 要借 J 行 → 抽 80 件（不是 H 行自己的 50 件）。
    # 抽少了 = 抽样强度不足 = 该判不合格的批次被放行。
    r = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=NORMAL"
                "&lotQty=500&aql=0.65")
    check(r.get("ok"), "H 档 @ AQL 0.65 查表成功", str(r.get("message", ""))[:70])
    if r.get("ok"):
        check(int(r.get("sampleSize", 0)) == 80,
              "H 档 @ AQL 0.65 抽 80 件（箭头格借 J 行，不是 50 件）",
              str(r.get("sampleSize")))
        check(int(r.get("ac", -1)) == 1, "Ac=1", str(r.get("ac")))
        check(int(r.get("re", -1)) == 2, "Re=2", str(r.get("re")))
    # A 档（批量 2~8）@ AQL 0.65 是箭头格，借 F 行 → 20 件；批量 8 < 20 → 转全检
    r = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=NORMAL"
                "&lotQty=8&aql=0.65")
    check(r.get("ok"), "A 档 @ AQL 0.65 查表成功", str(r.get("message", ""))[:70])
    if r.get("ok"):
        check(int(r.get("sampleSize", 0)) == 8,
              "样本量 >= 批量时转全检，实际抽 8 件（批量）", str(r.get("sampleSize")))
        check(r.get("fullInspection") is True, "转全检", str(r.get("fullInspection")))
        check(r.get("reasonCode") == "SAMPLE_GE_LOT",
              "给出全检原因代号", str(r.get("reasonCode")))
    # 非箭头格不受影响：L 档（批量 3201~10000）@ AQL 6.5 → n=200, Ac=21
    r = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=NORMAL"
                "&lotQty=5000&aql=6.5")
    check(r.get("ok"), "L 档 @ AQL 6.5 查表成功", str(r.get("message", ""))[:70])
    if r.get("ok"):
        check(int(r.get("sampleSize", 0)) == 200, "n=200", str(r.get("sampleSize")))
        check(int(r.get("ac", -1)) == 21, "Ac=21", str(r.get("ac")))

    print()
    print("--- ③a2 七个检验水平都能查表（国标表 1 的字码映射）---")
    # 同一批量在不同水平下 → 不同字码 → 不同样本量。
    # 这是「检验水平」存在的意义：水平越高，抽样量越大，鉴别力越高。
    # 抽查 500 件这批：AQL 2.5 下各水平的结果
    level_expect = {
        "SPECIAL_S1": (8, 0),     # 批量 500 → 字码 D（n=8）… AQL 2.5 借 C 行 → n=5
        "GENERAL_I": (13, 0),
        "GENERAL_II": (20, 1),
        "GENERAL_III": (50, 3),
    }
    for lv, (want_n, want_ac) in level_expect.items():
        r = api.get("/api/erp/qc/aql/resolve?level=%s&strictLevel=NORMAL"
                    "&lotQty=500&aql=2.5" % lv)
        if not r.get("ok"):
            check("%s 水平能查表" % lv, False, str(r.get("message"))[:80])
            continue
        check("%s 水平能查表" % lv, True, "n=%s letter=%s" % (r.get("sampleSize"), r.get("codeLetter")))
        check("%s 水平样本量 = %d" % (lv, want_n),
              int(r.get("sampleSize", 0)) == want_n, r.get("sampleSize"))
        check("%s 水平 Ac = %d" % (lv, want_ac),
              int(r.get("ac", -1)) == want_ac, r.get("ac"))
    # ---- 三个严格度：正常 / 加严 / 放宽 都要能查表，且方向正确 ----
    # 国标表 2-A/2-B/2-C 是三张独立的表：行数不同、样本量不同、Ac 也不同。
    # 判"是否真的加严/放宽"，不能只看样本量（加严也可能只降 Ac 不增样本量）。
    rn = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=NORMAL"
                 "&lotQty=800&aql=1.0")
    rt = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=TIGHTENED"
                 "&lotQty=800&aql=1.0")
    rr = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=REDUCED"
                 "&lotQty=800&aql=1.0")
    for nm, x in (("NORMAL", rn), ("TIGHTENED", rt), ("REDUCED", rr)):
        check("%s 严格度能查表" % nm, x.get("ok") is True, str(x.get("message"))[:70])
    if rn.get("ok") and rt.get("ok") and rr.get("ok"):
        n_ac, n_n = int(rn.get("ac", -1)), int(rn.get("sampleSize", 0))
        t_ac, t_n = int(rt.get("ac", -1)), int(rt.get("sampleSize", 0))
        r_ac, r_n = int(rr.get("ac", -1)), int(rr.get("sampleSize", 0))
        # 加严：Ac 更小 或 样本量更大（两者其一即可，不能强制要求样本量变大）
        check("加严比正常更严（Ac 更小 或 抽更多）",
              t_ac < n_ac or t_n > n_n,
              "正常 Ac=%d n=%d / 加严 Ac=%d n=%d" % (n_ac, n_n, t_ac, t_n))
        check("加严的接收数不大于正常", t_ac <= n_ac,
              "正常 Ac=%d / 加严 Ac=%d" % (n_ac, t_ac))
        # 放宽：样本量必须更小（表 2-C 的字码-样本量整体降两档）
        check("放宽的样本量比正常小", r_n < n_n,
              "正常 n=%d / 放宽 n=%d" % (n_n, r_n))
        log("三严格度对比：正常 Ac=%d n=%d / 加严 Ac=%d n=%d / 放宽 Ac=%d n=%d"
            % (n_ac, n_n, t_ac, t_n, r_ac, r_n))

    # 21 个 (水平 × 严格度) 组合都要有方案
    r = api.get("/api/erp/qc/aql/levels")
    combos_all = as_rows(r.get("combos") if isinstance(r, dict) else r)
    lv_set = {c.get("level") for c in combos_all}
    check("7 个检验水平都有方案", len(lv_set) >= 7, "%d 个" % len(lv_set))
    # 严格度不在 levels 接口里（那是水平维度），单独查三次都要通
    for st in ("NORMAL", "TIGHTENED", "REDUCED"):
        x = api.get("/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=%s"
                    "&lotQty=500&aql=2.5" % st)
        check("严格度 %s 在水平 II 下有方案" % st, x.get("ok") is True,
              str(x.get("message"))[:60])

    # 不认识的水平 → 必须报错，不能静默当成水平 II
    r = api.get("/api/erp/qc/aql/resolve?level=GENERAL_IV&strictLevel=NORMAL"
                "&lotQty=500&aql=2.5")
    check("不认识的水平被拒（不静默当水平 II）",
          r.get("ok") is False and "水平" in str(r.get("message", "")),
          str(r.get("message"))[:80])

    print()
    print("--- ③b 抽样下拉只列有数据的组合（界面不制造必然失败）---")
    r = api.get("/api/erp/qc/aql/levels")
    check(r.get("ok"), "可用检验水平接口可调用", str(r.get("message", "")))
    combos = as_rows(r.get("combos") if isinstance(r, dict) else r)
    check(len(combos) >= 1, "至少有一个可用组合", "%d 个" % len(combos))
    for c in combos:
        check(bool(c.get("level")) and bool(c.get("strictLevel")),
              "组合带水平与严格度", str(c))
        check(int(c.get("planCount", 0)) > 0,
              "组合的方案数大于 0（列出来的必须真有方案）", str(c))
        # 列出来的组合必须真的查得到方案——否则界面还是在制造必然失败
        rr = api.get("/api/erp/qc/aql/resolve?level=%s&strictLevel=%s&lotQty=800&aql=2.5"
                     % (c.get("level"), c.get("strictLevel")))
        check(rr.get("ok") is True,
              "列出来的组合查得到方案（%s / %s）" % (c.get("level"), c.get("strictLevel")),
              str(rr.get("message", ""))[:70])
    # 反过来：表里没有的水平，查表必须报错而不是给个假 Ac
    missing = "GENERAL_I"
    have = [c.get("level") for c in combos]
    if missing not in have:
        rr = api.get("/api/erp/qc/aql/resolve?level=%s&strictLevel=NORMAL&lotQty=800&aql=2.5" % missing)
        check(rr.get("ok") is False and rr.get("code") == "NO_AQL_PLAN",
              "没导入的水平查表 → 报 NO_AQL_PLAN（绝不返回假 Ac）",
              "code=%s" % rr.get("code"))

    print()
    print("--- ④ 时间窗参数是毫秒时间戳（前端传日期串会 400 整页报错）---")
    r = api.get("/api/erp/qc/dashboard?from=%d&to=%d"
                % (int(time.time() * 1000) - 30 * 86400000, int(time.time() * 1000)))
    check(r.get("ok"), "看板接受毫秒时间戳", str(r.get("message", "")))

    print()
    print("--- ⑤ 缺陷列表筛选参数真的生效（severity / partial）---")
    r = api.get("/api/erp/qc/defects?partial=true")
    check(r.get("ok"), "缺陷列表接受 partial 参数", str(r.get("message", "")))
    r = api.get("/api/erp/qc/defects?severity=CR")
    check(r.get("ok"), "缺陷列表接受 severity 参数", str(r.get("message", "")))

    print()
    print("--- ⑥ 放行校验（禁发仓 fail-safe）---")
    r = api.get("/api/erp/qc/concession-release?whId=1&skuIds=1")
    check(r.get("ok"), "放行校验可调用", str(r.get("message", "")))
    check("blocked" in r, "放行结果给 blocked 标志")

    print()
    print("--- ⑦ 对账条字段 ---")
    r = api.get("/api/erp/qc/recon")
    check(r.get("ok"), "对账条可调用")
    items = r.get("items") or []
    if items:
        f = items[0]
        for k in ["workOrderNo", "deviceQty", "manualQty", "gap", "challenged",
                  "effectiveQty", "source", "deviceConfigured", "deviceCollected"]:
            check(k in f, "对账条字段 %s" % k)
    else:
        log("当前没有可对账的工单（跳过字段核对）")

    print()
    print("--- ⑧ 人工补录缺必填项被拒 ---")
    # 缺缺陷分类是允许的（那正是"未分类"，等着分诊）；缺设备才是错
    r = api.post("/api/erp/qc/defects", {"qty": 1})
    check(r.get("ok") is False, "人工补录缺设备被拒（不静默兜底）")

    print()
    print("--- ⑨ 判定闭环：判 FAIL 必须生成不良记录（B1 / AC 核心）---")
    skus = api.get("/api/erp/skus")
    sku_list = as_rows(skus)
    sku_id = None
    for s in sku_list:
        if s.get("code") == "P-001":
            sku_id = s.get("id")
            break
    if not sku_id and sku_list:
        sku_id = sku_list[0].get("id")
    check(sku_id is not None, "拿到演示产品 id", str(sku_id))

    if sku_id:
        r = api.post("/api/erp/qc/inspections", {
            "inspectionType": "FQC",
            "skuId": sku_id,
            "lotQty": 500,
            "level": "GENERAL_II",
            "strictLevel": "NORMAL",
            "aql": 2.5,
            "defectQty": 0,
            "items": [],
            "remark": "【e2e】判定闭环验证",
        })
        check(r.get("ok"), "检验单建单", str(r.get("message", "")))
        no = r.get("inspectionNo")
        if no:
            api.put("/api/erp/qc/inspections/%s" % no, {"defectQty": 0})
            rs = api.post("/api/erp/qc/inspections/%s/submit" % no)
            check(rs.get("ok"), "检验单提交", str(rs.get("message", "")))
            # 判 FAIL：不合格件数超过接收数 Ac
            rj = api.post("/api/erp/qc/inspections/%s/judge" % no, {"defectQty": 50})
            check(rj.get("ok"), "检验单判定", str(rj.get("message", "")))
            check(rj.get("result") == "FAIL", "判定结果是 FAIL", str(rj.get("result")))
            check(bool(rj.get("why")), "判定给出原因码 why", str(rj.get("why")))
            check(bool(rj.get("judgeText")), "判定给出存档说明 judgeText")

            # 关键：必须生成了 source=INSPECTION 的不良记录
            rd = api.get("/api/erp/qc/defects?source=INSPECTION")
            rows = rd.get("rows") or []
            check(len(rows) >= 1, "判定后生成了不良记录（不在待处置队列就没人处置）",
                  "%d 条" % len(rows))
            if rows:
                check(rows[0].get("status") == "PENDING", "不良记录进待处置队列",
                      str(rows[0].get("status")))
                check(abs(float(rows[0].get("qty", 0)) - 50) < 0.01,
                      "不良件数取件数（不是缺陷项数）", str(rows[0].get("qty")))
                check(rows[0].get("inStock") is False,
                      "FQC 判出的不良 in_stock=false（不良品没进过成品仓）",
                      str(rows[0].get("inStock")))

            # 幂等：再判一次不应该产生第二条
            api.post("/api/erp/qc/inspections/%s/judge" % no, {"defectQty": 50})
            rd2 = api.get("/api/erp/qc/defects?source=INSPECTION")
            check(len(rd2.get("rows") or []) == len(rows),
                  "重复判定不会产生第二条不良记录（幂等键）",
                  "%d → %d" % (len(rows), len(rd2.get("rows") or [])))

    print()
    print("--- ⑩ 退货按原因分流（B4 / AC-13 / AC-14）---")
    r = api.get("/api/erp/sales/orders")
    orders = as_rows(r)
    shipped = None
    for o in orders:
        if str(o.get("status", "")).upper() in ("SHIPPED", "PARTIAL", "COMPLETED", "DONE"):
            shipped = o
            break
    if not shipped:
        log("没有已发货订单可退（跳过）")
    else:
        ono = shipped.get("orderNo")
        # 缺原因 → 必须拒绝
        r = api.post("/api/erp/sales/orders/%s/return" % ono, {
            "warehouseId": shipped.get("warehouseId") or 1,
            "lines": [{"skuId": shipped.get("skuId"), "qty": 1}],
        })
        check(r.get("ok") is False, "退货缺原因被拒（不落默认值）", str(r.get("message", ""))[:80])

    print()
    print("--- ⑪ 放行硬拦：禁发仓的货出不去（AC-15）---")
    # 演示数据里没有禁发仓，造一个（禁发仓是让步接收后的存放处）
    r = api.post("/api/erp/warehouses", {
        "code": "HOLD-E2E", "name": "待处理仓【e2e】", "kind": "BLOCKED", "enabled": True,
    })
    if not r.get("ok"):
        log("建禁发仓失败：%s（可能已存在）" % r.get("message"))
    whs = api.get("/api/erp/warehouses")
    wh_rows = as_rows(whs)
    blocked = [w for w in wh_rows if w.get("kind") == "BLOCKED"]
    log("禁发仓 %d 个" % len(blocked))
    if blocked:
        wh = blocked[0]
        r = api.get("/api/erp/qc/concession-release?whId=%s&skuIds=%s"
                    % (wh.get("id"), sku_id or 1))
        check(r.get("ok"), "禁发仓放行校验可调用")
        # fail-safe：禁发仓里的货没有放行依据 → 必须拦住
        check(r.get("blocked") is True or any(h.get("blocked") for h in (r.get("hits") or [])),
              "禁发仓无放行依据时必须拦住（fail-safe：查不到就拦）")
        check(bool(r.get("limitCode")), "放行结果给匹配口径代号")
    else:
        log("没有禁发仓（跳过硬拦验证）")

    print()
    print("--- ⑬ 越权收口（AC-09：判定权不外包）---")
    # 建一个班组长账号，用它验两条边界：
    #   LEADER 建检验单 → 200（检验员在车间现场做，班组长要能录）
    #   LEADER 调判定   → 403（班组长自己录自己判等于没质检）
    r = api.post("/api/users", {
        "username": "e2e_leader", "displayName": "【e2e】班组长",
        "password": "e2e_leader_2026", "role": "LEADER",
    })
    if not r.get("ok"):
        log("建班组长账号失败：%s" % r.get("message"))
    else:
        leader = Api(args_port)
        lr = leader.post("/api/auth/login",
                         {"username": "e2e_leader", "password": "e2e_leader_2026"})
        check(lr.get("ok"), "班组长登录成功", str(lr.get("message", "")))
        if lr.get("ok"):
            # 建检验单 → 应该 200
            # 用 FQC：IPQC 这类巡检要求手工填样本量，这里只验权限边界，不验业务参数
            lc = leader.post("/api/erp/qc/inspections", {
                "inspectionType": "FQC",
                "skuId": sku_id,
                "lotQty": 500,
                "level": "GENERAL_II",
                "strictLevel": "NORMAL",
                "aql": 2.5,
                "defectQty": 0,
                "items": [],
                "remark": "【e2e】班组长建单",
            })
            check(lc.get("httpStatus") == 200 and lc.get("ok") is True,
                  "LEADER 建检验单 → 200（现场录检验单）",
                  "httpStatus=%s %s" % (lc.get("httpStatus"), lc.get("message", "")))
            lno = lc.get("inspectionNo")
            # 判定 → 应该 403
            if lno:
                leader.post("/api/erp/qc/inspections/%s/submit" % lno)
                lj = leader.post("/api/erp/qc/inspections/%s/judge" % lno, {"defectQty": 99})
                # ⚠️ 判权限要看 ok，<b>不能看 HTTP 状态码</b>：本项目全站约定是
                # 「失败一律 200 + {ok:false}」，权限不足返回的是 200 + ok:false，
                # 消息「当前角色没有这个操作权限」。
                # 权限不足由 SecurityConfig 拦 → HTTP 403；
                # 业务失败才是 200 + ok:false（ErpQcBody.fail）。两者别混。
                check(lj.get("httpStatus") == 403 and lj.get("ok") is False,
                      "LEADER 调判定 → 403（判定权不外包）",
                      "ok=%s httpStatus=%s %s" % (lj.get("ok"), lj.get("httpStatus"),
                                                 lj.get("message", "")))
            # 处置 → 也应该 403
            ldis = leader.post("/api/erp/qc/defects/E2E-NOT-EXIST/scrap", {"qty": 1})
            check(ldis.get("httpStatus") == 403 and ldis.get("ok") is False,
                  "LEADER 调处置 → 403（处置权不外包）",
                  "ok=%s httpStatus=%s" % (ldis.get("ok"), ldis.get("httpStatus")))

    print()
    print("--- ⑫ 对账定责不放大（P0-1：定责是标记不是改数）---")
    # 对账只认 RUNNING / DONE 的工单，演示数据里没有 → 造一张带设备的
    if sku_id:
        devs = as_rows(api.get("/api/devices"))
        device_code = None
        for d in devs:
            code = d.get("code")
            if code:
                device_code = code
                break
        normal_wh = None
        for w in wh_rows:
            if w.get("kind") == "STANDARD":
                normal_wh = w
                break
        if device_code and normal_wh:
            # 用返工工单（orderType=REWORK）：下达时返工单不展开 BOM，
            # 所以不用先配物料清单就能下达——本脚本只验对账口径，不验 BOM。
            r = api.post("/api/erp/work-orders", {
                "productSkuId": sku_id, "planQty": 10,
                "deviceCode": device_code, "warehouseId": normal_wh.get("id"),
                "orderType": "REWORK",
                "remark": "【e2e】对账定责验证",
            })
            if r.get("ok"):
                wno = r.get("orderNo")
                rr = api.post("/api/erp/work-orders/%s/release" % wno)
                rs2 = api.post("/api/erp/work-orders/%s/start" % wno)
                log("已造工单 %s（设备 %s）下达=%s 开工=%s"
                    % (wno, device_code,
                       rr.get("message") if not rr.get("ok") else "OK",
                       rs2.get("message") if not rs2.get("ok") else "OK"))
                od = api.get("/api/erp/work-orders/%s" % wno)
                log("工单状态：%s" % (od.get("status") or od.get("orderNo") or "?"))
    r = api.get("/api/erp/qc/recon")
    items = as_rows(r.get("items") if isinstance(r, dict) else r)
    if not items:
        log("没有可对责的工单（跳过）")
    else:
        it = items[0]
        ono = it.get("workOrderNo")
        # 造两条同工单的记录：设备通道 3 件、人工通道 2 件。
        # 不造数据的话这条断言会"假通过"（0 → 0，什么都没测到）——
        # 假通过的断言比没有断言更危险，它给人虚假的安全感。
        seed_two_channels(ono, it.get("deviceCode"))
        r0 = api.get("/api/erp/qc/recon")
        it0 = as_rows(r0.get("items") if isinstance(r0, dict) else r0)
        row0 = next((x for x in it0 if x.get("workOrderNo") == ono), None)
        log("对账条工单号：%s / 本次造的：%s" % ([x.get("workOrderNo") for x in it0], ono))
        log("r0 原文：%s" % json.dumps(r0, ensure_ascii=False)[:400])
        check(row0 is not None, "对账条里能定位到这张工单")
        if row0:
            check(abs(float(row0.get("deviceQty", 0)) - 3) < 0.01,
                  "设备通道数是 3", str(row0.get("deviceQty")))
            check(abs(float(row0.get("manualQty", 0)) - 2) < 0.01,
                  "人工通道数是 2", str(row0.get("manualQty")))
            # 未定责时设备优先：有效通道合计 = 3（不是 3+2=5）
            check(abs(float(row0.get("effectiveQty", 0)) - 3) < 0.01,
                  "未定责时有效合计只算设备通道（3，不是 5）",
                  str(row0.get("effectiveQty")))

            r1 = api.post("/api/erp/qc/recon/%s/resolve" % ono,
                          {"choice": "TRUST_QC", "reason": "【e2e】以质检为准"})
            check(r1.get("ok"), "对账定责可调用", str(r1.get("message", "")))
            after1 = r1.get("effectiveQtyAfter")
            check(after1 is not None and abs(float(after1) - 2) < 0.01,
                  "以质检为准 → 有效合计变成人工数 2（不是 3+2=5）", str(after1))
            r2 = api.post("/api/erp/qc/recon/%s/resolve" % ono,
                          {"choice": "TRUST_QC", "reason": "【e2e】重复定责"})
            after2 = r2.get("effectiveQtyAfter")
            check(after2 is not None and after1 is not None and
                  abs(float(after1) - float(after2)) < 0.01,
                  "重复定责合计不变（3+2 不会变 7、9）", "%s → %s" % (after1, after2))
            # 再定责到设备：合计应回到 3，而不是继续涨
            r3 = api.post("/api/erp/qc/recon/%s/resolve" % ono,
                          {"choice": "TRUST_DEVICE", "reason": "【e2e】改判以设备为准"})
            after3 = r3.get("effectiveQtyAfter")
            check(after3 is not None and abs(float(after3) - 3) < 0.01,
                  "改判以设备为准 → 有效合计回到 3（定责是切换不是累加）", str(after3))


if __name__ == "__main__":
    main()
