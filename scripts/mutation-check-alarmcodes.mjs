#!/usr/bin/env node
/**
 * 报警码字典校验器的「变异验证」——
 *
 * 判据：**故意注入一个错误 → 校验器必须 exit 1**。
 * 「跑一下 exit 0」不能证明门禁有效（本项目已经栽过一次：
 *   暂存区里的 check-alarmcodes.mjs 是个 117 行空壳，跑一下就 exit 0，什么都拦不住）。
 *
 * 做法：把字典目录 + manifest 拷到临时目录 → 注入一个错误 →
 *       用 ALARM_DIR / ALARM_MANIFEST 指向临时目录跑校验器 → 断言退出码非 0。
 *       **不碰真文件**（校验器支持环境变量覆盖）。
 */
import { cpSync, readFileSync, writeFileSync, rmSync, mkdirSync, readdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC_ALARM = join(ROOT, 'src', 'main', 'resources', 'alarmcodes');
const SRC_MANIFEST = join(ROOT, 'src', 'main', 'resources', 'alarmcode-manifest.json');
const TMP = join(ROOT, 'target', 'mutation-check');
const CHECKER = join(ROOT, 'scripts', 'check-alarmcodes.mjs');

function fresh() {
  // **只覆盖，不删除**：本脚本的 11 类变异全是「改现有文件的内容」，不新增文件，
  // 所以每次从源码重新拷一遍（覆盖）就能拿到干净基线。
  //
  // 为什么不做 rm -rf 清空（2026-11-06 踩到）：某些环境（带批量删除保护的沙箱）
  // 按「单轮对话累计删除文件数」计数，超过 50 就中断整条命令。
  // 11 个变异 × 每次清 8 个文件 ≈ 88 > 50 → **门禁跑到第 6 个变异就被环境打断**，
  // 表现出来是「校验器变异验证未通过，不允许打包」，看着像字典坏了，其实是删文件被拦。
  // 那个保护挡的是「误删用户数据」，我们只是清自己的临时目录 —— 能不删就不删。
  // 覆盖式的额外好处：TMP 里永远不会累积垃圾。
  // 字典目录与 manifest 分开放 —— manifest 放字典目录里会被当成一个字典文件
  const alarm = join(TMP, 'alarmcodes');
  mkdirSync(alarm, { recursive: true });
  cpSync(SRC_ALARM, alarm, { recursive: true, force: true });
  cpSync(SRC_MANIFEST, join(TMP, 'manifest.json'));
  return { alarm, manifest: join(TMP, 'manifest.json') };
}
function readJson(p) { return JSON.parse(readFileSync(p, 'utf8')); }
function writeJson(p, o) { writeFileSync(p, JSON.stringify(o, null, 2) + '\n', 'utf8'); }

function runChecker(env) {
  try {
    const out = execFileSync('node', [CHECKER], {
      env: { ...process.env, ...env },
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { code: 0, out };
  } catch (e) {
    return { code: e.status ?? -1, out: (e.stdout || '') + (e.stderr || '') };
  }
}

// ---- 八类变异：每一类都必须被拦住 ----
const MUTATIONS = [
  {
    name: '① 清单行被删（index.txt 少登记一个文件）',
    apply: (d) => {
      const p = join(d.alarm, 'index.txt');
      const lines = readFileSync(p, 'utf8').split('\n').filter((l) => l.trim() && !l.startsWith('#'));
      lines.pop();
      writeFileSync(p, lines.join('\n') + '\n');
    },
  },
  {
    name: '② 码键前后加了空格（永远查不到）',
    apply: (d) => {
      const p = join(d.alarm, 'yaskawa-servo.json');
      const j = readJson(p);
      const codes = j.codes;
      const first = Object.keys(codes)[0];
      codes[' ' + first] = codes[first];
      delete codes[first];
      writeJson(p, j);
    },
  },
  {
    name: '③ remedy 被清空（查到但没告诉人怎么处理）',
    apply: (d) => {
      const p = join(d.alarm, 'panasonic-servo.json');
      const j = readJson(p);
      j.codes[Object.keys(j.codes)[0]].remedy = '';
      writeJson(p, j);
    },
  },
  {
    name: '④ 归一化碰撞（两个键归一到同一个值）',
    apply: (d) => {
      const p = join(d.alarm, 'huichuan-servo.json');
      const j = readJson(p);
      const codes = j.codes;
      const first = Object.keys(codes)[0];
      codes['ER' + first.slice(2)] = codes[first];   // E4100 与 ER4100 归一化后相同
      writeJson(p, j);
    },
  },
  {
    name: '⑤ 两个文件用同一个 brand（加载时静默覆盖）',
    apply: (d) => {
      const p = join(d.alarm, 'panasonic-a4-servo.json');
      const j = readJson(p);
      j.brand = 'panasonic';
      writeJson(p, j);
    },
  },
  {
    name: '⑥ 新品牌出现补零键（运行时喂裸整数，永远查不到）',
    apply: (d) => {
      const p = join(d.alarm, 'yaskawa-servo.json');
      const j = readJson(p);
      j.codes['0182'] = { displayCode: 'A.182', name: '测试', cause: '测试', remedy: '测试' };
      writeJson(p, j);
    },
  },
  {
    name: '⑦ 新品牌出现含字母键（运行时喂不到）',
    apply: (d) => {
      const p = join(d.alarm, 'huichuan-servo.json');
      const j = readJson(p);
      j.codes['ZZ99'] = { displayCode: 'ZZ99', name: '测试', cause: '测试', remedy: '测试' };
      writeJson(p, j);
    },
  },
  {
    name: '⑧ displayCode 被清空',
    apply: (d) => {
      const p = join(d.alarm, 'panasonic-servo.json');
      const j = readJson(p);
      j.codes[Object.keys(j.codes)[0]].displayCode = '';
      writeJson(p, j);
    },
  },
  {
    name: '⑨ 别名覆盖另一个主键（查那个数字会拿到别的含义）',
    apply: (d) => {
      const p = join(d.alarm, 'yaskawa-servo.json');
      const j = readJson(p);
      const codes = j.codes;
      const ks = Object.keys(codes);
      codes[ks[0]].aliases = [...(codes[ks[0]].aliases || []), ks[1]];
      writeJson(p, j);
    },
  },
  {
    name: '⑩ 别名 = 另一个条目的别名（两个主键抢同一个别名）',
    apply: (d) => {
      const p = join(d.alarm, 'yaskawa-servo.json');
      const j = readJson(p);
      const codes = j.codes;
      const ks = Object.keys(codes);
      // 把 A 的别名改成 B 已有的别名 → 谁先注册谁赢，B 的那条永远命中不到
      const donor = (codes[ks[1]].aliases || [])[0];
      if (donor === undefined) throw new Error('供体条目没有别名，跳过本用例');
      codes[ks[0]].aliases = [...(codes[ks[0]].aliases || []), donor];
      writeJson(p, j);
    },
  },
  {
    name: '⑪ manifest 条数与字典对不上',
    apply: (d) => {
      const m = readJson(d.manifest);
      m.files[0].expectedCount = 99999;
      writeJson(d.manifest, m);
    },
  },
];

console.log('=== 变异验证：注入一个错误 → 校验器必须 exit 1 ===\n');
// 基线：未变异必须 exit 0（否则下面全都不算）
{
  const d = fresh();
  const r = runChecker({ ALARM_DIR: d.alarm, ALARM_MANIFEST: d.manifest });
  console.log(`  [基线] 未变异的字典 → exit ${r.code}  ${r.code === 0 ? '✓' : '✗ 基线就不对，后面全不算'}`);
  if (r.code !== 0) { console.log(r.out.slice(0, 1200)); process.exit(1); }
}

let failed = 0;
for (const m of MUTATIONS) {
  const d = fresh();
  m.apply(d);
  const r = runChecker({ ALARM_DIR: d.alarm, ALARM_MANIFEST: d.manifest });
  const ok = r.code !== 0;
  if (!ok) failed++;
  const firstErr = (r.out.split('\n').find((l) => l.includes('阻断')) || '').trim();
  console.log(`  ${ok ? '✓' : '✗ 漏过'} ${m.name.padEnd(42)} exit ${r.code}  ${firstErr.slice(0, 96)}`);
}
rmSync(TMP, { recursive: true, force: true });
console.log(failed === 0
  ? `\n全部 ${MUTATIONS.length} 类变异都被拦住 ✓`
  : `\n有 ${failed} 类变异漏过 ✗ —— 门禁形同虚设，不能提交`);
process.exit(failed === 0 ? 0 : 1);
