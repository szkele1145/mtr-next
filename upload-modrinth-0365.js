const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.5-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.5 - 门重叠检测复刻上游 testDoors + 顶层组语义修正',
  version_number: '0.36.5',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.5 - 门重叠检测复刻上游 testDoors + 顶层组语义修正',
    '',
    '本版把上游 `DynamicVehicleModel.testDoors` 的门重叠检测完整移植进 mtr-next，',
    '并修正一个此前一直存在、但被旧手算路径掩盖的**分组语义偏差**。',
    '',
    '### 1. 顶层组语义修正（重要）',
    '',
    '上游 `DynamicVehicleModel.java:41-62` 的 `blockbenchModel.getOutlines()` **只迭代顶层**',
    'outliner 组：每个**顶层组**才有一个 `nameToPart` 锚点（`nameToPart.put(groupName, anchor)`），',
    '嵌套组只是链上的旋转/位移节点，**不建立锚点**。',
    '',
    '⇒ `modelPropertiesPart.json` 的 `parts[].names` 只能匹配**顶层组名**。',
    '',
    'mtr-next 此前用「祖先组名链」匹配（`isDoorByGroup`），与上游不一致：',
    '嵌套组（如 `win_n_a1`）被误认为可被 part 声明拥有。',
    '现改为只匹配顶层组名。',
    '',
    '### 2. 新增 `DoorOverlapDetector`（复刻上游 `testDoors`）',
    '',
    '- 按 **部件记录 × 位置实例** 粒度生成门盒（上游 `ModelPropertiesPart.java:158` 的粒度定义）；',
    '  ERIDU 车包的 `men2` 就有**两条**记录。',
    '- 门盒用 BB 坐标系的归一化 `Box`（见下节）；',
    '- 沿上游的时间片 `time = i/5`（i = 0..5）逐片计算位移后两两求交；',
    '- 上游的 0.1 零厚度补偿（`ModelPropertiesPart.java:244-246`）照搬。',
    '',
    '### 3. 上游 `Box` 的三轴倒序问题（已归一化）',
    '',
    '上游 `BlockbenchElement` 构造 `Box` 时对 X/Y/Z **全部取负**，',
    '于是「min」端反而大于「max」端（三轴通常全部倒序）。',
    'MC 的 `Box.intersects` 对倒序轴恒返回 false，',
    '因此上游 `testDoors` 对 bbmodel 车体**实际永远不会报重叠**——与它的 javadoc 意图相悖。',
    '',
    '本实现在 `Element.upstreamBox()` 里做**归一化**（min/max 排序），',
    '让门重叠检测能实现上游的**意图**。此为有意偏离，已在代码注释与文档中标注。',
    '',
    '### 4. 删除旧手算加载器',
    '',
    '`BbModelLoader`（自制六面 UV + 逐面绘制）早已不在渲染链路中，',
    '本版将其**删除**，自测全部改用原生树 `BbModelTree.parseForTest` + `DoorOverlapDetector`。',
    'jar 体积因此减少 3254 B（103933 → 100679）。',
    '',
    '### 其他',
    '',
    '- `CustomResourcesReloadMixin` 的资源失效改走 `BbModelTreeCache.invalidate()`；',
    '- `gradle.properties` 修复被 mojibake 损坏的中文注释（构建配置本身未变）。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=245 FAIL=0；`bbModelTreeTest` PASS=46 FAIL=0。',
    '- 构建 0 错误；jar 100679 B。',
    '- ERIDU 真实车包（970 元素 / 36 顶层组）端到端跑通，真实几何**无门重叠告警**。',
    '',
    '### 上游快照核对',
    '',
    '- `DynamicVehicleModel.java:41-62`（顶层组锚点）',
    '- `BlockbenchElement.java:23-59`（几何与 Box 计算）',
    '- `ModelPropertiesPart.java:158`（粒度）、`:244-246`（零厚度补偿）、`:647-651`（flip 语义）',
    '- `DoorAnimationType.java`（各门动画曲线）',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
    '',
    'No gameplay changes; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.5-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.5-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.5 (contact: szkele1145)',
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
