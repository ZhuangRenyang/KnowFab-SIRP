// 时区/时刻无关性验证：**穷举本地小时 0~23**，证明测试不再依赖「当前本地时刻」。
//
// ⚠️ 为什么需要（2026-11-07 CI 红绿的根因）：
//   `ProdRollupTodaySnapshotTest.refreshRespectsPerDeviceResetHour` 写死了「清零 8 点」，
//   当**当前本地时间落在 [8:00, 9:00)** 时，
//   `dayStart(now, 8)`（本地今天 8:00）**恰好等于「当前小时起点」**
//   ⇒ 生产代码把那一行判成「当前小时」⇒ 不进 closedSum
//   ⇒ 断言「起点之后那行该算」必然失败。
//   ⇒ 症状：**本地全绿、CI 全红**（CI 用 UTC 时区且在 08:17 跑 ⇒ 正好撞进坏窗口）。
//
// ⭐ 为什么「换几个时区跑一遍」不够：
//   那只覆盖**几个**本地小时 ⇒ 换个时间跑就漏掉了 ⇒ **验证本身不可靠**。
//   ⇒ 这里用 `Etc/GMT-N`（偏移连续 ⇒ 本地小时全覆盖）**穷举 0~23**。
//
// 用法：node tools/verify-hour-independence.mjs [--expect-fail]
//   --expect-fail：反向对照 —— 预期**至少一个**时区红（用于证明这条检查有鉴别力）
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const MVN = process.env.MVN || 'mvn';
const TESTS = 'ProdRollupTodayOutputTest,ProdRollupTodaySnapshotTest';
const EXPECT_FAIL = process.argv.includes('--expect-fail');

// ⭐ `Etc/GMT` 的符号是**反的**：`Etc/GMT-8` = UTC+8，`Etc/GMT+8` = UTC-8。
//   偏移范围 -14 ~ +12 ⇒ 本地小时 = UTC 小时 + 偏移 ⇒ **连续覆盖 24 个本地小时**。
//   （再混入一个非整小时时区 Asia/Kolkata +5:30 ⇒ 覆盖「本地几点带半小时」的情形。）
const ZONES = [];
for (let off = -12; off <= 14; off++) {
  ZONES.push(off >= 0 ? `Etc/GMT-${off}` : `Etc/GMT+${-off}`);
}
ZONES.push('Asia/Kolkata');   // +5:30：非整小时偏移

if (!existsSync(join(ROOT, 'target', 'test-classes'))) {
  console.error('✗ 没有 target/test-classes ⇒ 先 mvn test-compile');
  process.exit(2);
}

const redZones = [];
for (const tz of ZONES) {
  let status = 'ok';
  let detail = '';
  try {
    // ⚠️ Windows 上 Node **不能直接 spawn `.cmd`**（EINVAL）⇒ 必须走 shell
    const out = execFileSync(MVN, [
      '-o', 'test',
      `-Dtest=${TESTS}`,
      '-DfailIfNoSpecifiedTests=false',
      `-DargLine=-Duser.timezone=${tz}`,
    ], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], shell: true });
    const m = [...out.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)];
    const last = m[m.length - 1];
    if (!last) {
      status = 'bad';
      detail = '输出里找不到 Tests run 汇总';
    } else {
      const [, run, fail, err] = last;
      detail = `${run} 个用例`;
      if (fail !== '0' || err !== '0') {
        status = 'red';
        detail += `，failures=${fail} errors=${err}`;
      }
    }
  } catch (e) {
    status = 'red';
    const msg = String(e.stdout || '') + String(e.message);
    const m = [...msg.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+)/g)];
    detail = m.length
      ? `Tests run: ${m[m.length - 1][1]}, Failures: ${m[m.length - 1][2]}, Errors: ${m[m.length - 1][3]}`
      : String(e.message).split('\n')[0];
  }
  if (status === 'red') redZones.push(tz);
  const mark = status === 'ok' ? '✓' : (status === 'red' ? '❌' : '⚠️');
  console.log(`  ${mark} ${tz.padEnd(14)} ${detail}`);
}

console.log('');
if (EXPECT_FAIL) {
  // ---- 反向对照：必须**至少红一个**，否则这条检查是装饰性的 ----
  if (redZones.length === 0) {
    console.error('❌ 反向对照失败：旧版代码在所有时区都绿 ⇒ 说明「换时区」根本撞不上坏窗口'
      + ' ⇒ 这条检查本身没有鉴别力');
    process.exit(1);
  }
  console.log(`✓ 反向对照生效：旧版在 ${redZones.length} 个时区红了（${redZones.join(', ')}）`);
  console.log('  ⇒ 证明「换时区」确实能撞出依赖当前时刻的测试');
  process.exit(0);
}

if (redZones.length > 0) {
  console.error(`✗ ${redZones.length} 个时区失败（${redZones.join(', ')}）⇒ 测试仍依赖当前时刻（或时区）`);
  process.exit(1);
}
console.log(`✓ ${ZONES.length} 个时区全绿 ⇒ 测试不再依赖「当前本地时刻」（本地小时 0~23 全覆盖）`);
