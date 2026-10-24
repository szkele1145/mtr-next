// 核对 eridudanguei.bbmodel：顶层组 36 / 全部分组名 58 / 需镜像组数。
// 镜像判据：组内（含嵌套）含 mirror_uv=true 的元素。
const fs = require('fs');
const target = 'F:\\vibe coding工程\\mtr-turbo\\build\\eridu-pack\\assets\\mtr\\eridudanguei.bbmodel';
const data = JSON.parse(fs.readFileSync(target, 'utf8'));
const elements = data.elements || [];
const outliner = data.outliner || [];
const uuidToEl = new Map(elements.map(e => [e.uuid, e]));

let mirrorUvCount = 0;
for (const e of elements) if (e.mirror_uv) mirrorUvCount++;
console.log('元素总数:', elements.length, '带 mirror_uv=true:', mirrorUvCount);

// 递归统计每组（含嵌套子树）是否含 mirror_uv 元素
function subtreeHasMirror(group) {
  for (const c of (group.children || [])) {
    if (typeof c === 'string') {
      const e = uuidToEl.get(c);
      if (e && e.mirror_uv) return true;
    } else if (c && typeof c === 'object') {
      if (subtreeHasMirror(c)) return true;
    }
  }
  return false;
}

const topMirror = [];
const topNames = [];
for (const g of outliner) {
  topNames.push(g.name);
  if (subtreeHasMirror(g)) topMirror.push(g.name);
}
console.log('顶层组数:', topNames.length);
console.log('顶层组中含 mirror_uv 元素的:', topMirror.length, '→', topMirror.join(', '));

// 组自身的 mirror_uv 标记
const groupMirrorFlag = outliner.filter(g => g.mirror_uv).map(g => g.name);
console.log('顶层组自身 mirror_uv=true 的:', groupMirrorFlag.length, '→', groupMirrorFlag.join(', '));

// 名字分布
const dist = {};
for (const e of elements) dist[e.name] = (dist[e.name] || 0) + 1;
console.log('elements[].name 分布:', Object.entries(dist).sort((a,b)=>b[1]-a[1]).map(([k,v])=>`${k}:${v}`).join(' '));

// 门相关组名
const all = [];
(function walk(list){ for (const g of list) { all.push(g.name); const subs=(g.children||[]).filter(c=>c&&typeof c==='object'); if (subs.length) walk(subs);} })(outliner);
const doorish = [...new Set(all)].filter(n => /door|men|meng/i.test(n));
console.log('门相关分组名(' + doorish.length + '):', doorish.join(', '));
