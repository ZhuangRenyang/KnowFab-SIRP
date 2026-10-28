# -*- coding: utf-8 -*-
"""全功能冒烟：把当前所有模块的读接口 + 关键写路径跑一遍，输出"哪一页/哪个接口坏了"。

用法：
  python tools/smoke-all.py                        # 默认 http://127.0.0.1:8080
  python tools/smoke-all.py --base http://10.0.0.5:8080 --pass '管理员密码'

和 tools/selftest.py 的分工：
  · selftest.py 管"底层契约与权限"（40 条断言，含 CRUD/扫描/鉴权，自建自删）
  · smoke-all.py 管"每个功能页面背后的接口都活着、数据形状对"——每做完一个功能，
    先 seed-demo.py 铺数据，再跑这个，确认没有把别处改坏。

退出码：0=全过，1=有 FAIL。SKIP 不算失败（例如没有设备时跳过历史曲线）。
"""
import argparse
import datetime
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import http.cookiejar

BASE = "http://127.0.0.1:8080"
USER = "admin"
PASS = "123456"
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

results = []   # (section, name, status, detail)


def req(method, path, body=None, timeout=25, anon=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        r.add_header("Content-Type", "application/json")
    # anon=True 时用不带 Cookie 的 opener，专门验证"匿名拿不到数据"
    o = urllib.request.build_opener() if anon else opener
    try:
        with o.open(r, timeout=timeout) as resp:
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


def check(section, name, cond, detail="", skip=False):
    if skip:
        results.append((section, name, "SKIP", detail))
    else:
        results.append((section, name, "PASS" if cond else "FAIL", detail))
    return cond


def has(obj, *keys):
    if not isinstance(obj, dict):
        return False
    return all(k in obj for k in keys)


def login():
    st, r = req("POST", "/api/auth/login", {"username": USER, "password": PASS})
    if st != 200 or not r.get("ok"):
        print("登录失败：%s %s" % (st, r))
        sys.exit(1)
    return r


def today():
    return datetime.date.today().isoformat()


# ---------------------------------------------------------------- 各组

def sec_pages():
    s = "页面"
    st, body = req("GET", "/app/")
    check(s, "/app/ 可打开", st == 200 and isinstance(body, str) and 'id="app"' in body, st)
    for path in ("/login.html", "/screen.html", "/fault.html", "/"):
        st, _ = req("GET", path)
        check(s, "%s 可打开" % path, st in (200, 302), st)


def sec_screen():
    s = "大屏接口"
    st, r = req("GET", "/api/screen/snapshot")
    check(s, "车间大屏快照", st == 200 and isinstance(r, dict), st)
    st, r = req("GET", "/api/screen/ping")
    check(s, "大屏心跳", st == 200, st)


def sec_devices():
    s = "设备"
    st, devs = req("GET", "/api/devices")
    ok = st == 200 and isinstance(devs, list) and len(devs) > 0
    check(s, "设备列表", ok, "%s 台" % (len(devs) if isinstance(devs, list) else "?"))
    if not ok:
        return
    code = devs[0].get("code")
    st, cfg = req("GET", "/api/devices/%s/config" % urllib.parse.quote(code))
    check(s, "设备配置（%s）" % code, st == 200 and has(cfg, "code", "pointsJson"), st)
    try:
        pts = json.loads((cfg or {}).get("pointsJson") or "[]")
    except (TypeError, ValueError):
        pts = []
    if pts:
        key = pts[0].get("key")
        st, h = req("GET", "/api/devices/%s/history?point=%s&limit=10" %
                    (urllib.parse.quote(code), urllib.parse.quote(key)))
        check(s, "点位历史（%s）" % key, st == 200, st)
    else:
        check(s, "点位历史", True, "设备没有点位", skip=True)


def sec_alarms():
    s = "报警"
    st, r = req("GET", "/api/alarms/summary")
    check(s, "报警汇总", st == 200 and has(r, "open"), st)
    st, r = req("GET", "/api/alarms?state=all&level=all&range=7d&q=&page=1&size=5")
    check(s, "报警记录分页", st == 200 and has(r, "items", "total"), st)
    st, r = req("GET", "/api/alarms/rules")
    check(s, "报警规则", st == 200 and has(r, "items"), st)
    st, r = req("GET", "/api/alarms/notify")
    check(s, "通知配置", st == 200 and has(r, "config"), st)


def sec_energy():
    s = "能耗"
    st, r = req("GET", "/api/energy/overview?days=7")
    check(s, "能耗总览", st == 200 and isinstance(r, dict) and r, st)
    st, r = req("GET", "/api/energy/devices?range=7d")
    check(s, "设备能耗排行", st == 200 and isinstance(r, list), st)
    st, r = req("GET", "/api/energy/demand")
    check(s, "需量分析", st == 200 and isinstance(r, dict) and r, st)
    st, r = req("GET", "/api/energy/tariff")
    check(s, "峰谷电价", st == 200 and has(r, "plan"), st)
    st, r = req("GET", "/api/energy/demand/config")
    check(s, "需量配置", st == 200 and has(r, "config"), st)


def sec_reports():
    s = "报表"
    st, r = req("GET", "/api/reports/daily?date=%s" % today())
    check(s, "生产日报", st == 200 and isinstance(r, dict), st)
    st, r = req("GET", "/api/reports/shifts")
    check(s, "班次方案", st == 200 and has(r, "plan"), st)


def sec_ai():
    s = "AI 早报"
    st, r = req("GET", "/api/ai/digest")
    check(s, "早报数据", st == 200 and isinstance(r, dict) and r, st)
    st, r = req("GET", "/api/ai/config")
    check(s, "AI 配置", st == 200 and has(r, "config"), st)


def sec_system():
    s = "系统"
    st, r = req("GET", "/api/system/version")
    check(s, "版本信息", st == 200 and has(r, "version"), st)
    st, r = req("GET", "/api/system/update/status")
    check(s, "升级状态", st == 200, st)
    st, r = req("GET", "/api/system/settings")
    check(s, "系统设置", st == 200 and isinstance(r, dict) and r, st)
    st, r = req("GET", "/api/system/migrations")
    check(s, "结构迁移", st == 200 and has(r, "schemaVersion"), st)
    st, r = req("GET", "/api/users")
    check(s, "用户列表", st == 200 and isinstance(r, list) and len(r) > 0, st)


def sec_erp_base():
    s = "ERP 基础资料"
    st, skus = req("GET", "/api/erp/skus")
    check(s, "商品列表", st == 200 and isinstance(skus, list) and len(skus) > 0,
          "%s 个" % (len(skus) if isinstance(skus, list) else "?"))
    st, whs = req("GET", "/api/erp/warehouses")
    check(s, "仓库列表", st == 200 and isinstance(whs, list) and len(whs) > 0,
          "%s 个" % (len(whs) if isinstance(whs, list) else "?"))
    st, ps = req("GET", "/api/erp/partners")
    check(s, "往来单位", st == 200 and isinstance(ps, list) and len(ps) > 0,
          "%s 个" % (len(ps) if isinstance(ps, list) else "?"))
    return skus or [], whs or []


def sec_purchase(skus, whs):
    s = "采购"
    st, r = req("GET", "/api/erp/purchase/stats")
    check(s, "采购 KPI", st == 200 and has(r, "monthAmount", "openCount"), st)
    st, r = req("GET", "/api/erp/purchase/orders?page=1&size=5")
    ok = st == 200 and has(r, "rows", "total")
    check(s, "采购订单列表", ok, "%s 单" % (r.get("total") if isinstance(r, dict) else "?"))
    st, r = req("GET", "/api/erp/purchase/open?limit=5")
    check(s, "待收货清单", st == 200 and isinstance(r, list), st)
    st, r = req("GET", "/api/erp/purchase/orders?page=1&size=1")
    if isinstance(r, dict) and r.get("rows"):
        no = r["rows"][0]["orderNo"]
        st, d = req("GET", "/api/erp/purchase/orders/%s" % urllib.parse.quote(no))
        check(s, "采购订单详情（%s）" % no,
              st == 200 and has(d, "order", "lines", "bills"), st)
    else:
        check(s, "采购订单详情", True, "还没有采购单，先跑 seed-demo.py", skip=True)


def sec_stock():
    s = "库存"
    st, r = req("GET", "/api/erp/stock/types")
    check(s, "单据类型", st == 200 and has(r, "all"), st)
    st, r = req("GET", "/api/erp/stock/summary")
    check(s, "库存 KPI", st == 200 and has(r, "totalQty", "alertCount"), st)
    st, bal = req("GET", "/api/erp/stock/balances")
    ok = st == 200 and isinstance(bal, list) and len(bal) > 0
    check(s, "库存余额", ok, "%s 行" % (len(bal) if isinstance(bal, list) else "?"))
    st, r = req("GET", "/api/erp/stock/alerts")
    check(s, "库存预警", st == 200 and isinstance(r, list), "%s 项" % (len(r) if isinstance(r, list) else "?"))
    st, r = req("GET", "/api/erp/stock/trend?days=14")
    check(s, "出入库趋势", st == 200 and isinstance(r, list) and len(r) == 14, len(r) if isinstance(r, list) else st)
    st, bills = req("GET", "/api/erp/stock/bills?page=1&size=5")
    ok = st == 200 and has(bills, "rows", "total")
    check(s, "单据列表", ok, "%s 张" % (bills.get("total") if isinstance(bills, dict) else "?"))
    if ok and bills.get("rows"):
        no = bills["rows"][0]["billNo"]
        st, d = req("GET", "/api/erp/stock/bills/%s" % urllib.parse.quote(no))
        check(s, "单据详情（%s）" % no, st == 200 and has(d, "bill", "lines"), st)
    if ok and bal:
        b = bal[0]
        st, r = req("GET", "/api/erp/stock/flow?skuId=%s&warehouseId=%s&limit=20" %
                    (b["skuId"], b["warehouseId"]))
        check(s, "库存流水（%s）" % b["code"], st == 200 and isinstance(r, list), st)


def sec_sales():
    s = "销售"
    st, r = req("GET", "/api/erp/sales/stats")
    check(s, "销售 KPI", st == 200 and has(r, "monthAmount", "openCount"), st)
    st, r = req("GET", "/api/erp/sales/orders?page=1&size=5")
    ok = st == 200 and has(r, "rows", "total")
    check(s, "销售订单列表", ok, "%s 单" % (r.get("total") if isinstance(r, dict) else "?"))
    st, r = req("GET", "/api/erp/sales/open?limit=5")
    check(s, "待发货清单", st == 200 and isinstance(r, list), st)
    st, r = req("GET", "/api/erp/sales/orders?page=1&size=1")
    if isinstance(r, dict) and r.get("rows"):
        no = r["rows"][0]["orderNo"]
        st, d = req("GET", "/api/erp/sales/orders/%s" % urllib.parse.quote(no))
        check(s, "销售订单详情（%s）" % no,
              st == 200 and has(d, "order", "lines", "bills")
              and "shippedAmount" in (d.get("order") or {}), st)
    else:
        check(s, "销售订单详情", True, "还没有销售单，先跑 seed-demo.py", skip=True)


def sec_write(skus, whs):
    """写路径冒烟：建单→取消、建单→作废，验证完顺手把数据清干净。"""
    s = "写路径"
    if not skus or not whs:
        check(s, "写路径", True, "缺主数据", skip=True)
        return
    sku_id = skus[0]["id"]
    wh_id = whs[0]["id"]
    sup = req("GET", "/api/erp/partners?type=SUPPLIER")[1]
    sup_id = sup[0]["id"] if sup else None
    cus = req("GET", "/api/erp/partners?type=CUSTOMER")[1]
    cus_id = cus[0]["id"] if cus else None

    # 写路径每天最多跑一次：冒烟单虽然会取消，但没必要每跑一次就留两条废单
    st, today_pos = req("GET", "/api/erp/purchase/orders?kw=%s&page=1&size=1"
                        % urllib.parse.quote("【冒烟】采购草稿"))
    row = (today_pos.get("rows") or [None])[0] if isinstance(today_pos, dict) else None
    same_day = bool(row) and datetime.date.fromtimestamp(row["orderDate"] / 1000) == datetime.date.today()
    if same_day:
        check(s, "采购建单 → 取消", True, "今天已验证过", skip=True)
        check(s, "销售建单 → 取消", True, "今天已验证过", skip=True)
        check(s, "其他入库 → 作废（库存回滚）", True, "今天已验证过", skip=True)
        return

    if sup_id:
        st, r = req("POST", "/api/erp/purchase/orders", {
            "supplierId": sup_id, "remark": "【冒烟】采购草稿",
            "lines": [{"skuId": sku_id, "qty": 1, "unitPrice": 1}]})
        no = r.get("orderNo") if isinstance(r, dict) else None
        ok = bool(no)
        if ok:
            st2, r2 = req("POST", "/api/erp/purchase/orders/%s/cancel" % no, {})
            ok = bool(r2.get("ok"))
        check(s, "采购建单 → 取消", ok, no or r)

    if cus_id:
        st, r = req("POST", "/api/erp/sales/orders", {
            "customerId": cus_id, "remark": "【冒烟】销售草稿",
            "lines": [{"skuId": sku_id, "qty": 1, "unitPrice": 1}]})
        no = r.get("orderNo") if isinstance(r, dict) else None
        ok = bool(no)
        if ok:
            st2, r2 = req("POST", "/api/erp/sales/orders/%s/cancel" % no, {})
            ok = bool(r2.get("ok"))
        check(s, "销售建单 → 取消", ok, no or r)

    st, r = req("POST", "/api/erp/stock/bills", {
        "type": "OTHER_IN", "warehouseId": wh_id, "remark": "【冒烟】入库并作废",
        "lines": [{"skuId": sku_id, "qty": 1}]})
    ok = isinstance(r, dict) and r.get("ok")
    no = r.get("billNo") if ok else None
    if ok:
        st2, r2 = req("POST", "/api/erp/stock/bills/%s/void" % urllib.parse.quote(no), {})
        ok = bool(r2.get("ok"))
    check(s, "其他入库 → 作废（库存回滚）", ok, no or r)


def sec_auth_negative():
    s = "权限"
    st, _ = req("GET", "/api/erp/stock/summary", anon=True)
    check(s, "匿名读库存被拒(401)", st == 401, st)
    st, _ = req("GET", "/api/erp/purchase/orders", anon=True)
    check(s, "匿名读采购被拒(401)", st == 401, st)
    st, _ = req("GET", "/api/devices", anon=True)
    check(s, "匿名读设备被拒(401)", st == 401, st)


def print_report():
    print("\n" + "=" * 78)
    cur = None
    for section, name, status, detail in results:
        if section != cur:
            cur = section
            print("\n【%s】" % section)
        mark = {"PASS": "✓", "FAIL": "✗", "SKIP": "-"}[status]
        print("  %s %-28s %s" % (mark, name, detail if detail != "" else ""))
    fails = [r for r in results if r[2] == "FAIL"]
    skips = [r for r in results if r[2] == "SKIP"]
    print("\n" + "=" * 78)
    print("结果：%d 过 / %d 挂 / %d 跳过" % (len(results) - len(fails) - len(skips), len(fails), len(skips)))
    if fails:
        print("失败项：")
        for section, name, _, detail in fails:
            print("  ✗ [%s] %s（%s）" % (section, name, detail))
    return 1 if fails else 0


def main():
    global BASE, USER, PASS
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--user", default=USER)
    ap.add_argument("--pass", dest="password", default=PASS)
    a = ap.parse_args()
    BASE, USER, PASS = a.base.rstrip("/"), a.user, a.password

    login()
    st, me = req("GET", "/api/auth/me")
    check("认证", "登录会话", st == 200 and has(me, "username"), st)

    sec_pages()
    sec_screen()
    sec_devices()
    sec_alarms()
    sec_energy()
    sec_reports()
    sec_ai()
    sec_system()
    skus, whs = sec_erp_base()
    sec_purchase(skus, whs)
    sec_stock()
    sec_sales()
    sec_write(skus, whs)
    sec_auth_negative()

    sys.exit(print_report())


if __name__ == "__main__":
    main()
