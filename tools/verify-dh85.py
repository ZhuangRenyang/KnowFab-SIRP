# -*- coding: utf-8 -*-
"""DH-85 端到端验证（临时脚本，跑完删）。

验的是「派工/待办/权限/审计」这条链在真进程里能不能跑通：
  迁移 1.9.3 → 3 列 + 索引 → 建人 → 插一张工单 → 指派 → 待办 → 越权 403 → 审计
用法：python tools/_verify-dh85.py
"""
import http.cookiejar
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8081"
DB = "devicehub_verify"
LOG = "target/_verify-dh85.log"
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print("   %s  %-44s %s" % ("PASS" if cond else "FAIL", name, detail))
    return cond


class Session:
    def __init__(self):
        self.cj = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))

    def req(self, method, path, body=None, timeout=25):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(BASE + path, data=data, method=method)
        if data:
            r.add_header("Content-Type", "application/json")
        try:
            with self.op.open(r, timeout=timeout) as resp:
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
    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java.exe")
    if not os.path.exists(java):
        java = "java"
    jar = os.path.abspath("target/device-hub.jar")

    print("== 启动（库=%s 端口 8081）" % DB)
    logf = open(LOG, "wb")
    proc = subprocess.Popen([
        java, "-jar", jar, "--server.port=8081",
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/%s"
        "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true" % DB,
        "--spring.datasource.username=devicehub",
        "--spring.datasource.password=DeviceHub2026",
        "--energy.demo-seed=false", "--demand.demo-seed=false", "--report.demo-seed=false",
    ], stdout=logf, stderr=subprocess.STDOUT)

    try:
        t0 = time.time()
        ready = False
        while time.time() - t0 < 240:
            if proc.poll() is not None:
                print("   进程退出（%s），日志尾部：" % proc.returncode)
                with open(LOG, "rb") as f:
                    for ln in f.read().decode("utf-8", "replace").splitlines()[-14:]:
                        print("     " + ln)
                return 1
            try:
                with urllib.request.urlopen(BASE + "/api/system/version", timeout=3) as r:
                    if r.status == 200:
                        ready = True
                        break
            except Exception:
                pass
            time.sleep(3)
        if not check("应用就绪（无 Bean 循环依赖）", ready, "%.0f 秒" % (time.time() - t0)):
            return 1

        import pymysql
        conn = pymysql.connect(host="127.0.0.1", port=3306, user="devicehub",
                               password="DeviceHub2026", database=DB, charset="utf8mb4",
                               autocommit=True)
        cur = conn.cursor()

        # ---- 迁移与结构 ----
        cur.execute("select ok from schema_migration where version='1.9.3'")
        row = cur.fetchone()
        okv = (row[0] == b"\x01" if row and isinstance(row[0], bytes) else bool(row[0])) if row else None
        check("迁移 1.9.3 已执行且 ok", okv is True, "ok=%s" % okv)

        cur.execute("show columns from erp_maint_order")
        cols = {r[0] for r in cur.fetchall()}
        check("三列都在（assignee/assigned_at/assigned_by）",
              {"assignee", "assigned_at", "assigned_by"} <= cols,
              "缺 %s" % ({"assignee", "assigned_at", "assigned_by"} - cols or "无"))
        cur.execute("show index from erp_maint_order")
        idx = {r[2] for r in cur.fetchall()}
        check("索引 idx_mo_assignee 已建", "idx_mo_assignee" in idx, "%d 个索引" % len(idx))

        # ---- 登录与建人 ----
        admin = Session()
        st, r = admin.req("POST", "/api/auth/login", {"username": "admin", "password": "123456"})
        if not check("admin 登录", st == 200 and r.get("ok"), str(st)):
            return 1
        for u, dn, role, pw in (("leader", "车间主任", "LEADER", "leader123"),
                            ("sales", "销售", "SALES", "sales123")):
            st, r = admin.req("POST", "/api/users",
                              {"username": u, "displayName": dn, "password": pw, "role": role})
            print("      建用户 %-7s -> %s %s" % (u, st, r.get("message") if isinstance(r, dict) else ""))

        # ---- 造一张 ANLYZED 工单（assign 只需要工单存在）----
        cur.execute("delete from erp_maint_order where order_no='MO-DH85-T1'")
        cur.execute("insert into erp_maint_order "
                    "(order_no, device_code, device_name, alarm_code, alarm_text, status, created_by, created_at) "
                    "values ('MO-DH85-T1','CNC-01','CNC-01','33','DH85 验证用报警','ANALYZED','admin',%d)"
                    % int(time.time() * 1000))
        print("      已插入测试工单 MO-DH85-T1")

        # ---- AC-03 可派人员 ----
        st, r = admin.req("GET", "/api/erp/maint/assignable")
        names = [x.get("username") for x in r] if isinstance(r, list) else []
        check("assignable 只列能接单的角色", isinstance(r, list) and "leader" in names
              and "sales" not in names, "返回 %s" % names)

        # ---- AC-01 指派成功 ----
        st, r = admin.req("POST", "/api/erp/maint/MO-DH85-T1/assign", {"assignee": "leader"})
        check("admin 指派给 leader", st == 200 and r.get("ok"), "%s %s" % (st, r.get("message")))

        # ---- AC-02 派给不存在的人 ----
        st, r = admin.req("POST", "/api/erp/maint/MO-DH85-T1/assign", {"assignee": "nobody_xyz"})
        check("派给不存在的人被拒", r.get("ok") is False, str(r.get("message"))[:60])

        # ---- 派给 SALES 角色的人（能查到但不能接单）----
        st, r = admin.req("POST", "/api/erp/maint/MO-DH85-T1/assign", {"assignee": "sales"})
        check("派给 SALES 角色被拒", r.get("ok") is False, str(r.get("message"))[:60])

        # ---- AC-01 leader 的待办里有它 ----
        leader = Session()
        leader.req("POST", "/api/auth/login", {"username": "leader", "password": "leader123"})
        st, my = leader.req("GET", "/api/erp/maint/my")
        nos = [x.get("orderNo") for x in my] if isinstance(my, list) else []
        check("leader 的待办里能看到它", "MO-DH85-T1" in nos, "共 %d 条" % len(nos))

        # ---- AC-04 SALES 越权 ----
        sales = Session()
        sales.req("POST", "/api/auth/login", {"username": "sales", "password": "sales123"})
        st, r = sales.req("POST", "/api/erp/maint/MO-DH85-T1/assign", {"assignee": "sales"})
        check("SALES 调 assign 返回 403", st == 403, "实际 %s" % st)

        # ---- AC-09 终态不能再派 ----
        admin.req("POST", "/api/erp/maint/MO-DH85-T1/start")
        admin.req("POST", "/api/erp/maint/MO-DH85-T1/resolve",
                  {"causeCategory": "ELECTRIC", "rootCause": "验证用", "solution": "验证用"})
        st, r = admin.req("POST", "/api/erp/maint/MO-DH85-T1/assign", {"assignee": "leader"})
        check("已结案的工单不能再指派", r.get("ok") is False, str(r.get("message"))[:50])

        # ---- AC-05 审计 ----
        cur.execute("select count(*) from audit_log where action like 'maint.assign%' or detail like '%MO-DH85-T1%'")
        n = cur.fetchone()[0]
        check("指派落审计", n > 0, "命中 %d 条" % n)

        # ---- AI 工具（DH-85 三个）----
        st, tools = admin.req("GET", "/api/ai/chat/tools")
        print("      /api/ai/tools 原文:", json.dumps(tools, ensure_ascii=False)[:260])
        _tl = tools.get("tools") if isinstance(tools, dict) else tools
        tl = [t.get("name") for t in _tl] if isinstance(_tl, list) else []
        want = {"query_alarm_events", "create_maint_order", "assign_maint_order"}
        check("AI 侧出现 3 个新工具", want <= set(tl), "共 %d 个工具" % len(tl))

        st, tools_s = sales.req("GET", "/api/ai/chat/tools")
        _ts = tools_s.get("tools") if isinstance(tools_s, dict) else tools_s
        tl_s = [t.get("name") for t in _ts] if isinstance(_ts, list) else []
        check("SALES 看不到写工具", not (want & set(tl_s)), "SALES 有 %d 个" % len(tl_s))

        conn.close()
    finally:
        print("\n   停止应用…")
        try:
            proc.terminate()
            proc.wait(timeout=25)
        except Exception:
            proc.kill()
        logf.close()

    npass = sum(1 for _, p, _ in results if p)
    print("\n== 小结：%d 过 / %d 挂" % (npass, len(results) - npass))
    for name, p, d in results:
        if not p:
            print("   FAIL  %s  %s" % (name, d))
    return 0 if npass == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
