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
    code, body = req("POST", "/api/discovery/scan", {"prefixes": "abc"}, cookie=admin_cookie)
    check("E1 非法网段被拒", code == 200 and body.get("ok") is False, body)
    code, body = req("POST", "/api/discovery/scan", {"prefixes": "999.168.1"}, cookie=admin_cookie)
    check("E2 超范围数字被拒", code == 200 and body.get("ok") is False, body)
    code, body = req("POST", "/api/discovery/probe", {"ip": "127.0.0.1", "port": 1502, "unitId": 1,
                                                      "fc": 6, "framing": "tcp"}, cookie=admin_cookie)
    check("E3 写功能码被拒", body.get("ok") is False and "只" in str(body.get("error")), body)
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
    for path, expect in [("/login.html", 200), ("/screen.html", 200), ("/fault.html", 200)]:
        code, _ = req("GET", path)
        check(f"G 页面 {path} = {expect}", code == expect, code)
    # h2-console：H2 已废弃，路径不存在；匿名访问被安全层重定向到登录页（302）
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req_, fp, code_, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
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

    # ============ 清理 ============
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
