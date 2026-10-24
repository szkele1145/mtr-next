// 探查 eridudanguei.bbmodel 的 outliner children 结构：
// 找出非字符串 child 的实际字段，并统计真实嵌套层级。
const fs = require('fs');
const path = require('path');
const target = 'F:\\vibe coding工程\\mtr-turbo\\build\\eridu-pack\\assets\\mtr\\eridudanguei.bbmodel';
const data = JSON.parse(fs.readFileSync(target, 'utf8'));
const outliner = data.outliner || [];
const elements = data.elements || [];
const uuidToEl = new Map(elements.map(e => [e.uuid, e]));

console.log('=== outliner[0] 的原始键 ===');
console.log(Object.keys(outliner[0]).join(', '));
console.log('=== outliner[0] 去掉 children 后的内容 ===');
const { children, ...rest0 } = outliner[0];
console.log(JSON.stringify(rest0).slice(0, 400));

console.log('\n=== 找出所有非字符串 child 并打印其键 ===');
const keySet = new Set();
let objChild = 0, strChild = 0;
function scan(list, depth) {
  for (const g of list) {
    if (!Array.isArray(g.children)) continue;
    for (const c of g.children) {
      if (typeof c === 'string') { strChild++; continue; }
      objChild++;
      if (c && typeof c === 'object') {
        Object.keys(c).forEach(k => keySet.add(k));
        console.log(`  [depth ${depth}] 组 "${g.name}" 的对象 child 键: ${Object.keys(c).join(', ')}`);
        console.log(`      内容: ${JSON.stringify(c).slice(0, 260)}`);
      }
    }
    const subs = (g.children || []).filter(c => c && typeof c === 'object' && c.children);
    if (subs.length) scan(subs, depth + 1);
  }
}
scan(outliner, 0);
console.log(`\n字符串 child: ${strChild}, 对象 child: ${objChild}`);
console.log('对象 child 的键集合:', [...keySet].join(', '));

// 用正确的判据重算
function walkCorrect(list, depth, acc) {
  for (const g of list) {
    acc.all.push(g.name);
    if (depth === 0) acc.top.push(g.name);
    const subs = (g.children || []).filter(c => c && typeof c === 'object' && c.name !== undefined);
    if (subs.length) walkCorrect(subs, depth + 1, acc);
  }
  return acc;
}
const acc = walkCorrect(outliner, 0, { all: [], top: [] });
const uniq = [...new Set(acc.all)];
console.log(`\n修正后：顶层组 ${acc.top.length}，全部分组名(去重) ${uniq.length}`);
console.log('去重全名:', uniq.join(', '));
