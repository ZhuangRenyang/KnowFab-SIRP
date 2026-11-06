#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
用项目的 Modbus TCP 驱动去读真机（西门子 S7-200 SMART）。

为什么验这条而不是 OPC UA：
    S7-200 全系**不支持 OPC UA Server**（西门子官方口径，S7-1200 要固件 V4.4+
    且需 TIA 里手动启用；S7-200 连 Modbus TCP 都是靠 CP243-1 ETH 模块）。
    而我们项目对 S7-200 的定位本来就是 `modbus-tcp`
    （docs/README.en.md: "Direct Ethernet | modbus-tcp | ... e.g. Siemens S7-200 SMART"）。
    ⇒ 真机要验的工业通讯路径是 Modbus TCP。

这个脚本**不启动应用**，只做协议层探测：
    1. TCP 能不能连上（端口 502）
    2. Modbus TCP 帧能不能握手（发一次读保持寄存器）
    3. 扫一遍常见功能码/地址区，看哪些区有数据
目的：先摸清设备的寄存器布局，好配点表。

用法：
    python tools/probe-modbus.py 192.168.10.1
    python tools/probe-modbus.py 192.168.10.1 --unit 1
"""
import argparse
import socket
import struct
import sys
import time

TIMEOUT = 3.0


def mb_transaction(sock, unit, tid, func, addr, count):
    """发一条 Modbus TCP 请求，收一条响应。返回 (func_code, data_bytes) 或 None。"""
    # MBAP header: transactionId(2) protocolId(2) length(2) unitId(1)
    # PDU: funcCode(1) + data
    if func in (0x01, 0x02, 0x03, 0x04):
        pdu = struct.pack('>BHH', func, addr, count)
    elif func in (0x0F, 0x10):
        # 写多个：func + startAddr + qty + byteCount + data
        pdu = struct.pack('>BHHB', func, addr, count, count * 2) + b'\x00' * (count * 2)
    else:
        pdu = struct.pack('>BH', func, addr)
    header = struct.pack('>HHHB', tid, 0, len(pdu) + 1, unit)
    sock.sendall(header + pdu)
    resp = sock.recv(512)
    if len(resp) < 9:
        return None
    r_func = resp[7]
    r_len = struct.unpack('>H', resp[4:6])[0]
    data = resp[8:8 + r_len - 1]
    return r_func, data


def try_read(sock, unit, func, addr, count):
    """试一次读。返回 (ok, 说明, 值列表)。"""
    tid = int(time.time() * 1000) & 0xFFFF
    try:
        r = mb_transaction(sock, unit, tid, func, addr, count)
    except socket.timeout:
        return False, '超时', []
    except Exception as e:
        return False, '%s: %s' % (type(e).__name__, str(e)[:40]), []
    if r is None:
        return False, '响应过短', []
    r_func, data = r
    if r_func & 0x80:
        # 异常响应：最后一个字节是异常码
        code = data[0] if data else -1
        names = {
            1: '非法功能码', 2: '非法地址', 3: '非法数据值',
            4: '从站故障', 6: '从站忙', 8: '存储奇偶校验错',
            10: '网关路径不可用', 11: '网关目标无响应',
        }
        return False, '异常码 %d (%s)' % (code, names.get(code, '?')), []
    if func in (0x01, 0x02):
        byte_count = data[0]
        bits = []
        for b in data[1:1 + byte_count]:
            for i in range(8):
                bits.append((b >> i) & 1)
        return True, 'OK', bits[:count]
    if func in (0x03, 0x04):
        byte_count = data[0]
        vals = []
        for i in range(0, byte_count - 1, 2):
            vals.append(struct.unpack('>H', data[1 + i:3 + i])[0])
        return True, 'OK', vals[:count]
    return True, 'OK', []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('host')
    ap.add_argument('--port', type=int, default=502)
    ap.add_argument('--unit', type=int, default=1, help='从站地址（unitId）')
    ap.add_argument('--scan', action='store_true', help='扫描功能码与地址区')
    args = ap.parse_args()

    print('=== TCP 连通性 %s:%d ===' % (args.host, args.port))
    try:
        s = socket.create_connection((args.host, args.port), TIMEOUT)
        s.settimeout(TIMEOUT)
        print('  ✓ 连上了')
    except Exception as e:
        print('  ✗ 连不上：%s: %s' % (type(e).__name__, str(e)[:60]))
        print()
        print('排查顺序：')
        print('  1) 本机到 PLC 的路由通不通（netstat -rn 看有没有 192.168.10.0/24 的条目，网卡是「以太网」）')
        print('  2) PLC 上电了没、网线是不是插在以太网口（不是 RS-485 口）')
        print('  3) PLC 的 Modbus TCP 服务器有没有开（S7-200 SMART 默认开）')
        print('  4) 防火墙')
        return 2

    print()
    print('=== Modbus TCP 读测试（unitId=%d）===' % args.unit)
    for func, name in ((0x03, '读保持寄存器'), (0x04, '读输入寄存器'),
                       (0x01, '读线圈'), (0x02, '读离散输入')):
        ok, msg, vals = try_read(s, args.unit, func, 0, 8)
        extra = ''
        if ok and vals:
            extra = '  值=%s' % vals[:8]
        print('  %-12s (0x%02X) %s%s' % (name, func, msg, extra))

    if args.scan:
        print()
        print('=== 扫描：找哪个地址区有数据 ===')
        for func, name in ((0x03, '保持寄存器'), (0x04, '输入寄存器')):
            for base in (0, 100, 1000, 2000, 4000, 10000, 40000):
                ok, msg, vals = try_read(s, args.unit, func, base, 10)
                if ok and any(v != 0 for v in vals):
                    print('  ★ %s @ %d → %s' % (name, base, vals[:10]))
                elif ok:
                    print('    %s @ %-6d → 全 0' % (name, base))
                else:
                    print('    %s @ %-6d → %s' % (name, base, msg))

    s.close()
    print()
    print('提示：以上只是摸寄存器布局。要真正跑通本项目的采集链路，')
    print('     需要在系统里建一台 protocol=modbus-tcp 的设备并配好点表。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
