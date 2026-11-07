// 拆包验收脚本：**在编译之前**检出「漏改 import / 残留旧全限定名」。
//
// ⭐ 为什么需要它（拆包的真实成本）：
//   同包内互相引用**不需要 import** ⇒ 拆成子包后，**跨子包引用必须新增 import**
//   ⇒ 漏一个就是「编译失败：找不到符号」。
//   ⇒ `mvn clean test` 能抓到，但要 **60 秒**；本脚本 **1 秒**，
//      且能**逐条列出**漏在哪一行 ⇒ 属于「把编译器的报错提前到秒级」。
//
// ⚠️⚠️ **本脚本的第一版有一个「永远绿」的 bug，是被反向验证抓出来的**（2026-11-07）：
//   检查 1 我写成「只有当旧全限定名在索引里还能查到时才报」
//   ⇒ 而类一旦搬到子包，**旧路径恰恰就不存在了** ⇒ 永远查不到 ⇒ **这个检查永远绿**
//   ⇒ **一个永远绿的检查比没有还糟**（它会给人「已验证」的错觉）。
//   ⇒ 修法：改为「找该类**当前实际位置**，与旧全限定名不符就报」，并**同时给出正确的新名**。
//   ⇒ 这条教训来自项目既有铁律：**「绿灯最危险」—— 绿从来不会有人多看一眼**。
//   ⇒ 所以本脚本**必须**保留反向验证的用例（把某个类临时移到子包 ⇒ 必须报出残留）。
//
// 用法：node tools/verify-package-split.mjs [--expect-total 132]
//   --expect-total N：**erp 域的文件总数**必须等于 N（守恒检查）
//
// ⚠️⚠️ **守恒口径：统计 `com.factory.hub.erp.**` + `com.factory.hub.quality.**` 两个前缀下的
//   全部 .java 文件（main + test）** —— 不是「只算 erp 包」。
//   为什么（运维 2026-11-07 指出，是我漏掉的）：B 版改造要把 `quality` 包的 38+17 个
//   Erp\* 类整体移进 `erp.quality/` ⇒ 如果只数 `com.factory.hub.erp` 包，文件数会**变大**
//   ⇒ 守恒检查会红 ⇒ 会被误读成「拆包丢了文件」。
//   ⇒ 用「两前缀合计」的口径：**改造前后都恒等于 132**，且搬到哪里都成立。
//   （132 = main 106（erp 68 + quality 38）+ test 26（erp 9 + quality 17））
//
// ⚠️⚠️ **默认值必须是真实的期望值，不能是 null**（运维 2026-11-07 抓出，这是「永远绿」级别）：
//   第一版我写成 `expectIdx >= 0 ? Number(...) : null` ⇒ 不传参数时**守恒检查被静默跳过**，
//   但脚本**照样打印「✓ 未发现残留旧全限定名 / 悬空 import」**
//   ⇒ `check()` 的「退出码 0 但无输出」假绿检测**兜不住**（因为确有输出）
//   ⇒ 得到一条**永远绿的护栏** —— 而「永远绿的护栏比没有护栏更糟」。
//   ⇒ 修法：**默认值就是 132**，忘了传参数也不会绿。
//   ⇒ 通用判据：**「忘了传参数」不应导致检查消失** ⇒ 正确性不能外包给调用点。
//
// 退出码：0 = 通过；1 = 发现问题（逐条列出）；2 = 用法/环境错误
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const SRC = join(ROOT, 'src', 'main', 'java');
const TEST = join(ROOT, 'src', 'test', 'java');
const PKG_ROOT = 'com.factory.hub';

/** erp 域 = erp 包 + quality 包（quality 整个就是 erp 域的质量子域，100% Erp* 命名） */
const DOMAIN_PREFIXES = [`${PKG_ROOT}.erp`, `${PKG_ROOT}.quality`];

/** erp 域的文件总数期望值（main 106 + test 26 = 132）—— **实测值，不是估计** */
const EXPECT_TOTAL_DEFAULT = 132;

const args = process.argv.slice(2);
const expectIdx = args.indexOf('--expect-total');
const EXPECT_TOTAL = expectIdx >= 0 ? Number(args[expectIdx + 1]) : EXPECT_TOTAL_DEFAULT;

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (e.endsWith('.java')) out.push(p);
  }
  return out;
}

/** 全限定类名 → 文件路径 */
const CLASS_INDEX = new Map();
for (const root of [SRC, TEST]) {
  for (const f of walk(root)) {
    const rel = relative(root, f).replace(/\\/g, '/').replace(/\.java$/, '');
    CLASS_INDEX.set(rel.replace(/\//g, '.'), f);
  }
}

const problems = [];

// ---------- 检查 1：残留的旧全限定名 ----------
// 形如 com.factory.hub.erp.ErpStockService —— 如果 ErpStockService 已经搬到子包，
// 这个全限定名就失效了（编译错误）。
//
// ⚠️⚠️ **第一版这里写反了**（2026-11-07 自查发现）：
//   我原来写成「只有当旧全限定名在 CLASS_INDEX 里还能查到时才报」
//   ⇒ 而类一旦搬到子包，**旧路径恰恰就不存在了** ⇒ 永远查不到 ⇒ **这个检查永远绿**
//   ⇒ 一个永远绿的检查比没有还糟。
//   ⇒ 正确逻辑：**找到该类当前的实际位置**，若实际位置 ≠ 旧全限定名 ⇒ 它已过时 ⇒ 报。
//   （这个 bug 是被**反向验证**抓出来的：真把类移到子包后检查却没响 ⇒ 顺着查下去才发现逻辑反了。）
{
  const erpRoot = join(SRC, 'com', 'factory', 'hub', 'erp');
  const all = [...walk(SRC), ...walk(TEST)];

  /** 类名 → 当前实际所在的完整限定名 */
  const actualLocation = new Map();
  for (const fq of CLASS_INDEX.keys()) {
    const cls = fq.slice(fq.lastIndexOf('.') + 1);
    if (!actualLocation.has(cls)) actualLocation.set(cls, fq);
  }

  for (const file of all) {
    const text = readFileSync(file, 'utf8');
    const lines = text.split('\n');
    lines.forEach((line, i) => {
      // ⚠️⚠️ **import 行也要判「过时」**（2026-11-07 架构师抓到 15 处 `import static` 后我改了这里）：
      //   第一版我写 `if (/^\s*import\s/.test(line)) return;` ⇒ **完全跳过 import 行**
      //   ⇒ 而 `import static com.factory.hub.quality.ErpQcBody.str;` 这类**静态导入**
      //   是「旧全限定名」的一种形态 ⇒ 被漏掉了。
      //   ⇒ 现在改成：**import 行也用「目标类的实际位置 ≠ import 路径」来判定**。
      //   ⇒ 静态导入自动被覆盖，且**比之前更早暴露问题**（不必等编译失败）。
      const isImport = /^\s*import\s/.test(line);
      // ⚠️ 前缀要覆盖**两个域**（erp + quality），与守恒口径一致
      //   ⇒ 否则 `import static com.factory.hub.quality.ErpQcBody.str;` 这类**静态导入**
      //   在 quality 尚未搬进 erp 时**根本匹配不到** ⇒ 检查 1 对它无效。
      const re = new RegExp(`${PKG_ROOT}\\.(?:erp|quality)\\.([A-Z][A-Za-z0-9_]*)`, 'g');
      let m;
      while ((m = re.exec(line))) {
        const cls = m[1];
        const actual = actualLocation.get(cls);
        if (!actual) continue;                          // 该类不存在（别的检查管）
        // 「旧路径」= 去掉类名后的前缀部分
        const oldPath = m[0].slice(0, m[0].length - cls.length);
        if (actual === oldPath + cls) continue;        // 完全匹配 ⇒ 合法
        problems.push(
          `${isImport ? '过时的 import' : '残留旧全限定名'}：${relative(ROOT, file)}:${i + 1}  「${m[0]}」`
          + ` ⇒ ${cls} 现在位于 ${actual}，应改成 ${actual} 或短名+import`
        );
      }
    });
  }
}

// ---------- 检查 2：import 指向不存在的类 ----------
{
  const all = [...walk(SRC), ...walk(TEST)];
  for (const file of all) {
    const text = readFileSync(file, 'utf8');
    const lines = text.split('\n');
    lines.forEach((line, i) => {
      const m = line.match(/^\s*import\s+(?:static\s+)?(com\.factory\.hub\.[A-Za-z0-9_.]+)\s*;/);
      if (!m) return;
      const fq = m[1];
      if (CLASS_INDEX.has(fq)) return;
      // 静态导入可能指向成员，简化为「类路径存在即通过」
      const parts = fq.split('.');
      let ok = false;
      for (let i2 = parts.length; i2 > 3; i2--) {
        const cand = parts.slice(0, i2).join('.');
        if (CLASS_INDEX.has(cand)) { ok = true; break; }
      }
      if (!ok) {
        problems.push(
          `import 指向不存在的类：${relative(ROOT, file)}:${i + 1}  「${fq}」`
          + ` ⇒ 目标类找不到（漏改包名 / 类被删 / 拼写错）`
        );
      }
    });
  }
}

// ---------- 检查 3：文件总数守恒 ----------
// ⚠️ 口径是「**erp 域**」= erp 包 + quality 包（见文件头说明），不是「只算 erp 包」
{
  const countDomain = (root) => {
    let n = 0;
    const walk2 = (dir) => {
      if (!existsSync(dir)) return;
      for (const e of readdirSync(dir)) {
        const p = join(dir, e);
        if (statSync(p).isDirectory()) walk2(p);
        else if (e.endsWith('.java')) {
          // 相对路径反推完整类名，判断是否属于 erp 域
          const rel = relative(root, p).replace(/\\/g, '/').replace(/\.java$/, '');
          const fq = rel.replace(/\//g, '.');
          if (DOMAIN_PREFIXES.some((pre) => fq === pre || fq.startsWith(pre + '.'))) n++;
        }
      }
    };
    walk2(root);
    return n;
  };
  const main = countDomain(SRC);
  const test = countDomain(TEST);
  const total = main + test;
  if (total !== EXPECT_TOTAL) {
    problems.push(
      `erp 域文件总数不守恒：期望 ${EXPECT_TOTAL}（main+test），实际 ${total}（main ${main} + test ${test}）`
      + ` ⇒ 拆包过程中**丢文件或多出文件**了`
    );
  } else {
    console.log(`✓ erp 域文件总数守恒：${total}（main ${main} + test ${test}）`);
  }
}

// ---------- 输出 ----------
if (problems.length) {
  console.error(`\n✗ 发现 ${problems.length} 个问题：\n`);
  for (const p of problems) console.error('  ' + p);
  console.error('\n（这些问题会让编译失败。本脚本 1 秒出结果，mvn 编译要 60 秒 —— 先跑本脚本再跑 mvn。）');
  process.exit(1);
}
console.log('✓ 未发现残留旧全限定名 / 悬空 import');
