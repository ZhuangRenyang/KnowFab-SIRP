// 独立交叉验证 erp 域（B 版）拆包映射表。
//
// ⭐ **本文件的 PLAN 是「映射表的权威来源」** —— 架构师定稿、总监逐文件对照磁盘验证过。
//   ⚠️ 转录映射表极易出错（总监手工转录时就错过一次：basic 与 quality 重叠、根包数量写错）
//   ⇒ **任何执行者都必须以本文件为准，不要以聊天记录里的表格为准**。
//   ⇒ 改映射表时：只改 PLAN，然后跑本脚本验证 ⇒ 漏/多会被立刻报出来。
//
// 校验内容：
//   ① 映射表 vs 磁盘：**一个不漏、不多、不重复**
//   ② 各子包规模 vs 护栏阈值（check-package-size：直属 ≤24 / 同类角色 ≤12）
//   ③ 跨子包边数（口径：短类名出现，剥掉 import/package 行）
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const SRC = 'src/main/java/com/factory/hub';
const TEST = 'src/test/java/com/factory/hub';

// ---- 架构师定稿的映射表（main 106 个）----
const PLAN = {
  // 留 erp 根包的 4 个：跨子包公共契约 / 时间口径 / 事件
  'erp.root': 'ErpQualityReleaseGate ErpQualityReturnGate ProdDay StockBillVoidedEvent',
  'erp.basic': 'ErpBaseController ErpSku ErpSkuRepository ErpPartner ErpPartnerRepository ErpWarehouse ErpWarehouseRepository ErpWarehouseKindNotice',
  'erp.stock': 'ErpStockService ErpStockController ErpStockBill ErpStockBillHdr ErpStockBillRepository ErpStockBillHdrRepository ErpStockBackfill ErpProdInboundService ErpInboundQty',
  'erp.sales': 'ErpSalesService ErpSalesController ErpSalesQueryService ErpSalesQueryController ErpSalesOrder ErpSalesOrderItem ErpSalesOrderRepository ErpSalesOrderItemRepository',
  'erp.purchase': 'ErpPurchaseService ErpPurchaseController ErpPurchaseOrder ErpPurchaseOrderItem ErpPurchaseOrderRepository ErpPurchaseOrderItemRepository ErpPayment ErpPaymentRepository',
  'erp.production': 'ErpWorkOrderService ErpWorkOrderController ErpWorkOrder ErpWorkOrderMaterial ErpWorkOrderOutput ErpWorkOrderRepository ErpWorkOrderMaterialRepository ErpWorkOrderOutputRepository ErpBackflushService ErpProdController ErpProdLine ErpProdLineRepository ErpLineService ErpLineController ErpScheduleService ErpScheduleController ErpMaintService ErpMaintController ErpMaintOrder ErpMaintOrderRepository',
  'erp.analysis': 'ErpBom ErpBomRepository ErpBomService ErpBomController ErpCostService ErpCostController ErpFinanceService ErpFinanceController ErpReportService ErpReportController ErpReportExcel',
  'erp.quality': 'ErpQcAqlPlan ErpQcAqlPlanRepository ErpAqlController ErpAqlLevelMap ErpAqlSeeder ErpAqlService ErpAqlStandardGrid ErpDefectSyncService ErpInspectionJudge ErpQcBody ErpQcChannelRule ErpQcDefect ErpQcDefectCode ErpQcDefectCodeController ErpQcDefectCodeRepository ErpQcDefectCodeService ErpQcDefectController ErpQcDefectQueryService ErpQcDefectRepository ErpQcDefectService ErpQcDeviceQty ErpQcDisposition ErpQcDispositionRepository ErpQcInspection ErpQcInspectionController ErpQcInspectionItem ErpQcInspectionItemRepository ErpQcInspectionRepository ErpQcInspectionService ErpQcInspectionView ErpQcReconController ErpQcReconService ErpQcReleaseController ErpQcReleaseService ErpQcReturnService ErpQualityStockAdapter ErpQcBoardController ErpQcBoardService',
};

const diskMain = new Set(readdirSync(join(SRC, 'erp')).filter((f) => f.endsWith('.java')).map((f) => f.replace('.java', '')));
const diskQuality = new Set(readdirSync(join(SRC, 'quality')).filter((f) => f.endsWith('.java')).map((f) => f.replace('.java', '')));
const diskAll = new Set([...diskMain, ...diskQuality]);

const pkgOf = new Map();
const counts = {};
for (const [pkg, list] of Object.entries(PLAN)) {
  const items = list.split(/\s+/).filter(Boolean);
  counts[pkg] = items.length;
  for (const f of items) {
    if (pkgOf.has(f)) console.log('❌ 重复映射:', f);
    pkgOf.set(f, pkg);
  }
}

console.log('各子包文件数（main）:');
for (const [pkg, n] of Object.entries(counts)) console.log(`  ${pkg.padEnd(16)} ${n}`);
console.log('映射总数:', pkgOf.size, '| 磁盘 main 总数:', diskAll.size);

const missing = [...diskAll].filter((f) => !pkgOf.has(f));
const extra = [...pkgOf.keys()].filter((f) => !diskAll.has(f));
if (missing.length) {
  console.log('\n❌ 映射表漏掉的文件（' + missing.length + ' 个）:');
  for (const m of missing) console.log(`   ${m}   （在磁盘的 ${diskQuality.has(m) ? 'quality' : 'erp'} 包里）`);
}
if (extra.length) console.log('\n❌ 映射表多出的:', extra.join(', '));
if (!missing.length && !extra.length) console.log('✓ 映射表与磁盘完全一致');

// ---- 护栏阈值对照 ----
console.log('\n各子包规模对照护栏阈值（check-package-size：直属 ≤24 / 同类角色 ≤12）:');
let over = 0;
for (const [pkg, list] of Object.entries(PLAN)) {
  const items = list.split(/\s+/).filter(Boolean);
  const k = { Controller: 0, Service: 0, Repository: 0 };
  for (const f of items) {
    const m = f.match(/(Controller|Service|Repository)$/);
    if (m) k[m[1]]++;
  }
  const bad = items.length > 24 || k.Controller > 12 || k.Service > 12 || k.Repository > 12;
  if (bad) over++;
  console.log(`  ${pkg.padEnd(16)} 共 ${String(items.length).padStart(2)}  C ${k.Controller} / S ${k.Service} / R ${k.Repository}${bad ? '   ⚠️ 超阈值（需在 BASELINE 里豁免）' : ''}`);
}
if (over) console.log(`  ⇒ ${over} 个子包超阈值 ⇒ check-package-size 需为它们记基线（基线=当前值，涨了就报）`);

// ---- 跨子包边数 ----
const files = [...diskAll].map((f) => join(SRC, diskQuality.has(f) ? 'quality' : 'erp', f + '.java'));
let cross = 0; let inner = 0; const pairs = {};
for (const path of files) {
  const fname = path.split(/[\\/]/).pop().replace('.java', '');
  const src = readFileSync(path, 'utf8')
    .replace(/^\s*import .*$/gm, '').replace(/^\s*package .*$/gm, '');
  for (const other of diskAll) {
    if (other === fname) continue;
    if (!new RegExp(`\\b${other}\\b`).test(src)) continue;
    const a = pkgOf.get(fname); const b = pkgOf.get(other);
    if (!a || !b) continue;
    if (a === b) inner++;
    else { cross++; const key = [a, b].sort().join(' ↔ '); pairs[key] = (pairs[key] || 0) + 1; }
  }
}
console.log(`\n跨子包边 ${cross} | 包内边 ${inner} | 合计 ${cross + inner}`);
console.log('跨子包边最多的 8 组（= import 改动最集中的地方）:');
Object.entries(pairs).sort((a, b) => b[1] - a[1]).slice(0, 8)
  .forEach(([k, v]) => console.log(`  ${String(v).padStart(3)}  ${k}`));

if (missing.length || extra.length) process.exit(1);
