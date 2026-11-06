"""断网演练：设备离线 N 分钟再恢复，验证电量不丢、需量摊平、设备自动回线。

这是 DH-07「现场通信参数 + 断网演练」里**不需要人到现场的那一半**，
也是 DH-86 唯一没被单测覆盖的那一跳（真实采集链路）。

现场版本是「拔掉 AP 5 分钟再上电」，这里用「杀掉 Modbus 从站再重启」等价替代：
从站在 Modbus 协议里就是设备本身，主站看到的现象完全一样——连接被拒、读数中断、
恢复后累计电量已经跳到 N 分钟之后。

    python tools/drill-offline.py --jar target/device-hub.jar --offline-min 6

为什么必须真跑这一遍（而不是只跑单测）：
  单测只能证明**算法**对。真实链路里还有两跳容易错，而且错了不会报任何错——
    ① EnergyAccumulator 判 gap 用的是「上次读数时刻」，不是「上次电量」；
    ② EnergyRollup 要把 Delta.fromTs 传给 DemandTracker。
  传错的表现是「电量被摊到错误的窗口」：数字看起来正常，但峰值算错了月份/时段。
  客户拿它跟供电局对账时才发现，那时已经晚了。

判定「真的生效」而不是「看起来生效」：
  断网 6 分钟、180 kW 负荷 ≈ 18 kWh。如果补读没生效，摊平窗口里只有几十瓦时。
  所以断言的是**量级**，不是「有没有 estimated=1」——后者可以因为别的窗口凑巧满足。
"""
import argparse
import json
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

import pymysql

DEV = "DRILL-METER"          # 演练专用设备，不碰演示设备
RATE_KW = 180.0              # 表上的负荷（kW）；6 分钟 ≈ 18 kWh，量级够看不被噪声淹没
SCALE = 0.01                 # 点表 scale：寄存器单位 0.01 kWh
# 累计量只增不减的基准点。写死一个固定日期，从站重启后值继续往上走
EPOCH_MS = 1704067200000     # 2024-01-01 00:00:00 UTC


# ---------------------------------------------------------------- Modbus 从站
def energy_kwh_now():
    """电表读数（kWh）= 基准 + 从 2024-01-01 起按 RATE_KW 累加。"""
    hours = (time.time() * 1000 - EPOCH_MS) / 3_600_000.0
    return RATE_KW * hours


def regs_for(start, count):
    """按点表给出的寄存器区间，返回 16 位寄存器数组（累计电量放 reg[0..1]，u32 大端）。"""
    raw = int(round(energy_kwh_now() / SCALE))
    hi = (raw >> 16) & 0xFFFF
    lo = raw & 0xFFFF
    regs = []
    for i in range(start, start + count):
        if i == 0:
            regs.append(hi)
        elif i == 1:
            regs.append(lo)
        else:
            regs.append(0)
    return regs


def serve_modbus(port, stop_evt):
    """极简 Modbus TCP 从站，只支持 FC03（读保持寄存器）。"""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)
    srv.settimeout(0.5)

    def handle(conn):
        try:
            conn.settimeout(5)
            while not stop_evt.is_set():
                head = recv_exact(conn, 7)
                if not head:
                    return
                tid, proto, length, uid = struct.unpack(">HHHB", head)
                body = recv_exact(conn, length - 1)
                if not body:
                    return
                fc = body[0]
                if fc != 3 or len(body) < 5:
                    # 只读；写功能码回异常码 01（非法功能）
                    conn.sendall(struct.pack(">HHHBBB", tid, 0, 3, uid, fc | 0x80, 0x01))
                    continue
                start, count = struct.unpack(">HH", body[1:5])
                regs = regs_for(start, count)
                data = b"".join(struct.pack(">H", r) for r in regs)
                pdu = struct.pack(">BB", 3, len(data)) + data
                conn.sendall(struct.pack(">HHHB", tid, 0, len(pdu) + 1, uid) + pdu)
        except Exception:
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass

    while not stop_evt.is_set():
        try:
            conn, _ = srv.accept()
        except socket.timeout:
            continue
        except OSError:
            break
        threading.Thread(target=handle, args=(conn,), daemon=True).start()
    srv.close()


def recv_exact(conn, n):
    buf = b""
    while len(buf) < n:
        try:
            chunk = conn.recv(n - len(buf))
        except Exception:
            return b""
        if not chunk:
            return b""
        buf += chunk
    return buf


class Slave:
    """从站进程管理：start() / kill() 就是「上电」和「拔网线」。"""

    def __init__(self, port):
        self.port = port
        self.stop = threading.Event()
        self.th = None

    def start(self):
        self.stop = threading.Event()
        self.th = threading.Thread(target=serve_modbus,
                                   args=(self.port, self.stop), daemon=True)
        self.th.start()
        # 等端口真的能连上，否则应用首连会失败
        for _ in range(50):
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                    return True
            except OSError:
                time.sleep(0.1)
        return False

    def kill(self):
        self.stop.set()
        if self.th:
            self.th.join(timeout=3)
        # 让 TIME_WAIT 里的连接快速释放
        time.sleep(0.5)


# ---------------------------------------------------------------- HTTP
def req(base, method, path, body=None, cookie=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method)
    if data:
        r.add_header("Content-Type", "application/json")
    if cookie:
        r.add_header("Cookie", cookie)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw), \
                   resp.headers.get("Set-Cookie", "")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw), ""
        except Exception:
            return e.code, raw[:200], ""


def login(base, retries=45):
    for _ in range(retries):
        try:
            st, r, ck = req(base, "POST", "/api/auth/login",
                            {"username": "admin", "password": "123456"})
            if st == 200 and isinstance(r, dict) and r.get("ok"):
                m = re.search(r"JSESSIONID=([^;]+)", ck or "")
                return "JSESSIONID=%s" % m.group(1) if m else ck
        except Exception:
            pass
        time.sleep(2)
    return None


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default="target/device-hub.jar")
    ap.add_argument("--db", default="devicehub_verify")
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--slave-port", type=int, default=1502)
    ap.add_argument("--offline-min", type=float, default=6.0,
                    help="断网时长（分钟）。必须 > 5，否则不触发 backfill 判定")
    a = ap.parse_args()
    base = "http://127.0.0.1:%d" % a.port

    if a.offline_min <= 5:
        print("断网时长必须 > 5 分钟（BACKFILL_GAP_MS 阈值），否则这不是断网演练")
        sys.exit(2)

    passed, failed = [], []

    def check(name, ok, detail=""):
        (passed if ok else failed).append(name)
        print("  %s %-42s %s" % ("PASS" if ok else "FAIL", name, detail))

    def db():
        return pymysql.connect(host="127.0.0.1", port=3306, user="devicehub",
                               password="DeviceHub2026", database=a.db,
                               charset="utf8mb4", autocommit=True)

    # ---- 清掉上次演练的残留（设备 + 数据）----
    c = db()
    cur = c.cursor()
    cur.execute("delete from demand_window where device_code=%s", (DEV,))
    cur.execute("delete from energy_hourly where device_code=%s", (DEV,))
    cur.execute("delete from device where code=%s", (DEV,))
    points = json.dumps([{
        "key": "kwh", "name": "累计电量", "address": 0, "functionCode": 3,
        "dataType": "u32", "scale": SCALE, "offset": 0.0, "unit": "kWh",
        "decimals": 2, "role": "energy", "writable": False,
    }], ensure_ascii=False)
    cur.execute(
        "insert into device (code,name,protocol,host,port,unit_id,timeout_ms,"
        "poll_interval_ms,offline_after_fails,enabled,is_meter,points_json,line_seq,"
        "baud_rate,data_bits,stop_bits,parity,api_key_enabled,counter_mode,"
        "prod_auto_stockin,prod_reset_hour) "
        "values (%s,%s,'modbus-tcp','127.0.0.1',%s,1,2000,1000,3,1,1,%s,0,"
        "9600,8,1,0,0,'TOTAL',0,0)",
        (DEV, "断网演练电表", a.slave_port, points))
    c.close()
    print("演练设备已建：%s（modbus-tcp 127.0.0.1:%d，u32 累计电量，scale %.2f）"
          % (DEV, a.slave_port, SCALE))

    slave = Slave(a.slave_port)
    if not slave.start():
        print("从站起不来（端口 %d 被占？）" % a.slave_port)
        sys.exit(1)
    print("从站已上电\n")

    logf = open("target/_drill-offline.log", "wb")
    proc = subprocess.Popen([
        os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java"), "-jar", a.jar,
        "--server.port=%d" % a.port,
        "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/%s"
        "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        "&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true" % a.db,
        "--spring.datasource.username=devicehub",
        "--spring.datasource.password=DeviceHub2026",
        "--energy.demo-seed=false", "--demand.demo-seed=false", "--report.demo-seed=false",
    ], stdout=logf, stderr=subprocess.STDOUT)

    try:
        ck = login(base)
        check("应用启动 + 登录", ck is not None)
        if not ck:
            return

        # ---- 等采集真的在跑：当前窗口功率 > 0 ----
        online = False
        for _ in range(45):
            st, r, _ = req(base, "GET", "/api/energy/demand?device=%s" % DEV, cookie=ck)
            if st == 200 and isinstance(r, dict):
                if ((r.get("current") or {}).get("kw") or 0) > 0:
                    online = True
                    break
            time.sleep(2)
        check("采集正常（当前窗口功率 > 0）", online)

        # ---- 断网 ----
        print("\n>>> 拔网线：从站停止，等 %.0f 分钟（这期间应用会反复连接失败）" % a.offline_min)
        slave.kill()
        t_down = time.time()
        time.sleep(a.offline_min * 60)
        print(">>> 上电：从站恢复（累计电量已跳到 %.0f 分钟后）" % a.offline_min)

        # ---- 恢复 ----
        slave.start()
        t_up = time.time()

        # 设备应自动回线（DH-07 的验收点：不需要人工干预）
        back_online = False
        for _ in range(60):
            st, r, _ = req(base, "GET", "/api/energy/demand?device=%s" % DEV, cookie=ck)
            if st == 200 and isinstance(r, dict):
                if ((r.get("current") or {}).get("kw") or 0) > 0:
                    back_online = True
                    break
            time.sleep(2)
        check("恢复后设备自动回线（≤2 分钟）", back_online,
              "%.0f 秒" % (time.time() - t_up))

        # ---- 等需量窗口落库 ----
        # 不能「查到任意一行就算完」：断网前那个窗口（实测值）已经在库里，
        # 一查到它就会误判成「落库了但没摊平」。必须等到出现 estimated=1 的行。
        # tracker 每分钟 checkpoint 一次，所以给 4 分钟余量。
        print("\n>>> 等摊平结果落库（tracker 每分钟 checkpoint，最多等 4 分钟）……")
        rows = []
        for _ in range(48):
            time.sleep(5)
            c = db()
            cur = c.cursor()
            cur.execute("select window_start, kwh, demand_kw, estimated from demand_window "
                        "where device_code=%s order by window_start", (DEV,))
            rows = cur.fetchall()
            c.close()
            if any((r[3] == b"\x01" if isinstance(r[3], bytes) else bool(r[3])) for r in rows):
                break

        est = [r for r in rows if (r[3] == b"\x01" if isinstance(r[3], bytes) else bool(r[3]))]
        check("需量窗口已落库", len(rows) > 0, "%d 个窗口" % len(rows))
        check("出现「推算」窗口（estimated=1）", len(est) > 0, "%d 个" % len(est))

        # ---- 关键断言：量级对，不只是「有个标记」----
        # 断网期间的真实间隔 = 从站停止到恢复 + 恢复后首个成功读数的一点延迟
        gap_h = (t_up - t_down) / 3600.0
        expect_kwh = RATE_KW * gap_h
        got_kwh = sum(r[1] for r in est)
        check("摊平电量量级正确（补回了断网期间的电）",
              got_kwh > expect_kwh * 0.7,
              "实得 %.2f kWh / 应约 %.2f kWh（%.1f 分钟内）"
              % (got_kwh, expect_kwh, (t_up - t_down) / 60))

        # 这些窗口的峰值也要进月度最大需量
        st, r, _ = req(base, "GET", "/api/energy/demand?device=%s" % DEV, cookie=ck)
        peak = ((r or {}).get("monthMax") or {}).get("kw") or 0
        check("月度最大需量把推算窗口算进去", peak > 0,
              "%.1f kW（推算窗口峰值 %.1f kW）"
              % (peak, max([x[2] for x in est], default=0)))

        # 数据完整性提示：estimatedWindows 来自 demand_window（已落库），可以直接断言；
        # backfillKwh 来自 energy_hourly，那张表 10 分钟才 checkpoint 一次，
        # 刚演练完必然还是 0 —— 所以只打印，不判失败（否则是拿存储周期当缺陷）。
        st, r, _ = req(base, "GET", "/api/energy/demand?device=%s" % DEV, cookie=ck)
        dq = ((r or {}).get("dataQuality") or {})
        check("dataQuality 报告了推算窗口", (dq.get("estimatedWindows") or 0) > 0,
              "estimatedWindows=%s backfillKwh=%s（后者要等 10 分钟落库，仅供参考）"
              % (dq.get("estimatedWindows"), dq.get("backfillKwh")))

        # 优雅停机不丢电量：这次 terminate() 会走 SIGTERM，@PreDestroy 应把当前小时落库
        print()

        print("\n窗口明细（窗口起点 / kWh / kW / 推算）:")
        for w in rows:
            flag = "推算" if (w[3] == b"\x01" if isinstance(w[3], bytes) else bool(w[3])) else "实测"
            print("  %s  %8.2f kWh  %7.1f kW  %s"
                  % (time.strftime("%m-%d %H:%M", time.localtime(w[0] / 1000)), w[1], w[2], flag))

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=30)
        except Exception:
            proc.kill()
        logf.close()
        slave.kill()

        # 优雅停机（SIGTERM）应该把本小时还没落库的电量写下去。
        # EnergyRollup 原先没有 @PreDestroy → 每次 systemctl restart（发版升级必经）
        # 丢最多 10 分钟电量，而且不报错。这条断言就是钉它。
        # 阈值 1 kWh：断网前那几秒只有 0.15，摊平补回来的 ~20 kWh 若没落库就达不到。
        try:
            c = db()
            cur = c.cursor()
            cur.execute("select coalesce(sum(kwh),0) from energy_hourly where device_code=%s",
                        (DEV,))
            total = cur.fetchone()[0]
            cur.execute("delete from device where code=%s", (DEV,))
            c.close()
            if os.name == "nt":
                # Windows 的 proc.terminate() 是 TerminateProcess（硬杀），JVM 不执行
                # shutdown hook → @PreDestroy 根本不会被调用。这不是产品缺陷，
                # 是平台差异；客户环境是 Linux（systemctl restart 发 SIGTERM）。
                # 逻辑本身已由 EnergyRollupShutdownTest 单测覆盖，这里不制造假红。
                print("  SKIP %-42s Windows 上 terminate 是硬杀，@PreDestroy 不执行"
                      "（energy_hourly=%.2f kWh）；Linux 上会真验，单测已覆盖逻辑" % (
                          "优雅停机没丢当前小时电量（@PreDestroy）", total))
            else:
                check("优雅停机没丢当前小时电量（@PreDestroy）", total > 1.0,
                      "energy_hourly 合计 %.2f kWh" % total)
        except Exception as e:
            check("优雅停机没丢当前小时电量（@PreDestroy）", False, str(e)[:70])

    print()
    print("小结: %d 过 / %d 挂" % (len(passed), len(failed)))
    if failed:
        print("挂了: " + ", ".join(failed))
        print("--- 日志尾部 ---")
        print(open("target/_drill-offline.log", encoding="utf-8", errors="replace")
              .read()[-2500:])
        sys.exit(1)


if __name__ == "__main__":
    main()
