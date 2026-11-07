// erp 域拆包执行器 —— 读 tools/erp-split-plan.json，一次完成：
//   ① 改 package 声明 + 按新包移文件
//   ② 全限定名重写（com.factory.hub.erp.X → 新包.X，含 import static 形式）
//   ③ 补跨包 import（同包内原来不需要 import，拆开后需要）
//   ④ 删除变多余的「同包 import」
//
// 用法：
//   node tools/erp-split-apply.mjs --dry     只打印计划，不写盘
//   node tools/erp-split-apply.mjs           执行
//
// 为什么用脚本而不是手工：跨子包 import 需要新增 ~148 行，全限定名 33 处，
//   还有 15 处 import static —— 手工改必漏，而漏了会编译失败（能抓到但要 60 秒一轮）。
//   脚本一遍做完且幂等（重复跑无副作用）。
//
// ⚠️ 幂等设计：全限定名正则要求 `erp.`/`quality.` 后紧跟大写字母，
//   替换后的 `erp.stock.ErpX` 里 `erp.` 后是小写 `stock` ⇒ 不会二次匹配。
import { readFileSync, writeFileSync, readdirSync, mkdirSync, unlinkSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const PLAN = JSON.parse(readFileSync(join(ROOT, 'tools', 'erp-split-plan.json'), 'utf8'));
const DRY = process.argv.includes('--dry');

const MAIN_ROOT = join(ROOT, 'src', 'main', 'java');
const TEST_ROOT = join(ROOT, 'src', 'test', 'java');
const OLD_MAIN_PKG = 'com.factory.hub.erp';
const OLD_TEST_PKG = 'com.factory.hub.quality';

// ---------- 1. 建索引 ----------
const clsToPkg = new Map();   // 类名 → 新包（main + test）
const clsToSide = new Map();  // 类名 → 'main' | 'test'
const dup = [];
for (const side of ['main', 'test']) {
  for (const [pkg, list] of Object.entries(PLAN[side])) {
    for (const cls of list) {
      if (clsToPkg.has(cls)) dup.push(`${cls}: ${clsToPkg.get(cls)} 与 ${pkg}`);
      clsToPkg.set(cls, pkg);
      clsToSide.set(cls, side);
    }
  }
}
if (dup.length) {
  console.error('✗ 类名重复（会导致 import 无法唯一确定）：');
  for (const d of dup) console.error('   ' + d);
  process.exit(1);
}
console.log(`映射载入：main ${Object.values(PLAN.main).flat().length} + test ${Object.values(PLAN.test).flat().length} = ${clsToPkg.size} 个类，类名全部唯一 ✓`);

// 类名 → 当前文件路径（只扫两个旧包，main 与 test 各一份）
function listJava(dir) {
  if (!existsSync(dir)) return [];
  const out = [];
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) out.push(...listJava(p));
    else if (e.endsWith('.java')) out.push(p);
  }
  return out;
}

const oldMainDirs = [join(MAIN_ROOT, 'com/factory/hub/erp'), join(MAIN_ROOT, 'com/factory/hub/quality')];
const oldTestDirs = [join(TEST_ROOT, 'com/factory/hub/erp'), join(TEST_ROOT, 'com/factory/hub/quality')];
const oldMainFiles = oldMainDirs.flatMap(listJava);
const oldTestFiles = oldTestDirs.flatMap(listJava);

const clsToPath = new Map();
for (const p of [...oldMainFiles, ...oldTestFiles]) {
  const cls = p.split(/[\\/]/).pop().replace('.java', '');
  if (clsToPkg.has(cls)) clsToPath.set(cls, p);
}

// 目标目录：com.factory.hub.erp.stock → src/main/java/com/factory/hub/erp/stock
function pkgToDir(pkg, side) {
  const base = side === 'main' ? MAIN_ROOT : TEST_ROOT;
  // com.factory.hub.erp.quality.defect → com/factory/hub/erp/quality/defect
  return join(base, ...pkg.split('.'));
}

// ---------- 2. 改写工具 ----------
// 剥注释（只用于「扫短名」，不用于写回）
function stripComments(s) {
  return s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');
}

// 全限定名正则：erp./quality. 后必须紧跟大写字母（避免匹配已改好的 erp.stock.X）
const FQN_RE = /com\.factory\.hub\.(?:erp|quality)\.([A-Z][A-Za-z0-9_]*)/g;

const stats = { moved: 0, fqnFixed: 0, importAdded: 0, importDropped: 0, filesTouched: 0 };
const problems = [];

function newFqnOf(cls) {
  const pkg = clsToPkg.get(cls);
  return pkg ? `${pkg}.${cls}` : null;
}

// 处理单个文件；返回 { content, newPath } 或 null（无需改动）
function transform(path) {
  const cls = path.split(/[\\/]/).pop().replace('.java', '');
  const isMigrating = clsToPkg.has(cls) && clsToPath.get(cls) === path;
  const origPkgMatch = readFileSync(path, 'utf8').match(/^package\s+([\w.]+);/m);
  if (!origPkgMatch) {
    problems.push(`${relative(ROOT, path)}: 找不到 package 声明`);
    return null;
  }
  const origPkg = origPkgMatch[1];

  // 新包：迁移文件用 PLAN 的包；其余文件保持原包
  const newPkg = isMigrating ? clsToPkg.get(cls) : origPkg;
  const side = path.includes(`${join('src', 'test', 'java')}`) ? 'test' : 'main';

  let s = readFileSync(path, 'utf8');
  const before = s;

  // ① package 声明
  if (isMigrating && origPkg !== newPkg) {
    s = s.replace(/^package\s+[\w.]+;/m, `package ${newPkg};`);
  }

  // ② 全限定名重写（覆盖 import 行、import static 行、正文内联 FQN）
  s = s.replace(FQN_RE, (whole, c) => {
    const nf = newFqnOf(c);
    if (!nf) return whole;              // 不是本域的类，保持原样
    if (nf === whole) return whole;
    stats.fqnFixed++;
    return nf;
  });

  // ③ 删除「变多余的」同包普通 import（迁移后才可能出现）
  const lines = s.split('\n');
  const kept = [];
  for (const line of lines) {
    const m = line.match(/^import\s+(?!static)([\w.]+)\s*;\s*$/);
    if (m) {
      const fqn = m[1];
      const lastDot = fqn.lastIndexOf('.');
      if (lastDot > 0 && fqn.slice(0, lastDot) === newPkg) {
        stats.importDropped++;          // 与本文件同包 ⇒ 多余
        continue;
      }
    }
    kept.push(line);
  }
  s = kept.join('\n');

  // ④ 补跨包 import
  //   把正文（去 import 行、去注释）里的 FQN 折叠掉，避免「用了全限定名」被误判成「用了短名」
  const liveLines = s.split('\n').filter((l) => !/^\s*import\s/.test(l)).join('\n');
  let probe = stripComments(liveLines);
  probe = probe.replace(FQN_RE, '§');           // FQN 用法不产生短名需求

  const existing = new Set();
  for (const m of s.matchAll(/^import\s+(?:static\s+)?([\w.]+)/gm)) existing.add(m[1]);

  const toAdd = [];
  for (const [c, pkg] of clsToPkg) {
    if (c === cls) continue;
    if (pkg === newPkg) continue;                       // 同包 ⇒ 不需要
    if (!new RegExp(`\\b${c}\\b`).test(probe)) continue; // 正文没用短名
    const fqn = `${pkg}.${c}`;
    if (existing.has(fqn)) continue;
    // 已用全限定名引用过（probe 已折叠，这里再保险查一次原文）
    if (s.includes(fqn + '.') || new RegExp(`\\b${fqn.replace(/\./g, '\\.')}\\b`).test(s)) {
      // 原文里出现过该 FQN ⇒ 不需要 import（FQN 独立可用）
      // 但仍可能同时用短名 ⇒ 保守起见只在「FQN 出现次数覆盖不到短名用法」时加
      // 简化：FQN 出现过就跳过，避免冗余 import（编译不会因此失败）
      continue;
    }
    toAdd.push(fqn);
  }

  if (toAdd.length) {
    toAdd.sort();
    // 插到最后一个 import 行之后；没有 import 就插到 package 行之后
    const arr = s.split('\n');
    let lastImport = -1;
    for (let i = 0; i < arr.length; i++) if (/^\s*import\s/.test(arr[i])) lastImport = i;
    let insertAt;
    if (lastImport >= 0) insertAt = lastImport + 1;
    else {
      let pkgLine = arr.findIndex((l) => /^\s*package\s/.test(l));
      insertAt = pkgLine + 1;
      // 跳过 package 后的空行
      while (insertAt < arr.length && arr[insertAt].trim() === '') insertAt++;
      // 插入前补一个空行分隔
      arr.splice(insertAt, 0, '');
      insertAt++;
    }
    arr.splice(insertAt, 0, ...toAdd.map((f) => `import ${f};`));
    s = arr.join('\n');
    stats.importAdded += toAdd.length;
  }

  if (s === before && !isMigrating) return null;

  const newPath = isMigrating
    ? join(pkgToDir(newPkg, side), `${cls}.java`)
    : path;
  stats.filesTouched++;
  return { content: s, newPath, oldPath: path, moved: isMigrating && newPath !== path };
}

// ---------- 3. 全量扫描 ----------
// 只处理「自己会迁移」或「引用了本域类」的文件，其余不动
const allFiles = [...listJava(MAIN_ROOT), ...listJava(TEST_ROOT)];
const todo = [];
for (const p of allFiles) {
  const cls = p.split(/[\\/]/).pop().replace('.java', '');
  const migrating = clsToPkg.has(cls) && clsToPath.get(cls) === p;
  if (!migrating) {
    const txt = readFileSync(p, 'utf8');
    if (!/com\.factory\.hub\.(?:erp|quality)\./.test(txt)) continue; // 与本域无关
    // 只含 javadoc 提及、无 import 无正文引用的，也会被 transform 处理成「无改动」
  }
  const r = transform(p);
  if (r) todo.push(r);
}

console.log(`\n待改写文件 ${todo.length} 个（其中移动 ${todo.filter((t) => t.moved).length} 个）`);
console.log(`  全限定名重写 ${stats.fqnFixed} 处 | 新增 import ${stats.importAdded} 行 | 删除同包 import ${stats.importDropped} 行`);

if (problems.length) {
  console.error('\n✗ 问题：');
  for (const p of problems) console.error('   ' + p);
  process.exit(1);
}

if (DRY) {
  console.log('\n[dry-run] 只打印，不写盘。前 20 个移动示例：');
  for (const t of todo.filter((x) => x.moved).slice(0, 20)) {
    console.log(`   ${relative(ROOT, t.oldPath).replace(/\\/g, '/')}`);
    console.log(`     → ${relative(ROOT, t.newPath).replace(/\\/g, '/')}`);
  }
  console.log('\n执行请去掉 --dry');
  process.exit(0);
}

// ---------- 4. 落盘 ----------
// 先写新位置（内容已含新 package），再删旧文件，避免中途崩溃丢文件
for (const t of todo) {
  mkdirSync(dirname(t.newPath), { recursive: true });
  writeFileSync(t.newPath, t.content, 'utf8');
  if (t.moved && existsSync(t.oldPath) && t.oldPath !== t.newPath) unlinkSync(t.oldPath);
}

console.log(`\n✓ 落盘完成：改写 ${todo.length} 个文件，迁移 ${todo.filter((t) => t.moved).length} 个`);
console.log('下一步：node tools/verify-erp-mapping.mjs && node tools/verify-package-split.mjs && mvn clean test');
