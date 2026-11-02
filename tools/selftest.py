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
            if u.get("username") == "selftest_leader":
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
