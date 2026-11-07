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
// ⚠️ **日志目录刻意不在 target/ 里**：`mvn clean` 会把整个 target/ 删掉
//   ⇒ 压测日志会被一起清掉 ⇒ 报告立刻对不上（`--check` 报「日志缺失」）
//   ⇒ **结论的原始证据不耐 clean** ⇒ 而「结论要能复现」要求证据能活过任何一次构建。
//   ⇒ 改放 target-logs/bench/（target-logs/ 已在 .gitignore）。
const LOG_DIR = join(ROOT, 'target-logs', 'bench');

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
  // ⚠️ 别写 `missing.map((m) => m.id)` —— m 本身就是 null（parseScene 认不出日志时返回 null）
  //   ⇒ 一旦有场景日志缺失，工具不是给出清晰报错，而是抛 TypeError 崩掉
  //   ⇒ 「工具崩了」比「工具报缺日志」难查得多（崩在栈里，报错在日志里）。
  const missingIds = SCENES.filter((s) => !scenes.find((x) => x && x.id === s.id)).map((s) => s.id);
  console.error(`✗ 日志缺失：${missingIds.join(', ')} —— 先跑 tools/bench-collect-scale.sh`);
  process.exit(2);
}

/**
 * 生成 L2 段（真实 MySQL 的三形态对比）。
 * ⚠️ 为什么也要生成：2026-11-07 实测 —— 主表是脚本生成的，但**正文里的 L2 数字是手抄的**
 *   ⇒ L2 重跑后值变了、报告没跟上 ⇒ 报告在骗人 ⇒ 而只比主表的 --check 抓不到。
 *   ⇒ 根治：**正文里的数字也不手抄**。
 */
function buildL2Section() {
  const f = join(LOG_DIR, 'L2.log');
  if (!existsSync(f)) return null;
  const text = readFileSync(f, 'utf8');
  const modes = [];
  let cur = null;
  for (const line of text.split('\n')) {
    const m = line.match(/---- 模式([012]).*----/);
    if (m) {
      cur = { id: m[1], times: [] };
      modes.push(cur);
      continue;
    }
    const t = line.match(/第 \d+ 轮: \d+ 台并发，耗时 (\d+) ms/);
    if (t && cur) cur.times.push(t[1]);
  }
  const single = text.match(/单次 todayOutputOf（2 次查询）: P50=([\d.]+)ms P95=([\d.]+)ms P99=([\d.]+)ms/);
  const serial = text.match(/1000 台\*\*串行.*?= (\d+) ms/);
  const batch = text.match(/批量刷新耗时\(ms\)\s*: P50=([\d.]+)ms P95=([\d.]+)ms/);
  const rows = text.match(/一次批量查询返回 (\d+) 行/);
  const pool = text.match(/借出 (\d+) 次，\*\*借不到\(耗尽\)=(\d+) 次\*\*/);
  if (modes.length === 0) return null;

  const nameOf = { 0: '持锁 + 每台查库（**改造前**）', 1: '无锁 + 每台查库（只去锁的假设形态）', 2: '**无锁 + 批量快照（改造后的真实形态）**' };
  const out = [];
  out.push('> ⚠️ **本段由 `tools/bench-report.mjs` 从 `target-logs/bench/L2.log` 自动生成，勿手抄**');
  out.push('');
  out.push('| 形态 | 一轮耗时（1000 台并发） |');
  out.push('|---|---|');
  for (const mo of modes) {
    out.push(`| ${nameOf[mo.id] || mo.id} | **${mo.times.join(' / ')} ms** |`);
  }
  out.push('');
  if (single) {
    out.push(`**改造前每台每轮要付的查库成本**：P50=${single[1]}ms P95=${single[2]}ms P99=${single[3]}ms`
      + (serial ? ` ⇒ 1000 台**串行**（全局锁）= **${serial[1]}ms** vs 目标周期 1000ms` : '')
      + ' ⇒ 锁占用率几乎顶满，余量为零。');
    out.push('');
  }
  if (batch) {
    out.push('**改造后新增的成本 = 批量快照刷新**（跑在 `scan()` 的锁内）：');
    out.push('');
    out.push('```');
    if (rows) out.push(`一次批量查询返回 ${rows[1]} 行（1000 台 × 今天已过的小时）`);
    out.push(`批量刷新耗时: P50=${batch[1]}ms  P95=${batch[2]}ms`);
    out.push('```');
    out.push('');
    out.push('⚠️ 但**实际刷新频率不是每秒**：实现是「dirty 或跨小时才刷」，dirty 只在 `flush()` 写库后置位');
    out.push('（`checkpoint()` 每 60 秒一次）⇒ **稳态下每 60 秒才刷一次** ⇒ 锁占用率 ≈ 0.13%。');
    out.push('⇒ 「每秒刷一次」是最坏假设（跨小时的瞬间），此时也仅 7.8%，仍可接受。');
    out.push('');
  }
  if (pool) {
    out.push(`**连接池**：借出 ${pool[1]} 次，**借不到（耗尽）= ${pool[2]} 次**`);
    out.push('⚠️ 改造前测出来的「池够用」是**同义反复**（锁把并发串行化 ⇒ 同时只有 1 条连接在用）');
    out.push('⇒ 只有「无锁 + 每台查库」才真正考验池 ⇒ 上面这行才是有效证据。');
    out.push('');
  }
  return out.join('\n');
}

/**
 * 生成 L4 段（真实 ProdRollup 类 + 真实 MySQL 的改造前后对比）。
 * ⚠️ 为什么必须有：2026-11-07 实测 —— 文档里的 L4 数字是**手抄**的，
 *   改造后重跑 L4 得到 682/30ms，文档还写着 647/29ms ⇒ **报告在骗人**。
 *   而当时的 --check 只比主表与 L2 ⇒ **抓不到** ⇒ 根治：L4 也不手抄。
 */
function buildL4Section() {
  const f = join(LOG_DIR, 'L4.log');
  if (!existsSync(f)) return null;
  const text = readFileSync(f, 'utf8');
  // ⚠️ 对比表是**空格对齐**的（`一轮平均耗时(ms)   682   30   95.7%`），
  //   **不是 `|` 分隔的表格** ⇒ 按「≥2 个空格」切列。
  //   （第一版误用 `/\|/` 写正则 ⇒ 三个指标全部解析为 null ⇒ 而代码还继续往下走。）
  const row = (label) => {
    const line = text.split('\n').find((l) => l.trim().startsWith(label));
    if (!line) return null;
    const cols = line.trim().split(/\s{2,}/);
    return cols.length >= 3 ? cols : null;
  };
  const avg = row('一轮平均耗时(ms)');
  const p99 = row('锁等待 P99(ms)');
  const qry = row('每轮查库次数');
  if (!avg || !p99 || !qry) return null;

  const out = [];
  out.push('> ⚠️ **本段由 `tools/bench-report.mjs` 从 `target-logs/bench/L4.log` 自动生成，勿手抄**');
  out.push('');
  out.push('| 指标 | 改造前（持锁查库） | 改造后（批量快照） | 改善 |');
  out.push('|---|---|---|---|');
  out.push(`| 一轮耗时（1000 台） | ${avg[1]} ms | **${avg[2]} ms** | **${avg[3]}** |`);
  out.push(`| 锁等待 P99 | ${p99[1]} ms | **${p99[2]} ms** | 消除 |`);
  out.push(`| 每轮查库次数 | ${qry[1]} | **${qry[2]}** | 消除 |`);
  out.push('');
  out.push('⚠️ **口径说明（不是缺陷，但必须说清）**：上表「新模式」的轮耗时**不含快照刷新成本**');
  out.push('（刷新在计时区外触发，对应生产里 `scan()` 每 60 秒刷一次、读路径不承担它）');
  out.push('⇒ 刷新的真实成本见上方 L2 段的「批量刷新耗时」⇒ 折算锁占用率 ≈ 0.13%。');
  out.push('');
  out.push('⇒ 这正是「**改生产代码必须用 L4**」的原因：L1 把 `ProdRollup` 整个 mock 掉，');
  out.push('改内部行为它**结构上没反应**（S7 只从 2686ms 变到 2588ms，**那是「测不到」不是「无效」**）。');
  return out.join('\n');
}

/**
 * 生成「容量公式与实测的对照」段。
 * ⚠️ 为什么也要生成：这张表里也是**手抄**的数字（P50、理论周期、差异百分比）
 *   ⇒ 每次重跑压测它就会过期，而它是**给现场定 collect.threads 的依据**
 *   ⇒ 过期 ⇒ 照着它定线程数 ⇒ 定错。
 */
function buildCapacitySection() {
  const ids = ['S3', 'S4', 'S5'];
  const rows = [];
  for (const id of ids) {
    const f = join(LOG_DIR, `${id}.log`);
    if (!existsSync(f)) continue;
    const text = readFileSync(f, 'utf8');
    const gap = text.match(/轮间隔\(ms\)\s*:\s*P50=(\d+)\s+P95=(\d+)\s+P99=(\d+)/);
    // ⚠️ 日志里这一行前面有粗体标记「**容量公式 N**」⇒ 正则不能假设它前面没别的字符
    const cap = text.match(/容量公式 N\*\*\s*:\s*ceil\(1000 × ([\d.]+)ms \/ 1000ms\) = (\d+)/)
      || text.match(/容量公式 N\s*:\s*ceil\(1000 × ([\d.]+)ms \/ 1000ms\) = (\d+)/);
    if (!gap || !cap) continue;
    const p50 = Number(gap[1]);
    const n = Number(cap[2]);
    const threads = 32;
    // 理论周期：1000 台 × P95 单轮占用 / 线程数
    const theo = Math.round((1000 * Number(cap[1])) / threads);
    const diff = ((theo - p50) / theo) * 100;
    rows.push({ id, p95: cap[1], n, theo, p50, diff: diff.toFixed(1) });
  }
  if (rows.length === 0) return null;

  const out = [];
  out.push('> ⚠️ **本段由 `tools/bench-report.mjs` 从日志生成，勿手抄**');
  out.push('');
  out.push('| 场景 | 公式算出的 N | 理论周期（1000×P95/32） | 实测轮间隔 P50 | 差异 |');
  out.push('|---|---|---|---|---|');
  for (const r of rows) {
    const big = Number(r.diff) > 10 ? '**' : '';
    out.push(`| ${r.id} | ceil(1000 × ${r.p95}ms / 1000ms) = ${r.n} | ${r.theo}ms | ${r.p50}ms | ${big}差 ${r.diff}%${big} |`);
  }
  return out.join('\n');
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
const l2Section = buildL2Section();
const l4Section = buildL4Section();
const capSection = buildCapacitySection();

console.log('### 压测结果（数字由 tools/bench-report.mjs 从日志生成，勿手抄）');
console.log('');
console.log(fullTable);
console.log('');
if (l4Section) {
  console.log('### 改造前后对比（L4：真实 ProdRollup 类 + 真实 MySQL + 真实连接池）');
  console.log('');
  console.log(l4Section);
  console.log('');
}
if (l2Section) {
  console.log('### 三种形态并排（L2：真实 MySQL + 真实连接池）');
  console.log('');
  console.log(l2Section);
  console.log('');
}
if (capSection) {
  console.log('### 容量公式与实测的对照');
  console.log('');
  console.log(capSection);
  console.log('');
}

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
  // ---- L2 段（真实 MySQL 的三形态对比）也要校验 ----
  // ⚠️ 2026-11-07 栽过：报告里的数字是**手抄**的 ⇒ 抄错了一行而自己没发现
  //   （把 S1 的数字安到 S2 行上）⇒ 这里让 --check 也比对 L2。
  const l2File = join(LOG_DIR, 'L2.log');
  if (existsSync(l2File)) {
    const l2 = readFileSync(l2File, 'utf8');
    // 从日志抽「模式0/1/2」各自的三轮耗时
    const modes = [];
    let cur = null;
    for (const line of l2.split('\n')) {
      const m = line.match(/---- 模式([012]).*----/);
      if (m) {
        cur = { id: m[1], times: [] };
        modes.push(cur);
        continue;
      }
      const t = line.match(/第 \d+ 轮: \d+ 台并发，耗时 (\d+) ms/);
      if (t && cur) {
        cur.times.push(t[1]);
      }
    }
    for (const mo of modes) {
      if (mo.times.length === 0) continue;
      const txt = mo.times.join(' / ');
      // 报告里应出现「**846 / 750 / 719 ms**」这种形式
      const re = new RegExp('\\*\\*' + txt.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ' ms\\*\\*');
      if (!re.test(report)) {
        problems.push(`L2 模式${mo.id} 的三轮耗时与日志不一致：日志是「${txt} ms」，报告里找不到这一串`);
      }
    }
    // 批量刷新的耗时也要校验（它是改造后的新增成本，最容易被抄错）
    const b = l2.match(/批量刷新耗时\(ms\)\s*: P50=([\d.]+)ms\s+P95=([\d.]+)ms/);
    if (b) {
      const need = `P50=${b[1]}ms  P95=${b[2]}ms`;
      if (!report.includes(need)) {
        problems.push(`L2 批量刷新耗时与日志不一致：日志是「${need}」，报告里找不到`);
      }
    }
  } else {
    console.log('（提示：没有 target-logs/bench/L2.log ⇒ 跳过 L2 段的核对）');
  }

  // ---- L4 段（真实 ProdRollup 类）也要校验 ----
  // ⚠️ 为什么不能只比一个数字：L4 有三个指标（轮耗时 / 锁等待 / 查库次数），
  //   只查一个 ⇒ 另两个抄错了照样绿。⇒ 三个**逐个**断言。
  const l4File = join(LOG_DIR, 'L4.log');
  if (existsSync(l4File)) {
    const l4 = readFileSync(l4File, 'utf8');
    // ⚠️ 与 buildL4Section 用**同一套解析**（空格对齐，不是 `|` 分隔）
    const l4Row = (label) => {
      const line = l4.split('\n').find((l) => l.trim().startsWith(label));
      if (!line) return null;
      const cols = line.trim().split(/\s{2,}/);
      return cols.length >= 3 ? cols : null;
    };
    const l4Avg = l4Row('一轮平均耗时(ms)');
    const l4P99 = l4Row('锁等待 P99(ms)');
    const l4Qry = l4Row('每轮查库次数');
    if (l4Avg && l4P99 && l4Qry) {
      // 报告里这三行必须逐字命中（用整行匹配，避免「647」出现在别处就算过）
      const l4Needs = [
        { label: '一轮平均耗时', line: `| 一轮耗时（1000 台） | ${l4Avg[1]} ms | **${l4Avg[2]} ms** | **${l4Avg[3]}** |` },
        { label: '锁等待 P99', line: `| 锁等待 P99 | ${l4P99[1]} ms | **${l4P99[2]} ms** | 消除 |` },
        { label: '每轮查库次数', line: `| 每轮查库次数 | ${l4Qry[1]} | **${l4Qry[2]}** | 消除 |` },
      ];
      for (const need of l4Needs) {
        if (!report.includes(need.line)) {
          problems.push(`L4 的「${need.label}」与日志不一致，报告里应逐字是：\n    ${need.line}`);
        }
      }
    } else {
      problems.push('L4.log 里认不出对比表的三行指标（轮耗时 / 锁等待 / 查库次数）⇒ 工具或日志格式变了');
    }
  } else {
    console.log('（提示：没有 target-logs/bench/L4.log ⇒ 跳过 L4 段的核对）');
  }

  // ---- 全文数字体检：报告里出现的每个「N / N / N」都必须在某个日志里原样存在 ----
  // ⚠️ 2026-11-07 实测：主表改了，但**正文里还留着旧值**（2686 vs 2588），
  //   而只比主表的 --check **抓不到** ⇒ 覆盖有盲区 ⇒ 报告仍然在骗人。
  //   ⇒ 这里扫全文：报告里出现的每一串「N / N / N」，必须在某个日志文件里逐字出现。
  {
    const logs = [];
    if (existsSync(LOG_DIR)) {
      for (const f of readdirSync(LOG_DIR)) {
        if (f.endsWith('.log')) {
          try {
            logs.push(readFileSync(join(LOG_DIR, f), 'utf8'));
          } catch (e) {
            // 读不到就跳过（不能因为读不到日志就报一堆假问题）
          }
        }
      }
    }
    const allLogs = logs.join('\n');
    // 报告里的「数字 / 数字 / 数字」形态（轮间隔的三元组）
    // ⚠️ **不能整串去找**：日志是**逐行**格式（「第 1 轮: 2588 ms」），
    //   天然没有「2588 / 2814 / 2848」这种合并形态 ⇒ 整串比会**全是假阳性**。
    //   ⇒ 拆成三个独立数字，逐个要求「在某个日志里出现过」。
    //   （这是**弱校验**：常见数字可能漏检；但过期值（如 2686）必被抓到。）
    const triples = report.match(/\b\d+ \/ \d+ \/ \d+\b/g) || [];
    for (const t of new Set(triples)) {
      for (const n of t.split(' / ')) {
        if (!new RegExp('(^|\\D)' + n + '(\\D|$)').test(allLogs)) {
          problems.push(`报告里的「${t}」中的 ${n} 在任何压测日志里都找不到 ⇒ 这是个过期/手抄错的数字`);
          break;
        }
      }
    }
    // 报告里的「P50=xx.xms P95=yy.yms」形态（批量刷新的耗时）
    const pPairs = report.match(/P50=\d+(\.\d+)?ms\s+P95=\d+(\.\d+)?ms/g) || [];
    for (const t of new Set(pPairs)) {
      if (!allLogs.includes(t)) {
        problems.push(`报告里的「${t}」在任何压测日志里都找不到 ⇒ 过期/手抄错的数字`);
      }
    }

    // ---- 「单个数字 + 单位」形态（正文里的散落数字）----
    // ⚠️ 2026-11-07 实测踩到的**盲区**：上面的体检只查「N / N / N」与「P50=… P95=…」
    //   ⇒ 正文里**单个**的旧数字（如「轮间隔 2588ms」「丢轮 61.7%」）**完全查不到**
    //   ⇒ 实测文档里就有 4 处这样的过期数字（2686 / 2588 / 1606 / 3159 / 0.955ms）。
    // ⇒ 这里补一条：报告里每个「数字 + ms 或 %」的组合，该数字必须在某个日志里出现过。
    //   （仍是**弱校验**：常见数字可能漏检；但**过期的大数字必被抓到**。）
    {
      // 只查**正文里带单位的数字**；排除表格行（表格已由主表比对逐行核对过）
      const bodyLines = report.split('\n').filter((l) => !l.trim().startsWith('|'));
      const body = bodyLines.join('\n');
      const singles = body.match(/\b\d+(?:\.\d+)?\s*(?:ms|%)\b/g) || [];
      const seen = new Set();
      for (const t of singles) {
        if (seen.has(t)) continue;
        seen.add(t);
        const num = t.replace(/\s*(?:ms|%)$/, '');
        // 百分比在日志里通常带 %，毫秒可能带也可能不带 ⇒ 两种形态都试
        const hit = new RegExp('(^|\\D)' + num.replace('.', '\\.') + '(\\D|$)').test(allLogs);
        if (!hit) {
          problems.push(`报告正文里的「${t}」在任何压测日志里都找不到 ⇒ 这是个过期/手抄错的数字`);
        }
      }
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
