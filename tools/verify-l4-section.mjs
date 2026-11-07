// L4 段核对逻辑的**验证脚本**。
//
// 为什么要有这个文件：「绿灯是最危险的颜色」—— 一个「报告与日志一致」的检查，
//   如果**从来没在「不一致」时被验证过**，那它可能根本不比较任何东西
//   （比如正则写错 ⇒ 永远解析失败 ⇒ 走了「跳过」分支 ⇒ 一直绿）。
// ⇒ 这里做**双向对照**：
//   反向（必须红）：报告里改错一个数字 ⇒ 必须报出问题
//   正向（必须绿）：报告与日志一致 ⇒ 必须 0 问题
//
// ⚠️ 本脚本**不依赖真实压测日志**：用合成日志自证核对逻辑
//   ⇒ 可以接进 CI 门禁（压测本身不在 CI 里跑）。
//   若本地存在 target-logs/bench/L4.log，则**额外**做一次「真实日志 vs 真实报告」的核对。
//
// 用法：node tools/verify-l4-section.mjs
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(import.meta.dirname, '..');

/**
 * 从 L4 日志文本里取对比表的某一行（**空格对齐**，不是 `|` 分隔）。
 * ⚠️ 第一版误用 `/\|/` 写正则 ⇒ 三个指标全解析为 null ⇒ 而代码还继续往下走
 *   ⇒ 「解析不出来」被当成了「没有不一致」⇒ 假绿。⇒ 这里返回 null 时上层必须报错。
 */
function l4Row(text, label) {
  const line = text.split('\n').find((l) => l.trim().startsWith(label));
  if (!line) return null;
  const cols = line.trim().split(/\s{2,}/);
  return cols.length >= 3 ? cols : null;
}

/** 核对：报告里是否逐字包含日志对应的三行指标 */
function check(l4Text, reportText) {
  const problems = [];
  const l4Avg = l4Row(l4Text, '一轮平均耗时(ms)');
  const l4P99 = l4Row(l4Text, '锁等待 P99(ms)');
  const l4Qry = l4Row(l4Text, '每轮查库次数');
  if (l4Avg && l4P99 && l4Qry) {
    const needs = [
      { label: '一轮平均耗时', line: `| 一轮耗时（1000 台） | ${l4Avg[1]} ms | **${l4Avg[2]} ms** | **${l4Avg[3]}** |` },
      { label: '锁等待 P99', line: `| 锁等待 P99 | ${l4P99[1]} ms | **${l4P99[2]} ms** | 消除 |` },
      { label: '每轮查库次数', line: `| 每轮查库次数 | ${l4Qry[1]} | **${l4Qry[2]}** | 消除 |` },
    ];
    for (const n of needs) {
      if (!reportText.includes(n.line)) {
        problems.push(`L4 的「${n.label}」与日志不一致，报告里应逐字是：\n    ${n.line}`);
      }
    }
  } else {
    problems.push('L4.log 里认不出对比表的三行指标（轮耗时 / 锁等待 / 查库次数）⇒ 工具或日志格式变了');
  }
  return problems;
}

// ---- 合成日志（形状与真实 L4.log 一致）----
const SYNTH_LOG = [
  ' 一轮平均耗时(ms)                      682                   30      95.7%',
  ' 锁等待 P99(ms)                        941                    0        改善',
  ' 每轮查库次数                         1000                    0        降低',
].join('\n');

const SYNTH_GOOD_REPORT = [
  '| 一轮耗时（1000 台） | 682 ms | **30 ms** | **95.7%** |',
  '| 锁等待 P99 | 941 ms | **0 ms** | 消除 |',
  '| 每轮查库次数 | 1000 | **0** | 消除 |',
].join('\n');

let failed = false;

// ---- ① 反向对照：报告里把一个数字改错 ⇒ 必须报出问题 ----
{
  const badReport = SYNTH_GOOD_REPORT.replace('682 ms', '999999 ms');
  const problems = check(SYNTH_LOG, badReport);
  if (problems.length === 0) {
    console.error('❌ 反向对照失败：报告里的数字被改错了，核对却没报错 ⇒ 这条检查是装饰性的');
    failed = true;
  } else {
    console.log(`✓ 反向对照生效：报告改错一个数字 ⇒ 报出 ${problems.length} 个问题`);
  }
}

// ---- ② 正向对照：报告与日志一致 ⇒ 必须 0 问题 ----
{
  const problems = check(SYNTH_LOG, SYNTH_GOOD_REPORT);
  if (problems.length > 0) {
    console.error('❌ 正向对照失败：报告与日志一致却报出问题：');
    problems.forEach((p) => console.error('   ' + p));
    failed = true;
  } else {
    console.log('✓ 正向对照通过：报告与日志一致 ⇒ 0 个问题');
  }
}

// ---- ③ 格式守卫：日志格式变了（认不出三行指标）⇒ 必须报错，不能静默跳过 ----
{
  const problems = check('这一行不是对比表\n随便什么内容', SYNTH_GOOD_REPORT);
  if (problems.length === 0) {
    console.error('❌ 格式守卫失败：日志格式认不出来时应当报错，却静默通过了 ⇒ 假绿');
    failed = true;
  } else {
    console.log('✓ 格式守卫生效：日志格式认不出来 ⇒ 报错而不是静默跳过');
  }
}

// ---- ④ 有真实日志时，额外核对「真实日志 vs 真实报告」 ----
const realLog = join(ROOT, 'target', 'bench', 'L4.log');
const realReport = join(ROOT, 'docs', 'COLLECT-SCALE-BENCH.md');
if (existsSync(realLog) && existsSync(realReport)) {
  const problems = check(readFileSync(realLog, 'utf8'), readFileSync(realReport, 'utf8'));
  if (problems.length > 0) {
    console.error('❌ 真实日志与报告不一致：');
    problems.forEach((p) => console.error('   ' + p));
    failed = true;
  } else {
    console.log('✓ 真实日志与报告一致');
  }
} else {
  console.log('（提示：没有 target-logs/bench/L4.log ⇒ 跳过「真实日志 vs 真实报告」这一项）');
}

if (failed) {
  process.exit(1);
}
console.log('✓ L4 段的核对逻辑有效（错时红、对时绿、格式变了也红）');
