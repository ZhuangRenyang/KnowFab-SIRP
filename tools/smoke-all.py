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
    for path in ("/screen.html", "/fault.html", "/"):
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

    # 产量统计四张表（日/月/年/综合）：各点之和必须等于对应 KPI——口径不能各算各的
    st, ps = req("GET", "/api/erp/prod/status?code=%s" % urllib.parse.quote(code))
    if st == 200 and (ps.get("hasCount") or ps.get("auto")):
        # 产量是实时涨的：status 和后面那张图不是同一时刻的快照，
        # 中间隔几个请求，计数器可能又跳了几件。所以对比要给一点容差，
        # 不然这个检查会时不时自己挂（跟口径对不对没关系）。
        def close(a, b):
            return abs(float(a) - float(b)) <= max(5.0, abs(float(b)) * 0.0005)

        st, day = req("GET", "/api/erp/prod/chart?code=%s&mode=day" % urllib.parse.quote(code))
        pts = day.get("points") or []
        dsum = round(sum(float(x["qty"]) for x in pts), 2)
        check(s, "日表24小时=今日", st == 200 and len(pts) == 24
              and close(dsum, ps.get("today", -1)),
              "24点 和%s / 今日%s" % (dsum, ps.get("today")))
        # 月表按当月实际天数铺满（28/29/30/31），未到的天为 0
        st, mon = req("GET", "/api/erp/prod/chart?code=%s&mode=month" % urllib.parse.quote(code))
        mpts = mon.get("points") or []
        msum = round(sum(float(x["qty"]) for x in mpts), 2)
        import calendar as _cal
        today = datetime.date.today()
        ndays = _cal.monthrange(today.year, today.month)[1]
        check(s, "月表=当月整月天数", st == 200 and len(mpts) == ndays
              and close(msum, ps.get("month", -1)),
              "%d点(本月%d天) 和%s / 本月%s" % (len(mpts), ndays, msum, ps.get("month")))
        st, yr = req("GET", "/api/erp/prod/chart?code=%s&mode=year" % urllib.parse.quote(code))
        ysum = round(sum(float(x["qty"]) for x in (yr.get("points") or [])), 2)
        check(s, "年表12月=今年", st == 200 and len(yr.get("points") or []) == 12
              and close(ysum, ps.get("year", -1)),
              "和%s / 今年%s" % (ysum, ps.get("year")))
        st, al = req("GET", "/api/erp/prod/chart?code=%s&mode=all" % urllib.parse.quote(code))
        n_all = len(al.get("points") or [])
        check(s, "综合表=全历史(1~400点)", st == 200 and 1 <= n_all <= 400, "%s 点" % n_all)
    else:
        check(s, "日表24小时=今日", True, "设备未启用产量统计", skip=True)


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
    # 开源版免配置：未填更新源时默认盯 Gitee Releases（国内现场更快），
    # 连不上再自动兜底 GitHub Releases（2026-10-29 起是这个顺序）。
    # 两个源都要在，缺一个就等于某个网络环境下的客户拿不到更新。
    st, r = req("GET", "/api/system/update/check")
    srcs = [x.get("source") for x in (r.get("sources") or [])] if isinstance(r, dict) else []
    check(s, "默认升级源 Gitee 主 + GitHub 兜底", st == 200
          and srcs and srcs[0].startswith("gitee://ZhuangRenyang/")
          and any(x.startswith("github://ZhuangRenyang/") for x in srcs),
          srcs[:2] or st)
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


def sec_finance():
    s = "财务"
    st, r = req("GET", "/api/erp/finance/summary")
    check(s, "财务 KPI", st == 200 and has(r, "arOutstanding", "apOutstanding", "arAging"), st)
    st, r = req("GET", "/api/erp/finance/receivables?page=1&size=5")
    check(s, "应收明细", st == 200 and has(r, "rows", "total", "aging"),
          "%s 单" % (r.get("total") if isinstance(r, dict) else "?"))
    st, r = req("GET", "/api/erp/finance/payables?page=1&size=5")
    check(s, "应付明细", st == 200 and has(r, "rows", "total", "aging"),
          "%s 单" % (r.get("total") if isinstance(r, dict) else "?"))
    st, r = req("GET", "/api/erp/finance/payments?page=1&size=5")
    check(s, "收付款记录", st == 200 and has(r, "rows", "total"),
          "%s 笔" % (r.get("total") if isinstance(r, dict) else "?"))
    st, r = req("GET", "/api/erp/finance/partners?type=CUSTOMER")
    check(s, "客户往来对账", st == 200 and isinstance(r, list), st)
    st, r = req("GET", "/api/erp/finance/partners?type=SUPPLIER")
    check(s, "供应商往来对账", st == 200 and isinstance(r, list), st)


def req_blob(path, timeout=40):
    """取二进制响应（导出 xlsx 用）：只回状态码/类型/长度/前两字节，别把 table 解码成文本。"""
    r = urllib.request.Request(BASE + path)
    try:
        with opener.open(r, timeout=timeout) as resp:
            b = resp.read()
            return resp.status, resp.headers.get("content-type", ""), len(b), b[:2]
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("content-type", ""), 0, b""


def sec_reports():
    s = "报表"
    today = datetime.date.today()
    frm = today.replace(day=1).isoformat()
    q = "from=%s&to=%s" % (frm, today.isoformat())

    st, r = req("GET", "/api/erp/reports/overview?" + q)
    check(s, "经营概况", st == 200 and has(r, "purchaseAmount", "salesAmount", "trend", "topSkus"),
          "采购 ¥%s / 销售 ¥%s" % (r.get("purchaseAmount"), r.get("salesAmount")) if isinstance(r, dict) else st)

    st, r = req("GET", "/api/erp/reports/stock?" + q)
    rows = (r.get("rows") or []) if isinstance(r, dict) else []
    ok = st == 200 and isinstance(rows, list) and has(r.get("totals") or {}, "amount", "skuCount")
    check(s, "库存状况（进销存）", ok, "%d 行" % len(rows))
    # 恒等式：期初 + 本期入 − 本期出 = 期末（报表的核心承诺，不能只对一屏好看）
    bad = [x for x in rows
           if abs((x["opening"] + x["inQty"] - x["outQty"]) - x["closing"]) > 0.001]
    check(s, "期初+入-出=期末", not bad, "全部 %d 行闭合" % len(rows) if not bad else bad[0])

    st, r = req("GET", "/api/erp/reports/flows?" + q)
    ok = st == 200 and isinstance(r.get("byType"), list) and isinstance(r.get("daily"), list) \
        and isinstance(r.get("byWarehouse"), list)
    check(s, "出入库统计", ok, "%d 类 / %d 天" % (len(r.get("byType") or []), len(r.get("daily") or []))
          if isinstance(r, dict) else st)

    st, r = req("GET", "/api/erp/reports/trade?side=purchase&" + q)
    check(s, "采购执行", st == 200 and isinstance(r.get("orders"), list) and isinstance(r.get("partners"), list),
          "%d 单" % len(r.get("orders") or []))
    st, r = req("GET", "/api/erp/reports/trade?side=sales&" + q)
    check(s, "销售执行", st == 200 and isinstance(r.get("orders"), list),
          "%d 单" % len(r.get("orders") or []))

    for t in ("overview", "stock", "flows", "trade"):
        st, ctype, size, magic = req_blob("/api/erp/reports/export?type=%s&%s&side=sales" % (t, q))
        check(s, "导出 %s.xlsx" % t,
              st == 200 and "spreadsheetml" in (ctype or "") and size > 3000 and magic == b"PK",
              "%s 字节" % size)


def sec_lines():
    s = "产线"
    st, r = req("GET", "/api/erp/lines")
    rows = (r.get("rows") or []) if isinstance(r, dict) else []
    ok = st == 200 and isinstance(rows, list) and has(r.get("summary") or {}, "lines", "devices", "today")
    check(s, "产线列表", ok, "%d 条产线 / %d 台设备" % (len(rows), (r.get("summary") or {}).get("devices", 0)))
    if not rows:
        # 同 DH-82：数据前置不满足 ≠ 功能坏了。判 FAIL 会制造一条永远存在的假红。
        check(s, "产线详情", False, "没有产线，跳过（先跑 seed-demo.py）", skip=True)
        return

    code = rows[0]["code"]
    st, d = req("GET", "/api/erp/lines/%s?days=30" % code)
    devices = (d.get("devices") or []) if isinstance(d, dict) else []
    check(s, "产线详情", st == 200 and isinstance(devices, list) and has(d.get("totals") or {}, "today", "month"),
          "%s %d 台设备" % (code, len(devices)))
    seqs = [x["seq"] for x in devices]
    check(s, "设备按工位排序", seqs == sorted(seqs), seqs)
    check(s, "产量曲线 30 天", len(d.get("daily") or []) == 30, len(d.get("daily") or []))

    # 核心口径：产线产量 = 成品计数设备今日产量（不是所有设备相加）
    fin = [x for x in devices if x.get("finished")]
    if fin:
        same = abs(float(fin[0]["today"]) - float((d.get("totals") or {}).get("today", -1))) < 0.01
        check(s, "产线产量=成品设备产量", same,
              "成品 %s %s / 产线 %s" % (fin[0]["code"], fin[0]["today"], d["totals"]["today"]))
        # 再和"设备详情页"那条独立链路对一次：两边口径必须一模一样（曾经都漏 0-8 点）
        st2, ps = req("GET", "/api/erp/prod/status?code=%s" % fin[0]["code"])
        dev_today = ps.get("today") if isinstance(ps, dict) else None
        check(s, "与设备自身今日产量一致",
              dev_today is not None and abs(float(dev_today) - float(fin[0]["today"])) < 0.01,
              "设备 %s / 产线 %s" % (dev_today, fin[0]["today"]))
    else:
        check(s, "产线产量=成品设备产量", False, "没有解析出成品计数设备")

    # 有设备的产线不许删（保护客户编排）
    st, r2 = req("DELETE", "/api/erp/lines/%s" % code)
    check(s, "非空产线禁止删除", isinstance(r2, dict) and r2.get("ok") is False,
          (r2 or {}).get("message") if isinstance(r2, dict) else st)

    # 对外接口必须带 Key
    st, _ = req("GET", "/open/v1/lines", anon=True)
    check(s, "开放接口未带 Key 401", st == 401, st)


def as_user(username, password):
    """用独立会话登录一个账号，返回 callable(method, path)"""
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def inner(method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(BASE + path, data=data, method=method)
        if data:
            r.add_header("Content-Type", "application/json")
        try:
            with op.open(r, timeout=25) as resp:
                raw = resp.read().decode()
                return resp.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, (json.loads(raw) if raw else {})
            except json.JSONDecodeError:
                return e.code, raw

    inner("POST", "/api/auth/login", {"username": username, "password": password})
    return inner


def sec_sales_query():
    s = "销售查询"
    st, r = req("GET", "/api/erp/sales-query/stock")
    rows = (r.get("rows") or []) if isinstance(r, dict) else []
    check(s, "库存查询", st == 200 and isinstance(rows, list) and has(r.get("totals") or {}, "available"),
          "%d 个商品" % len(rows))
    # 可承诺 = max(0, 现存 − 占用)：超卖时截到 0，缺口记在 shortage 里
    bad = [x for x in rows
           if abs(max(0.0, float(x["onHand"]) - float(x["allocated"])) - float(x["available"])) > 0.001
           or abs(max(0.0, float(x["allocated"]) - float(x["onHand"])) - float(x["shortage"])) > 0.001]
    check(s, "可承诺/欠交口径", not bad, "全部 %d 行闭合" % len(rows) if not bad else bad[0])
    # 成本字段绝不能出现（销售查询是要发给客户的）
    leaked = [k for x in rows for k in x.keys()
              if k.lower() in ("purchaseprice", "cost", "amount", "stockvalue")]
    check(s, "不含成本字段", not leaked, leaked[:3])

    st, ctype, size, magic = req_blob("/api/erp/sales-query/export")
    check(s, "导出 xlsx", st == 200 and "spreadsheetml" in (ctype or "") and size > 3000 and magic == b"PK",
          "%s 字节" % size)

    # 销售角色只能查这一页：建临时账号实测，跑完删掉
    uname = "t_smoke_sales"
    st, _ = req("POST", "/api/users", {
        "username": uname, "password": "Smoke123456", "displayName": "冒烟销售", "role": "SALES"})
    created = isinstance(st, int) and st == 200
    if not created:
        # 上次异常退出可能残留，先删再建
        _, users = req("GET", "/api/users")
        for u in (users.get("rows") or [] if isinstance(users, dict) else []):
            if u.get("username") == uname:
                req("DELETE", "/api/users/%s" % u["id"])
        st, _ = req("POST", "/api/users", {
            "username": uname, "password": "Smoke123456", "displayName": "冒烟销售", "role": "SALES"})
        created = isinstance(st, int) and st == 200

    if created:
        call = as_user(uname, "Smoke123456")
        code, _ = call("GET", "/api/erp/sales-query/stock")
        check(s, "销售可查库存(200)", code == 200, code)
        denied = []
        for path in ("/api/erp/stock/balances", "/api/devices", "/api/reports/daily",
                     "/api/erp/finance/summary", "/api/alarms/summary"):
            c, _ = call("GET", path)
            if c != 403:
                denied.append("%s=%s" % (path, c))
        check(s, "销售读车间数据被拒(403)", not denied, denied[:2] or "5 项全拒")
        _, users = req("GET", "/api/users")
        for u in (users.get("rows") or [] if isinstance(users, dict) else []):
            if u.get("username") == uname:
                req("DELETE", "/api/users/%s" % u["id"])
    else:
        check(s, "销售角色账号", False, "创建临时销售账号失败")


def sec_ai_chat():
    s = "AI助手"
    st, r = req("GET", "/api/ai/chat/tools")
    tools = [t.get("name") for t in (r.get("tools") or [])] if isinstance(r, dict) else []
    check(s, "工具清单(管理员)", st == 200 and "query_stock" in tools and "query_finance" in tools,
          "%d 个工具" % len(tools))
    st, r = req("POST", "/api/ai/chat", {"messages": [{"role": "user", "content": "今天产量"}]})
    # 未启用时必须给可操作的提示，而不是 500 或空答复
    check(s, "未启用有引导提示", isinstance(r, dict) and r.get("code") == "AI_DISABLED"
          and "AI 设置" in str(r.get("message")), str(r.get("code")) if isinstance(r, dict) else st)

    # 临时销售：工具只剩库存查询
    uname = "t_smoke_aisales"
    req("POST", "/api/users", {"username": uname, "password": "Smoke123456",
                               "displayName": "冒烟AI", "role": "SALES"})
    call = as_user(uname, "Smoke123456")
    st, r = call("GET", "/api/ai/chat/tools")
    tools = [t.get("name") for t in (r.get("tools") or [])] if isinstance(r, dict) else []
    check(s, "销售只有库存工具", st == 200 and tools == ["query_stock"], tools)
    _, users = req("GET", "/api/users")
    users = users if isinstance(users, list) else (users.get("rows") or [])
    for u in users:
        if u.get("username") == uname:
            req("DELETE", "/api/users/%s" % u["id"])

    # 对话留痕：本人历史可读（未启用时为空列表也是 200）
    st, r = req("GET", "/api/ai/chat/history?limit=5")
    check(s, "对话历史接口", st == 200 and isinstance(r.get("messages"), list), st)

    # 经营解读：未启用给引导；班组长无权
    st, r = req("POST", "/api/ai/chat/interpret", {"from": "2026-10-01", "to": "2026-10-28"})
    check(s, "解读未启用有提示", isinstance(r, dict) and r.get("code") == "AI_DISABLED",
          str(r.get("code")) if isinstance(r, dict) else st)
    uname2 = "t_smoke_ail"
    req("POST", "/api/users", {"username": uname2, "password": "Smoke123456",
                               "displayName": "冒烟班组长", "role": "LEADER"})
    call2 = as_user(uname2, "Smoke123456")
    st2, r2 = call2("POST", "/api/ai/chat/interpret", {})
    check(s, "班组长无经营解读", isinstance(r2, dict) and r2.get("ok") is False
          and "车间主任" in str(r2.get("message")), str(r2.get("message")) if isinstance(r2, dict) else st2)
    _, users = req("GET", "/api/users")
    users = users if isinstance(users, list) else (users.get("rows") or [])
    for u in users:
        if u.get("username") == uname2:
            req("DELETE", "/api/users/%s" % u["id"])


def sec_qc():
    """质检模块冒烟：四个 tab 背后的接口都活着，数据形状对。

    起因：这一轮新做的质检模块（看板/检验单/不良品/主数据）**一次都没进过冒烟**。
    smoke-all 的定位是「每个功能页面背后的接口都活着」——页面点开一片空白，
    通常就是这里某个接口 404 或形状变了。

    这里的检查偏「形状」不偏「业务」：业务正确性由 qc-e2e.py 与后端单测负责。
    """
    s = "质检"

    # ---- ① 看板 ----
    st, r = req("GET", "/api/erp/qc/dashboard")
    check(s, "质检看板", st == 200 and r.get("ok") is True and has(r, "qcDefectQty", "deviceDefectQty"),
          st)
    # 两个口径必须分开给（不能合成一个数）
    check(s, "看板两口径分开", isinstance(r, dict) and "qcDefectQty" in r and "deviceDefectQty" in r,
          list(r.keys())[:8] if isinstance(r, dict) else st)
    st, r = req("GET", "/api/erp/qc/pareto")
    check(s, "帕累托图", st == 200 and r.get("ok") is True and isinstance(r.get("rows"), list), st)

    # ---- ② 检验单 ----
    st, r = req("GET", "/api/erp/qc/inspections?page=1&size=5")
    check(s, "检验单列表", st == 200 and r.get("ok") is True and isinstance(r.get("rows"), list), st)
    check(s, "检验单列表带分页", isinstance(r, dict) and "total" in r and "page" in r,
          list(r.keys())[:8] if isinstance(r, dict) else st)
    # 单条详情（用列表里的第一张）
    # ⚠️ 后端的详情是**三层结构** {ok, header, items, defects}——
    # 单头在 header 里，前端在 api/qcOps.ts 里摊平到顶层。
    # 冒烟要对**后端的真实形状**断言，不是前端的摊平后形状。
    rows = r.get("rows") if isinstance(r, dict) else []
    if rows:
        no = rows[0].get("inspectionNo")
        st, d = req("GET", "/api/erp/qc/inspections/%s" % no)
        hdr = d.get("header") if isinstance(d, dict) else None
        check(s, "检验单详情", st == 200 and d.get("ok") is True
              and isinstance(hdr, dict) and hdr.get("inspectionNo") == no,
              "%s no=%s hdr=%s" % (st, no, type(hdr).__name__))
        check(s, "检验单详情带明细与不良记录",
              isinstance(d, dict) and isinstance(d.get("items"), list)
              and isinstance(d.get("defects"), list),
              list(d.keys()) if isinstance(d, dict) else st)
    else:
        check(s, "检验单详情", True, "无数据跳过", skip=True)
        check(s, "检验单详情带明细与不良记录", True, "无数据跳过", skip=True)

    # ---- ③ 不良品 ----
    st, r = req("GET", "/api/erp/qc/defects?page=1&size=5")
    check(s, "不良记录列表", st == 200 and r.get("ok") is True and isinstance(r.get("rows"), list), st)
    # 追溯链字段必须在（前端 QcDefectRow 声明了，后端要真给）
    drows = r.get("rows") if isinstance(r, dict) else []
    if drows:
        need = ("defectNo", "source", "status", "qty", "inStock", "inspectionNo", "workOrderNo")
        missing = [k for k in need if k not in drows[0]]
        check(s, "不良记录带追溯链字段", not missing, "缺 %s" % missing)
    else:
        check(s, "不良记录带追溯链字段", True, "无数据跳过", skip=True)
    # 待处置汇总：件数与条数要分开（3 条 3000 件 ≠ 3000 条 1 件）
    check(s, "不良统计给件数与条数",
          isinstance(r, dict) and "pendingQty" in r and "pendingRecords" in r,
          list(r.keys())[:8] if isinstance(r, dict) else st)

    # ---- ④ 主数据：缺陷分类 + AQL 方案 ----
    st, r = req("GET", "/api/erp/qc/defect-codes?enabled=true&page=1&size=50")
    check(s, "缺陷分类列表", st == 200 and r.get("ok") is True and isinstance(r.get("rows"), list), st)
    st, r = req("GET", "/api/erp/qc/aql/plans")
    check(s, "AQL 方案表", st == 200 and r.get("ok") is True and isinstance(r.get("rows"), list), st)
    # 方案表要带水平与严格度（抽样下拉按它推导选项，少了字段下拉就列不全）
    arows = r.get("rows") if isinstance(r, dict) else []
    if arows:
        check(s, "AQL 方案带水平/严格度",
              "level" in arows[0] and "strictLevel" in arows[0], list(arows[0].keys())[:8])
    else:
        check(s, "AQL 方案带水平/严格度", True, "无数据跳过", skip=True)

    # ---- ⑤ 抽样查表：国标箭头格要借样本量 ----
    st, r = req("GET", "/api/erp/qc/aql/resolve?level=GENERAL_II&strictLevel=NORMAL"
                     "&lotQty=500&aql=0.65")
    check(s, "AQL 查表", st == 200 and r.get("ok") is True and "sampleSize" in r, st)
    # H 档 @ AQL 0.65 在国标表 2-A 里是箭头格（↓ 借 J 行）→ 抽 80 件，不是 50 件
    check(s, "H 档 @0.65 抽 80 件（国标箭头格）",
          isinstance(r, dict) and int(r.get("sampleSize") or 0) == 80,
          r.get("sampleSize") if isinstance(r, dict) else st)
    st, r = req("GET", "/api/erp/qc/aql/levels")
    check(s, "可用检验水平", st == 200 and r.get("ok") is True and isinstance(r.get("combos"), list), st)

    # ---- ⑥ 放行校验（禁发仓 fail-safe）----
    st, r = req("GET", "/api/erp/qc/concession-release?whId=1&skuIds=1")
    check(s, "放行校验接口", st == 200 and r.get("ok") is True, st)


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

    if cus_id:
        st, r = req("POST", "/api/erp/finance/payments", {
            "direction": "IN", "partnerId": cus_id, "amount": 1,
            "method": "现金", "remark": "【冒烟】预收并作废"})
        ok = isinstance(r, dict) and r.get("ok")
        no = r.get("payNo") if ok else None
        if ok:
            st2, r2 = req("POST", "/api/erp/finance/payments/%s/void" % urllib.parse.quote(no), {})
            ok = bool(r2.get("ok"))
        check(s, "预收登记 → 作废", ok, no or r)

    st, r = req("POST", "/api/erp/stock/bills", {
        "type": "OTHER_IN", "warehouseId": wh_id, "remark": "【冒烟】入库并作废",
        "lines": [{"skuId": sku_id, "qty": 1}]})
    ok = isinstance(r, dict) and r.get("ok")
    no = r.get("billNo") if ok else None
    if ok:
        st2, r2 = req("POST", "/api/erp/stock/bills/%s/void" % urllib.parse.quote(no), {})
        ok = bool(r2.get("ok"))
    check(s, "其他入库 → 作废（库存回滚）", ok, no or r)


def sec_schedule():
    """生产排程（DH-72）：登记计划时间 + 同产线冲突检测 + 甘特取数。

    口径（详见 ErpScheduleService 类注释）：
      NULL=未排程 / 半开区间[start,end) 相接不算冲突 / 只判非终态工单 /
      粒度是产线不是设备 / 没指定产线不参与判定。
    """
    s = "生产排程"
    st, rows = req("GET", "/api/erp/work-orders?page=1&size=5")
    if st != 200:
        check(s, "取工单列表", False, st)
        return
    if not rows.get("rows"):
        # 「没有工单」是数据前置不满足，不是功能坏了 —— 判 SKIP。
        # 判 FAIL 会让全绿检查长期带着一条假红，久了就没人看红了（DH-82）。
        check(s, "取工单列表", False, "没有工单，跳过排程校验（seed-demo.py 不造工单）", skip=True)
        return
    wo = rows["rows"][0]
    no = wo.get("orderNo")
    line = "SMOKE-LINE"
    # 用一个固定的时间窗（2026-01-05 08:00~12:00 / 09:00~13:00 / 12:00~16:00）
    t1, t2, t3 = 1767580800000, 1767595200000, 1767609600000

    st, r = req("GET", "/api/erp/schedule")
    check(s, "甘特接口可读", st == 200 and isinstance(r, dict), st)

    st, r = req("POST", "/api/erp/schedule/%s" % no,
                {"planStart": t1, "planEnd": t2, "lineCode": line})
    check(s, "排程成功", st == 200 and r.get("ok") is True, st)

    st, r = req("POST", "/api/erp/schedule/%s" % no,
                {"planStart": t2, "planEnd": t3, "lineCode": line})
    # 半开区间：结束 == 开始 → 相接 → **不算冲突**
    check(s, "半开区间：首尾相接不算冲突", st == 200 and r.get("ok") is True, st)

    st, r = req("GET", "/api/erp/schedule/conflicts")
    check(s, "冲突清单可读", st == 200 and isinstance(r, dict) and "count" in r, st)

    # 排一张真冲突的（同产线重叠）：用第二张工单
    if len(rows["rows"]) > 1:
        no2 = rows["rows"][1].get("orderNo")
        st, r = req("POST", "/api/erp/schedule/%s" % no2,
                    {"planStart": t1 + 1800000, "planEnd": t2 + 1800000, "lineCode": line})
        check(s, "同产线时间重叠 → 拒绝排程",
              st == 200 and r.get("ok") is False and "重叠" in (r.get("message") or ""), st)
        # 清理（保持幂等）
        req("DELETE", "/api/erp/schedule/%s" % no2)

    # 清理：取消排程（幂等要求）
    req("DELETE", "/api/erp/schedule/%s" % no)


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
    sec_finance()
    sec_reports()
    sec_lines()
    sec_sales_query()
    sec_ai_chat()
    sec_qc()
    sec_schedule()
    sec_write(skus, whs)
    sec_auth_negative()

    sys.exit(print_report())


if __name__ == "__main__":
    main()
