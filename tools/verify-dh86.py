"""临时脚本：DH-86 真进程验证（跑完删）。

验四件事：
  1. 1.9.4 迁移在「表已存在」的老库上真的把 estimated 列加上
  2. 加了这列之后应用还能起来（JPA 映射没写错）
  3. overview 的 dataQuality 能算出 estimatedWindows / backfillKwh
  4. 摊平的电量落库后 estimated=1，且峰值把它算进去了
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

import pymysql

DB = "devicehub_verify"
PORT = 8081
BASE = "http://127.0.0.1:%d" % PORT
ADMIN = ("admin", "123456")
LOG = "target/_verify-dh86.log"


def mysql(sql, args=None, fetch=True):
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="devicehub",
                           password="DeviceHub2026", database=DB, charset="utf8mb4",
                           autocommit=True)
    try:
        cur = conn.cursor()
        cur.execute(sql, args)
        return cur.fetchall() if fetch else None
    finally:
        conn.close()


def req(method, path, body=None, cookie=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        r.add_header("Content-Type", "application/json")
    if cookie:
        r.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw), resp.headers.get("Set-Cookie", "")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw), ""
        except Exception:
            return e.code, raw[:300], ""


def login(retries=40):
    for i in range(retries):
        try:
            st, r, ck = req("POST", "/api/auth/login",
                            {"username": ADMIN[0], "password": ADMIN[1]})
            if st == 200 and isinstance(r, dict) and r.get("ok"):
                sid = re.search(r"JSESSIONID=([^;]+)", ck or "")
                return "JSESSIONID=%s" % sid.group(1) if sid else (ck or "")
        except Exception:
            pass
        time.sleep(2)
    return None


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else "target/device-hub.jar"
    java = os.path.join(os.environ["JAVA_HOME"], "bin", "java")

    # ---- 迁移前：确认老库里还没有 estimated 列（否则这个验证没意义）----
    cols = [r[0] for r in mysql(
        "select column_name from information_schema.columns "
        "where table_schema=%s and table_name='demand_window'", (DB,))]
    print("启动前 demand_window 的列: %s" % (", ".join(cols) if cols else "(表不存在)"))
    had_col_before = "estimated" in cols
    if had_col_before:
        print("  注意：estimated 列已存在（上次验证留下的），本次只验功能不验加列")

    passed, failed = [], []

    def check(name, ok, detail=""):
        (passed if ok else failed).append(name)
        print("  %s %-40s %s" % ("PASS" if ok else "FAIL", name, detail))

    # ---- 启动 ----
    logf = open(LOG, "wb")
    proc = subprocess.Popen([
        java, "-jar", jar, "--server.port=%d" % PORT,
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/%s"
        "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true" % DB,
        "--spring.datasource.username=devicehub",
        "--spring.datasource.password=DeviceHub2026",
        "--energy.demo-seed=false", "--demand.demo-seed=false", "--report.demo-seed=false",
    ], stdout=logf, stderr=subprocess.STDOUT)
    try:
        ck = login()
        check("应用启动 + admin 登录", ck is not None)
        if not ck:
            return

        # ---- AC：1.9.4 迁移把列加上 ----
        row = mysql("select ok, attempts, left(coalesce(err_text,''),70) from schema_migration "
                    "where version='1.9.4'")
        ok14 = bool(row) and ((row[0][0] == b"\x01") if isinstance(row[0][0], bytes) else bool(row[0][0]))
        check("迁移 1.9.4 记录 ok", ok14, str(row[0][1:]) if row else "无记录")
        cols = [r[0] for r in mysql(
            "select column_name from information_schema.columns "
            "where table_schema=%s and table_name='demand_window'", (DB,))]
        check("demand_window.estimated 列存在", "estimated" in cols)

        st, r, _ = req("GET", "/api/energy/demand", cookie=ck)
        check("overview 200", st == 200)
        dq = (r or {}).get("dataQuality") if isinstance(r, dict) else None
        check("overview 带 dataQuality", isinstance(dq, dict), json.dumps(dq, ensure_ascii=False)[:80])

        # ---- 造数据：2 个推算窗口 + 1 个实测窗口 ----
        mysql("delete from demand_window where device_code='DH86-T'")
        base = 1_700_000_000_000
        base -= base % 900_000
        now = int(time.time() * 1000)
        win = now - (now % 900_000) - 4 * 900_000      # 本月内的一个过去窗口
        mysql("insert into demand_window (device_code,window_start,kwh,demand_kw,estimated) "
              "values ('DH86-T',%s,15,60,1),('DH86-T',%s,10,40,1),('DH86-T',%s,6,24,0)",
              (win, win + 900_000, win + 1_800_000))

        # backfill 电量：插一条 energy_hourly（本月的）
        mysql("delete from energy_hourly where device_code='DH86-T'")
        hour = now - (now % 3_600_000)
        mysql("insert into energy_hourly (device_code,hour_start,seg_type,kwh,price,cost,backfill_kwh) "
              "values ('DH86-T',%s,'FLAT',100,0.6,60,40)", (hour,))

        st, r, _ = req("GET", "/api/energy/demand?device=DH86-T", cookie=ck)
        dq = (r or {}).get("dataQuality") or {}
        check("estimatedWindows 计数正确（2）", dq.get("estimatedWindows") == 2, str(dq.get("estimatedWindows")))
        check("backfillKwh 读出（40）", abs((dq.get("backfillKwh") or 0) - 40) < 0.01, str(dq.get("backfillKwh")))
        check("hasGap=true", dq.get("hasGap") is True, str(dq.get("hasGap")))
        check("峰值把推算窗口算进去（60kW @ 15kWh）",
              abs(((r.get("monthMax") or {}).get("kw") or 0) - 60) < 0.6,
              str((r.get("monthMax") or {}).get("kw")))

        # 清掉测试数据
        mysql("delete from demand_window where device_code='DH86-T'")
        mysql("delete from energy_hourly where device_code='DH86-T'")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=25)
        except Exception:
            proc.kill()
        logf.close()

    print()
    print("小结: %d 过 / %d 挂" % (len(passed), len(failed)))
    if failed:
        print("挂了: " + ", ".join(failed))
        tail = open(LOG, encoding="utf-8", errors="replace").read()[-2500:]
        print("--- 日志尾部 ---")
        print(tail)
        sys.exit(1)


if __name__ == "__main__":
    main()
