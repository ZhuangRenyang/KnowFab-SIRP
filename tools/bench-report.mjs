#!/usr/bin/env node
/**
 * 从压测日志生成报告表格（**数字不许手抄**）。
 *
 * <p><b>为什么要有这个脚本</b>：2026-11-07 手工写报告时，把 S1（零延迟）的
 * 数字安到了 S2（30ms）那行 —— 而 S2 实际是**失败**的（第一次矩阵退出码=1，
 * 当时归因成「编译问题」没细看）。⇒ 报告结论因此偏乐观了一档。
 * ⇒ **结论里的每个数字都必须来自日志**，且由脚本生成 ⇒ 手抄不可能出错。
 *
 * <p>用法：
 *   node tools/bench-report.mjs            # 输出 Markdown 表格
 *   node tools/bench-report.mjs --check docs/COLLECT-SCALE-BENCH.md
 *                                     # 校验报告里的表格与日志是否一致（不一致 exit 1）
 *
 * <p>⚠️ 退出码约定：
 *   0 = 一致（或仅生成表格）
 *   1 = 报告与日志不一致（**这是最严重的一类问题**：报告在骗人）
 *   2 = 日志缺失（工具没跑或跑挂了）
 */
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const LOG_DIR = join(ROOT, 'target', 'bench');

/** 场景定义：名字 → 描述（与 tools/bench-collect-scale.sh 保持一致） */
const SCENES = [
  { id: 'S1', desc: '零延迟反向对照' },
  { id: 'S2', desc: '30ms（局域网快设备）' },
  { id: 'S3', desc: '50ms（现场常见）' },
  { id: 'S4', desc: '100ms（重负载）' },
  { id: 'S5', desc: '30ms + 抖动 0~30ms' },
  { id: 'S7', desc: 'DB 1ms（验证全局锁排队）' },
  { id: 'S3P', desc: '50ms + 64 线程（加线程对照）' },
];

/** 从一段日志里抠出某个字段的值（找不到返回 null —— **不返回 0，0 是结论**） */
function field(text, label) {
  // ⚠️ label 传的是**正则片段**（可能已含 `\(` 等转义）⇒ 这里**不能再二次转义**，
  //   否则 `\(` 变成 `\\(` ⇒ 匹配不到 ⇒ 字段变成 null ⇒ **表格里显示「—」**
  //   ⇒ 而「—」看起来像「没这项」，实际上日志里有 ⇒ **解析失败被当成了没有**。
  const re = new RegExp(label + '\\s*[:：]\\s*([^\\n]+)');
  const m = text.match(re);
  if (!m) return null;
  return m[1].trim();
}

/** 抽「轮间隔(ms) : P50=1001 P95=1002 P99=1002」这类 */
function triple(text, label) {
  const re = new RegExp(label + '\\s*[:：]\\s*P50=([\\d.]+)\\s+P95=([\\d.]+)\\s+P99=([\\d.]+)');
  const m = text.match(re);
  return m ? { p50: m[1], p95: m[2], p99: m[3] } : null;
}

function parseScene(id) {
  const file = join(LOG_DIR, `${id}.log`);
  if (!existsSync(file)) return null;
  const text = readFileSync(file, 'utf8');
  const gap = triple(text, '轮间隔\\(ms\\)');
  const occ = triple(text, '线程占用\\(ms\\)');
  const wait = triple(text, '\\*\\*全局锁等待\\(ms\\*\\*\\)');
  const failed = text.includes('❌');
  const passed = /✅ 通过/.test(text);
  return {
    id,
    gap,
    occ,
    wait,
    lost: field(text, '丢轮率'),
    threads: field(text, '\\*\\*采集线程数\\*\\*'),
    capacity: field(text, '\\*\\*容量公式 N\\*\\*'),
    callsRatio: field(text, '\\*\\*calls:reads 比值\\*\\*'),
    cpu: field(text, '进程 CPU'),
    starved: field(text, '\\*\\*饥饿设备\\(逐台判定\\*\\*\\)'),
    verdict: failed ? '不达标' : (passed ? '通过' : '未判定'),
    reasons: text.split('\n')
      .filter((l) => l.includes('❌'))
      .map((l) => l.replace(/^\s*❌\s*/, '').trim()),
  };
}

const scenes = SCENES.map((s) => parseScene(s.id));

// ---- S6 负向对照单独处理 ----
const s6File = join(LOG_DIR, 'S6.log');
const s6 = existsSync(s6File) ? (() => {
  const text = readFileSync(s6File, 'utf8');
  return {
    id: 'S6',
    desc: '负向对照（常量值驱动，必须变红）',
    calls: field(text, 'todayOutputOf 调用次数'),
    negativeWorked: text.includes('负向对照：如期变红'),
  };
})() : null;

const missing = scenes.filter((s) => !s);
if (missing.length) {
  console.error(`✗ 日志缺失：${missing.map((m) => m.id).join(', ')} —— 先跑 tools/bench-collect-scale.sh`);
  process.exit(2);
}

// ---- 生成 Markdown 表格 ----
function cell(v, fallback = '—') {
  return v === null || v === undefined ? fallback : String(v);
}

const rows = scenes.map((s) => {
  const gapTxt = s.gap ? `${s.gap.p50} / ${s.gap.p95} / ${s.gap.p99}` : '—';
  const occTxt = s.occ ? `${s.occ.p50} / ${s.occ.p95}` : '—';
  const lost = (s.lost || '—').replace('%', '%');
  const verdict = s.verdict === '通过' ? '✅ 通过' : `❌ **${s.verdict}**`;
  return `| ${s.id} | ${SCENES.find((x) => x.id === s.id).desc} | ${gapTxt} | ${lost} | ${cell(s.threads)} | ${cell(s.capacity)} | ${verdict} |`;
});

const table = [
  '| 场景 | 说明 | 轮间隔 P50/P95/P99 (ms) | 丢轮率 | 采集线程数 | 容量公式 N | 判定 |',
  '|---|---|---|---|---|---|---|',
  ...rows,
].join('\n');

const s6Line = s6
  ? `| S6 | ${s6.desc} | — | — | — | — | ${s6.negativeWorked ? '✅ 如期变红（护栏生效）' : '❌ 没变红'} |`
  : '| S6 | 负向对照 | — | — | — | — | （日志缺失）|';

const fullTable = table + '\n' + s6Line;

console.log('### 压测结果（数字由 tools/bench-report.mjs 从日志生成，勿手抄）');
console.log('');
console.log(fullTable);
console.log('');

const failures = [];
for (const s of scenes) {
  if (s.reasons.length) {
    failures.push({ id: s.id, reasons: s.reasons });
  }
}
if (failures.length) {
  console.log('### 各场景的失败原因');
  console.log('');
  for (const f of failures) {
    console.log(`**${f.id}**`);
    for (const r of f.reasons) {
      console.log(`- ${r}`);
    }
    console.log('');
  }
}

// ---- --check 模式：校验报告里的表格与日志一致 ----
const checkIdx = process.argv.indexOf('--check');
if (checkIdx >= 0) {
  const reportPath = process.argv[checkIdx + 1];
  if (!reportPath || !existsSync(reportPath)) {
    console.error('✗ --check 需要给报告路径');
    process.exit(2);
  }
  const report = readFileSync(reportPath, 'utf8');
  // ⚠️ 只比**主结果表**：报告里还有别的表也含「| S3 |」这种行
  //   （如容量公式对照表）⇒ 按「表头」定位主表，否则会把两张表凑在一起比
  //   ⇒ 行数对不上 ⇒ 报一个假的不一致。
  const HEADER = '| 场景 | 说明 | 轮间隔';
  const pickTable = (text) => {
    const ls = text.split('\n');
    const s0 = ls.findIndex((l) => l.startsWith(HEADER));
    if (s0 < 0) return [];
    const out = [];
    for (let i = s0 + 2; i < ls.length; i++) {
      if (!ls[i].startsWith('|')) break;
      out.push(ls[i]);
    }
    return out;
  };
  const reportRows = pickTable(report);
  const expectRows = pickTable(fullTable);
  const problems = [];
  if (reportRows.length !== expectRows.length) {
    problems.push(`报告里 S1~S6 的行数是 ${reportRows.length}，日志生成的是 ${expectRows.length}`);
  }
  for (let i = 0; i < Math.min(reportRows.length, expectRows.length); i++) {
    // 只比「数字单元格」：抽出行里的所有数字序列
    const nums = (line) => (line.match(/\d+\.?\d*/g) || []).join(',');
    const a = nums(reportRows[i]);
    const b = nums(expectRows[i]);
    if (a !== b) {
      problems.push(`第 ${i + 1} 行数字不一致：\n    报告: ${reportRows[i]}\n    日志: ${expectRows[i]}`);
    }
  }
  if (problems.length) {
    console.error('✗ 报告与日志不一致 —— **报告在骗人**：');
    for (const p of problems) {
      console.error('  ' + p);
    }
    process.exit(1);
  }
  console.log('✓ 报告里的数字与日志完全一致');
}
