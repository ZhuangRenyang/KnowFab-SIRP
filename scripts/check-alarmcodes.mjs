#!/usr/bin/env node
/**
 * 报警码字典校验器
 * =================
 *
 * 为什么需要这个脚本（不是"锦上添花"）
 * ------------------------------------
 * 1. **打包不跑单测**。release.yml 走 `make-dist.sh --build`，内部是
 *    `mvn -q -DskipTests clean package` → JUnit 全程不执行；
 *    本机 `tools/release.sh` 同样不跑单测。
 *    → 「写了 JUnit 测试」在这个项目里**不等于**「测试会被跑」。
 *    这个脚本是纯静态检查，不需要数据库、不需要 jar、能进任何门禁。
 * 2. **字典的错误是静默的**。字典文件写错不会抛异常，只是「那个码永远查不到」，
 *    现场看到的就是"查品牌手册"。不比对数量的话没人会发现。
 * 3. **已经真的错过**。台达 14 条键是三位补零（"001"），而设备上报裸整数（1），
 *    normalize 后不匹配 → **14 条 100% 在生产中不可达**。本脚本就是防这类事的。
 *
 * ⚠️ **这个文件曾被截断成只剩注释头，脚本变成「永远退出 0」的假绿门禁** ——
 *    比没有门禁更危险（看起来在守，实际什么都没查）。
 *    → 改完这个文件**必须实际跑一次看输出**，确认它在检查而不是静默通过。
 *
 * 检查项
 * ------
 *   V1  index.txt 清单与实际 json 文件双向一一对应（漏登记 = 静默失效）
 *   V2  schema 完整（brand/brandText/note/codes + 每条 name/cause/remedy 非空）
 *   V3  brand 键与前端 BRANDS 常量一致（品牌错字 = 设备配了也不翻译）
 *   V4  **码键归一化后唯一**（同品牌内两个 key 归一到同一值 → 其中一条永远查不到）
 *   V5  **码键生产可达性**：模拟运行时喂裸整数，检查字典键能否被命中
 *   V6  key 不含前后空格（trim 后与原值不等 → 永远查不到）
 *   G7  **aliases[] 合法**：必须是数组、元素非空、**不得覆盖任何主键**
 *   V8  manifest 与字典文件一致（条数 / brand / 登记齐全）
 *
 * 为什么用 node 而不是 python
 * ---------------------------
 * 本机没有可用的 python（WindowsApps 下只有 Store 存根，真执行会跳商店），
 * 而 node 是项目前端工具链的既有依赖（scripts/check-i18n.mjs 就是同一类脚本）。
 *
 * 用法
 * ----
 *   node scripts/check-alarmcodes.mjs           # 校验，失败退出码 1
 *   node scripts/check-alarmcodes.mjs --quiet   # 只在失败时输出
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
// ALARM_DIR 可用环境变量覆盖 —— 只为「变异验证」服务（把字典拷到临时目录、注入一个错误、
// 断言校验器必须 exit 1），**默认行为不变**。没有它就只能改真文件来测，风险太高。
const ALARM_DIR = process.env.ALARM_DIR || join(ROOT, 'src', 'main', 'resources', 'alarmcodes');
const INDEX = join(ALARM_DIR, 'index.txt');
const FRONTEND_BRANDS = join(ROOT, 'frontend', 'src', 'api', 'devices.ts');
const MANIFEST = process.env.ALARM_MANIFEST || join(ROOT, 'src', 'main', 'resources', 'alarmcode-manifest.json');

/**
 * 项目已知的 servo_brand 取值（设备表单下拉 = 设备表 servo_brand）。
 *
 * ⚠️ **这一份和前端 `frontend/src/api/devices.ts` 的 BRANDS 常量是同一份事实的两个副本。**
 * 加品牌时两边都要改，否则：
 *   - 只改这边 → 前端选不到，字典是死键（V3 会拦）
 *   - 只改前端 → 字典没有该品牌，设备配了不翻译（G3 会拦）
 * 校验器会双向核对，所以两边不一致时一定会被报出来。
 */
const KNOWN_BRANDS = new Set([
  'mitsubishi', 'delta', 'yaskawa',
  'panasonic',        // 松下 MINAS A6 / A5
  'panasonic-a4',     // 松下 MINAS A4 老款（两位码，与 A6 不是同一套编号）
  'huichuan', 'plc',
]);

/**
 * 与 AlarmCodeDict.normalize() 逐字一致。
 *
 * ⚠️ **normalize() 已冻结，不得修改**。
 * 它服务的是「人工输入 AL.33 / Er.02」这类查询入口 —— 剥前缀正是它的用途。
 * 碰撞不是它的缺陷，而是「一个数字 key 只能对应一个含义」的必然结果，
 * 由 V4（归一化后 key 唯一）来守。
 *
 * 这里必须复制实现而不是调 Java —— 校验器要在没有 JDK 的环境也能跑。
 * 如果将来 AlarmCodeDict.normalize() 要改，**必须同步改这里**，
 * 否则校验器会验错东西（这是两份实现的一致性债务，改时要一起改）。
 */
function normalize(raw) {
  if (raw === null || raw === undefined) return '';
  let s = String(raw).replace(/(AL|ER|ERR|NO)/gi, '');
  s = s.replace(/\./g, '').replace(/#/g, '');
  s = s.replace(/\s/g, '');
  return s.toUpperCase();
}

/**
 * 键制豁免登记表（**逐文件登记 + 写理由**，不做整块豁免）。
 *
 * 背景：台达 14 条键是三位补零（"001"），而设备寄存器上报裸整数（1），
 * normalize 后不匹配 → **14 条在生产中 100% 不可达**。
 * 本轮已由加载器的「去前导零别名索引」修通（台达 JSON 本身一个字没改，
 * 所以这里是**存量键形态**而非缺陷）；但新文件必须遵守「键即裸数字」，
 * 否则同样会 100% 不可达。
 *
 * 豁免的两种粒度：
 *   allowZeroPadded —— 允许「补零形态」的键（存量台达）
 *   allowNonNumeric —— 允许「含字母」的键（存量三菱 "8A"，属文档码）
 *
 * ⚠️ **豁免只豁免对应的那一条检查，其余检查照常跑**（下面把检查拆开了）。
 */
const LEGACY_KEY_FORMATS = new Map([
  ['delta-servo.json', {
    reason: '台达键为三位补零（001），运行时上报裸整数（1）→ 靠加载器的去前导零别名索引生效。'
      + '键形态是存量，本轮不改（改了历史维修单会断链）。',
    allowZeroPadded: true,
  }],
  ['mitsubishi-servo.json', {
    reason: '三菱键为裸数字且 19/20 可达（"8A" 含字母，运行时喂不出来，属文档码）。'
      + '保持现状，仅豁免「键必须纯数字」这一条。',
    allowNonNumeric: true,
  }],
]);

function readIndex() {
  const errors = [];
  const files = [];
  if (!existsSync(INDEX)) {
    errors.push('缺少 alarmcodes/index.txt —— 字典加载器靠它读清单，没有它字典全空');
    return { files, errors };
  }
  const raw = readFileSync(INDEX, 'utf8');
  for (const line of raw.split(/\r?\n/)) {
    const s = line.trim();
    if (!s || s.startsWith('#')) continue;
    files.push(s);
  }
  return { files, errors };
}

function actualJsonFiles() {
  if (!existsSync(ALARM_DIR)) return [];
  return readdirSync(ALARM_DIR).filter((n) => n.endsWith('.json')).sort();
}

/** 从前端 devices.ts 的 BRANDS 常量里抠出 value 列表 */
function frontendBrandValues() {
  if (!existsSync(FRONTEND_BRANDS)) return null;
  const src = readFileSync(FRONTEND_BRANDS, 'utf8');
  const m = src.match(/export const BRANDS\s*=\s*\[([\s\S]*?)\]/);
  if (!m) return null;
  return new Set([...m[1].matchAll(/value:\s*'([^']*)'/g)].map((x) => x[1]));
}

/**
 * 检查单个字典文件的内部一致性。
 */
function checkFileSchema(fname, data, errors, warnings) {
  const legacy = LEGACY_KEY_FORMATS.get(fname) ?? null;
  const allowZeroPadded = legacy?.allowZeroPadded === true;
  const allowNonNumeric = legacy?.allowNonNumeric === true;

  for (const key of ['brand', 'brandText', 'note']) {
    const v = data[key];
    if (v === undefined || v === null || String(v).trim() === '') {
      errors.push(`${fname}: 缺字段 ${key}`);
    }
  }

  const brand = String(data.brand ?? '').trim();
  if (brand && !KNOWN_BRANDS.has(brand)) {
    errors.push(`${fname}: brand='${brand}' 不在已知品牌集合 [${[...KNOWN_BRANDS].join(', ')}] 内`
      + '（设备表单下拉选不出来的品牌 = 配了也不翻译）');
  }

  const codes = data.codes;
  if (typeof codes !== 'object' || codes === null || Array.isArray(codes)) {
    errors.push(`${fname}: codes 必须是对象`);
    return;
  }
  const codeKeys = Object.keys(codes);
  if (codeKeys.length === 0) {
    errors.push(`${fname}: codes 是空的（空字典等于没做）`);
    return;
  }

  // G2：归一化后必须唯一（一个数字 key 只能有一个含义）
  const seen = new Map();
  // G8：别名之间也必须唯一（一个别名只能指向一个含义）
  const aliasSeen = new Map();
  for (const code of codeKeys) {
    if (code !== code.trim()) {
      errors.push(`${fname}: 码键 '${code}' 前后有空格（永远查不到）`);
    }
    if (code === '') {
      errors.push(`${fname}: 有空的码键`);
      continue;
    }
    const norm = normalize(code);
    if (seen.has(norm)) {
      errors.push(`${fname}: 码键 '${seen.get(norm)}' 与 '${code}' 归一化后都是 '${norm}'`
        + ' —— 其中一条永远查不到（一个码只能有一个含义）');
    } else {
      seen.set(norm, code);
    }

    const item = codes[code];
    if (typeof item !== 'object' || item === null) {
      errors.push(`${fname}: 码 '${code}' 必须是对象`);
      continue;
    }
    for (const field of ['name', 'cause', 'remedy']) {
      const v = item[field];
      if (v === undefined || v === null || String(v).trim() === '') {
        errors.push(`${fname}: 码 '${code}' 缺 ${field}（或为空）`);
      }
    }
    // displayCode 是可选字段（厂家原始形态，如 "A.720"），给了就必须非空
    if ('displayCode' in item) {
      const dc = item.displayCode;
      if (dc === undefined || dc === null || String(dc).trim() === '') {
        errors.push(`${fname}: 码 '${code}' 的 displayCode 为空（给了就必须有值）`);
      }
    }
    // G7：aliases[] 必须是数组、元素非空，且**不得覆盖任何主键**
    // （别名覆盖主键 = 查那个数字会命中另一个含义 = 给现场一个错含义）
    if ('aliases' in item) {
      const al = item.aliases;
      if (!Array.isArray(al)) {
        errors.push(`${fname}: 码 '${code}' 的 aliases 必须是数组`);
      } else {
        for (const a of al) {
          if (a === undefined || a === null || String(a).trim() === '') {
            errors.push(`${fname}: 码 '${code}' 的 aliases 里有空值`);
            continue;
          }
          const alias = String(a).trim();
          if (alias === code) {
            errors.push(`${fname}: 码 '${code}' 的别名与自身相同（多余）`);
          }
          if (Object.prototype.hasOwnProperty.call(codes, alias)) {
            errors.push(`${fname}: 码 '${code}' 的别名 '${alias}' 与另一个主键撞了`
              + ' —— 别名不得覆盖主键（查它会命中别的含义）');
          }
          const aliasNorm = normalize(alias);
          if (seen.has(aliasNorm)) {
            errors.push(`${fname}: 码 '${code}' 的别名 '${alias}' 归一化后与主键 '${seen.get(aliasNorm)}' 撞了`
              + ' —— 别名不得覆盖主键');
          }
          // G8：同一个别名不得被两个条目共用
          // （否则加载时先到的赢，另一个条目的别名形同虚设 → 查它会命中别的含义）
          if (aliasSeen.has(alias)) {
            errors.push(`${fname}: 别名 '${alias}' 被 '${aliasSeen.get(alias)}' 与 '${code}' 两个条目共用`
              + ' —— 一个别名只能指向一个含义');
          } else {
            aliasSeen.set(alias, code);
          }
        }
      }
    }
  }

  // G4：新增文件的键必须是「运行时喂得出来的裸数字」
  //     存量文件按 LEGACY_KEY_FORMATS 逐个豁免。
  if (!allowZeroPadded && !allowNonNumeric) {
    const bad = [];
    for (const code of codeKeys) {
      const c = String(code);
      if (!/^\d+$/.test(c)) {
        bad.push([c, '非数字']);
      } else if (c.length > 1 && c.startsWith('0')) {
        bad.push([c, '补零形态（设备上报裸整数）']);
      }
    }
    if (bad.length > 0) {
      const sample = bad.slice(0, 6).map(([c, why]) => `'${c}'(${why})`).join(', ');
      const more = bad.length > 6 ? ` 等 ${bad.length} 条` : '';
      errors.push(`${fname}: ${bad.length} 条码键不是「运行时喂得出来的裸数字」`
        + `（${sample}${more}）→ 这些码在生产中永远查不到。`
        + '新加的字典必须用裸数字作键（厂家原始形态放 displayCode 字段）');
    }
  }

  // G9：别名必须真的指向它自己
  // 用真实加载顺序（原始键 → 别名）重建索引，再逐个别名回查：
  // 若某个别名被别的条目抢走，这条别名就查不到自己的含义 → 现场按错方向排查。
  {
    const idx = new Map();
    for (const [k, v] of Object.entries(codes)) idx.set(k, v);
    for (const [k, v] of Object.entries(codes)) {
      const al = v?.aliases;
      if (!Array.isArray(al)) continue;
      for (const a of al) {
        if (a === null || a === undefined) continue;
        const alias = String(a).trim();
        if (alias) idx.set(alias, v);   // 与加载器同序：后者覆盖前者
      }
    }
    for (const [k, v] of Object.entries(codes)) {
      const al = v?.aliases;
      if (!Array.isArray(al)) continue;
      for (const a of al) {
        if (a === null || a === undefined) continue;
        const alias = String(a).trim();
        if (!alias) continue;
        const hit = idx.get(alias);
        if (hit !== v) {
          errors.push(`${fname}: 码 '${k}' 的别名 '${alias}' 实际指向了另一个条目`
            + ' —— 现场查它会命中错的含义');
        }
      }
    }
  }

  // V5：生产可达性 —— 模拟运行时喂裸整数（存量文件按豁免规则放行）
  const unreachable = [];
  for (const code of codeKeys) {
    const c = String(code);
    if (!/^\d+$/.test(c)) {
      // 含字母的码（如三菱 8A）运行时喂不出来，属文档码 → 提示不阻断
      if (!allowNonNumeric) {
        warnings.push(`${fname}: 码 '${c}' 含非数字字符，运行时喂裸整数时不可达`
          + '（需现场点位能读出字符串型报警字才生效）');
      }
      continue;
    }
    const runtime = String(parseInt(c, 10));   // 设备寄存器报的是裸整数
    if (normalize(runtime) !== normalize(c)) {
      if (!allowZeroPadded) {
        unreachable.push([c, runtime]);
      }
    }
  }
  if (unreachable.length > 0) {
    const sample = unreachable.slice(0, 6)
      .map(([c, r]) => `'${c}'(运行时 ${r})`).join(', ');
    const more = unreachable.length > 6 ? ` 等 ${unreachable.length} 条` : '';
    errors.push(`${fname}: ${unreachable.length} 条码在生产中不可达 —— 键是补零形态`
      + `（${sample}${more}），而设备上报裸整数 → 永远查不到`);
  }
}

/**
 * 自检：这个脚本自己会不会是「空转」的？
 *
 * <p><b>为什么需要</b>：本脚本曾被改到只剩注释头、没有 main() 调用 ——
 * 于是它「永远退出 0」，看起来门禁在守，实际什么都没查。
 * <b>假绿比没有门禁更危险</b>（没门禁你知道它没有，假绿你以为它有）。
 *
 * <p><b>做法</b>：造几份「各自只犯一种错」的坏字典，逐份跑检查逻辑，
 * 断言每一种错<b>都必须被抓到</b>。这样任何一条检查被改残，自检就会先失败。
 *
 * <p>自检失败时直接退出 1 —— 让它跟真正的校验失败一样阻断打包。
 */
function selfTest() {
  /**
   * 每条检查一个**独立样本** + 预期最少错误数。
   *
   * <p>为什么用「错误数下限」而不是「匹配某条消息」：
   * 匹配消息会被另一条检查的报错顶替 —— 之前 G9 失效时，G8 的报错里带了「别名」
   * 关键词，导致自检误判为通过。断言错误数下限，每条检查被改残时错误数必然下降。
   *
   * <p>样本的 brand 一律用合法品牌（避免 brand 检查给每个样本都加一条错误，
   * 那样错误数下限就失去区分度）。brand 检查单独用一个非法 brand 的样本验。
   */
  const VALID = 'mitsubishi';   // 合法 brand —— 让 brand 检查不产生额外错误
  const cases = [
    {
      why: '补零形态的键（生产不可达）',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: { '001': { name: 'n', cause: 'c', remedy: 'r' } },
      },
      expectMinErrors: 1,   // G4 裸数字 + V5 可达性
    },
    {
      why: '归一化后同形的两个键',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: {
          '10': { name: 'n', cause: 'c', remedy: 'r' },
          'AL10': { name: 'n2', cause: 'c', remedy: 'r' },
        },
      },
      expectMinErrors: 1,   // V4 归一化唯一（+ G4 裸数字）
    },
    {
      why: '缺 remedy 字段',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: { '10': { name: 'n', cause: 'c' } },
      },
      expectMinErrors: 1,   // V2 必填字段
    },
    {
      why: '别名覆盖了另一个主键',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: {
          '10': { name: 'n', cause: 'c', remedy: 'r', aliases: ['20'] },
          '20': { name: 'n2', cause: 'c', remedy: 'r' },
        },
      },
      expectMinErrors: 1,   // G7 别名撞主键
    },
    {
      why: '同一个别名被两个条目共用',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: {
          '10': { name: 'n', cause: 'c', remedy: 'r', aliases: ['99'] },
          '20': { name: 'n2', cause: 'c', remedy: 'r', aliases: ['99'] },
        },
      },
      expectMinErrors: 2,   // G8 共用 + G9 指向自己
    },
    {
      why: 'brand 不在已知品牌集合里',
      data: {
        brand: 'selftest-not-a-brand', brandText: 'x', note: 'x',
        codes: { '10': { name: 'n', cause: 'c', remedy: 'r' } },
      },
      expectMinErrors: 1,   // V3 brand 校验
    },
    {
      why: '码键前后有空格',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: { ' 10 ': { name: 'n', cause: 'c', remedy: 'r' } },
      },
      expectMinErrors: 1,   // V6 空格
    },
    {
      why: 'aliases 不是数组',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: { '10': { name: 'n', cause: 'c', remedy: 'r', aliases: 'x' } },
      },
      expectMinErrors: 1,   // G7 类型
    },
    {
      why: 'displayCode 给了却是空',
      data: {
        brand: VALID, brandText: 'x', note: 'x',
        codes: { '10': { displayCode: '', name: 'n', cause: 'c', remedy: 'r' } },
      },
      expectMinErrors: 1,   // V2 displayCode
    },
  ];

  const missed = [];
  for (const c of cases) {
    const errors = [];
    const warnings = [];
    checkFileSchema('selftest.json', c.data, errors, warnings);
    // 断言「抓到的错误数」达到预期，而不是匹配某条消息 ——
    // 匹配消息会被另一条检查的报错顶替（曾因此让 G9 的失效没被自检发现）。
    if (errors.length < c.expectMinErrors) {
      missed.push(`${c.why}（预期至少抓到 ${c.expectMinErrors} 个问题，`
        + `实际 ${errors.length} 个：${errors.join('；') || '无报错'}）`);
    }
  }

  if (missed.length > 0) {
    console.error('字典校验器自检失败：以下检查没有抓出故意埋下的错误 —— '
      + '本脚本的检查逻辑可能已失效（例如 main() 调用丢失或某条检查被改坏），门禁会变成假绿。');
    for (const m of missed) console.error(`  [自检失败] ${m}`);
    process.exit(1);
  }
}

function main() {
  const quiet = process.argv.includes('--quiet');

  // 先自检：确保本脚本的检查逻辑本身是活的
  selfTest();

  const errors = [];
  const warnings = [];

  const { files: listed, errors: idxErrors } = readIndex();
  errors.push(...idxErrors);
  const actual = actualJsonFiles();

  // V1：清单 → 文件
  for (const f of listed) {
    if (!actual.includes(f)) {
      errors.push(`index.txt 列了 '${f}'，但 alarmcodes/ 下没有这个文件 —— 该品牌等于没加载`);
    }
  }
  // V1：文件 → 清单（没登记 = 永远不会被加载，静默失效）
  for (const f of actual) {
    if (!listed.includes(f)) {
      errors.push(`alarmcodes/${f} 没在 index.txt 里登记 —— 加载时会被静默跳过`);
    }
  }

  // V3：前端品牌常量与字典 brand 是否对得上
  const fb = frontendBrandValues();
  const dictBrands = new Map();   // brand -> 文件名
  let totalCodes = 0;

  for (const f of listed) {
    const path = join(ALARM_DIR, f);
    if (!existsSync(path)) continue;
    let data;
    try {
      data = JSON.parse(readFileSync(path, 'utf8'));
    } catch (e) {
      errors.push(`${f}: JSON 解析失败 —— ${e.message}`);
      continue;
    }
    if (typeof data !== 'object' || data === null || Array.isArray(data)) {
      errors.push(`${f}: 顶层必须是对象`);
      continue;
    }
    const b = String(data.brand ?? '').trim();
    if (b) {
      // G3：两个文件用同一个 brand → AlarmCodeDict.load() 里 brands.put(brand, m)
      // 会**后写覆盖先写，静默无报错** → 前一个文件的码全部失效，且没人会发现。
      if (dictBrands.has(b)) {
        errors.push(`G3 brand 冲突：'${dictBrands.get(b)}' 与 '${f}' 都用 brand='${b}'`
          + ' —— 加载时后者会静默覆盖前者，前者的码全部失效');
      } else {
        dictBrands.set(b, f);
      }
    }
    totalCodes += Object.keys(data.codes ?? {}).length;
    checkFileSchema(f, data, errors, warnings);
  }

  if (fb) {
    for (const b of [...dictBrands.keys()].sort()) {
      if (!fb.has(b) && b !== 'plc') {
        errors.push(`字典品牌 '${b}'（文件 ${dictBrands.get(b)}）不在前端 BRANDS 常量里`
          + '（frontend/src/api/devices.ts）→ 设备表单选不出来');
      }
    }
    for (const b of [...KNOWN_BRANDS].filter((x) => x !== 'plc').sort()) {
      if (!fb.has(b)) warnings.push(`前端 BRANDS 常量里没有 '${b}'，但它是已知品牌`);
    }
  }

  // V8：manifest 声明的条数必须与实际文件一致
  // manifest 记录了每个字典的来源与适用型号，是对外可追溯的依据。
  // 它和字典文件如果脱节，就等于「文档说的和代码做的不是一回事」。
  if (existsSync(MANIFEST)) {
    let manifest = null;
    try {
      manifest = JSON.parse(readFileSync(MANIFEST, 'utf8'));
    } catch (e) {
      errors.push(`alarmcode-manifest.json 解析失败 —— ${e.message}`);
    }
    if (manifest && Array.isArray(manifest.files)) {
      const declared = new Map();
      for (const entry of manifest.files) {
        if (entry && entry.file) declared.set(entry.file, entry);
      }
      for (const [file] of declared) {
        if (!existsSync(join(ALARM_DIR, file))) {
          errors.push(`manifest 声明了 '${file}'，但 alarmcodes/ 下没有这个文件`);
        }
      }
      for (const file of actual) {
        if (!declared.has(file)) {
          errors.push(`alarmcodes/${file} 没在 manifest 里登记 —— 它的来源与适用型号会丢失`);
        }
      }
      for (const [file, entry] of declared) {
        const path = join(ALARM_DIR, file);
        if (!existsSync(path)) continue;
        let data;
        try {
          data = JSON.parse(readFileSync(path, 'utf8'));
        } catch {
          continue;
        }
        const actualCount = Object.keys(data.codes ?? {}).length;
        if (typeof entry.expectedCount === 'number' && entry.expectedCount !== actualCount) {
          errors.push(`${file}: manifest 声明 expectedCount=${entry.expectedCount}，`
            + `实际 ${actualCount} 条 —— manifest 与字典脱节`);
        }
        if (entry.brand && data.brand && entry.brand !== data.brand) {
          errors.push(`${file}: manifest 声明 brand='${entry.brand}'，实际 '${data.brand}'`);
        }
      }
    }
  }

  if (!quiet) {
    console.log(`报警码字典校验：${listed.length} 个品牌文件，${totalCodes} 条码`);
    if (warnings.length > 0) {
      console.log(`提示 ${warnings.length} 条：`);
      for (const w of warnings) console.log(`  [提示] ${w}`);
    }
  }

  if (errors.length > 0) {
    console.error(`字典校验失败：${errors.length} 个问题`);
    for (const e of errors) console.error(`  [阻断] ${e}`);
    process.exit(1);
  }

  if (!quiet) console.log('字典校验通过');
  process.exit(0);
}

main();
