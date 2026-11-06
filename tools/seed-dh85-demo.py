# -*- coding: utf-8 -*-
"""DH-85 派工演示数据（幂等，可重复跑）。

给「我的待办」「指派」这两件事造几条看得见的数据：
  MO-DEMO-A001  ANALYZED     指派给 leader   ← 待办里能看到
  MO-DEMO-A002  ANALYZED     未指派          ← 谁都能派人去认领
  MO-DEMO-A003  IN_PROGRESS  指派给 leader   ← 处理中
  MO-DEMO-A004  RESOLVED     指派过且已结案  ← 终态不可再派（演示拦截）

为什么用户走 API、工单直连库：
  · 用户必须走 API —— 密码要经过应用自己的哈希，手写 SQL 插 hash 是自找麻烦（且盐算法会变）
  · 工单只能直连 —— 应用没有「凭空建维修单」的接口，只有「从报警建单」(analyze)，
    而演示环境不一定有真实报警；硬造报警再等采集，既慢又不可控

用法：
  python tools/seed-dh85-demo.py                       # 默认 http://127.0.0.1:8080 + devicehub 库
  python tools/seed-dh85-demo.py --base http://127.0.0.1:8081 --db devicehub_verify
数据库可用 DEVICEHUB_DB_HOST/PORT/USER/PASSWORD 覆盖。
"""
import argparse
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
ADMIN = "admin"
ADMIN_PASS = "123456"

DEMO_USER = "leader"
DEMO_PASS = "leader123"
DEMO_DISPLAY = "车间主任"

ORDERS = [
    ("MO-DEMO-A001", "CNC-01", "CNC-01 加工中心", "33", "伺服报警 AL.33（演示）", "ANALYZED",
     DEMO_USER, "建议先查再生电阻接线，再看减速时间设置"),
    ("MO-DEMO-A002", "INJ-01", "INJ-01 注塑机", "12", "加热圈断线（演示）", "ANALYZED",
     None, "建议测加热圈阻值，正常 20~30Ω"),
    ("MO-DEMO-A003", "PRESS-01", "PRESS-01 冲床", "55", "过载保护动作（演示）", "IN_PROGRESS",
     DEMO_USER, "已停机，检查模具是否卡料"),
    ("MO-DEMO-A004", "METER-01", "一车间总电表", None, "需量接近合同值（演示）", "RESOLVED",
     DEMO_USER, "已核算本月峰值，无需扩容"),
]

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def req(method, path, body=None, timeout=25):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        r.add_header("Content-Type", "application/json")
    try:
        with opener.open(r, timeout=timeout) as resp:
            raw = resp.read().decode()
            try:
                return resp.status, (json.loads(raw) if raw else {})
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, (json.loads(raw) if raw else {})
        except json.JSONDecodeError:
            return e.code, raw


def main():
    global BASE
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--db", default="devicehub")
    ap.add_argument("--admin-pass", default=ADMIN_PASS)
    a = ap.parse_args()
    BASE = a.base.rstrip("/")

    st, r = req("POST", "/api/auth/login", {"username": ADMIN, "password": a.admin_pass})
    if st != 200 or not r.get("ok"):
        print("admin 登录失败：%s %s" % (st, r))
        return 1
    print("admin 登录 OK")

    # 1) 演示用户（走 API，密码交给应用哈希）
    st, r = req("POST", "/api/users", {
        "username": DEMO_USER, "displayName": DEMO_DISPLAY,
        "password": DEMO_PASS, "role": "LEADER"})
    msg = r.get("message") if isinstance(r, dict) else r
    print("用户 %-7s -> %s %s" % (DEMO_USER, st, msg))

    # 2) 演示工单（直连库：应用没有「凭空建单」的接口）
    try:
        import pymysql
    except ImportError:
        print("缺 pymysql：pip install pymysql")
        return 1
    conn = pymysql.connect(
        host=os.environ.get("DEVICEHUB_DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("DEVICEHUB_DB_PORT", "3306")),
        user=os.environ.get("DEVICEHUB_DB_USER", "devicehub"),
        password=os.environ.get("DEVICEHUB_DB_PASSWORD", "DeviceHub2026"),
        database=a.db, charset="utf8mb4", autocommit=True)
    cur = conn.cursor()

    now = int(time.time() * 1000)
    add = upd = 0
    for no, dev, devname, code, text, status, assignee, advice in ORDERS:
        cur.execute("select id from erp_maint_order where order_no=%s", (no,))
        row = cur.fetchone()
        at = now if assignee else None
        if row:
            cur.execute(
                "update erp_maint_order set assignee=%s, assigned_at=%s, assigned_by=%s "
                "where order_no=%s", (assignee, at, ADMIN if assignee else None, no))
            upd += 1
        else:
            cur.execute(
                "insert into erp_maint_order (order_no, device_code, device_name, alarm_code,"
                " alarm_text, ai_advice, ai_source, ai_at, status, assignee, assigned_at,"
                " assigned_by, created_by, created_at) "
                "values (%s,%s,%s,%s,%s,%s,'LOCAL',%s,%s,%s,%s,%s,%s,%s)",
                (no, dev, devname, code, text, advice, now, status, assignee, at,
                 ADMIN if assignee else None, ADMIN, now))
            add += 1
    print("工单：新增 %d 条，更新 %d 条" % (add, upd))

    # 3) 复核：leader 的待办里应该能看到两条（A001 / A003）
    opener2 = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    st, r = req("POST", "/api/auth/login", {"username": DEMO_USER, "password": DEMO_PASS})
    if st == 200 and r.get("ok"):
        st, my = req("GET", "/api/erp/maint/my")
        nos = [x.get("orderNo") for x in my] if isinstance(my, list) else []
        print("%s 的待办（%d 条）：%s" % (DEMO_USER, len(nos), ", ".join(nos) or "（空）"))
    else:
        print("用 %s 登录失败：%s（演示用户可能没建成）" % (DEMO_USER, st))

    cur.execute("select count(*) from erp_maint_order where order_no like 'MO-DEMO-%%'")
    print("库中演示工单共 %d 条" % cur.fetchone()[0])
    conn.close()
    print("\n去「报警」页打开任一报警的诊断弹窗，或在 AI 助手里说「给 CNX-01 的报警建个维修单派给 leader」看效果")
    return 0


if __name__ == "__main__":
    sys.exit(main())
