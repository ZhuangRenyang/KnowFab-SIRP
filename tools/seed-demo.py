# -*- coding: utf-8 -*-
"""演示数据播种（幂等）——给整套 ERP 铺一份"像真工厂"的数据，用来验收功能。

用法：
  python tools/seed-demo.py                       # 默认 http://127.0.0.1:8080，admin/123456
  python tools/seed-demo.py --base http://10.0.0.5:8080 --pass '管理员密码'

设计原则：
1. **幂等**：主数据按编码判重（P-101/WH-03/GYS-02…），业务单据按备注里的场景标签判重
   （【演示-PO-待到货】…）。重复执行只补缺的，不会把库存刷成一大坨。
2. **不改用户已有数据**：只创建自己的编码/标签，不删不改别人的单。
3. **每个场景覆盖一个状态**：采购 6 态、销售 6 态、库存 7 类单据，验收时一眼能看到全部状态。

⚠️ 只应在演示/测试库执行；生产库跑之前先 mysqldump。
"""
import argparse
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
created = {"sku": 0, "warehouse": 0, "partner": 0, "purchase": 0, "sales": 0, "bill": 0, "payment": 0}
skipped = 0


def call(method, path, body=None, timeout=25):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, (json.loads(raw) if raw else {})
        except json.JSONDecodeError:
            return e.code, raw


def fail(msg):
    print("  ✗ " + msg)
    sys.exit(1)


def ok(msg):
    print("  ✓ " + msg)


def login():
    st, r = call("POST", "/api/auth/login", {"username": USER, "password": PASS})
    if st != 200 or not r.get("ok"):
        fail("登录失败：%s %s" % (st, r))
    print("登录成功：%s（%s）" % (USER, r.get("roleText")))


# ---------------------------------------------------------------- 主数据

SKUS = [
    # code, name, category, unit, spec, purchase, sale, min, max
    ("P-101", "减速机箱体", "成品", "件", "JZQ-350", 320, 580, 20, 200),
    ("P-102", "电机端盖", "成品", "件", "Y2-132", 45, 95, 50, 500),
    ("P-103", "齿轮轴", "成品", "件", "M3-Z24", 68, 140, 30, 300),
    ("M-001", "45#圆钢", "原材料", "kg", "Φ60", 4.2, 5.5, 2000, 20000),
    ("M-002", "铸铁 HT250", "原材料", "kg", "HT250", 5.8, 7.2, 1500, 30000),
    ("M-003", "深沟球轴承 6206", "外购件", "套", "6206", 12, 22, 100, 2000),
    ("M-004", "六角螺栓", "外购件", "件", "M8×30", 0.35, 0.8, 3000, 50000),
]

WAREHOUSES = [
    ("WH-03", "原材料仓", "王仓管", "13700000003"),
]

PARTNERS = [
    ("GYS-02", "精工铸造", "SUPPLIER", "周经理", "13700000002"),
    ("GYS-03", "华远轴承", "SUPPLIER", "陈经理", "13700000004"),
    ("KH-02", "三一重工配套", "CUSTOMER", "刘工", "13800000002"),
    ("KH-03", "江淮农机", "CUSTOMER", "赵工", "13800000004"),
]


def ensure_master():
    global skipped
    print("\n[主数据]")
    _, skus = call("GET", "/api/erp/skus")
    have = {s["code"] for s in skus}
    for code, name, cat, unit, spec, pp, sp, mn, mx in SKUS:
        if code in have:
            skipped += 1
            continue
        st, r = call("POST", "/api/erp/skus", {
            "code": code, "name": name, "category": cat, "unit": unit, "spec": spec,
            "purchasePrice": pp, "salePrice": sp, "minStock": mn, "maxStock": mx,
        })
        if not r.get("ok"):
            fail("商品 %s 创建失败：%s" % (code, r.get("message")))
        created["sku"] += 1
        ok("商品 %s %s" % (code, name))

    _, whs = call("GET", "/api/erp/warehouses")
    have = {w["code"] for w in whs}
    for code, name, mgr, phone in WAREHOUSES:
        if code in have:
            skipped += 1
            continue
        st, r = call("POST", "/api/erp/warehouses", {
            "code": code, "name": name, "manager": mgr, "phone": phone,
        })
        if not r.get("ok"):
            fail("仓库 %s 创建失败：%s" % (code, r.get("message")))
        created["warehouse"] += 1
        ok("仓库 %s %s" % (code, name))

    _, ps = call("GET", "/api/erp/partners")
    have = {p["code"] for p in ps}
    for code, name, typ, contact, phone in PARTNERS:
        if code in have:
            skipped += 1
            continue
        st, r = call("POST", "/api/erp/partners", {
            "code": code, "name": name, "type": typ, "contact": contact, "phone": phone,
        })
        if not r.get("ok"):
            fail("往来单位 %s 创建失败：%s" % (code, r.get("message")))
        created["partner"] += 1
        ok("往来单位 %s %s" % (code, name))


# ---------------------------------------------------------------- 索引

def ids():
    _, skus = call("GET", "/api/erp/skus")
    _, whs = call("GET", "/api/erp/warehouses")
    _, ps = call("GET", "/api/erp/partners")
    return ({s["code"]: s["id"] for s in skus},
            {w["code"]: w["id"] for w in whs},
            {p["code"]: p["id"] for p in ps})


def order_exists(kind, tag):
    st, r = call("GET", "/api/erp/%s/orders?kw=%s&page=1&size=1" % (kind, urllib.parse.quote(tag)))
    return st == 200 and r.get("total", 0) > 0


def bill_exists(tag):
    st, r = call("GET", "/api/erp/stock/bills?kw=%s&page=1&size=1" % urllib.parse.quote(tag))
    return st == 200 and r.get("total", 0) > 0


def day(offset):
    import datetime
    return (datetime.date.today() + datetime.timedelta(days=offset)).isoformat()


# ---------------------------------------------------------------- 单据

def create_po(tag, supplier_id, lines, expect_offset=None, remark=""):
    body = {
        "supplierId": supplier_id,
        "orderDate": day(0),
        "lines": lines,
        "remark": "【%s】%s" % (tag, remark),
    }
    if expect_offset is not None:
        body["expectDate"] = day(expect_offset)
    st, r = call("POST", "/api/erp/purchase/orders", body)
    if not r.get("ok"):
        fail("采购订单创建失败：%s" % r.get("message"))
    return r["orderNo"]


def create_so(tag, customer_id, lines, deliver_offset=None, remark=""):
    body = {
        "customerId": customer_id,
        "orderDate": day(0),
        "lines": lines,
        "remark": "【%s】%s" % (tag, remark),
    }
    if deliver_offset is not None:
        body["deliverDate"] = day(deliver_offset)
    st, r = call("POST", "/api/erp/sales/orders", body)
    if not r.get("ok"):
        fail("销售订单创建失败：%s" % r.get("message"))
    return r["orderNo"]


def confirm(kind, no):
    st, r = call("POST", "/api/erp/%s/orders/%s/confirm" % (kind, no), {})
    if not r.get("ok"):
        fail("确认 %s 失败：%s" % (no, r.get("message")))


def receive(no, wh, lines):
    st, r = call("POST", "/api/erp/purchase/orders/%s/receive" % no,
                 {"warehouseId": wh, "lines": lines, "remark": "【演示-入库】"})
    if not r.get("ok"):
        fail("入库 %s 失败：%s" % (no, r.get("message")))
    return r.get("billNo")


def ship(no, wh, lines):
    st, r = call("POST", "/api/erp/sales/orders/%s/ship" % no,
                 {"warehouseId": wh, "lines": lines, "remark": "【演示-发货】"})
    if not r.get("ok"):
        fail("发货 %s 失败：%s" % (no, r.get("message")))
    return r.get("billNo")


def purchase_flow(s, w, p):
    global skipped
    print("\n[采购订单：6 种状态]")

    # 1 待到货
    tag = "演示-PO-待到货"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        no = create_po(tag, p["GYS-02"], [
            {"skuId": s["M-001"], "qty": 5000, "unitPrice": 4.2},
            {"skuId": s["M-002"], "qty": 3000, "unitPrice": 5.8},
        ], expect_offset=3, remark="原材料月度补货")
        confirm("purchase", no)
        created["purchase"] += 1
        ok("待到货 %s（M-001 5000 / M-002 3000）" % no)

    # 2 部分入库 + 采购退货
    tag = "演示-PO-部分入库"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        no = create_po(tag, p["GYS-03"], [
            {"skuId": s["M-003"], "qty": 600, "unitPrice": 12},
        ], expect_offset=1, remark="轴承分批到货")
        confirm("purchase", no)
        receive(no, w["WH-03"], [{"skuId": s["M-003"], "qty": 350}])
        st, r = call("POST", "/api/erp/purchase/orders/%s/return" % no, {
            "warehouseId": w["WH-03"], "lines": [{"skuId": s["M-003"], "qty": 20}],
            "remark": "【演示-采购退货】游隙不合格退回",
        })
        if not r.get("ok"):
            fail("采购退货失败：%s" % r.get("message"))
        created["purchase"] += 1
        created["bill"] += 1
        ok("部分入库 %s（收 350 / 退 20 / 净 330）" % no)

    # 3 已完成
    tag = "演示-PO-已完成"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        no = create_po(tag, p["GYS-01"], [
            {"skuId": s["P-101"], "qty": 30, "unitPrice": 320},
        ], expect_offset=-2, remark="箱体整单到货")
        confirm("purchase", no)
        receive(no, w["WH-01"], [{"skuId": s["P-101"], "qty": 30}])
        created["purchase"] += 1
        ok("已完成 %s（P-101 30 件进成品一仓）" % no)

    # 4 草稿
    tag = "演示-PO-草稿"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        no = create_po(tag, p["GYS-02"], [
            {"skuId": s["M-004"], "qty": 10000, "unitPrice": 0.35},
        ], remark="等车间确认用量")
        created["purchase"] += 1
        ok("草稿 %s（M-004 10000 件）" % no)

    # 5 逾期未到
    tag = "演示-PO-逾期"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        no = create_po(tag, p["GYS-03"], [
            {"skuId": s["M-003"], "qty": 200, "unitPrice": 12},
        ], expect_offset=-3, remark="催货：已过预计到货日")
        confirm("purchase", no)
        created["purchase"] += 1
        ok("逾期未到 %s（预计 %s）" % (no, day(-3)))

    # 6 账龄单（50 天前，已入库未付款 → 应付账龄 31-60 档）
    tag = "演示-PO-账龄"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        st, r = call("POST", "/api/erp/purchase/orders", {
            "supplierId": p["GYS-02"], "orderDate": day(-50),
            "lines": [{"skuId": s["M-002"], "qty": 100, "unitPrice": 5.8}],
            "remark": "【%s】上月到货未结" % tag,
        })
        if not r.get("ok"):
            fail("账龄采购单创建失败：%s" % r.get("message"))
        no = r["orderNo"]
        confirm("purchase", no)
        receive(no, w["WH-03"], [{"skuId": s["M-002"], "qty": 100}])
        created["purchase"] += 1
        ok("账龄单 %s（50 天前，应付未付）" % no)

    # 7 已取消
    tag = "演示-PO-取消"
    if order_exists("purchase", tag):
        skipped += 1
    else:
        no = create_po(tag, p["GYS-01"], [
            {"skuId": s["P-103"], "qty": 5, "unitPrice": 68},
        ], remark="客户改型，取消")
        st, r = call("POST", "/api/erp/purchase/orders/%s/cancel" % no, {})
        if not r.get("ok"):
            fail("取消采购单失败：%s" % r.get("message"))
        created["purchase"] += 1
        ok("已取消 %s" % no)


def sales_flow(s, w, p):
    global skipped
    print("\n[销售订单：6 种状态]")

    # 1 待发货
    tag = "演示-SO-待发货"
    if order_exists("sales", tag):
        skipped += 1
    else:
        no = create_so(tag, p["KH-02"], [
            {"skuId": s["P-101"], "qty": 10, "unitPrice": 580},
        ], deliver_offset=5, remark="月底前交付")
        confirm("sales", no)
        created["sales"] += 1
        ok("待发货 %s（P-101 10 件）" % no)

    # 2 部分发货 + 销售退货
    tag = "演示-SO-部分发货"
    if order_exists("sales", tag):
        skipped += 1
    else:
        no = create_so(tag, p["KH-03"], [
            {"skuId": s["P-002"], "qty": 60, "unitPrice": 15},
        ], deliver_offset=1, remark="先发一半")
        confirm("sales", no)
        ship(no, w["WH-01"], [{"skuId": s["P-002"], "qty": 40}])
        st, r = call("POST", "/api/erp/sales/orders/%s/return" % no, {
            "warehouseId": w["WH-01"], "lines": [{"skuId": s["P-002"], "qty": 5}],
            "remark": "【演示-销售退货】外观磕碰退回",
        })
        if not r.get("ok"):
            fail("销售退货失败：%s" % r.get("message"))
        created["sales"] += 1
        created["bill"] += 1
        ok("部分发货 %s（发 40 / 退 5 / 净 35）" % no)

    # 3 已完成
    tag = "演示-SO-已完成"
    if order_exists("sales", tag):
        skipped += 1
    else:
        no = create_so(tag, p["KH-01"], [
            {"skuId": s["P-001"], "qty": 200, "unitPrice": 2.5},
        ], deliver_offset=-1, remark="老客户补单")
        confirm("sales", no)
        ship(no, w["WH-01"], [{"skuId": s["P-001"], "qty": 200}])
        created["sales"] += 1
        ok("已完成 %s（P-001 200 件）" % no)

    # 4 逾期未发
    tag = "演示-SO-逾期"
    if order_exists("sales", tag):
        skipped += 1
    else:
        no = create_so(tag, p["KH-02"], [
            {"skuId": s["P-102"], "qty": 50, "unitPrice": 95},
        ], deliver_offset=-2, remark="已过交期，待补货")
        confirm("sales", no)
        created["sales"] += 1
        ok("逾期未发 %s（预计 %s）" % (no, day(-2)))

    # 5 草稿
    tag = "演示-SO-草稿"
    if order_exists("sales", tag):
        skipped += 1
    else:
        no = create_so(tag, p["KH-03"], [
            {"skuId": s["P-103"], "qty": 5, "unitPrice": 140},
        ], remark="等客户确认价格")
        created["sales"] += 1
        ok("草稿 %s（P-103 5 件）" % no)

    # 6 账龄单（45 天前发货未收款 → 应收账龄 31-60 档）
    tag = "演示-SO-账龄"
    if order_exists("sales", tag):
        skipped += 1
    else:
        st, r = call("POST", "/api/erp/sales/orders", {
            "customerId": p["KH-01"], "orderDate": day(-45),
            "lines": [{"skuId": s["P-101"], "qty": 10, "unitPrice": 580}],
            "remark": "【%s】上月发货未结" % tag,
        })
        if not r.get("ok"):
            fail("账龄销售单创建失败：%s" % r.get("message"))
        no = r["orderNo"]
        confirm("sales", no)
        ship(no, w["WH-01"], [{"skuId": s["P-101"], "qty": 10}])
        created["sales"] += 1
        ok("账龄单 %s（45 天前，应收未收）" % no)

    # 7 已取消
    tag = "演示-SO-取消"
    if order_exists("sales", tag):
        skipped += 1
    else:
        no = create_so(tag, p["KH-01"], [
            {"skuId": s["P-001"], "qty": 10, "unitPrice": 2.5},
        ], remark="客户重复下单")
        st, r = call("POST", "/api/erp/sales/orders/%s/cancel" % no, {})
        if not r.get("ok"):
            fail("取消销售单失败：%s" % r.get("message"))
        created["sales"] += 1
        ok("已取消 %s" % no)


def stock_flow(s, w):
    global skipped
    print("\n[库存单据：其他出入库 / 调拨 / 盘点]")

    # 其他入库（原材料期初）
    tag = "演示-其他入库"
    if bill_exists(tag):
        skipped += 1
    else:
        st, r = call("POST", "/api/erp/stock/bills", {
            "type": "OTHER_IN", "warehouseId": w["WH-03"], "remark": "【%s】原材料期初建账" % tag,
            "lines": [
                {"skuId": s["M-001"], "qty": 800, "unitPrice": 4.2},
                {"skuId": s["M-004"], "qty": 5000, "unitPrice": 0.35},
            ],
        })
        if not r.get("ok"):
            fail("其他入库失败：%s" % r.get("message"))
        created["bill"] += 1
        ok("其他入库 %s（M-001 800kg / M-004 5000 件）" % r["billNo"])

    # 盘点（M-001 盘亏 20）
    tag = "演示-盘点"
    if bill_exists(tag):
        skipped += 1
    else:
        st, bal = call("GET", "/api/erp/stock/balances?warehouseId=%d" % w["WH-03"])
        book = next((b["qty"] for b in bal if b["skuId"] == s["M-001"]), 0)
        st, r = call("POST", "/api/erp/stock/stocktake", {
            "warehouseId": w["WH-03"], "remark": "【%s】月末盘点" % tag,
            "lines": [{"skuId": s["M-001"], "qty": float(book) - 20}],
        })
        if not r.get("ok"):
            fail("盘点失败：%s" % r.get("message"))
        created["bill"] += 1
        ok("盘点 %s（M-001 账面 %s → 实盘 %s）" % (r.get("billNo"), book, float(book) - 20))

    # 其他出库（车间领用）
    tag = "演示-其他出库"
    if bill_exists(tag):
        skipped += 1
    else:
        st, r = call("POST", "/api/erp/stock/bills", {
            "type": "OTHER_OUT", "warehouseId": w["WH-03"], "remark": "【%s】机加车间领用" % tag,
            "lines": [{"skuId": s["M-004"], "qty": 200}],
        })
        if not r.get("ok"):
            fail("其他出库失败：%s" % r.get("message"))
        created["bill"] += 1
        ok("其他出库 %s（M-004 200 件）" % r["billNo"])

    # 调拨（成品一仓 → 半成品仓）
    tag = "演示-调拨"
    if bill_exists(tag):
        skipped += 1
    else:
        st, r = call("POST", "/api/erp/stock/bills", {
            "type": "TRANSFER", "warehouseId": w["WH-02"], "srcWarehouseId": w["WH-01"],
            "remark": "【%s】箱体转半成品仓待装配" % tag,
            "lines": [{"skuId": s["P-101"], "qty": 5, "unitPrice": 320}],
        })
        if not r.get("ok"):
            fail("调拨失败：%s" % r.get("message"))
        created["bill"] += 1
        ok("调拨 %s（P-101 5 件 成品一仓→半成品仓）" % r["billNo"])


def history_flow(s, w, p):
    """历史流水：给报表的"趋势 / 期初库存"铺内容。

    为什么要 backdate：报表按业务日期统计，没有历史单据的话
    "库存状况"的期初永远是 0、"每日趋势"只有今天一根柱子，验收时看不出对错。
    这里补 45 天、每 5 天一对采购入库 + 销售出库（带供应商/客户），
    这样经营概况的"采购/销售趋势"和账龄都能看出形状。
    """
    global skipped
    print("\n[历史流水：给报表铺 45 天动账]")
    for back in range(5, 50, 5):
        tag_in = "演示-历史采购-D%d" % back
        if bill_exists(tag_in):
            skipped += 1
        else:
            st, r = call("POST", "/api/erp/stock/bills", {
                "type": "PO_IN", "warehouseId": w["WH-03"], "partnerId": p["GYS-01"],
                "date": day(-back), "remark": "【%s】历史采购入库补录" % tag_in,
                "lines": [{"skuId": s["M-003"], "qty": 400, "unitPrice": 12}],
            })
            if not r.get("ok"):
                fail("历史采购入库失败：%s" % r.get("message"))
            created["bill"] += 1
            ok("历史采购入库 %s（%d 天前，M-003 400 套）" % (r["billNo"], back))

        tag_out = "演示-历史销售-D%d" % back
        if bill_exists(tag_out):
            skipped += 1
        else:
            st, r = call("POST", "/api/erp/stock/bills", {
                "type": "SO_OUT", "warehouseId": w["WH-03"], "partnerId": p["KH-01"],
                "date": day(-back), "remark": "【%s】历史销售出库补录" % tag_out,
                "lines": [{"skuId": s["M-003"], "qty": 150, "unitPrice": 22}],
            })
            if not r.get("ok"):
                fail("历史销售出库失败：%s" % r.get("message"))
            created["bill"] += 1
            ok("历史销售出库 %s（%d 天前，M-003 150 套）" % (r["billNo"], back))


def payment_exists(tag):
    st, r = call("GET", "/api/erp/finance/payments?kw=%s&withVoided=true&page=1&size=1"
                 % urllib.parse.quote(tag))
    return st == 200 and r.get("total", 0) > 0


def order_no_of(kind, tag):
    st, r = call("GET", "/api/erp/%s/orders?kw=%s&page=1&size=1" % (kind, urllib.parse.quote(tag)))
    rows = r.get("rows") or []
    return rows[0]["orderNo"] if rows else None


def finance_row(kind, order_no):
    """取该订单的未收/未付（receivables=应收，payables=应付）"""
    st, r = call("GET", "/api/erp/finance/%s?kw=%s&onlyOpen=false&size=20" %
                 ("receivables" if kind == "sales" else "payables", urllib.parse.quote(order_no)))
    for row in r.get("rows") or []:
        if row["orderNo"] == order_no:
            return row
    return None


def finance_flow(s, w, p):
    global skipped
    print("\n[财务：收付款与账龄]")
    scenarios = [
        ("sales", "演示-SO-已完成", "演示-收款-已完成", 0.6, "IN"),
        ("sales", "演示-SO-部分发货", "演示-收款-部分", 0.5, "IN"),
        ("purchase", "演示-PO-已完成", "演示-付款-已完成", 0.5, "OUT"),
        ("purchase", "演示-PO-部分入库", "演示-付款-部分", 1.0, "OUT"),
    ]
    for kind, order_tag, pay_tag, ratio, direction in scenarios:
        if payment_exists(pay_tag):
            skipped += 1
            continue
        no = order_no_of(kind, order_tag)
        if not no:
            skipped += 1
            continue
        row = finance_row(kind, no)
        if not row or float(row["outstanding"]) <= 0:
            skipped += 1
            continue
        amt = round(float(row["outstanding"]) * ratio, 2)
        body = {
            "direction": direction, "partnerId": row["partnerId"], "orderNo": no,
            "amount": amt, "method": "银行转账", "payDate": day(0),
            "remark": "【%s】%s" % (pay_tag, "按比例收" if direction == "IN" else "按比例付"),
        }
        st, r = call("POST", "/api/erp/finance/payments", body)
        if not r.get("ok"):
            fail("登记收付款失败：%s" % r.get("message"))
        created["payment"] = created.get("payment", 0) + 1
        ok("%s %s：%s %s 元（订单未收/未付 %s）" %
           ("收款" if direction == "IN" else "付款", no, direction, amt, row["outstanding"]))

    # 预收 / 预付
    for tag, direction, partner_code, amt, word in [
        ("演示-预收", "IN", "KH-02", 1000, "预收"),
        ("演示-预付", "OUT", "GYS-02", 2000, "预付"),
    ]:
        if payment_exists(tag):
            skipped += 1
            continue
        st, r = call("POST", "/api/erp/finance/payments", {
            "direction": direction, "partnerId": p[partner_code], "amount": amt,
            "method": "银行转账", "payDate": day(0), "remark": "【%s】%s货款" % (tag, word),
        })
        if not r.get("ok"):
            fail("%s登记失败：%s" % (word, r.get("message")))
        created["payment"] = created.get("payment", 0) + 1
        ok("%s %s 元（%s，未关联订单）" % (word, amt, partner_code))

    # 作废演示
    if payment_exists("演示-作废"):
        skipped += 1
    else:
        st, r = call("POST", "/api/erp/finance/payments", {
            "direction": "IN", "partnerId": p["KH-03"], "amount": 500,
            "method": "现金", "payDate": day(0), "remark": "【演示-作废】录错金额，待作废",
        })
        if not r.get("ok"):
            fail("作废演示单创建失败：%s" % r.get("message"))
        no = r["payNo"]
        st, r2 = call("POST", "/api/erp/finance/payments/%s/void" % urllib.parse.quote(no), {})
        if not r2.get("ok"):
            fail("作废失败：%s" % r2.get("message"))
        created["payment"] = created.get("payment", 0) + 1
        ok("作废演示：%s 已作废" % no)


def report(s, w):
    print("\n[当前库存概览]")
    st, bal = call("GET", "/api/erp/stock/balances")
    if st != 200:
        fail("库存接口异常：%s" % st)
    for b in bal:
        print("  %-7s %-10s %-8s %10s %s" % (
            b["code"], b["name"][:10], b["warehouseName"], b["qty"], b.get("unit") or ""))
    st, al = call("GET", "/api/erp/stock/alerts")
    print("  预警 %d 项" % len(al))
    for a in al[:5]:
        print("    · %s %s 合计 %s（下限 %s）" % (a["code"], a["name"], a["total"], a.get("minStock")))
    st, ps = call("GET", "/api/erp/purchase/stats")
    st2, ss = call("GET", "/api/erp/sales/stats")
    if ps.get("ok") and ss.get("ok"):
        print("  本月采购额 ¥%s / 本月销售额 ¥%s" % (ps["monthAmount"], ss["monthAmount"]))
        print("  待到货 %d 单 / 待发货 %d 单 / 采购逾期 %d / 销售逾期 %d" % (
            ps["openCount"], ss["openCount"], ps["overdueCount"], ss["overdueCount"]))
    st, rv = call("GET", "/api/erp/reports/overview?from=%s&to=%s" % (day(-30), day(0)))
    if rv.get("ok"):
        print("  报表近 30 天：采购入库 ¥%s / 销售出库 ¥%s / 生产入库 %s / 库存金额 ¥%s" % (
            rv["purchaseAmount"], rv["salesAmount"], rv["prodInQty"], rv["stockValue"]))
    st, fs = call("GET", "/api/erp/finance/summary")
    if fs.get("ok"):
        print("  应收未收 ¥%s（逾期 ¥%s） / 应付未付 ¥%s（逾期 ¥%s）" % (
            fs["arOutstanding"], fs["arOverdue"], fs["apOutstanding"], fs["apOverdue"]))
        print("  预收 ¥%s / 预付 ¥%s · 本月收款 ¥%s / 付款 ¥%s" % (
            fs["arPre"], fs["apPre"], fs["monthIn"], fs["monthOut"]))


def main():
    global BASE, USER, PASS
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--user", default=USER)
    ap.add_argument("--pass", dest="password", default=PASS)
    a = ap.parse_args()
    BASE, USER, PASS = a.base.rstrip("/"), a.user, a.password

    print("演示数据播种 → %s" % BASE)
    login()
    ensure_master()
    s, w, p = ids()
    purchase_flow(s, w, p)
    sales_flow(s, w, p)
    stock_flow(s, w)
    history_flow(s, w, p)
    finance_flow(s, w, p)
    report(s, w)

    print("\n======== 播种完成：新增 商品 %d / 仓库 %d / 单位 %d / 采购单 %d / 销售单 %d / 库存单 %d / 收付款 %d，跳过已存在 %d ========"
          % (created["sku"], created["warehouse"], created["partner"],
             created["purchase"], created["sales"], created["bill"],
             created.get("payment", 0), skipped))


if __name__ == "__main__":
    main()
