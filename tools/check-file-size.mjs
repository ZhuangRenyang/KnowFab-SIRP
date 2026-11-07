// 单文件行数护栏：**不许再造新的超长文件**。
//
// ⭐ 为什么需要
//   2026-11-07 实测：后端 247 个文件里 **46 个超 300 行**，最长 `ErpStockService` 1126 行。
//   行数本身不是罪，但它带来三件真事：
//     ① 定位成本：找一段逻辑要在千行里翻
//     ② 改动风险：一次改动的影响面大（改 A 顺手碰坏 B）
//     ③ 并行冲突：两个人同时改同一文件必然冲突
//   ⇒ 但**治理存量**和**防止新增**是两件事，本护栏只做后者（理由见下）。
//
// ⚠️⚠️ 为什么**只拦新增、不拦存量**
//   用户 2026-11-07 明确选择的是「护栏 + 测试基线」，不是「全拆 46 个」，因为：
//     · 46 个里有 **6 个是 JPA Entity**（`ErpQcInspection` 324 行里 80 个 get/set、
//       `DeviceEntity` 426 行里 20 个 get/set + 36 个注解）⇒ 拆了只是两个更碎的 Entity，
//       **破坏 JPA 映射，零收益**
//     · 有 **4 个是注释撑起来的**（`ProdRollup` 897 行里 291 行注释 = 去锁改造的架构决策
//       记录；`ErpAqlStandardGrid` 320 行里 149 行注释 = 国标表说明）⇒ 拆了把决策记录拆散
//     · 按**有效代码行**（去注释/空行/注解/getter-setter）算，46 个里只有 **26 个**真超 300
//   ⇒ 存量靠「按需重构」（改动某文件时才顺手拆），不靠运动式整改。
//
//   ⚠️ 还有一个必须说清的理由：**存量文件增长是正常迭代**。
//      在 1126 行的文件里加 20 行是家常便饭 ⇒ 若拦「比基线更长」，门禁会天天红
//      ⇒ **一个天天红的门禁等于没有门禁**（这正是项目里反复踩过的「狼来了」）。
//      ⇒ 所以：存量只在**降到阈值内**时提示「基线可下调」，增长不拦。
//
// ⚠️ 为什么只管 `src/main/java`（不管前端、不管 test）
//   · 前端 `frontend/src/locales/zh-CN.ts` 是 **3118 行的键值字典**（2841 个键）
//     ⇒ 行数规则对它**根本不适用**（不可能把 2841 个键压到 300 行）
//     ⇒ 若一并检查，护栏第一天就会被 locale 判红 ⇒ 又变成摆设
//   · `src/test` 里 `ErpStockTestKit` / `*ScaleBench` 这类辅助类天然长，且不是生产风险
//   ⇒ 本护栏的判定对象 = **生产代码的 Java 文件**，边界清晰。
//
// 用法：
//   node tools/check-file-size.mjs                    # 检查（有问题 exit 1）
//   node tools/check-file-size.mjs --print-baseline    # 自动生成基线（**禁止手写**）
//
// ⭐ 基线必须由工具生成：我在 `check-package-size.mjs` 上手写基线**连错三次**，
//   每次都让护栏立刻误报 ⇒ 「一个乱报的护栏比没有护栏更糟」（它会让人养成忽略它的习惯）。
//
// 退出码：0 = 通过；1 = 有新增超长文件；2 = 用法/环境错误
import { readdirSync, statSync, existsSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const SRC = join(ROOT, 'src', 'main', 'java');

// 阈值：单文件 ≤ 300 行（项目既有规范，见 docs/decisions/OPEN-DECISIONS.md）
const MAX_LINES = 300;

// ---- 基线：2026-11-07 实测的 46 个存量文件（路径 → 行数）----
//   只用于：① 豁免（不算新增）② 降到阈值内时提示下调
//   新增条目只能由 `--print-baseline` 生成后粘贴，不许手写。
// 46 个存量文件超 300 行（2026-11-07 实测）
const BASELINE = {
  'com/factory/hub/erp/stock/ErpStockService.java': 1126,
  'com/factory/hub/system/UpdateService.java': 1071,
  'com/factory/hub/ai/AiChatService.java': 1016,
  'com/factory/hub/erp/production/ErpWorkOrderService.java': 980,
  'com/factory/hub/report/ProdRollup.java': 896,
  'com/factory/hub/erp/production/ErpMaintService.java': 811,
  'com/factory/hub/report/ReportService.java': 808,
  'com/factory/hub/alarm/AlarmEngine.java': 788,
  'com/factory/hub/erp/sales/ErpSalesService.java': 776,
  'com/factory/hub/energy/EnergyService.java': 768,
  'com/factory/hub/erp/purchase/ErpPurchaseService.java': 742,
  'com/factory/hub/erp/production/ErpLineService.java': 625,
  'com/factory/hub/erp/quality/inspection/ErpQcInspectionService.java': 611,
  'com/factory/hub/erp/analysis/ErpFinanceService.java': 594,
  'com/factory/hub/erp/analysis/ErpReportService.java': 567,
  'com/factory/hub/erp/analysis/ErpReportExcel.java': 549,
  'com/factory/hub/screen/ScreenService.java': 541,
  'com/factory/hub/report/MonthExcel.java': 536,
  'com/factory/hub/web/DeviceController.java': 522,
  'com/factory/hub/alarm/AlarmNotifier.java': 510,
  'com/factory/hub/erp/analysis/ErpCostService.java': 510,
  'com/factory/hub/system/SettingStore.java': 476,
  'com/factory/hub/demand/DemandService.java': 474,
  'com/factory/hub/erp/quality/defect/ErpQcDefectQueryService.java': 455,
  'com/factory/hub/erp/stock/ErpProdInboundService.java': 453,
  'com/factory/hub/model/DeviceEntity.java': 425,
  'com/factory/hub/alarm/AlarmRule.java': 423,
  'com/factory/hub/alarm/AlarmController.java': 401,
  'com/factory/hub/erp/production/ErpProdController.java': 395,
  'com/factory/hub/energy/EnergyBenchmarkService.java': 384,
  'com/factory/hub/energy/EnergyRollup.java': 376,
  'com/factory/hub/erp/quality/inspection/ErpAqlService.java': 375,
  'com/factory/hub/erp/quality/defect/ErpQcDefectService.java': 371,
  'com/factory/hub/erp/production/ErpMaintOrder.java': 366,
  'com/factory/hub/erp/quality/inspection/ErpAqlSeeder.java': 352,
  'com/factory/hub/demand/DemandTracker.java': 345,
  'com/factory/hub/erp/ErpBaseController.java': 339,
  'com/factory/hub/alarm/AlarmEvent.java': 337,
  'com/factory/hub/report/ProdExcel.java': 337,
  'com/factory/hub/erp/quality/defect/ErpQcDefect.java': 331,
  'com/factory/hub/erp/quality/inspection/ErpQcInspection.java': 323,
  'com/factory/hub/system/MigrationInitializer.java': 320,
  'com/factory/hub/erp/quality/inspection/ErpAqlStandardGrid.java': 319,
  'com/factory/hub/erp/quality/defect/ErpQualityStockAdapter.java': 311,
  'com/factory/hub/DeviceTemplateController.java': 310,
  'com/factory/hub/driver/opcua/OpcUaDriver.java': 307,
};

// 统计口径必须与 `wc -l` 一致：数换行符，不把末尾空串算一行
const countLines = (s) => (s === '' ? 0 : s.split('\n').length - (s.endsWith('\n') ? 1 : 0));

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (e.endsWith('.java')) out.push(p);
  }
  return out;
}

const rows = walk(SRC).map((p) => ({
  rel: relative(SRC, p).replace(/\\/g, '/'),
  lines: countLines(readFileSync(p, 'utf8')),
}));
rows.sort((a, b) => b.lines - a.lines);

// ---- `--print-baseline`：自动生成（根治手写错误）----
if (process.argv.includes('--print-baseline')) {
  const over = rows.filter((r) => r.lines > MAX_LINES);
  console.log(`// ${over.length} 个存量文件超 ${MAX_LINES} 行（2026-11-07 实测）`);
  console.log('const BASELINE = {');
  for (const r of over) console.log(`  '${r.rel}': ${r.lines},`);
  console.log('};');
  process.exit(0);
}

const problems = [];
const improved = [];

for (const r of rows) {
  const base = BASELINE[r.rel];
  if (base === undefined) {
    // 不在基线里 ⇒ 新增文件
    if (r.lines > MAX_LINES) {
      problems.push(
        `新增文件超长：src/main/java/${r.rel} 有 ${r.lines} 行（上限 ${MAX_LINES}）`
        + ` ⇒ 新代码请拆成多个类；不要往这条边上踩`
      );
    }
  } else if (r.lines <= MAX_LINES) {
    // 存量文件已降到阈值内 ⇒ 基线该下调了（提示，不失败）
    improved.push(`${r.rel}：${base} → ${r.lines} 行（已降到阈值内）`);
  }
  // 存量文件增长：**刻意不报**（理由见文件头「为什么只拦新增」）
}

// ---- 汇总输出（必须始终有输出：门禁把「零输出」判为假绿）----
const overCount = rows.filter((r) => r.lines > MAX_LINES).length;
const baseCount = Object.keys(BASELINE).length;
console.log(`单文件行数：阈值 ${MAX_LINES} 行｜实测超阈值 ${overCount} 个｜基线豁免 ${baseCount} 个`);

if (problems.length) {
  console.error(`\n✗ 有 ${problems.length} 个新增超长文件：\n`);
  for (const p of problems) console.error('  ' + p);
  console.error('\n（存量 46 个已豁免；本护栏只拦新增 —— 理由见 tools/check-file-size.mjs 文件头）');
  process.exit(1);
}

if (improved.length) {
  console.log('');
  for (const s of improved) console.log(`  ↻ 基线可下调：${s}`);
  console.log('  ⇒ 请用 `node tools/check-file-size.mjs --print-baseline` 重新生成基线后粘贴（不要手改）');
}

console.log(`\n✓ 无新增超长文件（存量 ${overCount} 个按「按需重构」处理）`);
