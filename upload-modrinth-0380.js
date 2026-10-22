const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.38.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.38.0 - 渲染计划快照：加载期预解析（渲染序列逐字不变）',
  version_number: '0.38.0',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.38.0 - 渲染计划快照：加载期预解析（渲染序列逐字不变）',
    '',
    '本版**不改渲染行为**，把「部件过滤 + 位定义查表」整体挪到加载期，',
    '消灭渲染热路径上**每帧每 NORMAL 部件一次的 `PositionGroup[]` 数组分配**',
    '（0.37.0 的遗留分配：查表虽已提到循环外，但数组本身还是每帧 new）。',
    '',
    '### 1. 新增 LayerRenderCache（渲染计划快照）',
    '',
    '新增 `LayerRenderCache`：加载期把 properties.parts[] 过滤成',
    '「type=NORMAL 且位定义名非空且至少解析到一个组」的部件列表，',
    '每个部件的位定义组**预解析成数组**（含 null 过滤）。',
    '',
    '缓存键 = `properties + "|" + positionDefinitions`（两者任一变化都换键，',
    '不会半新半旧）；跟随资源重载钩子整体失效。',
    '',
    '渲染期循环变成纯遍历：**零查表、零分配**。',
    '',
    '★ **渲染顺序完全不变**：过滤规则与旧循环的 continue 逐条对应，',
    '绘制序列（name-major / defName-minor、正位在前翻转位在后）逐字一致。',
    '',
    '### 2. 等价性回归测试',
    '',
    '新增 `testLayerRenderPlanEquivalence`：把 0.37.0 的旧循环**原样照抄**',
    '为参照实现，与新的快照遍历对**同一输入**产出「绘制指令序列」逐条比对。',
    '',
    '覆盖 10 组用例：正常部件 / 非 NORMAL / 无位定义 / 位定义查不到 /',
    'null 与非 null 混合 / positions+positionsFlipped 并存 / 多部件多组名多定义 /',
    '同一组被多条记录引用（ERIDU men2 形态）/ 空 parts / 畸形输入。',
    '另含 null 安全与列表不可变断言。',
    '',
    '### 3. 同步改了编组预览渲染器',
    '',
    '`FormationPreviewRenderer` 同一套循环结构一并处理，避免主渲染器与预览器分叉。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=**282** FAIL=0（0.37.0 = 278，+4 为本版新增）；',
    '- `bbModelTreeTest` PASS=46 FAIL=0；',
    '- 构建 0 错误。',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.38.0-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.38.0-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
raw(fileBuf);
str('\r\n--' + boundary + '--\r\n');
const body = Buffer.concat(chunks);
console.log('Body length:', body.length);
console.log('SHA-512:', sha512);
const options = {
  hostname: 'api.modrinth.com', port: 443, method: 'POST',
  path: '/v2/version',
  headers: {
    'Authorization': mr,
    'User-Agent': 'szkele1145/mtr-next/0.38.0 (contact: szkele1145)',
    'Content-Type': 'multipart/form-data; boundary=' + boundary,
    'Content-Length': body.length
  }
};
const req = https.request(options, res => {
  let body = '';
  res.on('data', c => body += c);
  res.on('end', () => {
    console.log('Status:', res.statusCode);
    console.log(body);
  });
});
req.on('error', e => console.error('ERR', e.message));
req.write(body);
req.end();
