// 把 bench-report 生成的表格**写回**报告文档（不手抄）。
// 用法：node tools/bench-report-apply.mjs docs/COLLECT-SCALE-BENCH.md
//
// ⚠️ 为什么要有这个：报告里的数字如果靠人复制粘贴 ⇒ 迟早抄错
//   （实测栽过两次：L1 主表抄错一行；L4 段忘了更新）。
//   ⇒ 根治：**表格由脚本生成 ⇒ 脚本直接写回文档** ⇒ 抄写这一步从流程里消失。
//
// ⚠️ 替换必须**精确**：只替换「表头行之后、下一个非 | 开头行之前」的连续表体，
//   不能全文替换（文档里有多个含 | 的表）。
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';

const ROOT = join(import.meta.dirname, '..');
const reportPath = process.argv[2] || join(ROOT, 'docs', 'COLLECT-SCALE-BENCH.md');

if (!existsSync(reportPath)) {
  console.error(`✗ 找不到报告文件：${reportPath}`);
  process.exit(2);
}

// ---- 1. 让 bench-report.mjs 生成表格（复用同一份逻辑，不另写一份）----
const gen = execFileSync('node', [join(ROOT, 'tools', 'bench-report.mjs')], {
  cwd: ROOT,
  encoding: 'utf8',
});

// ---- 2. 从生成结果里切出「主表」（表头 + 表体）----
function pickTable(text, headerPrefix) {
  const ls = text.split('\n');
  const s0 = ls.findIndex((l) => l.startsWith(headerPrefix));
  if (s0 < 0) return null;
  const out = [ls[s0]];
  for (let i = s0 + 1; i < ls.length; i++) {
    if (!ls[i].startsWith('|')) break;
    out.push(ls[i]);
  }
  return out.join('\n');
}

const newTable = pickTable(gen, '| 场景 | 说明 | 轮间隔');
if (!newTable) {
  console.error('✗ 从生成结果里切不出主表 ⇒ bench-report.mjs 的输出格式变了');
  process.exit(1);
}

// ---- 2b. 同样切出 L2 段的**完整内容**（表 + 说明段）----
// ⚠️ 只换表不够：L2 段里还有「单次调用成本 P50=… P95=…」等**说明文字里的数字**，
//   它们同样是脚本生成的 ⇒ 同样手抄不得 ⇒ 整段替换。
//   段落边界：从「⚠️ **本段由」标记行，到下一个空行后的段落结束（遇到 `---` 或 `##` 或 `###` 停）。
const l2Marker = '> ⚠️ **本段由 `tools/bench-report.mjs` 从 `target-logs/bench/L2.log` 自动生成，勿手抄**';
function pickSection(text, marker) {
  const ls = text.split('\n');
  const s0 = ls.findIndex((l) => l.trim() === marker);
  if (s0 < 0) return null;
  const out = [];
  for (let i = s0; i < ls.length; i++) {
    const l = ls[i];
    // 到下一个标题或分隔线就停
    if (i > s0 && (/^#{1,3} /.test(l) || l.trim() === '---')) break;
    out.push(l);
  }
  // 去掉尾部空行
  while (out.length && out[out.length - 1].trim() === '') out.pop();
  return out.join('\n');
}

const newL2Section = pickSection(gen, l2Marker);
if (!newL2Section) {
  console.error('✗ 从生成结果里切不出 L2 段 ⇒ bench-report.mjs 的输出格式变了');
  process.exit(1);
}

// ---- 2c. 同样切出 L4 段（改造前后对比表）----
const l4Marker = '> ⚠️ **本段由 `tools/bench-report.mjs` 从 `target-logs/bench/L4.log` 自动生成，勿手抄**';
const newL4Section = pickSection(gen, l4Marker);
if (!newL4Section) {
  console.error('✗ 从生成结果里切不出 L4 段 ⇒ bench-report.mjs 的输出格式变了');
  process.exit(1);
}

// ---- 2d. 容量公式与实测的对照段 ----
const capMarker = '> ⚠️ **本段由 `tools/bench-report.mjs` 从日志生成，勿手抄**';
const newCapSection = pickSection(gen, capMarker);
if (!newCapSection) {
  console.error('✗ 从生成结果里切不出「容量公式与实测的对照」段 ⇒ bench-report.mjs 的输出格式变了');
  process.exit(1);
}

// ---- 3. 在文档里定位同名的主表并整块替换 ----
const doc = readFileSync(reportPath, 'utf8');
const lines = doc.split('\n');
const h0 = lines.findIndex((l) => l.startsWith('| 场景 | 说明 | 轮间隔'));
if (h0 < 0) {
  console.error('✗ 文档里找不到主表表头 ⇒ 文档结构变了，先人工确认');
  process.exit(1);
}
let h1 = h0 + 1;
while (h1 < lines.length && lines[h1].startsWith('|')) h1++;

const out = [...lines.slice(0, h0), ...newTable.split('\n'), ...lines.slice(h1)];

// ---- 4. 同样替换 L2 段（整段：表 + 说明文字）----
const l0 = out.findIndex((l) => l.trim() === l2Marker);
if (l0 < 0) {
  console.error('✗ 文档里找不到 L2 段的标记行 ⇒ 文档结构变了，先人工确认');
  process.exit(1);
}
let l1 = l0;
for (; l1 < out.length; l1++) {
  const l = out[l1];
  if (l1 > l0 && (/^#{1,3} /.test(l) || l.trim() === '---')) break;
}
// 段落末尾的空行一并吃掉（保持文档整洁）
let lEnd = l1;
while (lEnd > l0 && out[lEnd - 1].trim() === '') lEnd--;

let cur = [...out.slice(0, l0), ...newL2Section.split('\n'), ...out.slice(lEnd)];

// ---- 5. 同样替换 L4 段 ----
const l4i = cur.findIndex((l) => l.trim() === l4Marker);
if (l4i < 0) {
  console.error('✗ 文档里找不到 L4 段的标记行 ⇒ 文档结构变了，先人工确认');
  process.exit(1);
}
let l4j = l4i;
for (; l4j < cur.length; l4j++) {
  const l = cur[l4j];
  if (l4j > l4i && (/^#{1,3} /.test(l) || l.trim() === '---')) break;
}
let l4End = l4j;
while (l4End > l4i && cur[l4End - 1].trim() === '') l4End--;

const final = [...cur.slice(0, l4i), ...newL4Section.split('\n'), ...cur.slice(l4End)];

// ---- 6. 同样替换「容量公式与实测的对照」段 ----
const ci = final.findIndex((l) => l.trim() === capMarker);
if (ci < 0) {
  console.error('✗ 文档里找不到「容量公式与实测的对照」段的标记行 ⇒ 文档结构变了，先人工确认');
  process.exit(1);
}
let cj = ci;
for (; cj < final.length; cj++) {
  const l = final[cj];
  if (cj > ci && (/^#{1,3} /.test(l) || l.trim() === '---')) break;
}
let cEnd = cj;
while (cEnd > ci && final[cEnd - 1].trim() === '') cEnd--;

const result = [...final.slice(0, ci), ...newCapSection.split('\n'), ...final.slice(cEnd)];
writeFileSync(reportPath, result.join('\n'), 'utf8');
console.log(`✓ 已把主表写回 ${reportPath}（${h1 - h0} 行 → ${newTable.split('\n').length} 行）`);
console.log(`✓ 已把 L2 段写回（${lEnd - l0} 行 → ${newL2Section.split('\n').length} 行）`);
console.log(`✓ 已把 L4 段写回（${l4End - l4i} 行 → ${newL4Section.split('\n').length} 行）`);
console.log(`✓ 已把容量公式对照段写回（${cEnd - ci} 行 → ${newCapSection.split('\n').length} 行）`);
console.log('  ⇒ 现在跑 --check 核对，确认报告与日志一致');
