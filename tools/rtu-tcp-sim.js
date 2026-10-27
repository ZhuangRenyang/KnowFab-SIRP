// Modbus RTU over TCP 从站模拟器 —— 用来在没有硬件时验证 modbus-rtu-tcp 驱动
// 用法: node tools/rtu-tcp-sim.js [端口]
// 它模拟一个串口服务器的透传口：收到 RTU 帧（带 CRC、无 MBAP 头），回 RTU 帧。

const net = require('net');

const PORT = Number(process.argv[2] || 15020);
const UNIT = 1;

// 模拟一台机床的寄存器区
const regs = {
  0: 2,        // 运行状态: 2 = 运行
  1: 1450,     // 主轴转速 rpm
  2: 0,        // 累计产量 u32 高字
  3: 12345,    // 累计产量 u32 低字
  4: 1250,     // 主轴电流, scale 0.01 => 12.50 A
  5: 385       // 温度 s16, scale 0.1 => 38.5 ℃
};

function crc16(buf) {
  let crc = 0xFFFF;
  for (const b of buf) {
    crc ^= b;
    for (let i = 0; i < 8; i++) {
      if (crc & 1) { crc >>= 1; crc ^= 0xA001; }
      else { crc >>= 1; }
    }
  }
  return crc & 0xFFFF;
}

function withCrc(bytes) {
  const c = crc16(bytes);
  return Buffer.from([...bytes, c & 0xFF, (c >> 8) & 0xFF]);
}

function handle(req) {
  if (req.length !== 8) return null;

  const unit = req[0];
  const fc = req[1];
  const addr = (req[2] << 8) | req[3];
  const arg = (req[4] << 8) | req[5];
  const crcIn = req[6] | (req[7] << 8);

  if (crc16(req.slice(0, 6)) !== crcIn) {
    console.log('  [拒绝] 请求 CRC 不对');
    return null;
  }
  if (unit !== UNIT) {
    console.log(`  [忽略] 从站地址 ${unit} 不是本机(${UNIT})`);
    return null;
  }

  // 异常响应：地址越界（只对读取有意义，写操作的 arg 是要写的值不是数量）
  if ((fc === 1 || fc === 2 || fc === 3 || fc === 4) && (addr > 5 || addr + arg > 6)) {
    console.log(`  [异常] 地址越界 addr=${addr} count=${arg}`);
    return withCrc([unit, fc | 0x80, 0x02]);
  }

  if (fc === 3 || fc === 4) {
    const data = [];
    for (let i = 0; i < arg; i++) {
      const v = regs[addr + i] ?? 0;
      data.push((v >> 8) & 0xFF, v & 0xFF);
    }
    console.log(`  [读寄存器] addr=${addr} count=${arg} -> ${arg * 2} 字节`);
    return withCrc([unit, fc, data.length, ...data]);
  }

  if (fc === 1 || fc === 2) {
    const nBytes = Math.ceil(arg / 8);
    const data = new Array(nBytes).fill(0);
    for (let i = 0; i < arg; i++) {
      const v = regs[addr + i] ?? 0;
      if (v) data[i >> 3] |= (1 << (i % 8));
    }
    console.log(`  [读位] addr=${addr} count=${arg}`);
    return withCrc([unit, fc, nBytes, ...data]);
  }

  if (fc === 5 || fc === 6) {
    const v = fc === 5 ? (arg === 0xFF00 ? 1 : 0) : arg;
    regs[addr] = v;
    console.log(`  [写入] addr=${addr} = ${v}`);
    return withCrc([unit, fc, req[2], req[3], req[4], req[5]]);
  }

  console.log(`  [不支持] 功能码 ${fc}`);
  return withCrc([unit, fc | 0x80, 0x01]);
}

net.createServer(sock => {
  console.log(`串口服务器模拟器：${sock.remoteAddress} 已连接`);
  let buf = Buffer.alloc(0);

  sock.on('data', chunk => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 8) {
      const frame = buf.slice(0, 8);
      buf = buf.slice(8);
      const resp = handle(frame);
      if (resp) {
        sock.write(resp);
      } else {
        // 地址不是本机的，从站静默不回（真实 485 就是这样）
      }
    }
  });

  sock.on('close', () => console.log('连接断开'));
  sock.on('error', e => console.log('连接错误:', e.message));
}).listen(PORT, '127.0.0.1', () => {
  console.log(`Modbus RTU over TCP 模拟从站已启动: 127.0.0.1:${PORT} (从站地址 ${UNIT})`);
  console.log('寄存器: 0=运行状态 1=转速 2-3=累计产量 4=电流 5=温度');
});
