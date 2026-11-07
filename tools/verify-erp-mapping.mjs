// 独立交叉验证架构师的 erp 拆包映射表。
// 目的：不靠「方案看起来对」，而是自己算出「跨子包边数」并与方案声明比对。
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const DIR = 'src/main/java/com/factory/hub/erp';

// 架构师方案声明的映射表（6 个子包，68 个文件）
const PLAN = {
  'erp.basic': 'ErpBaseController ErpSku ErpSkuRepository ErpPartner ErpPartnerRepository ErpWarehouse ErpWarehouseRepository ErpWarehouseKindNotice',
  'erp.stock': 'ErpStockService ErpStockController ErpStockBill ErpStockBillHdr ErpStockBillRepository ErpStockBillHdrRepository ErpStockBackfill ErpProdInboundService ErpInboundQty StockBillVoidedEvent ErpQualityReleaseGate ErpQualityReturnGate',
  'erp.sales': 'ErpSalesService ErpSalesController ErpSalesQueryService ErpSalesQueryController ErpSalesOrder ErpSalesOrderItem ErpSalesOrderRepository ErpSalesOrderItemRepository',
  'erp.purchase': 'ErpPurchaseService ErpPurchaseController ErpPurchaseOrder ErpPurchaseOrderItem ErpPurchaseOrderRepository ErpPurchaseOrderItemRepository ErpPayment ErpPaymentRepository',
  'erp.production': 'ErpWorkOrderService ErpWorkOrderController ErpWorkOrder ErpWorkOrderMaterial ErpWorkOrderOutput ErpWorkOrderRepository ErpWorkOrderMaterialRepository ErpWorkOrderOutputRepository ErpBackflushService ErpProdController ErpProdLine ErpProdLineRepository ErpLineService ErpLineController ErpScheduleService ErpScheduleController ProdDay ErpMaintService ErpMaintController ErpMaintOrder ErpMaintOrderRepository',
  'erp.analysis': 'ErpBom ErpBomRepository ErpBomService ErpBomController ErpCostService ErpCostController ErpFinanceService ErpFinanceController ErpReportService ErpReportController ErpReportExcel',
};

const actual = new Set(
  readdirSync(DIR).filter((f) => f.endsWith('.java')).map((f) => f.replace('.java', ''))
);

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

console.log('各子包文件数:', JSON.stringify(counts));
console.log('映射总数:', pkgOf.size, '| 实际文件数:', actual.size);

const missing = [...actual].filter((f) => !pkgOf.has(f));
const extra = [...pkgOf.keys()].filter((f) => !actual.has(f));
if (missing.length) console.log('❌ 漏掉的:', missing.join(', '));
if (extra.length) console.log('❌ 多出的:', extra.join(', '));
if (!missing.length && !extra.length) console.log('✓ 映射表完整：68 个文件一个不漏不多');

// ---- 跨子包边数（口径：短类名出现，剥掉 import/package 行）----
const all = [...actual].map((f) => f + '.java');
let cross = 0;
let inner = 0;
const pairs = {};
for (const f of all) {
  const src = readFileSync(join(DIR, f), 'utf8')
    .replace(/^\s*import .*$/gm, '')
    .replace(/^\s*package .*$/gm, '');
  const self = f.replace('.java', '');
  for (const c of all) {
    const cls = c.replace('.java', '');
    if (cls === self) continue;
    if (!new RegExp(`\\b${cls}\\b`).test(src)) continue;
    const a = pkgOf.get(self);
    const b = pkgOf.get(cls);
    if (!a || !b) continue;
    if (a === b) {
      inner++;
    } else {
      cross++;
      const k = [a, b].sort().join(' ↔ ');
      pairs[k] = (pairs[k] || 0) + 1;
    }
  }
}
console.log('');
console.log(`按该映射表 ⇒ 跨子包边 ${cross} | 子包内边 ${inner} | 合计 ${cross + inner}`);
console.log('跨子包边最多的组合（= import 改动最集中的地方）：');
Object.entries(pairs).sort((x, y) => y[1] - x[1]).slice(0, 8)
  .forEach(([k, v]) => console.log(`  ${String(v).padStart(3)}  ${k}`));

// ---- 各子包的文件规模（对照护栏阈值）----
console.log('');
console.log('各子包规模（对照 check-package-size 的阈值：直属 ≤24 / 同类角色 ≤12）：');
for (const [pkg, list] of Object.entries(PLAN)) {
  const items = list.split(/\s+/).filter(Boolean);
  const k = { Controller: 0, Service: 0, Repository: 0 };
  for (const f of items) {
    const m = f.match(/(Controller|Service|Repository)$/);
    if (m) k[m[1]]++;
  }
  const flag = k.Controller > 12 || k.Service > 12 || k.Repository > 12 ? '  ⚠️ 超同类角色阈值' : '';
  console.log(`  ${pkg.padEnd(16)} 共 ${String(items.length).padStart(2)}  Controller ${k.Controller} / Service ${k.Service} / Repository ${k.Repository}${flag}`);
}
