# -*- coding: utf-8 -*-
"""极简 Modbus TCP 从站模拟器，给本地测试「设备发现」用。

用法：python tools/modbus-tcp-sim.py [端口]   # 默认 1502

寄存器/离散输入安排（故意设计成"对拍法"教材）：
  0 号保持寄存器：时间戳低位，每次读都变 —— 模拟"变化点"
  2 号保持寄存器：状态开关 —— 每 10 秒在 0/1 之间翻转
  3 号保持寄存器：每 5 秒 +1 —— 模拟产量计数点
  0 号离散输入（FC02）：跟随 2 号寄存器 —— 模拟 DI 运行触点
  1 号离散输入（FC02）：恒 0 —— 模拟报警触点
支持功能码 02/03/04 读；写功能码回异常码 01（探测工具只读不写，模拟器也一样）。
"""
import socket
import struct
import threading
import time
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1502
REGS = [0] * 100
LOCK = threading.Lock()


def tick():
    """后台线程：让寄存器"活"起来。"""
    n = 0
    while True:
        with LOCK:
            REGS[0] = int(time.time()) % 65536
            REGS[2] = 1 if int(time.time()) % 10 < 5 else 0
            REGS[3] = n
        n += 1
        time.sleep(5)


def send_mbap(conn, tid, uid, pdu):
    conn.sendall(struct.pack(">HHHB", tid, 0, len(pdu) + 1, uid) + pdu)


def di_bits(start, count):
    with LOCK:
        return [1 if (start + i == 0 and REGS[2] == 1) else 0 for i in range(count)]


def handle(conn):
    try:
        while True:
            head = conn.recv(7)
            if len(head) < 7:
                return
            tid, proto, length, uid = struct.unpack(">HHHB", head)
            rest = b""
            while len(rest) < length - 1:
                chunk = conn.recv(length - 1 - len(rest))
                if not chunk:
                    return
                rest += chunk
            if len(rest) < 5:
                return
            fc = rest[0]
            start, count = struct.unpack(">HH", rest[1:5])
            if fc == 2:
                if count < 1 or count > 125 or start + count > 65536:
                    pdu = struct.pack(">BB", fc | 0x80, 3)
                else:
                    bits = di_bits(start, count)
                    nbytes = (count + 7) // 8
                    payload = bytearray(nbytes)
                    for i, bit in enumerate(bits):
                        if bit:
                            payload[i // 8] |= 1 << (i % 8)
                    pdu = bytes([fc, nbytes]) + bytes(payload)
                send_mbap(conn, tid, uid, pdu)
            elif fc in (3, 4):
                if count < 1 or count > 125 or start + count > 65536:
                    pdu = struct.pack(">BB", fc | 0x80, 3)
                else:
                    with LOCK:
                        vals = REGS[start:start + count]
                    body = bytes([fc, count * 2])
                    for v in vals:
                        body += struct.pack(">H", v)
                    send_mbap(conn, tid, uid, body)
            else:
                pdu = struct.pack(">BB", fc | 0x80, 1)
                send_mbap(conn, tid, uid, pdu)
    except OSError:
        pass
    finally:
        conn.close()


def main():
    threading.Thread(target=tick, daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(8)
    print("Modbus TCP slave on 127.0.0.1:%d, Ctrl+C 退出" % PORT)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
