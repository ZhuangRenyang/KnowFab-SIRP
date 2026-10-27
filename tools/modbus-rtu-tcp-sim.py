# -*- coding: utf-8 -*-
"""串口服务器模拟器（Modbus RTU over TCP），给本地测试「设备发现」B 路径用。

用法：python tools/modbus-rtu-tcp-sim.py [端口]   # 默认 1503

模拟一台"485 转网口盒子"透传：收到合法 RTU 帧（CRC 对）才应答，
从站号 1 和 3 各挂了一台设备，其他地址保持沉默（和真 485 总线一样）。
CRC 错的帧直接吞掉——真世界里的干扰帧就是没有回应。

寄存器/离散输入语义和 modbus-tcp-sim.py 相同：
  0 号保持寄存器每秒变、2 号每 10 秒翻转、3 号每 5 秒 +1，专练"对拍找点"。
  0 号离散输入（FC02）跟随 2 号寄存器（模拟 DI 运行触点），1 号恒 0（报警）。
支持功能码 02/03/04 读，其他回 RTU 异常帧（FC|0x80 + 码 01）。
"""
import socket
import struct
import threading
import time
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1503
ALIVE_UNITS = (1, 3)          # 盒子后面活着的从站
REGS = [0] * 100
LOCK = threading.Lock()


def crc16(data):
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc & 0xFFFF


def tick():
    n = 0
    while True:
        with LOCK:
            REGS[0] = int(time.time()) % 65536
            REGS[2] = 1 if int(time.time()) % 10 < 5 else 0
            REGS[3] = n
        n += 1
        time.sleep(5)


def di_bits(start, count):
    with LOCK:
        return [1 if (start + i == 0 and REGS[2] == 1) else 0 for i in range(count)]


def handle(conn):
    try:
        while True:
            # RTU 帧最短 8 字节（从站+FC+4 数据+CRC2），逐字节收
            frame = b""
            while len(frame) < 8:
                b = conn.recv(8 - len(frame))
                if not b:
                    return
                frame += b
            if crc16(frame[:6]) != struct.unpack("<H", frame[6:8])[0]:
                continue          # CRC 错：干扰帧，沉默
            uid, fc = frame[0], frame[1]
            if uid not in ALIVE_UNITS:
                continue          # 别人的地址：从站保持沉默，客户端等超时
            start, count = struct.unpack(">HH", frame[2:6])
            if fc == 2:
                if count < 1 or count > 125 or start + count > 65536:
                    resp = struct.pack(">BB", fc | 0x80, 3)
                else:
                    bits = di_bits(start, count)
                    nbytes = (count + 7) // 8
                    payload = bytearray(nbytes)
                    for i, bit in enumerate(bits):
                        if bit:
                            payload[i // 8] |= 1 << (i % 8)
                    resp = bytes([uid, fc, nbytes]) + bytes(payload)
            elif fc in (3, 4):
                if count < 1 or count > 125 or start + count > 65536:
                    resp = struct.pack(">BB", fc | 0x80, 3)
                else:
                    with LOCK:
                        vals = REGS[start:start + count]
                    body = bytes([uid, fc, count * 2])
                    for v in vals:
                        body += struct.pack(">H", v)
                    resp = body
            else:
                resp = struct.pack(">BB", fc | 0x80, 1)
            conn.sendall(resp + struct.pack("<H", crc16(resp)))
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
    print("RTU-over-TCP slave on 127.0.0.1:%d (alive units: %s), Ctrl+C 退出" % (PORT, ALIVE_UNITS))
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
