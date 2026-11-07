// 依赖方向护栏：**业务层不许反向依赖 Web 层**。
//
// ⭐ 为什么需要（2026-11-07 修完的债，这里防它长回去）
//   改前有 4 个业务层类直接 `import com.factory.hub.web.WsHandler`：
//     alarm/AlarmEngine、collect/CollectScheduler、collect/DeviceCollector、demand/DemandMonitor
//   ⇒ 依赖方向是反的（业务层认识 Web 层实现）。
//   倒置的实际代价：想换传输方式（SSE / MQTT / 长轮询），或想在无 Web 容器里跑业务逻辑，
//   都得改业务层 —— 而业务层本来与「怎么送到浏览器」无关。
//
//   修法：抽出 `com.factory.hub.service.Broadcaster` 契约，业务层依赖它，
//   `web.WsHandler implements Broadcaster` ⇒ 方向变成 `web → service ← 业务层`。
//
// ⚠️ **光改一次没用**：几个月后有人图省事又 import 回来，就白做了
//   ⇒ 必须有一条会响的护栏。这正是本项目反复强调的「改完不留护栏＝没改」。
//
// 判据（唯一一条）：
//   除 `web/`（实现方）与 `config/`（装配方，本来就该认识具体实现）之外，
//   任何 `com.factory.hub.*` 下的类都**不许** import `com.factory.hub.web.*`。
//
// 为什么放过 `config`：
//   `WebSocketConfig` 的职责就是「把具体实现装配到 Spring 里」——
//   装配层认识实现类是它的工作，不是倒置。（它 import 的是 WsHandler 本体，不是业务逻辑）
//
// 用法：node tools/check-layer-direction.mjs
// 退出码：0 = 通过；1 = 有反向依赖（逐条列出文件:行号）
import { readdirSync, statSync, existsSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = join(import.meta.dirname, '..');
const SRC = join(ROOT, 'src', 'main', 'java', 'com', 'factory', 'hub');

/** 允许认识 web 层的包（实现方 + 装配方），只写第一层目录名 */
const ALLOWED = new Set(['web', 'config']);

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (e.endsWith('.java')) out.push(p);
  }
  return out;
}

const problems = [];
const files = walk(SRC);

for (const file of files) {
  const rel = relative(SRC, file).replace(/\\/g, '/');
  const top = rel.includes('/') ? rel.slice(0, rel.indexOf('/')) : '';
  if (ALLOWED.has(top)) continue;          // web 自己 / config 装配

  const lines = readFileSync(file, 'utf8').split('\n');
  lines.forEach((line, i) => {
    // 只认真正的 import 语句（注释里提到 web.WsHandler 不算）
    const m = line.match(/^\s*import\s+(static\s+)?(com\.factory\.hub\.web\.[\w.*]+)\s*;/);
    if (!m) return;
    problems.push(
      `反向依赖 Web 层：${rel}:${i + 1}  import ${m[2]}`
      + ` ⇒ 业务层不该认识 Web 层实现；需要推送请依赖 com.factory.hub.service.Broadcaster`
    );
  });
}

const scanned = files.filter((f) => {
  const rel = relative(SRC, f).replace(/\\/g, '/');
  const top = rel.includes('/') ? rel.slice(0, rel.indexOf('/')) : '';
  return !ALLOWED.has(top);
}).length;

if (problems.length) {
  console.error(`\n✗ 发现 ${problems.length} 处反向依赖（业务层 → Web 层）：\n`);
  for (const p of problems) console.error('  ' + p);
  console.error('\n（推送的契约在 com.factory.hub.service.Broadcaster；实现是 web.WsHandler）');
  process.exit(1);
}

console.log(`✓ 依赖方向正确：扫了 ${scanned} 个业务/基础设施类，无一处 import com.factory.hub.web.*`);
console.log(`  （放行 ${[...ALLOWED].join(' / ')}：实现方与装配方本来就该认识具体实现）`);
