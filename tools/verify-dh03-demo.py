"""DH-03 验收：关演示数据（开关 + 清除 + 重启后确实是干净的）。

交付时客户手里的库必须是干净的——如果留着编的电费和产量，一旦被发现，
整份数据的可信度归零，后面每个数字他都会怀疑。

这个脚本验的是**交付时真正要做的顺序**：
    关三个开关 → 停服务 → 清库 → 启动 → 确认 0 行

为什么必须停服务再清：
    `energy_hourly` / `demand_window` 的当前小时在内存里，purge 删了库之后
    下一次 checkpoint（60 秒 / 10 分钟）会把内存里那几个桶写回去。
    purge 接口自己的提示也写着「当前小时的实时数据会继续累积」。
    所以「清完就是 0 行」这个验收，只能在服务停着的时候成立。

用法：
    python tools/verify-dh03-demo.py --jar target/device-hub.jar
"""
import argparse
import json
import os
import pymysql
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEV_SETTINGS = {
    "energy.demo-seed": "false",
    "demand.demo-seed": "false",
    "report.demo-seed": "false",
}
DEV_ON = {k: "true" for k in DEV_SETTINGS}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default="target/device-hub.jar")
    ap.add_argument("--db", default="devicehub_verify")
    ap.add_argument("--port", type=int, default=8081)
    a = ap.parse_args()
    base = "http://127.0.0.1:%d" % a.port
    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java")

    passed, failed = [], []

    def check(name, ok, detail=""):
        (passed if ok else failed).append(name)
        print("  %s %-44s %s" % ("PASS" if ok else "FAIL", name, detail))

    def db():
        return pymysql.connect(host="127.0.0.1", port=3306, user="devicehub",
                               password="DeviceHub2026", database=a.db,
                               charset="utf8mb4", autocommit=True)

    def counts():
        c = db()
        cur = c.cursor()
        out = {}
        for t in ("energy_hourly", "demand_window", "prod_hourly"):
            try:
                cur.execute("select count(*) from %s" % t)
                out[t] = cur.fetchone()[0]
            except Exception:
                out[t] = -1      # 表不存在
        c.close()
        return out

    def req(method, path, body=None, cookie=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(base + path, data=data, method=method)
        if data:
            r.add_header("Content-Type", "application/json")
        if cookie:
            r.add_header("Cookie", cookie)
        try:
            with urllib.request.urlopen(r, timeout=60) as resp:
                raw = resp.read().decode("utf-8", "replace")
                return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw), \
                       resp.headers.get("Set-Cookie", "")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw), ""
            except Exception:
                return e.code, raw[:200], ""

    def login(retries=45):
        for _ in range(retries):
            try:
                st, r, ck = req("POST", "/api/auth/login",
                                {"username": "admin", "password": "123456"})
                if st == 200 and isinstance(r, dict) and r.get("ok"):
                    m = re.search(r"JSESSIONID=([^;]+)", ck or "")
                    return "JSESSIONID=%s" % m.group(1) if m else ck
            except Exception:
                pass
            time.sleep(2)
        return None

    def start():
        logf = open("target/_verify-dh03.log", "ab")
        p = subprocess.Popen([
            java, "-jar", a.jar, "--server.port=%d" % a.port,
            "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/%s"
            "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true" % a.db,
            "--spring.datasource.username=devicehub",
            "--spring.datasource.password=DeviceHub2026",
        ], stdout=logf, stderr=subprocess.STDOUT)
        return p, logf

    def stop(p, logf):
        p.terminate()
        try:
            p.wait(timeout=30)
        except Exception:
            p.kill()
        logf.close()

    # ---------- 前置：把库和开关都置成「刚装好、演示数据开着」的确定状态 ----------
    # 不这么做的话，脚本依赖上次跑完留下的状态，第二次跑就会假红/假绿。
    print("=== 前置：清库 + 三个开关置 true ===")
    c = db()
    cur = c.cursor()
    for k in DEV_SETTINGS:
        cur.execute("insert into app_setting (k, v) values (%s, 'true') "
                    "on duplicate key update v = 'true'", ("sys." + k,))
        cur.execute("delete from app_setting where k = %s", (k,))
    for t in ("energy_hourly", "demand_window", "prod_hourly"):
        try:
            cur.execute("delete from %s" % t)
        except Exception:
            pass
    c.close()
    print("     行数: %s" % counts())

    # ---------- 第 1 次：默认开着，应该有演示数据 ----------
    print("\n=== 第 1 次启动（默认开演示数据）===")
    p, logf = start()
    try:
        ck = login()
        check("应用启动 + 登录", ck is not None)
        if not ck:
            return
        # seeder 是 ApplicationRunner，在启动阶段跑；写几百行要几秒，
        # 等太短会「purge 删 0 行、然后 seeder 才写完」——表现成假红（踩过）
        time.sleep(10)
        c1 = counts()
        print("     行数: %s" % c1)
        check("默认配置下有演示数据（energy_hourly > 0）", c1["energy_hourly"] > 0,
              "%d 行" % c1["energy_hourly"])

        # ---------- 验 purge 接口真的能删 ----------
        # 注意：三个接口的返回结构不一样（只有 energy 带 deleted，另两个只有 ok+message），
        # 所以不能用「deleted 加总 > 0」判断——那样一个 404 会被另一个的成功掩盖掉（踩过）。
        # 逐个断言 ok=true。report 的路径是复数 /api/reports/purge（前端也是这么调的）。
        all_ok = True
        for path in ("/api/energy/purge", "/api/energy/demand/purge", "/api/reports/purge"):
            st, r, _ = req("POST", path, {"confirm": True}, cookie=ck)
            ok = st == 200 and isinstance(r, dict) and r.get("ok") is True
            all_ok = all_ok and ok
            print("     %-28s %s ok=%s %s" % (
                path, st,
                (r or {}).get("ok") if isinstance(r, dict) else "-",
                ((r or {}).get("message") or "")[:36] if isinstance(r, dict) else str(r)[:36]))
        check("三个清除接口都返回 ok", all_ok)

        # ---------- 关三个开关 ----------
        st, r, _ = req("POST", "/api/system/settings", DEV_SETTINGS, cookie=ck)
        check("三个演示开关已关闭", st == 200 and isinstance(r, dict) and r.get("ok") is not False,
              str((r or {}).get("restartRequired") if isinstance(r, dict) else ""))
    finally:
        stop(p, logf)

    # ---------- 交付真实顺序：停服务后再清残量 ----------
    print("\n=== 停服务后清掉内存回写的残量（交付时就是这么做的）===")
    c = db()
    cur = c.cursor()
    for t in ("energy_hourly", "demand_window", "prod_hourly"):
        try:
            cur.execute("delete from %s" % t)
        except Exception:
            pass
    c.close()
    c2 = counts()
    print("     行数: %s" % c2)
    check("库已清空", all(v <= 0 for v in c2.values()), str(c2))

    # ---------- 第 2 次：开关关着，重启后必须还是 0 ----------
    print("\n=== 第 2 次启动（开关关着，验证 seeder 不再灌）===")
    p, logf = start()
    try:
        ck = login()
        check("应用启动 + 登录", ck is not None)
        time.sleep(5)          # 如果 seeder 没跳过，这几秒就能看到行数涨起来
        c3 = counts()
        print("     行数: %s" % c3)
        check("重启后仍然是 0 行（seeder 确实跳过了）",
              all(v == 0 for v in c3.values()), str(c3))
    finally:
        stop(p, logf)

    # ---------- 第 3 次：开回开关，应恢复演示数据 ----------
    print("\n=== 第 3 次启动（开关开回，验证能恢复）===")
    c = db()
    cur = c.cursor()
    for k, v in DEV_ON.items():
        # 真实交付时这一步是点界面（走 SettingStore.save）。
        # 这里为了「停着服务改」才直接写库，所以必须按实现写对 key：
        # SettingStore 把用户改过的值存在 sys.<key> 下，get() 优先读它，
        # 读不到才回落到 application.yml。写成不带前缀的 key 是无效的（踩过）。
        cur.execute("insert into app_setting (k, v) values (%s, %s) "
                    "on duplicate key update v = values(v)", ("sys." + k, v))
    c.close()

    p, logf = start()
    try:
        ck = login()
        check("应用启动 + 登录", ck is not None)
        time.sleep(8)
        c4 = counts()
        print("     行数: %s" % c4)
        check("开关开回后演示数据恢复", c4["energy_hourly"] > 0,
              "%d 行" % c4["energy_hourly"])
    finally:
        stop(p, logf)

    print()
    print("小结: %d 过 / %d 挂" % (len(passed), len(failed)))
    if failed:
        print("挂了: " + ", ".join(failed))
        print("--- 日志尾部 ---")
        print(open("target/_verify-dh03.log", encoding="utf-8", errors="replace")
              .read()[-2000:])
        sys.exit(1)


if __name__ == "__main__":
    main()
