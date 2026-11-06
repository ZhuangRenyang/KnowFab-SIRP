#!/usr/bin/env node
/**
 * 报警码字典校验器的「变异验证」——
 *
 * 判据：**故意注入一个错误 → 校验器必须 exit 非 0，而且失败必须正是这一条造成的**。
 *
 * 「跑一下 exit 0」不能证明门禁有效（本项目已经栽过一次：
 *   暂存区里的 check-alarmcodes.mjs 是个 117 行空壳，跑一下就 exit 0，什么都拦不住）。
 * 只断「exit 非 0」也不够 —— 别的因素让它红，你这条变异就会**假通过**。
 * 所以每条变异要**同时**满足三条：
 *   ① exit 非 0
 *   ② 输出里出现**期望的诊断文案**（红得对，不是红在别的地方）
 *   ③ **失败条数正好等于预期**（多一条 ⇒ 还有别的东西混进来了）
 *
 * 做法：把字典目录 + manifest 拷到临时目录 → 注入一个错误 →
 *       用 ALARM_DIR / ALARM_MANIFEST 指向临时目录跑校验器 → 断言上面三条。
 *       **不碰真文件**（校验器支持环境变量覆盖）。每条之后再跑一次真文件 ⇒ 必须回到绿。
 *
 * ⚠️ **锚点必须挂在结构上，不能挂在字典内容/文件名上** ——
 *   报警码字典是**手写维护**的（加品牌、改码、改文件名）。
 *   写死 'yaskawa-servo.json' 的话，字典一改，这条变异就会：
 *     · 注入失败（要人改脚本）—— 至少是响的
 *     · 或者**静默地验了别的东西**（文件被改名/键序变了 ⇒ 注入的内容跟着变）—— 这是哑的
 *   ⇒ 所以下面每条变异都用**结构谓词**去挑目标文件（见 pick()），挑不到就报错退出。
 *
 * ⚠️ **失败条数是刻意的耦合**：校验器新增/合并一条断言，或字典内容变了，
 *    这里都要同步更新 expectFails。**这是故意的** —— 改的时候人会顺手看一眼
 *    「多出来的那条报错对不对」，而不是默默跟着红。
 */
import { cpSync, readFileSync, writeFileSync, rmSync, mkdirSync, readdirSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SRC_ALARM = join(ROOT, 'src', 'main', 'resources', 'alarmcodes');
const SRC_MANIFEST = join(ROOT, 'src', 'main', 'resources', 'alarmcode-manifest.json');
const TMP = join(ROOT, 'target', 'mutation-check');
const CHECKER = join(ROOT, 'scripts', 'check-alarmcodes.mjs');

function fresh() {
  // **只覆盖，不删除**：本脚本的变异全是「改现有文件的内容」，不新增文件，
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

/** 校验器输出里的「阻断」条数（[提示] 是 warning，不算失败） */
function countBlocks(out) {
  return out.split('\n').filter((l) => l.includes('[阻断]')).length;
}

/** index.txt 里登记的文件 —— 字典的清单，是结构的一部分 */
function listedFiles(d) {
  return readFileSync(join(d.alarm, 'index.txt'), 'utf8')
    .split(/\r?\n/).map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));
}

/**
 * 「干净」的字典文件：键全是**裸数字**、无补零、无字母。
 *
 * 为什么要这个谓词：台达（三位补零）与三菱（含字母 "8A"）在校验器里是**逐文件豁免**的，
 * 在这两个文件里注入畸形键可能**不被拦**（豁免只豁免对应的那一条检查）⇒ 变异会失效。
 * ⇒ 挑「干净」文件，注入的畸形键**必定**被拦 ⇒ 计数稳定。
 */
function isCleanFile(j) {
  const ks = Object.keys(j.codes || {});
  return ks.length > 0 && ks.every((k) => /^\d+$/.test(k) && !(k.length > 1 && k.startsWith('0')));
}

/**
 * 按**结构**挑目标字典文件 —— 不写死文件名。
 * 挑不到就抛错（注入失败 ≠ 通过），由调用方报错退出。
 */
function pick(d, pred, what) {
  for (const f of listedFiles(d)) {
    const p = join(d.alarm, f);
    if (!existsSync(p)) continue;
    let j;
    try { j = readJson(p); } catch { continue; }
    if (pred(j, f)) return { file: f, path: p, json: j };
  }
  throw new Error('按结构找不到满足「' + what + '」的字典文件 —— 注入失败，这一项不算数（不是「通过」）');
}

// ---- 11 类变异：每一类都必须被拦住 ----
// apply 返回 { marker }：期望在校验器输出里出现的诊断文案片段。
// expectFails：期望的「阻断」条数（**量出来的**，不是猜的）。
const MUTATIONS = [
  {
    name: '① 清单行被删（index.txt 少登记一个文件）',
    apply: (d) => {
      const p = join(d.alarm, 'index.txt');
      const lines = readFileSync(p, 'utf8').split(/\r?\n/).filter((l) => l.trim() && !l.startsWith('#'));
      if (lines.length < 2) throw new Error('index.txt 登记的文件太少，注入失败');
      lines.pop();
      writeFileSync(p, lines.join('\n') + '\n');
      return { marker: '没在 index.txt 里登记' };
    },
    expectFails: 1,
  },
  {
    name: '② 码键前后加了空格（永远查不到）',
    apply: (d) => {
      const t = pick(d, isCleanFile, '键全是裸数字的字典');
      const codes = t.json.codes;
      const first = Object.keys(codes)[0];
      codes[' ' + first] = codes[first];
      delete codes[first];
      writeJson(t.path, t.json);
      return { marker: '前后有空格' };
    },
    expectFails: 2,
  },
  {
    name: '③ remedy 被清空（查到但没告诉人怎么处理）',
    apply: (d) => {
      const t = pick(d, isCleanFile, '键全是裸数字的字典');
      const codes = t.json.codes;
      const k = Object.keys(codes)[0];
      codes[k].remedy = '';
      writeJson(t.path, t.json);
      return { marker: '缺 remedy' };
    },
    expectFails: 1,
  },
  {
    name: '④ 归一化碰撞（两个键归一到同一个值）',
    apply: (d) => {
      // 结构：存在键 k，且 'ER'+k 还没被占用 ⇒ 归一化后必然与 k 相同（normalize 会剥掉 ER）
      const usable = (j) => Object.keys(j.codes || {}).some((k) => !('ER' + k in j.codes) && /^[A-Z0-9]+$/.test(k));
      const t = pick(d, (j) => isCleanFile(j) && usable(j), '能构造归一化碰撞的字典');
      const codes = t.json.codes;
      const k = Object.keys(codes).find((x) => !('ER' + x in codes) && /^[A-Z0-9]+$/.test(x));
      // 只抄必填字段，**不抄 aliases** ——
      // 抄整条的话，报错条数会随「这个品牌有几个别名」变（同一次注入两次跑出不同条数），
      // 那样的条数就不是「这条变异造成的」，而是「字典内容造成的」⇒ 条数判据失去意义。
      codes['ER' + k] = { name: codes[k].name, cause: codes[k].cause, remedy: codes[k].remedy };
      writeJson(t.path, t.json);
      return { marker: '归一化后都是' };
    },
    expectFails: 3,
  },
  {
    name: '⑤ 两个文件用同一个 brand（加载时静默覆盖）',
    apply: (d) => {
      // 结构：找两个 brand 不同的字典文件，把 A 的 brand 改成 B 的
      const files = listedFiles(d).map((f) => {
        const p = join(d.alarm, f);
        if (!existsSync(p)) return null;
        let j;
        try { j = readJson(p); } catch { return null; }
        return j && j.brand ? { file: f, path: p, json: j, brand: String(j.brand) } : null;
      }).filter(Boolean);
      if (files.length < 2) throw new Error('字典文件不足两个，注入失败');
      const a = files[0];
      const b = files.find((x) => x.brand !== a.brand);
      if (!b) throw new Error('找不到两个 brand 不同的字典文件，注入失败');
      a.json.brand = b.brand;
      writeJson(a.path, a.json);
      return { marker: 'G3 brand 冲突' };
    },
    expectFails: 2,
  },
  {
    name: '⑥ 新品牌出现补零键（运行时喂裸整数，永远查不到）',
    apply: (d) => {
      const t = pick(d, isCleanFile, '键全是裸数字的字典');
      const codes = t.json.codes;
      // 注入一个**当前不存在**的补零键（已存在就会被覆盖 ⇒ 变异失效 ⇒ 静默通过）
      let key = '0182';
      while (key in codes) key = '0' + (parseInt(key, 10) + 1);
      codes[key] = { displayCode: 'A.182', name: '测试', cause: '测试', remedy: '测试' };
      writeJson(t.path, t.json);
      return { marker: key };
    },
    expectFails: 3,
  },
  {
    name: '⑦ 新品牌出现含字母键（运行时喂不到）',
    apply: (d) => {
      const t = pick(d, isCleanFile, '键全是裸数字的字典');
      const codes = t.json.codes;
      let key = 'ZZ99';
      while (key in codes) key = 'ZZ' + (parseInt(key.slice(2), 10) + 1);
      codes[key] = { displayCode: key, name: '测试', cause: '测试', remedy: '测试' };
      writeJson(t.path, t.json);
      return { marker: key };
    },
    expectFails: 2,
  },
  {
    name: '⑧ displayCode 被清空',
    apply: (d) => {
      const t = pick(d, (j) => isCleanFile(j) && Object.keys(j.codes || {})
        .some((k) => 'displayCode' in (j.codes[k] || {})), '至少有一条码带 displayCode 的字典');
      const codes = t.json.codes;
      const k = Object.keys(codes).find((x) => 'displayCode' in (codes[x] || {}));
      codes[k].displayCode = '';
      writeJson(t.path, t.json);
      return { marker: 'displayCode 为空' };
    },
    expectFails: 1,
  },
  {
    name: '⑨ 别名覆盖另一个主键（查那个数字会拿到别的含义）',
    apply: (d) => {
      const t = pick(d, (j) => isCleanFile(j) && Object.keys(j.codes || {}).length >= 2, '至少有两条码的字典');
      const codes = t.json.codes;
      const ks = Object.keys(codes);
      codes[ks[0]].aliases = [...(codes[ks[0]].aliases || []), ks[1]];
      writeJson(t.path, t.json);
      return { marker: '不得覆盖主键' };
    },
    expectFails: 1,
  },
  {
    name: '⑩ 别名 = 另一个条目的别名（两个主键抢同一个别名）',
    apply: (d) => {
      const t = pick(d, (j) => isCleanFile(j) && Object.values(j.codes || {})
        .some((it) => Array.isArray(it.aliases) && it.aliases.length > 0), '至少有一条码带别名的字典');
      const codes = t.json.codes;
      const ks = Object.keys(codes);
      const donorEntry = ks.find((k) => Array.isArray(codes[k].aliases) && codes[k].aliases.length > 0);
      const donor = codes[donorEntry].aliases[0];
      // victim 只要「当前没有这个别名」即可；
      // 把它的别名**整个替换**成被抢的那一个（而不是追加）——
      // 追加的话，它原有的别名也可能带来额外报错，条数就不只由注入决定了。
      const victim = ks.find((k) => k !== donorEntry && !(codes[k].aliases || []).includes(donor));
      if (!victim) throw new Error('找不到能抢该别名的条目，注入失败');
      codes[victim].aliases = [donor];
      writeJson(t.path, t.json);
      return { marker: '共用' };
    },
    expectFails: 2,
  },
  {
    name: '⑪ manifest 条数与字典对不上',
    apply: (d) => {
      const m = readJson(d.manifest);
      const entry = (m.files || []).find((x) => x && x.file && existsSync(join(d.alarm, x.file)));
      if (!entry) throw new Error('manifest 里没有可用条目，注入失败');
      entry.expectedCount = 99999;
      writeJson(d.manifest, m);
      return { marker: 'manifest 声明 expectedCount' };
    },
    expectFails: 1,
  },
];

console.log('=== 变异验证：注入一个错误 → 校验器必须 exit 非 0，且失败正是这一条造成的 ===\n');

// 基线：未变异必须 exit 0（否则下面全都不算）
{
  const d = fresh();
  const r = runChecker({ ALARM_DIR: d.alarm, ALARM_MANIFEST: d.manifest });
  const n = countBlocks(r.out);
  const ok = r.code === 0 && n === 0;
  console.log(`  [基线] 未变异的字典（副本） → exit ${r.code}，阻断 ${n} 条  ${ok ? '✓' : '✗ 基线就不对，后面全不算'}`);
  if (!ok) { console.log(r.out.slice(0, 1200)); process.exit(1); }
}

let failed = 0;
for (const m of MUTATIONS) {
  const d = fresh();
  let injected;
  try {
    injected = m.apply(d);
  } catch (e) {
    // 注入失败 = 这一项**不算数**。绝不能当成「校验器是绿的」——
    // 「没注入成功」和「校验器拦住了」是两回事（同一个坑第三次出现）。
    failed++;
    console.log(`  ✗ 注入失败  ${m.name}`);
    console.log(`      ${e.message}`);
    continue;
  }
  const r = runChecker({ ALARM_DIR: d.alarm, ALARM_MANIFEST: d.manifest });
  const n = countBlocks(r.out);
  const marker = injected.marker;
  const ok = r.code !== 0 && r.out.includes(marker) && n === m.expectFails;
  if (!ok) failed++;
  const why = r.code === 0 ? '校验器没红（漏过）'
    : !r.out.includes(marker) ? `诊断不对（期望含「${marker}」）`
    : `失败条数不对（期望 ${m.expectFails}，实际 ${n}）`;
  console.log(`  ${ok ? '✓' : '✗ 漏过'} ${m.name.padEnd(40)} exit ${r.code}  阻断 ${n} 条`
    + (ok ? `（含「${marker}」）` : `  ← ${why}`));
  if (!ok) {
    for (const l of r.out.split('\n').filter((x) => x.includes('[阻断]')).slice(0, 3)) console.log('      ' + l.trim());
  }

  // 还原后必须回到绿：跑真文件 ⇒ 证明这一条变异没在共享工作树里留下残留
  const after = runChecker({});
  if (after.code !== 0 || countBlocks(after.out) !== 0) {
    failed++;
    console.log(`  ✗ 还原后仍红  ${m.name}  ← 变异有残留（真文件被改了？）`);
    for (const l of after.out.split('\n').filter((x) => x.includes('[阻断]')).slice(0, 3)) console.log('      ' + l.trim());
  }
}

// 收尾：清临时目录。**清不掉也不能让整条门禁红** —— 有些环境有批量删除保护，
// 清理失败与「字典有没有问题」无关，不该在这里阻断（先例里踩过：清目录被拦 ⇒ 门禁中断）。
try {
  rmSync(TMP, { recursive: true, force: true });
} catch (e) {
  console.log(`  （临时目录清理失败，不影响判定：${e.code || e.message}）`);
}

console.log(failed === 0
  ? `\n全部 ${MUTATIONS.length} 类变异都被拦住（exit 非 0 + 诊断正确 + 失败条数正好）✓`
  : `\n有 ${failed} 项不通过 ✗ —— 门禁形同虚设，不能提交`);
process.exit(failed === 0 ? 0 : 1);
