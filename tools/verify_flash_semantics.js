// 核对上游 flash 是 per-part 还是整层：统计同一 properties 文件内
// 各 part 的 flashOnTime/flashOffTime 配置分布。
// 上游证据：ModelPropertiesPartWrapper.java:80-105 每条 wrapper 把自己的
// flashOnTime/flashOffTime 传给新建的 ModelPropertiesPart 实例 → per-part。
const fs = require('fs');
const path = require('path');

const dir = 'F:\\vibe coding工程\\mtr-turbo\\build\\eridu-pack\\assets\\mtr';
if (!fs.existsSync(dir)) {
  console.log('跳过：找不到', dir);
  process.exit(0);
}
const files = fs.readdirSync(dir).filter(f => f.startsWith('properties_') && f.endsWith('.json'));
console.log(`扫描 ${files.length} 个 properties 文件\n`);

let totalParts = 0, totalNonZero = 0;
const risk = [];
for (const fn of files) {
  const data = JSON.parse(fs.readFileSync(path.join(dir, fn), 'utf8'));
  const parts = data.parts || [];
  totalParts += parts.length;
  const nz = parts.filter(p => (p.flashOnTime || 0) !== 0 || (p.flashOffTime || 0) !== 0);
  totalNonZero += nz.length;
  if (!nz.length) continue;

  const byCfg = new Map();
  for (const p of nz) {
    const k = `${p.flashOnTime || 0},${p.flashOffTime || 0}`;
    if (!byCfg.has(k)) byCfg.set(k, []);
    byCfg.get(k).push((p.names || []).join('+'));
  }
  const cfgs = [...byCfg.keys()];
  const same = cfgs.length === 1;
  console.log(`${fn}: 部件 ${parts.length}，带flash ${nz.length}，配置种类 ${cfgs.length} => ${same ? '一致' : '★不一致'}`);
  for (const [k, names] of byCfg) {
    const [on, off] = k.split(',').map(Number);
    console.log(`    on=${on} off=${off} 周期=${on + off}ms  → ${names.join(', ')}`);
  }
  if (!same) risk.push(fn);
}

console.log(`\n合计：${totalParts} 个部件记录，其中 ${totalNonZero} 个带非零 flash 配置`);
if (risk.length) {
  console.log(`⚠ ${risk.length} 个文件存在同层 flash 配置不一致 —— per-part 与"整层取第一条"会产生差异：`);
  risk.forEach(f => console.log('   ' + f));
} else {
  console.log('✓ 本车包内所有带 flash 的部件配置一致 —— per-part 与整层取第一条在本包上等价');
}
console.log('\n结论：上游 flash 是 per-part（ModelPropertiesPart 实例级），不是整层共享。');
