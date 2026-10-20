const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.0 - bbmodel → MC 原生模型树（复刻上游构造过程）',
  version_number: '0.36.0',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.0 - bbmodel → MC 原生模型树（复刻上游构造过程）',
    '',
    '**核心变更：不再手算四边面，改为复刻上游 MTR 4 的构造链**',
    '',
    '上游 MTR 4 没有「逐面手算」这条路：它把 bbmodel 翻译成 MC 原生的 `ModelPart` 树，再走 `ModelPart.render`。逐项对照上游源码（`BlockbenchOutline` / `GroupTransformations` / `BlockbenchElement` / `ModelPartExtension` / `EntityModelExtension`）后，mtr-next 改为复刻**构造过程**而非**手算结果**。',
    '',
    '旧路径丢掉的 bbmodel 语义（全部修复）：',
    '- 元素 `rotation`（旧：**完全忽略**）→ 现在进入变换链',
    '- 元素 `origin`（旋转中心）→ 现在是链的一节',
    '- outliner 嵌套组层级（旧：**扁平化**）→ 现在是 pivot 逐级差分的链',
    '- `shade` / `mirror_uv` / `inflate` → 按上游语义进 `CubeListBuilder`',
    '- box-UV 布局 → 由 MC 自己的 `addBox` 计算，与上游同一套代码路径',
    '',
    '**本次修掉的实现缺陷（均对照上游源码逐条核实）**',
    '- `addElement` 漏减 `modelYOffset*16` **两处**（上游 `BlockbenchElement` L24 / L35）',
    '- 元素中心坐标未从盒原点出发算',
    '- 链节点名路径未继承父路径 → bake 后取不到节点（元素全丢）',
    '- `CustomResourcesReloadMixin` 需一并失效原生树缓存',
    '',
    '**事实修正（实测推翻旧文档）**',
    '- MC `CubeListBuilder.mirror(true)` **不改变 UV 区域**，只翻转**顶点顺序**（贴图沿 V 镜像）。旧文档「mirror 改 UV 布局」的说法有误。',
    '- MC 1.20.1 `ModelPart$Cube` 的六面 box-UV 布局已用**真实 MC 类**实测确证（16³ 盒、u=100,v=200：down=[116..132]×[200..216]、up=[132..148]×[200..216]、west=[100..116]×[216..232]、north=[116..132]×[216..232]、east=[132..148]×[216..232]、south=[148..164]×[216..232]）。',
    '',
    '**新增**',
    '- `client/render/BbModelTree.java`：bbmodel → MC 原生树（复刻上游全部构造语义）。',
    '- `client/render/BbModelTreeCache.java`：资源加载 + 缓存（键 = modelResource + propertiesResource）。',
    '- 离线自测 `BbModelTreeTest`（gradle 任务 `bbModelTreeTest`）：34 项原生树构造语义断言。',
    '',
    '**重写**',
    '- `BbModelTrainRenderer`：门位移 / 翻转位 / 位置实例逐条对齐上游 `ModelPropertiesPart.renderNormal`（L347-364）与 `iteratePositions`（L654-656）。',
    '- `FormationPreviewRenderer`：预览与实车**共用同一棵树**，两者几何必然一致。',
    '',
    '**验证**',
    '- 离线自测 `parserSelfTest` PASS=243 FAIL=0；`bbModelTreeTest` PASS=34 FAIL=0。',
    '- 构建 0 错误；jar 100841 B。',
    '',
    '**已知未验证项（诚实标注）**',
    '- 实际贴图采样 / 光照与透明批次分层 / 上游 `OptimizedModel` 优化渲染路径：均需图形客户端目视，离线无法验证（见 docs/client-verification-boundary.md）。',
    '',
    'No gameplay changes; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.0-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.0-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.0 (contact: szkele1145)',
    'Content-Type': 'multipart/form-data; boundary=' + boundary,
    'Content-Length': body.length
  }
};
const req = https.request(options, res => {
  let raw = '';
  res.on('data', c => raw += c);
  res.on('end', () => { console.log('HTTP_CODE:' + res.statusCode); console.log(raw); });
});
req.on('error', e => console.error('ERROR:', e.message));
req.write(body);
req.end();
