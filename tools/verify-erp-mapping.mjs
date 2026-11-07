// erp 域包归属校验 —— 拆包后**唯一**能抓「错位」的检查。
//
// ⭐ 为什么需要它（踩过的坑）：
//   `verify-package-split.mjs` 的守恒检查只数**文件总数**（132）。
//   我在转录映射表时把 ErpBom 4 个文件放进了根包、还凭空多出 erp.basic 包，
//   而**总数仍是 106** ⇒ 守恒检查全绿 ⇒ 错误一路传到了执行环节。
//   ⇒ **总数守恒防不了错位**，必须逐类校验「它在哪个包」。
//
// 校验内容：
//   ① 每个类在 PLAN 指定的包里（错位 ⇒ 报，且同时给出实际位置与期望位置）
//   ② 没有漏、没有多、没有重名
//   ③ 各包规模 vs 护栏阈值（直属 ≤24 / 同类角色 ≤12）
//   ④ 跨子包边数（口径：短类名出现，剥掉 import/package 行）
//
// 真相来源：tools/erp-split-plan.json（**不是本文件内硬编码**）
//   ⇒ 与拆包执行器 tools/erp-split-apply.mjs 共用同一份映射，不可能漂
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, dirname, basename } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const SRC = join(ROOT, 'src', 'main', 'java');
const TEST = join(ROOT, 'src', 'test', 'java');
const PKG_ROOT = 'com.factory.hub';
const PLAN = JSON.parse(readFileSync(join(ROOT, 'tools', 'erp-split-plan.json'), 'utf8'));

const problems = [];

// ---------- 期望：类名 → 包 ----------
const expected = new Map(); // cls → { pkg, side }
const expectCounts = {};
for (const side of ['main', 'test']) {
  expectCounts[side] = {};
  for (const [pkg, list] of Object.entries(PLAN[side])) {
    expectCounts[side][pkg] = list.length;
    for (const cls of list) {
      if (expected.has(cls)) {
        problems.push(`映射表内重复：${cls} 同时出现在 ${expected.get(cls).pkg} 与 ${pkg}`);
      }
      expected.set(cls, { pkg, side });
    }
  }
}

// ---------- 实际：递归扫 erp 域，从目录推导包名 ----------
function walk(dir) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (e.endsWith('.java')) out.push(p);
  }
  return out;
}

const actual = new Map(); // cls → { pkg, side, path }
for (const [side, base] of [['main', SRC], ['test', TEST]]) {
  for (const p of walk(join(base, 'com', 'factory', 'hub', 'erp'))) {
    const cls = basename(p, '.java');
    const dirPkg = relative(base, dirname(p)).replace(/\\/g, '.');
    if (actual.has(cls)) {
      problems.push(`磁盘上同名类出现两次：${cls}（${relative(ROOT, actual.get(cls).path)} 与 ${relative(ROOT, p)}）`);
      continue;
    }
    actual.set(cls, { pkg: dirPkg, side, path: p });
  }
}

// ---------- ① 逐类对照归属 ----------
const misplaced = [];
for (const [cls, exp] of expected) {
  const act = actual.get(cls);
  if (!act) {
    problems.push(`缺失：${cls} 应在 ${exp.pkg}，但磁盘上找不到`);
    continue;
  }
  if (act.pkg !== exp.pkg) {
    misplaced.push(`错位：${cls}`);
    problems.push(`错位：${cls}\n         应在 ${exp.pkg}\n         实际在 ${act.pkg}（${relative(ROOT, act.path).replace(/\\/g, '/')}）`);
  }
}

// ---------- ② 多出来的类 ----------
for (const [cls, act] of actual) {
  if (!expected.has(cls)) {
    problems.push(`多出：${cls} 在 ${act.pkg}，但映射表里没有它（${relative(ROOT, act.path).replace(/\\/g, '/')}）`);
  }
}

// ---------- 汇总输出 ----------
console.log('各包规模（映射表）:');
for (const side of ['main', 'test']) {
  for (const [pkg, n] of Object.entries(expectCounts[side])) {
    console.log(`  [${side}] ${pkg.padEnd(30)} ${n}`);
  }
}
console.log(`\n映射表 ${expected.size} 个类 | 磁盘 ${actual.size} 个文件`);

// ---------- ③ 护栏阈值 ----------
console.log('\n各包规模对照护栏阈值（直属 ≤24 / 同类角色 ≤12）:');
let over = 0;
for (const side of ['main']) {
  for (const [pkg, list] of Object.entries(PLAN[side])) {
    const k = { Controller: 0, Service: 0, Repository: 0 };
    for (const f of list) {
      const m = f.match(/(Controller|Service|Repository)$/);
      if (m) k[m[1]]++;
    }
    const bad = list.length > 24 || k.Controller > 12 || k.Service > 12 || k.Repository > 12;
    if (bad) over++;
    console.log(`  ${pkg.padEnd(30)} 共 ${String(list.length).padStart(2)}  C ${k.Controller} / S ${k.Service} / R ${k.Repository}${bad ? '   ⚠️ 超阈值' : ''}`);
  }
}
if (over) console.log(`  ⇒ ${over} 个包超阈值 ⇒ 需在 check-package-size 的 BASELINE 里记录（基线=当前值，涨了就报）`);
else console.log('  ⇒ 全部在阈值内，不需要任何基线豁免 ✓');

// ---------- ④ 跨子包边数 ----------
const all = [...actual.keys()];
let cross = 0;
let inner = 0;
const pairs = {};
for (const [cls, act] of actual) {
  const src = readFileSync(act.path, 'utf8')
    .replace(/^\s*import .*$/gm, '').replace(/^\s*package .*$/gm, '');
  for (const other of all) {
    if (other === cls) continue;
    if (!new RegExp(`\\b${other}\\b`).test(src)) continue;
    const b = actual.get(other);
    if (act.pkg === b.pkg) inner++;
    else {
      cross++;
      const key = [act.pkg, b.pkg].sort().join(' ↔ ');
      pairs[key] = (pairs[key] || 0) + 1;
    }
  }
}
console.log(`\n跨子包边 ${cross} | 包内边 ${inner} | 合计 ${cross + inner}`);
console.log('跨子包边最多的 8 组（= 跨包 import 最集中的地方）:');
Object.entries(pairs).sort((a, b) => b[1] - a[1]).slice(0, 8)
  .forEach(([k, v]) => console.log(`  ${String(v).padStart(3)}  ${k}`));

// ---------- 结论 ----------
if (problems.length) {
  console.error(`\n✗ 发现 ${problems.length} 个问题：\n`);
  for (const p of problems) console.error('  ' + p);
  process.exit(1);
}
console.log(`\n✓ 包归属完全正确：${expected.size} 个类全部在映射表指定的包里（零漏、零多、零错位）`);
