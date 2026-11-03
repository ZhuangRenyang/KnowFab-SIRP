# -*- coding: utf-8 -*-
"""device-hub 全链路回归自测脚本。

用法：
  python tools/selftest.py                          # 默认 http://127.0.0.1:8080
  python tools/selftest.py --base http://10.0.0.5:8080 --pass '管理员密码'

覆盖：认证/三级权限/设备 CRUD 与校验/数据接口/设备发现（两帧+校验+从站扫描）/
开放接口鉴权/页面可达性/系统设置校验。测试自建自清（TEST-01 设备与 selftest 用户）。

前置：服务在跑；模拟器可选（1502/1503 不在跑时，相关用例标 SKIP 而不是 FAIL）。
退出码：0=全过，1=有 FAIL。
"""
import argparse
import json
import sys
import urllib.request
import urllib.error
import urllib.parse
from datetime import date

BASE = "http://127.0.0.1:8080"
ADMIN_USER = "admin"
ADMIN_PASS = ""
results = []


def req(method, path, body=None, cookie=None, key=None, timeout=15):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if data:
        r.add_header("Content-Type", "application/json")
    if cookie:
        r.add_header("Cookie", cookie)
    if key:
        r.add_header("X-API-Key", key)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        raw = resp.read().decode()
        try:
            return resp.status, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return e.code, raw


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + (("  <- " + str(detail)[:110]) if not cond and detail else ""))


def login(user, password):
    """登录并接住 Set-Cookie（urllib 不自动管会话）。"""
    data = json.dumps({"username": user, "password": password}).encode()
    r = urllib.request.Request(BASE + "/api/auth/login", data=data, method="POST")
    r.add_header("Content-Type", "application/json")
    try:
        resp = urllib.request.urlopen(r, timeout=15)
        sc = resp.headers.get("Set-Cookie", "")
        cookie = sc.split(";")[0] if sc else ""
        try:
            body = json.loads(resp.read().decode())
        except json.JSONDecodeError:
            body = {}
        return resp.status, body, cookie
    except urllib.error.HTTPError as e:
        return e.code, {}, ""


def main():
    global BASE, ADMIN_USER, ADMIN_PASS
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--user", default="admin")
    ap.add_argument("--pass", dest="password", default="")
    args = ap.parse_args()
    BASE = args.base.rstrip("/")
    ADMIN_USER = args.user
    ADMIN_PASS = args.password
    if not ADMIN_PASS:
        print("需要 --pass 管理员密码"); sys.exit(2)

    # ============ A. 认证 ============
    code, body, _ = login(ADMIN_USER, "错误密码" + ADMIN_PASS)
    check("A1 错误密码被拒(401)", code == 401, code)
    code, body = req("GET", "/api/devices")
    check("A2 匿名访问设备列表被拒(401)", code == 401, code)
    code, body, admin_cookie = login(ADMIN_USER, ADMIN_PASS)
    check("A3 管理员登录成功", code == 200, (code, body))

    # ============ B. 三级权限 ============
    code, users = req("GET", "/api/users", cookie=admin_cookie)
    old_names = {u.get("username") for u in users} if isinstance(users, list) else set()
    code, body = req("POST", "/api/users", {"username": "selftest_leader", "displayName": "自测班组长",
                                            "password": "Leader#2026", "role": "LEADER"}, cookie=admin_cookie)
    check("B1 创建班组长用户", code == 200, (code, body))
    code, body, leader_cookie = login("selftest_leader", "Leader#2026")
    check("B2 班组长可登录", code == 200, code)
    code, body = req("GET", "/api/users", cookie=leader_cookie)
    check("B3 班组长看用户列表被拒(403)", code == 403, code)
    code, body = req("POST", "/api/devices", {"code": "HACK", "protocol": "sim"}, cookie=leader_cookie)
    check("B4 班组长建设备被拒(403)", code == 403, code)
    code, body = req("POST", "/api/discovery/scan", {"prefixes": "192.168.1"}, cookie=leader_cookie)
    check("B5 班组长用发现被拒(403)", code == 403, code)
    code, body = req("GET", "/api/screen/snapshot")
    check("B6 大屏快照全员可看(200)", code == 200 and body.get("ok") is True, code)

    # ============ C. 设备 CRUD ============
    points = [
        {"key": "status", "name": "运行状态", "address": 0, "functionCode": 3, "dataType": "u16", "role": "status"},
        {"key": "output", "name": "计数", "address": 1, "functionCode": 3, "dataType": "u32", "role": "count"},
    ]
    dev = {"code": "TEST-01", "name": "自测设备", "protocol": "sim", "unitId": 1,
           "pollIntervalMs": 1000, "timeoutMs": 1000, "enabled": True,
           "apiKey": "selftest-key-123", "apiKeyEnabled": True, "points": points}
    code, body = req("POST", "/api/devices", dev, cookie=admin_cookie)
    check("C1 创建 sim 设备", code in (200, 201), (code, body))
    code, devs = req("GET", "/api/devices", cookie=admin_cookie)
    ids = {d.get("code"): d.get("id") for d in devs} if isinstance(devs, list) else {}
    check("C2 设备出现在列表", "TEST-01" in ids, ids.keys() if isinstance(ids, dict) else devs)
    code, body = req("POST", "/api/devices", dev, cookie=admin_cookie)
    code2, devs2 = req("GET", "/api/devices", cookie=admin_cookie)
    dup = [d for d in devs2 if d.get("code") == "TEST-01"] if isinstance(devs2, list) else []
    check("C3 重复编码不产生第二行(upsert)", len(dup) == 1, len(dup))
    bad = dict(dev); bad["code"] = "TEST-02"
    bad["points"] = [{"key": "x", "name": "坏点", "address": -5, "functionCode": 3, "dataType": "u16", "role": "value"}]
    code, body = req("POST", "/api/devices", bad, cookie=admin_cookie)
    check("C4 非法点地址被拒(400)", code == 400 and "地址" in str(body.get("message", "")), (code, body))
    code, body = req("POST", "/api/devices/reload", {}, cookie=admin_cookie)
    check("C5 热加载生效", code == 200, code)
    import time as _t
    names = []
    for _ in range(6):
        code, body = req("GET", "/api/screen/snapshot")
        names = [d.get("code") for d in (body.get("deviceList") or [])]
        if "TEST-01" in names:
            break
        _t.sleep(1.2)
    check("C6 新设备进入快照(≤7s)", "TEST-01" in names, names)

    # ============ D. 数据接口 ============
    code, body = req("GET", "/api/energy/overview?days=7", cookie=admin_cookie)
    check("D1 能耗总览 200", code == 200, code)
    code, body = req("GET", "/api/reports/daily", cookie=admin_cookie)
    check("D2 日报 200", code == 200, code)
    code, body = req("GET", "/api/reports/range?days=7", cookie=admin_cookie)
    check("D3 周趋势 200", code == 200, code)

    # ============ E. 设备发现 ============
    # 自动网段探测（发现页打开即预填）：返回本机各 IPv4 的 C 段，前端用它免手填
    code, body = req("GET", "/api/discovery/local-subnets", cookie=admin_cookie)
    check("E0 本机网段探测 200 且返回数组", code == 200 and body.get("ok") is True
          and isinstance(body.get("subnets"), list), body)
    code, body = req("POST", "/api/discovery/scan", {"prefixes": "abc"}, cookie=admin_cookie)
    check("E1 非法网段被拒", code == 200 and body.get("ok") is False, body)
    code, body = req("POST", "/api/discovery/scan", {"prefixes": "999.168.1"}, cookie=admin_cookie)
    check("E2 超范围数字被拒", code == 200 and body.get("ok") is False, body)
    code, body = req("POST", "/api/discovery/probe", {"ip": "127.0.0.1", "port": 1502, "unitId": 1,
                                                      "fc": 6, "framing": "tcp"}, cookie=admin_cookie)
    check("E3 写功能码被拒", body.get("ok") is False and "只" in str(body.get("error")), body)
    # 设备模板（2026-10-29）：列表可读 + 建/删闭环 + 坏点表被拒
    code, body = req("GET", "/api/device-templates", cookie=admin_cookie)
    check("E5 设备模板列表 200", code == 200 and body.get("ok") is True, body)
    code, body = req("POST", "/api/device-templates", {
        "name": "SELFTEST-模板", "protocol": "modbus-tcp", "port": 502, "unitId": 1,
        "pollIntervalMs": 1000, "timeoutMs": 800,
        "pointsJson": "[{\"key\":\"t\",\"name\":\"测试\",\"address\":0,"
                      "\"functionCode\":3,\"dataType\":\"u16\",\"role\":\"value\"}]"
    }, cookie=admin_cookie)
    tpl_id = (body.get("item") or {}).get("id")
    check("E6 存设备模板", body.get("ok") is True and tpl_id, body)
    code, body = req("POST", "/api/device-templates", {
        "name": "SELFTEST-模板", "protocol": "modbus-tcp", "pointsJson": "[{\"key\":\"t\"}]"
    }, cookie=admin_cookie)
    check("E7 模板重名被拒", body.get("ok") is False, body)
    code, body = req("POST", "/api/device-templates", {
        "name": "SELFTEST-空点表", "protocol": "modbus-tcp", "pointsJson": "[]"
    }, cookie=admin_cookie)
    check("E8 空点表被拒", body.get("ok") is False, body)
    if tpl_id:
        code, body = req("DELETE", f"/api/device-templates/{tpl_id}", cookie=admin_cookie)
        check("E9 删除设备模板", body.get("ok") is True, body)
    code, body = req("POST", "/api/discovery/probe", {"ip": "127.0.0.1", "port": 1502, "unitId": 1,
                                                      "fc": 3, "framing": "sideways"}, cookie=admin_cookie)
    check("E4 非法 framing 被拒", body.get("ok") is False, body)
    # 模拟器在跑才测真探测
    import socket as _s
    def port_open(p):
        try:
            s = _s.create_connection(("127.0.0.1", p), 1); s.close(); return True
        except OSError:
            return False
    for port, framing, tag in [(1502, "tcp", "A"), (1503, "rtu", "B")]:
        if not port_open(port):
            check(f"E{tag} 模拟器 {port} 未运行", True, "SKIP")
            continue
        code, body = req("POST", "/api/discovery/probe",
                         {"ip": "127.0.0.1", "port": port, "unitId": 1, "fc": 3,
                          "from": 0, "count": 3, "framing": framing}, cookie=admin_cookie)
        check(f"E{tag} FC03 {framing} 探测", body.get("ok") is True, body)
        code, body = req("POST", "/api/discovery/probe",
                         {"ip": "127.0.0.1", "port": port, "unitId": 1, "fc": 2,
                          "from": 0, "count": 4, "framing": framing}, cookie=admin_cookie)
        check(f"E{tag} FC02 离散输入探测", body.get("ok") is True and len(body.get("values", [])) == 4, body)
    if port_open(1503):
        code, body = req("POST", "/api/discovery/units",
                         {"ip": "127.0.0.1", "port": 1503, "framing": "rtu", "from": 1, "to": 8}, cookie=admin_cookie)
        check("E-B 从站扫描命中[1,3]", body.get("alive") == [1, 3], body)
        code, body = req("POST", "/api/discovery/units",
                         {"ip": "127.0.0.1", "port": 1503, "framing": "rtu", "from": 1, "to": 300}, cookie=admin_cookie)
        check("E-B 从站范围 300 被拒", body.get("ok") is False, body)

    # ============ F. 开放接口 ============
    code, body = req("GET", "/open/v1/TEST-01/status", key="selftest-key-123")
    check("F1 X-Api-Key 请求头 200", code == 200, (code, body))
    code, body = req("GET", "/open/v1/TEST-01/status?key=selftest-key-123")
    check("F1b query 参数 200", code == 200, (code, body))
    code, body = req("GET", "/open/v1/TEST-01/status")
    check("F2 无 Key 被拒(401)", code == 401, code)
    code, body = req("GET", "/open/v1/TEST-01/status", key="wrong-key")
    check("F3 错 Key 被拒(401)", code == 401, code)

    # ============ G. 页面 ============
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req_, fp, code_, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    # 2026-10-29 旧版跳转壳（/login.html 等）已删除：/ 由后端转发到 /app/ SPA 壳
    for path, expect in [("/", 200), ("/screen.html", 200), ("/fault.html", 200)]:
        code, _ = req("GET", path)
        check(f"G 页面 {path} = {expect}", code == expect, code)
    # /discover.html 是设备发现的书签入口（跳转壳 → /app/#/discovery），
    # 当天误删过一次导致用户入口丢失，恢复后纳入断言防止再被清掉
    code, body = req("GET", "/discover.html")
    ok = code == 200 and isinstance(body, str) and "/app/#/discovery" in body
    check("G /discover.html 书签入口跳转发现页", ok, code)
    # /app/ 是主界面。SPA 外壳必须匿名可取（登录页在 /app/#/login，由前端路由守卫兜底，
    # 2026-10-28 曾因外壳受保护造成匿名 302 死循环），把"外壳公开 ≠ 数据公开"钉成回归项
    code, body = req("GET", "/app/", cookie=admin_cookie)
    ok = code == 200 and isinstance(body, str) and 'id="app"' in body
    check("G /app/ 登录后 200 且为 SPA 壳", ok, code)
    try:
        resp = opener.open(urllib.request.Request(BASE + "/app/"), timeout=10)
        code_anon_app = resp.status
        anon_app_body = resp.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        code_anon_app = e.code
        anon_app_body = ""
    check("G /app/ 匿名可取(200，登录页在壳里)",
          code_anon_app == 200 and 'id="app"' in anon_app_body, code_anon_app)
    # 匿名拿不到任何业务数据（外壳公开 ≠ 数据公开）
    code, _ = req("GET", "/api/erp/stock/summary")
    check("G 匿名读库存接口被拒(401)", code == 401, code)
    # 2026-10-29 旧版跳转壳（/index.html 等）已删除，/ 由后端转发到 /app/ SPA 壳
    code, body = req("GET", "/", cookie=admin_cookie)
    ok = code == 200 and isinstance(body, str) and 'id="app"' in body
    check("G / 转发到 /app/ SPA 壳", ok, code)
    # 旧书签 /index.html、/login.html 转发到 SPA 壳（WebConfig）。
    # 关键是「已登录」这一支：匿名有安全层 302 兜底，已登录没人兜——
    # 曾因壳页删除而 404（discover.html 同类问题的后续修复）
    for path in ("/index.html", "/login.html"):
        code, body = req("GET", path, cookie=admin_cookie)
        ok = code == 200 and isinstance(body, str) and 'id="app"' in body
        check(f"G 已登录 {path} 转发 SPA 壳(200)", ok, code)
    # h2-console：H2 已废弃，路径不存在；匿名访问被安全层重定向到登录页（302）
    try:
        resp = opener.open(urllib.request.Request(BASE + "/h2-console"), timeout=10)
        code302 = resp.status
    except urllib.error.HTTPError as e:
        code302 = e.code
    check("G 页面 /h2-console 无 H2 且被拦截(302)", code302 == 302, code302)

    # ============ H. 系统设置校验 ============
    code, before = req("GET", "/api/system/settings", cookie=admin_cookie)
    code, body = req("POST", "/api/system/settings", {"alarm.storm-threshold": "abc"}, cookie=admin_cookie)
    check("H1 非法设置值被拒", code >= 400 or (isinstance(body, dict) and body.get("ok") is False), (code, body))
    code, body = req("POST", "/api/system/settings", {"alarm.storm-threshold": "50"}, cookie=admin_cookie)
    check("H2 合法设置值写入", code == 200, code)

    # ============ I. 操作审计（DH-24） ============
    # 审计要守住的四条：①改了就留痕 ②只增不改不删 ③非管理员看不到 ④搜得到
    # 触发器用设备模板：名字唯一，审计里一定搜得到，不依赖任何已有数据。
    code, body = req("GET", "/api/audit")
    check("I1 匿名读审计被拒(401)", code == 401, code)
    code, body = req("GET", "/api/audit", cookie=leader_cookie)
    check("I2 班组长读审计被拒(403)", code == 403, code)
    code, body = req("GET", "/api/audit", cookie=admin_cookie)
    check("I3 管理员读审计(200)", code == 200 and body.get("ok") is True, code)
    code, body = req("POST", "/api/device-templates", {
        "name": "SELFTEST-AUDIT-PROBE", "protocol": "modbus-tcp", "pointsJson":
        "[{\"key\":\"t\",\"name\":\"测试\",\"address\":0,\"functionCode\":3,"
        "\"dataType\":\"u16\",\"role\":\"value\"}]"
    }, cookie=admin_cookie)
    probe_id = (body.get("item") or {}).get("id")
    check("I4 写操作成功", body.get("ok") is True and probe_id, body)
    code, body = req("GET", "/api/audit?keyword=SELFTEST-AUDIT-PROBE&limit=50", cookie=admin_cookie)
    rows = body.get("rows") or []
    hit = [r for r in rows if "AUDIT-PROBE" in (r.get("targetName") or "") + (r.get("detail") or "")]
    check("I5 这次改动能搜到", len(hit) >= 1, (len(hit), len(rows)))
    check("I6 审计带操作人与来源 IP",
          bool(hit) and bool(hit[0].get("operator")) and hit[0].get("ip") is not None,
          hit[0] if hit else "")
    # 中文关键词也要能搜——客户来问「谁改过电价」，搜「电价」两个字就该出来
    code, body = req("GET", "/api/audit?keyword=" + urllib.parse.quote("审计") + "&limit=50",
                     cookie=admin_cookie)
    check("I7 中文关键词可搜", code == 200, code)
    # 只增不改不删：查询之外不该有任何写审计的入口
    code, body = req("POST", "/api/audit", {"detail": "伪造"}, cookie=admin_cookie)
    check("I8 审计表不可写入", code in (404, 405), code)
    code, body = req("DELETE", "/api/audit/1", cookie=admin_cookie)
    check("I9 审计表不可删除", code in (404, 405), code)
    # 分类过滤
    code, body = req("GET", "/api/audit?category=DEVICE&limit=50", cookie=admin_cookie)
    drows = body.get("rows") or []
    check("I10 分类过滤只回该类", code == 200 and all(r.get("category") == "DEVICE" for r in drows),
          [r.get("category") for r in drows[:5]])
    if probe_id:
        req("DELETE", f"/api/device-templates/{probe_id}", cookie=admin_cookie)

    # ============ J. 告警限值分级（DH-14） ============
    # 核心承诺：一条规则配多级限值后，同一设备同一测点**最多只有一条未关闭告警**，
    # 且等级 = 按命中的最高档。
    # 断言不用另读一次温度（sim 温度是随机游走的，再读一次就可能跨档，测试会飘），
    # 而是用告警自己记下来的 value 反推期望档——同一份数据，无竞态。
    RANK = {"MINOR": 1, "MAJOR": 2, "CRITICAL": 3}

    def top_tier(value, tiers):
        """命中里等级最高的那一档（只按 > 判定，本组规则用的就是 > ）"""
        best = None
        for t in tiers or []:
            th = t.get("threshold")
            if th is None or value is None:
                continue
            if value > float(th) and (best is None
                                      or RANK.get(t.get("level"), 0) > RANK.get(best.get("level"), 0)):
                best = t
        return best

    JTIERS = [{"level": "MINOR", "threshold": 35},
              {"level": "MAJOR", "threshold": 50},
              {"level": "CRITICAL", "threshold": 65}]
    jrule = {"name": "SELFTEST-分级", "type": "THRESHOLD", "deviceCodes": "CNC-01",
             "pointKey": "temperature", "op": ">", "forSeconds": 0, "recoverSeconds": 0,
             "cooldownSeconds": 0, "enabled": True, "tiers": JTIERS}
    code, body = req("POST", "/api/alarms/rules", jrule, cookie=admin_cookie)
    check("J1 分级规则可保存", body.get("ok") is True, body)
    jrule_id = (body.get("rule") or {}).get("id")
    code, body = req("GET", "/api/alarms/rules", cookie=admin_cookie)
    items = body.get("items") or []
    jback = next((it.get("rule") for it in items if (it.get("rule") or {}).get("id") == jrule_id), None)
    check("J2 档位读回一致", jback is not None
          and len(jback.get("tiers") or []) == 3
          and {(t.get("level"), t.get("threshold")) for t in jback.get("tiers") or []}
          == {(t["level"], t["threshold"]) for t in JTIERS},
          (jback or {}).get("tiers"))
    # 非法配置必须被拒——配反了的档位存进去就是「该报的级别不对」，比不报更危险
    code, body = req("POST", "/api/alarms/rules", dict(jrule, name="SELFTEST-缺阈值",
                                                        tiers=[{"level": "MINOR"}, {"level": "MAJOR", "threshold": 50}]),
                     cookie=admin_cookie)
    check("J3 缺阈值被拒", body.get("ok") is False, body)
    code, body = req("POST", "/api/alarms/rules", dict(jrule, name="SELFTEST-同档重复",
                                                        tiers=[{"level": "MINOR", "threshold": 35},
                                                               {"level": "MINOR", "threshold": 40}]),
                     cookie=admin_cookie)
    check("J4 同档重复被拒", body.get("ok") is False, body)
    code, body = req("POST", "/api/alarms/rules", dict(jrule, name="SELFTEST-不单调",
                                                        tiers=[{"level": "MINOR", "threshold": 90},
                                                               {"level": "CRITICAL", "threshold": 65}]),
                     cookie=admin_cookie)
    check("J5 阈值不单调被拒", body.get("ok") is False, body)
    code, body = req("POST", "/api/alarms/rules", dict(jrule, name="SELFTEST-等值多档", op="==",
                                                        tiers=[{"level": "MINOR", "threshold": 35},
                                                               {"level": "CRITICAL", "threshold": 65}]),
                     cookie=admin_cookie)
    check("J6 等值型配多档被拒", body.get("ok") is False, body)
    # 老规则（单值、不带 tiers）必须照常工作，存量客户机上都是这种格式
    code, body = req("POST", "/api/alarms/rules", {
        "name": "SELFTEST-老规则", "type": "THRESHOLD", "deviceCodes": "CNC-01",
        "pointKey": "temperature", "op": ">", "threshold": 70, "forSeconds": 0,
        "recoverSeconds": 0, "cooldownSeconds": 0, "level": "MAJOR", "enabled": False,
    }, cookie=admin_cookie)
    check("J7 老规则（单值）可保存", body.get("ok") is True, body)
    legacy_id = (body.get("rule") or {}).get("id")
    code, body = req("GET", "/api/alarms/rules", cookie=admin_cookie)
    lback = next((it.get("rule") for it in (body.get("items") or [])
                  if (it.get("rule") or {}).get("id") == legacy_id), None)
    check("J8 老规则读回不变", lback is not None and not (lback.get("tiers") or [])
          and lback.get("threshold") == 70 and lback.get("level") == "MAJOR",
          {"tiers": (lback or {}).get("tiers"), "threshold": (lback or {}).get("threshold")})
    # 端到端：等引擎评估出告警，验「只有一条」+「等级是最高命中档」
    jevents = []
    for _ in range(24):
        code, body = req("GET", "/api/alarms?state=ALL&level=ALL&range=1d&page=1&size=200",
                         cookie=admin_cookie)
        rows = body.get("items") or []
        jevents = [e for e in rows if e.get("ruleId") == jrule_id
                   and e.get("state") in ("ACTIVE", "ACKED")]
        if jevents:
            break
        _t.sleep(1.5)
    check("J9 分级规则触发后只有一条未关闭告警", len(jevents) == 1,
          [(e.get("id"), e.get("level"), e.get("value")) for e in jevents])
    if jevents:
        ev = jevents[0]
        want = top_tier(ev.get("value"), JTIERS)
        check("J10 告警等级=按其取值算出的最高命中档",
              want is not None and ev.get("level") == want.get("level"),
              (ev.get("value"), ev.get("level"), (want or {}).get("level")))
        check("J11 告警阈值=该档阈值", want is not None and ev.get("threshold") == want.get("threshold"),
              (ev.get("threshold"), (want or {}).get("threshold")))
    else:
        check("J10 告警等级=按其取值算出的最高命中档", False, "没有触发告警")
        check("J11 告警阈值=该档阈值", False, "没有触发告警")
    if jrule_id:
        req("DELETE", f"/api/alarms/rules/{jrule_id}", cookie=admin_cookie)
    if legacy_id:
        req("DELETE", f"/api/alarms/rules/{legacy_id}", cookie=admin_cookie)

    # ============ K. 需量告警生命周期（DH-29） ============
    # 需量告警是**系统告警**：规则不在 alarm.rules 里（配置在 energy.demand）。
    # 以前它在重启时被当成「规则被删了」自动关掉，理由还写着「规则已删除或停用」——
    # 规则根本没被删过，那是句假话，追溯时会把客户带偏。
    # 这里锁住两件事：①它是系统告警 ②关掉它的理由必须说真话。
    def demand_alarms():
        code, b = req("GET", "/api/alarms?state=ALL&level=ALL&range=30d&page=1&size=200",
                      cookie=admin_cookie)
        return [e for e in (b.get("items") or []) if e.get("ruleId") == "r-demand-over"]

    code, body = req("GET", "/api/energy/demand/config", cookie=admin_cookie)
    dcfg = (body.get("config") or {}) if body.get("ok") else None
    check("K1 能读到需量配置", isinstance(dcfg, dict) and dcfg.get("contractDemandKw"), body)
    code, body = req("GET", "/api/alarms/rules", cookie=admin_cookie)
    rule_ids = {(it.get("rule") or {}).get("id") for it in (body.get("items") or [])}
    check("K2 需量告警不在规则表里（它是系统告警）", "r-demand-over" not in rule_ids,
          sorted(x for x in rule_ids if x))
    if isinstance(dcfg, dict) and dcfg.get("contractDemandKw"):
        opens = [e for e in demand_alarms() if e.get("state") in ("ACTIVE", "ACKED")]
        if not opens:
            # 没有挂着的就造一条：合同需量调到 1 kW，工厂峰值必然超
            low = dict(dcfg)
            low["contractDemandKw"] = 1.0
            low["enabled"] = True
            req("POST", "/api/energy/demand/config", low, cookie=admin_cookie)
            # 要等够久：需量窗口由 tracker 每 60 秒生成、告警由 monitor 每 30 秒评估，
            # 全新库上要一分半钟才有数据。交付库是长期运行的（窗口早就有了），
            # 所以这个用例以前只在老库上跑得通——新装机的第一次跑就会挂。
            for _ in range(75):
                _t.sleep(2)
                opens = [e for e in demand_alarms() if e.get("state") in ("ACTIVE", "ACKED")]
                if opens:
                    break
        check("K3 有一条未关闭的需量告警", len(opens) >= 1,
              [(e.get("id"), e.get("state")) for e in demand_alarms()[:3]])
        # 关掉需量监控 → 这条告警必须被收掉，而且理由得是真话
        off = dict(dcfg)
        off["enabled"] = False
        req("POST", "/api/energy/demand/config", off, cookie=admin_cookie)
        cleared = []
        # 告警每 30 秒评估一次，等两分钟足够它反应
        for _ in range(75):
            _t.sleep(2)
            # 两种理由都算过：合同需量被临时调到 1kW 后，实时功率是有波动的，
            # 恰好落回范围内的话系统会先自动收掉（理由「需量已回落到合同范围内」）——
            # 那是真话，不该判失败。要紧的是「关掉监控后告警确实被收掉了」。
            cleared = [e for e in demand_alarms()
                       if e.get("state") == "CLEARED"
                       and e.get("clearReason") in ("需量监控已关闭", "需量已回落到合同范围内")]
            if cleared:
                break
        check("K4 关掉需量监控后告警被收掉（理由是真话即可）", len(cleared) >= 1,
              [(e.get("id"), e.get("clearReason")) for e in demand_alarms()[:3]])
        check("K5 需量告警没有再被写成「规则已删除或停用」（那是假话）",
              not [e for e in demand_alarms() if e.get("clearReason") == "规则已删除或停用"],
              [(e.get("id"), e.get("clearReason")) for e in demand_alarms()[:5]])
        # 还原配置
        req("POST", "/api/energy/demand/config", dcfg, cookie=admin_cookie)

    # ============ L. OEE 完整版（DH-15） ============
    # 核心原则：**算不出来就说算不出来**。缺理想节拍、缺不良数数据 →
    # 对应因子给 null，OEE 也给 null，并说明缺什么。
    # 绝不能按 0 或按 100% 处理——那等于编一个 OEE 给客户，拿去做改善决策比没有更糟。
    code, body = req("GET", "/api/reports/daily", cookie=admin_cookie)
    oee = body.get("oee") or {}
    check("L1 日报带 OEE 区块（三因子 + 缺失说明）", code == 200
          and "availability" in oee and "performance" in oee
          and "quality" in oee and "notes" in oee, list(oee.keys()))
    devs = body.get("devices") or []
    rows = [d for d in devs if d.get("oee")]
    check("L2 设备明细每一行都带 OEE", len(rows) == len(devs) and len(devs) >= 1,
          "%d 台设备 / %d 台带 OEE" % (len(devs), len(rows)))
    # OEE 必须是 0~100 的百分数：三个因子都是百分数，相乘要除两次 100。
    # 早先实现写成 a×p×q/100，OEE 一律大了 100 倍（页面上显示 4034.0% 这种），
    # 而单测断言的是同一个错公式，等于把 bug 焊死了。这里从接口层再钉一道。
    def _oee_vals():
        vs = []
        o = body.get("oee") or {}
        if o.get("oee") is not None:
            vs.append(("全厂", o.get("oee")))
        for d in rows:
            v = (d.get("oee") or {}).get("oee")
            if v is not None:
                vs.append((d.get("code") or d.get("deviceCode"), v))
        return vs
    bad_oee = [(who, v) for who, v in _oee_vals() if v is not None and not (0 <= float(v) <= 100)]
    check("L2b OEE 在 0~100 之间（早先公式错了 100 倍）", not bad_oee, bad_oee[:5])
    no_cycle = [d for d in rows if (d.get("oee") or {}).get("idealCycleSec") is None]
    if no_cycle:
        o = no_cycle[0].get("oee") or {}
        check("L3 没配理想节拍 → 性能是 null 且说清缺什么",
              o.get("performance") is None
              and any("理想节拍" in n for n in (o.get("notes") or [])),
              (o.get("performance"), o.get("notes")))
    no_defect = [d for d in rows if not (d.get("oee") or {}).get("hasDefectData")]
    if no_defect:
        o = no_defect[0].get("oee") or {}
        check("L4 没有不良数数据 → 良率是 null（不能当成零不良）",
              o.get("quality") is None
              and any("不良数" in n for n in (o.get("notes") or [])),
              (o.get("quality"), o.get("notes")))
    # 补录不良数：录了 → 读回来是这个数；留空 → 存的是 null（跟「录了 0」必须分得开）
    l_dev = (rows[0].get("code") if rows else "CNC-01")
    l_today = date.today().isoformat()
    code, body = req("POST", "/api/manual", {
        "deviceCode": l_dev, "fromDay": l_today, "toDay": l_today,
        "output": 1, "defect": 0, "reason": "SELFTEST-OEE"
    }, cookie=admin_cookie)
    check("L5 补录能录不良数", code == 200 and body.get("ok") is True, body)
    code, body = req("GET", f"/api/manual?from={l_today}&to={l_today}", cookie=admin_cookie)
    mrows = [r for r in (body.get("rows") or [])
             if r.get("deviceCode") == l_dev and r.get("reason") == "SELFTEST-OEE"]
    check("L6 录了 0 件不良，读回来是 0（是「确实是零不良」）",
          len(mrows) >= 1 and mrows[0].get("defect") == 0,
          [r.get("defect") for r in mrows])
    l_id = mrows[0].get("id") if mrows else None
    if l_id:
        req("POST", f"/api/manual/{l_id}/revoke", {}, cookie=admin_cookie)
    code, body = req("POST", "/api/manual", {
        "deviceCode": l_dev, "fromDay": l_today, "toDay": l_today,
        "output": 1, "reason": "SELFTEST-OEE-空"
    }, cookie=admin_cookie)
    code, body = req("GET", f"/api/manual?from={l_today}&to={l_today}", cookie=admin_cookie)
    mrows2 = [r for r in (body.get("rows") or [])
              if r.get("deviceCode") == l_dev and r.get("reason") == "SELFTEST-OEE-空"]
    check("L7 不良数留空 → 存的是 null（数据缺失，不是零不良）",
          len(mrows2) >= 1 and mrows2[0].get("defect") is None,
          [r.get("defect") for r in mrows2])
    for r in mrows2:
        req("POST", f"/api/manual/{r.get('id')}/revoke", {}, cookie=admin_cookie)

    # ============ M. 越权防线（2026-11-03 补）============
    # 起因：安全审计实测，「销售」账号能直接 curl 下面这些接口：
    # 清空告警/能耗/报表/需量历史、读写补录（改产量口径）、确认告警、读设备点表。
    # 前端把按钮藏了不算权限——SecurityConfig 自己的注释就写着这句。
    # 这组用例把「后端真的挡住了」钉死，以后谁改 SecurityConfig 忘了收口，这里会红。
    code, body = req("POST", "/api/users", {"username": "selftest_sales", "displayName": "自测销售",
                                            "password": "Sales#2026", "role": "SALES"}, cookie=admin_cookie)
    check("M1 创建销售用户", code == 200, (code, body))
    code, body, sales_cookie = login("selftest_sales", "Sales#2026")
    check("M2 销售可登录", code == 200, code)
    # ① 清历史四件套：越权等于删客户半年数据
    for label, path in [("告警", "/api/alarms/purge"), ("能耗", "/api/energy/purge"),
                        ("报表", "/api/reports/purge"), ("需量", "/api/energy/demand/purge")]:
        code, body = req("POST", path, {"confirm": True}, cookie=sales_cookie)
        check("M3 销售清%s历史被拒(403)" % label, code == 403, (code, body))
    # ② 补录：能改产量/电量口径，等于改全厂报表数字；读出来还带电费成本
    code, body = req("GET", "/api/manual", cookie=sales_cookie)
    check("M4 销售读补录被拒(403)", code == 403, (code, body))
    code, body = req("POST", "/api/manual", {"deviceCode": "CNC-01", "fromDay": date.today().isoformat(),
                                             "toDay": date.today().isoformat(), "output": 9999,
                                             "reason": "SELFTEST-越权"}, cookie=sales_cookie)
    check("M5 销售写补录被拒(403)", code == 403, (code, body))
    # ③ 确认告警：班组长本来就要能确认，销售不能（否则审计里留下销售的假线索）
    code, body = req("POST", "/api/alarms/999999/ack", {}, cookie=sales_cookie)
    check("M6 销售确认告警被拒(403)", code == 403, (code, body))
    code, body = req("POST", "/api/alarms/ack-all", {}, cookie=sales_cookie)
    check("M7 销售批量确认告警被拒(403)", code == 403, (code, body))
    code, body = req("POST", "/api/alarms/999999/ack", {}, cookie=leader_cookie)
    check("M8 班组长仍可确认告警（别收得过紧）", code != 403, (code, body))
    # ④ 设备模板点表：含寄存器地址、协议与端口，等于现场设备拓扑
    code, body = req("GET", "/api/device-templates", cookie=sales_cookie)
    check("M9 销售读设备模板点表被拒(403)", code == 403, (code, body))
    # 对照组：销售该有的能力不能被误伤
    code, body = req("GET", "/api/erp/sales-query/stock?q=&page=1&size=1", cookie=sales_cookie)
    check("M10 销售仍可读销售查询(200)", code == 200, (code, str(body)[:100]))
    code, body = req("GET", "/api/erp/sales-query/stock?q=&page=1&size=1", cookie=leader_cookie)
    check("M11 班组长仍可读销售查询(200)", code == 200, (code, str(body)[:100]))
    # 管理员没被误收口：purge 不带 confirm 时是安全的空操作（只留一条审计）
    code, body = req("POST", "/api/alarms/purge", {}, cookie=admin_cookie)
    check("M12 管理员清历史没被误拦（未确认应返回 ok:false）",
          code == 200 and body.get("ok") is False, (code, body))

    # ============ N. BOM 与生产工单（2026-11-03 补，DH-70/71）============
    # 起因：在此之前 ERP 只有「设备产出 → 成品入库」，原料那一侧一分不动。
    # 客户买 100 吨钢、做完 80 个零件卖掉，仓库里钢还是 100 吨——
    # 进销存「期初+入-出=期末」算术上永远对，但那是单边账。
    # 这组用例把「BOM 展开 → 领料扣库存 → 库存不足拦截」钉死。
    ST_CODE_M, ST_CODE_P, ST_WH = "ST-MAT", "ST-PROD", "ST-BOM-WH"

    code, skus = req("GET", "/api/erp/skus?q=" + ST_CODE_M, cookie=admin_cookie)
    st_mat = next((x for x in (skus or []) if x.get("code") == ST_CODE_M), None)
    if not st_mat:
        req("POST", "/api/erp/skus", {"code": ST_CODE_M, "name": "自测材料", "unit": "kg",
                                      "category": "原材料", "purchasePrice": 10}, cookie=admin_cookie)
        code, skus = req("GET", "/api/erp/skus?q=" + ST_CODE_M, cookie=admin_cookie)
        st_mat = next((x for x in (skus or []) if x.get("code") == ST_CODE_M), None)
    code, skus = req("GET", "/api/erp/skus?q=" + ST_CODE_P, cookie=admin_cookie)
    st_prod = next((x for x in (skus or []) if x.get("code") == ST_CODE_P), None)
    if not st_prod:
        req("POST", "/api/erp/skus", {"code": ST_CODE_P, "name": "自测成品", "unit": "件",
                                      "category": "成品", "purchasePrice": 99}, cookie=admin_cookie)
        code, skus = req("GET", "/api/erp/skus?q=" + ST_CODE_P, cookie=admin_cookie)
        st_prod = next((x for x in (skus or []) if x.get("code") == ST_CODE_P), None)
    check("N1 自测商品就绪", bool(st_mat and st_prod), (st_mat, st_prod))
    mat_id, prod_id = st_mat["id"], st_prod["id"]

    code, whs = req("GET", "/api/erp/warehouses?q=" + ST_WH, cookie=admin_cookie)
    wh = next((x for x in (whs or []) if x.get("code") == ST_WH), None)
    if not wh:
        req("POST", "/api/erp/warehouses", {"code": ST_WH, "name": "自测仓"}, cookie=admin_cookie)
        code, whs = req("GET", "/api/erp/warehouses?q=" + ST_WH, cookie=admin_cookie)
        wh = next((x for x in (whs or []) if x.get("code") == ST_WH), None)
    check("N2 自测仓库就绪", bool(wh), wh)
    wh_id = wh["id"] if wh else None

    # ① 没配 BOM 就下达：必须拒绝，且说清该去配什么。
    #    允许"下达但没用料"的工单存在，领料时才发现没料可领，现场只能绕开系统手工出库。
    code, body = req("POST", "/api/erp/work-orders",
                     {"productSkuId": prod_id, "planQty": 5, "warehouseId": wh_id}, cookie=admin_cookie)
    no_wo = body.get("orderNo") if isinstance(body, dict) else None
    code, body = req("POST", "/api/erp/work-orders/%s/release" % no_wo, {}, cookie=admin_cookie)
    check("N3 没配 BOM 不能下达（且提示去配物料清单）",
          isinstance(body, dict) and body.get("ok") is False and "BOM" in str(body.get("message")),
          (code, body))

    # ② 配 BOM：成品 = 材料 x 2 件，损耗 10%
    code, body = req("POST", "/api/erp/bom?productSkuId=%d" % prod_id,
                     {"lines": [{"materialSkuId": mat_id, "qty": 2, "lossRate": 10}]}, cookie=admin_cookie)
    check("N4 保存 BOM", code == 200 and body.get("ok") is True, (code, body))
    code, body = req("POST", "/api/erp/bom?productSkuId=%d" % prod_id,
                     {"lines": [{"materialSkuId": prod_id, "qty": 1}]}, cookie=admin_cookie)
    check("N5 材料不能是产品自己（应拒绝）", body.get("ok") is False, (code, body))

    # ③ 下达：用料 = 5 x 2 x 1.1 = 11
    code, body = req("POST", "/api/erp/work-orders/%s/release" % no_wo, {}, cookie=admin_cookie)
    check("N6 工单下达成功", body.get("ok") is True, (code, body))
    code, body = req("GET", "/api/erp/work-orders/%s" % no_wo, cookie=admin_cookie)
    mats = body.get("materials") or [] if isinstance(body, dict) else []
    need = float(mats[0]["needQty"]) if mats else -1
    check("N7 用料按「计划 x 用量 x 损耗」展开（5x2x1.1=11）", abs(need - 11) < 0.01, need)

    # ④ 库存不足：仓里 0 却要领 11，必须拒绝并说清差多少
    code, body = req("POST", "/api/erp/work-orders/%s/issue" % no_wo,
                     {"warehouseId": wh_id, "lines": [{"skuId": mat_id, "qty": 11}]}, cookie=admin_cookie)
    check("N8 库存不足时领料被拒（整单失败，不写半张单）",
          body.get("ok") is False and "库存不足" in str(body.get("message")), (code, body))

    # ⑤ 补料 50 后再领：库存必须真的减
    req("POST", "/api/erp/stock/bills",
        {"type": "OTHER_IN", "warehouseId": wh_id, "remark": "SELFTEST",
         "lines": [{"skuId": mat_id, "qty": 50}]}, cookie=admin_cookie)
    code, body = req("POST", "/api/erp/work-orders/%s/auto-issue" % no_wo,
                     {"warehouseId": wh_id}, cookie=admin_cookie)
    check("N9 一键按缺口领料成功（生成生产领料单）",
          body.get("ok") is True and str(body.get("billNo", "")).startswith("SCLL"), (code, body))
    code, bal = req("GET", "/api/erp/stock/balances?whId=%d" % wh_id, cookie=admin_cookie)
    row = next((x for x in (bal or []) if x.get("skuId") == mat_id), None)
    left = float(row["qty"]) if row else None
    check("N10 领料后原料库存真的减少（50-11=39）", left is not None and abs(left - 39) < 0.01, left)

    # ⑥ 权限对照：班组长能开工（现场操作），但不能下达（下达会展开用料，属计划动作）
    code, body = req("POST", "/api/erp/work-orders/%s/start" % no_wo, {}, cookie=leader_cookie)
    check("N11 班组长可以开工（现场操作，别收得过紧）", code != 403, (code, body))
    code, body = req("POST", "/api/erp/work-orders",
                     {"productSkuId": prod_id, "planQty": 1}, cookie=leader_cookie)
    check("N12 班组长不能建工单(403)", code == 403, (code, body))

    # ============ 清理 ============
    # H2 往 storm-threshold 写了个 50 做校验测试，用完必须还原——
    # 不还原的话，客户机上这个批量告警合并阈值就比 yml 默认的 8 宽松了 6 倍多，
    # 交换机一挂全厂离线时本来该合并成一条的告警会被逐条刷到群里。
    code, body = req("DELETE", "/api/system/settings/alarm.storm-threshold", cookie=admin_cookie)
    check("Z0 批量告警阈值恢复默认", code in (200, 204), code)
    # I4 的审计探针模板：正常路径已内联删除，这里按名字兜底清一次
    code, tpls = req("GET", "/api/device-templates", cookie=admin_cookie)
    for t in ((tpls.get("items") if isinstance(tpls, dict) else tpls) or []):
        if t.get("name") == "SELFTEST-AUDIT-PROBE":
            req("DELETE", f"/api/device-templates/{t.get('id')}", cookie=admin_cookie)
    # J 组留的告警规则：正常路径已内联删除，这里按名字兜底清一次
    # （留着会一直触发告警，把客户的告警列表搞脏）
    code, rl = req("GET", "/api/alarms/rules", cookie=admin_cookie)
    for it in (rl.get("items") or []):
        r = it.get("rule") or {}
        if str(r.get("name") or "").startswith("SELFTEST-"):
            req("DELETE", f"/api/alarms/rules/{r.get('id')}", cookie=admin_cookie)
    tid = ids.get("TEST-01")
    if tid:
        code, body = req("DELETE", f"/api/devices/{tid}", cookie=admin_cookie)
        check("Z1 删除测试设备", code in (200, 204), code)
    for tcode in ("TEST-02", "HACK"):
        code, devs3 = req("GET", "/api/devices", cookie=admin_cookie)
        for d in (devs3 if isinstance(devs3, list) else []):
            if d.get("code") == tcode:
                req("DELETE", f"/api/devices/{d.get('id')}", cookie=admin_cookie)
    code, users = req("GET", "/api/users", cookie=admin_cookie)
    if isinstance(users, list):
        for u in users:
            if u.get("username") in ("selftest_leader", "selftest_sales"):
                code, body = req("DELETE", f"/api/users/{u.get('id')}", cookie=admin_cookie)
                check("Z2 删除测试用户", code == 200, code)
    code, body = req("POST", "/api/devices/reload", {}, cookie=admin_cookie)

    fails = [r for r in results if not r[1]]
    print("\n======== 结果: %d 过 / %d 挂 ========" % (len(results) - len(fails), len(fails)))
    for name, _, detail in fails:
        print("  FAIL:", name, str(detail)[:120])
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
