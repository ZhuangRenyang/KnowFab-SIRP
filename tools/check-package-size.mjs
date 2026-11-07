// 防止 `erp` 包重新长成「上帝包」的护栏。
//
// ⭐ 为什么需要：2026-11-07 拆包前，`com.factory.hub.erp` 有 **68 个文件、零子包**
//   （14 Controller + 14 Service + 16 Repository + 16 Entity 全平铺）⇒ 占后端 27.5%
//   ⇒ 点开一个包看到 68 个文件，开发体验与可维护性双降。
//   ⇒ 拆完之后如果不加护栏，**3 个月后它会长回去** ⇒ 「拆了等于没拆」。
//
// ⚠️ 本护栏测的是**结构形态**，不是「代码写得好不好」：
//   它能挡住「文件又堆回一个包里」，但挡不住「一个包里 30 个 Controller」这种同形态问题
//   ⇒ 因此同时限制**单包的 Controller/Service/Repository 数量**。
//
// 用法：node tools/check-package-size.mjs
//   （本脚本只读文件结构，不编译、不需要数据库 ⇒ 可挂在门禁的后端块外面）
//
// 退出码：0 = 通过；1 = 有包超限；2 = 环境错误
import { readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const SRC = join(ROOT, 'src', 'main', 'java', 'com', 'factory', 'hub');

// ---- 阈值（按「当前实际分布 + 留余量」定，不是拍脑袋）----
const MAX_FILES_PER_DIR = 24;   // 单个目录（不含子目录）的直接文件数上限
const MAX_KIND_PER_PKG = 12;    // 单个包里同一类角色（Controller/Service/Repository）的数量上限
const MAX_FILES_PER_PKG = 40;   // 单个包**含子包**的文件数上限

// ⚠️⚠️ **基线豁免：存量超标项不拦，只拦「比基线更差」**
//   为什么必须有：本次整改范围是「拆 erp + 收进 quality」，
//   而 `quality` 包本身就有 38 个文件 ⇒ 若不留豁免，**这条护栏一挂门禁就红**
//   ⇒ 门禁红了 ⇒ 没人能用 ⇒ 护栏变成摆设。
//   ⇒ 正确形态：**存量记为基线 → 只拦「比基线更差」→ 拆完后基线需人工下调**。
//   （这是「门禁防新增不治存量」的标准实现，也符合「一个永远红的门禁等于没有门禁」。）
//
//   格式：`包名: { direct, kinds: {角色: 数量}, total }` —— 记录的是**实测值**。
//   ⚠️ 这些数字**必须实测**，不许凭印象写 —— 我第一版就是凭印象写的，
//      结果 quality 的 Service/Repository 数量全错 ⇒ 护栏立刻误报 ⇒
//      **一个乱报的护栏比没有护栏更糟**（它会让人养成忽略它的习惯）。
//   ⭐ **基线不会自动下调**（脚本只**打印提示**，改基线要人工改这里）——
//      这是刻意的：自动下调意味着「拆完后 erp 降到 20，基线也变 20，
//      将来它悄悄长回 68 也不报」⇒ **等于把护栏废掉**。
//      ⇒ 拆包完成后**必须人工把基线改成新的实测值**（运维会做）。
const BASELINE = {
  '(root)': { direct: 2, total: 247, kinds: {"Controller":1} },
  ai: { direct: 13, total: 13, kinds: {"Controller":2,"Repository":2,"Service":2} },
  alarm: { direct: 14, total: 14, kinds: {"Controller":1,"Repository":1,"Service":1} },
  audit: { direct: 5, total: 5, kinds: {"Controller":1,"Repository":1,"Service":1} },
  auth: { direct: 7, total: 7, kinds: {"Controller":2,"Repository":1,"Service":1} },
  collect: { direct: 3, total: 3, kinds: {} },
  config: { direct: 6, total: 6, kinds: {"Repository":1} },
  demand: { direct: 9, total: 9, kinds: {"Controller":1,"Service":1,"Repository":1} },
  discovery: { direct: 4, total: 4, kinds: {"Controller":1,"Service":1} },
  driver: { direct: 6, total: 16, kinds: {} },
  'driver/modbus': { direct: 6, total: 6, kinds: {} },
  'driver/opcua': { direct: 3, total: 3, kinds: {} },
  'driver/sim': { direct: 1, total: 1, kinds: {} },
  energy: { direct: 13, total: 13, kinds: {"Service":2,"Controller":1,"Repository":1} },
  // 2026-11-07 拆包完成：direct 68→13（根包只剩共享内核与契约），total 106（含 7 个子包）
  // ⚠️ 这条基线必须与 `--print-baseline` 的实测值一致；拆包后忘了下调 = 护栏变成
  //    「还能再塞 55 个文件才报」的空壳（架构师 advisory 点名的交接项）
  erp: { direct: 13, total: 106, kinds: { Controller: 1, Repository: 3 } },
  manual: { direct: 5, total: 5, kinds: {"Controller":1,"Repository":1,"Service":1} },
  model: { direct: 4, total: 4, kinds: {} },
  repo: { direct: 2, total: 2, kinds: {"Repository":2} },
  report: { direct: 13, total: 13, kinds: {"Repository":2,"Controller":1,"Service":1} },
  screen: { direct: 4, total: 4, kinds: {"Controller":1,"Service":1} },
  service: { direct: 1, total: 1, kinds: {"Service":1} },
  system: { direct: 14, total: 14, kinds: {"Controller":4,"Service":3,"Repository":1} },
  web: { direct: 3, total: 6, kinds: {"Controller":2} },
  'web/dto': { direct: 3, total: 3, kinds: {} },
};

const problems = [];
const improved = [];   // 比基线更好的包 ⇒ 需要人工下调基线（脚本只提示，不自动改）

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (e.endsWith('.java')) out.push(p);
  }
  return out;
}

// ---- 统计每个「包目录」（= 有 .java 文件的目录）的直属文件与含子包总数 ----
const pkgDirs = new Map();   // dir -> { files, subdirs, total }
function countAll(d) {
  let n = 0;
  for (const e of readdirSync(d)) {
    const p = join(d, e);
    if (statSync(p).isDirectory()) n += countAll(p);
    else if (e.endsWith('.java')) n++;
  }
  return n;
}
function collect(dir) {
  const entries = existsSync(dir) ? readdirSync(dir) : [];
  const files = entries.filter((e) => e.endsWith('.java'));
  const subdirs = entries.filter((e) => statSync(join(dir, e)).isDirectory());
  if (files.length || subdirs.length) {
    let total = files.length;
    for (const s of subdirs) total += countAll(join(dir, s));
    pkgDirs.set(dir, { files, subdirs, total });
  }
  for (const s of subdirs) collect(join(dir, s));
}
collect(SRC);

for (const [dir, info] of pkgDirs) {
  const name = relative(SRC, dir).replace(/\\/g, '/') || '(root)';
  const base = BASELINE[name];

  // 1) 含子包的文件总数（防止「把子包搬来搬去让某个包重新膨胀」）
  if (base) {
    if (info.total > base.total) {
      problems.push(
        `包 ${name} 含子包共 ${info.total} 个文件 > 基线 ${base.total}`
        + ` ⇒ 比整改前更差了`
      );
    } else if (info.total < base.total) {
      improved.push(`包 ${name}：含子包总数 ${base.total} → ${info.total}（基线需人工下调）`);
    }
  } else if (info.total > MAX_FILES_PER_PKG) {
    problems.push(
      `包 ${name} 含子包共 ${info.total} 个文件（上限 ${MAX_FILES_PER_PKG}）⇒ 该拆了`
    );
  }

  // 2) 直属文件数
  if (base) {
    if (info.files.length > base.direct) {
      problems.push(
        `包 ${name} 的直属文件数 ${info.files.length} > 基线 ${base.direct}`
        + ` ⇒ 比整改前更差了（基线是「不治存量、只防恶化」）`
      );
    } else if (info.files.length < base.direct) {
      improved.push(`包 ${name}：直属文件 ${base.direct} → ${info.files.length}（基线需人工下调）`);
    }
  } else if (info.files.length > MAX_FILES_PER_DIR) {
    problems.push(
      `包 ${name} 有 ${info.files.length} 个直属文件（上限 ${MAX_FILES_PER_DIR}）`
      + ` ⇒ 文件又堆在一个包里了。该拆成子包。`
    );
  }

  // 2) 同一类角色数量（只管 Controller/Service/Repository/Test ——
  //    「其它」= Entity/DTO/枚举，数量多很正常 ⇒ 有限额就是误报）
  const kinds = new Map();
  for (const f of info.files) {
    const m = f.match(/(Controller|Service|Repository|Test|TestKit)\.java$/);
    if (!m) continue;                       // ← 排除「其它」
    const k = m[1];
    if (!kinds.has(k)) kinds.set(k, []);
    kinds.get(k).push(f);
  }
  for (const [k, list] of kinds) {
    const limit = base ? (base.kinds[k] ?? 0) : MAX_KIND_PER_PKG;
    if (base) {
      if (list.length > limit) {
        problems.push(
          `包 ${name} 的 ${k} 数量 ${list.length} > 基线 ${limit}`
          + ` ⇒ 比整改前更差了`
        );
      }
    } else if (list.length > MAX_KIND_PER_PKG) {
      problems.push(
        `包 ${name} 有 ${list.length} 个 ${k}（上限 ${MAX_KIND_PER_PKG}）`
        + ` ⇒ 同类角色过多，该按业务子域再分。`
      );
    }
  }
}

// ---- 汇总：打印各包规模，方便人工复核 ----
const rows = [];
for (const [dir, info] of pkgDirs) {
  const name = relative(SRC, dir).replace(/\\/g, '/') || '(root)';
  rows.push([name, info.files.length, info.total]);
}
rows.sort((a, b) => b[2] - a[2]);

// ---- `--print-baseline`：自动生成基线，**根治「凭印象写数字」** ----
// ⚠️⚠️ 为什么必须有这个模式（我连犯三次）：
//   BASELINE 里的数字我**手写**了三次，三次都写错（quality 的 Service/Repository、
//   root 的 kinds）⇒ 每次都让护栏**立刻误报** ⇒ 一个乱报的护栏比没有护栏更糟。
//   ⇒ 根治：**基线必须由工具生成，人只负责复制粘贴**。
//   用法：node tools/check-package-size.mjs --print-baseline
if (process.argv.includes('--print-baseline')) {
  console.log('// 把下面这段粘到本文件的 BASELINE 里（然后按需删掉不再需要的包）：');
  console.log('const BASELINE = {');
  // ⚠️ 直接遍历 pkgDirs（key 就是目录路径）⇒ 不用反推路径（ES module 里没有 require）
  for (const [dir, info] of pkgDirs) {
    const n = relative(SRC, dir).replace(/\\/g, '/') || '(root)';
    const kinds = {};
    for (const f of info.files) {
      const m = f.match(/(Controller|Service|Repository|Test|TestKit)\.java$/);
      if (!m) continue;
      kinds[m[1]] = (kinds[m[1]] || 0) + 1;
    }
    const key = n === '(root)' ? "'(root)'" : `'${n}'`;
    console.log(`  ${key}: { direct: ${info.files.length}, total: ${info.total}, kinds: ${JSON.stringify(kinds)} },`);
  }
  console.log('};');
  process.exit(0);
}

console.log('包规模（直属 / 含子包）：');
for (const [n, direct, total] of rows.slice(0, 12)) {
  console.log(`  ${String(total).padStart(4)} / ${String(direct).padStart(3)}  ${n}`);
}

if (problems.length) {
  console.error(`\n✗ 包结构超出阈值（${problems.length} 项）：\n`);
  for (const p of problems) console.error('  ' + p);
  console.error('\n（拆分前 `erp` 是 68 文件零子包 ⇒ 这条护栏就是为了防止它长回去）');
  process.exit(1);
}
if (improved.length) {
  console.log('');
  for (const s of improved) console.log(`  ↻ ${s}`);
  console.log('  ⇒ 以上基线**需人工下调**（脚本刻意不自动改：自动下调会让它将来长回去也不报）');
}
console.log(`\n✓ 包结构在阈值内（直属 ≤ ${MAX_FILES_PER_DIR} / 含子包 ≤ ${MAX_FILES_PER_PKG} / 同类角色 ≤ ${MAX_KIND_PER_PKG}）`);
