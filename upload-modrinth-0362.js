const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.2-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.2 - bbmodel → MC 原生模型树 + 门位移插值层',
  version_number: '0.36.2',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.2 - bbmodel → MC 原生模型树 + 门位移插值层',
    '',
    '### 0.36.0 核心变更：不再手算四边面，改为复刻上游构造过程',
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
    '- MC 1.20.1 `ModelPart$Cube` 的六面 box-UV 布局已用**真实 MC 类**实测确证。',
    '',
    '### 0.36.1：修 props-only 层的 null 资源 NPE',
    '',
    '`Properties4xLoader.load` 直接把 `null` 资源路径塞进 `ConcurrentHashMap.computeIfAbsent`，会抛 NPE。现在先做空值短路，并去掉与 `BbModelTree` 重复的一层缓存。',
    '',
    '### 0.36.2：门 Z 位移接上上游 500 ms 插值层',
    '',
    '上游 `ModelPropertiesPart.java:348` 对 Z 位移不直接用曲线值，而是走 `PersistentVehicleData.getInterpolatedDoorValue` → `DoorMovementInterpolation`（`PersistentVehicleData.java:164-178`），内含 500 ms 线性插值，**用于消除开门↔关门方向翻转处的跳变**。',
    '',
    '- 新增 `DoorMovementInterpolation`（对齐上游 `org.mtr.mod.resource.Interpolation` 的线性混合语义）。',
    '- 插值器按上游 key 规则分桶：`doorZMultiplier + "_" + doorAnimationType + "_" + flipped`。',
    '- 每列车一份（上游挂在 `PersistentVehicleData` 上）。',
    '- **X 位移不插值**——上游 L347 走 `getDoorAnimationX`，无插值层。',
    '',
    '实测 ERIDU 车包有 42 个门部件（`STANDARD` 18 + `STANDARD_SLOW` 24）走上游这条路径。',
    '',
    '### 数据层补齐（0.35.4 成果，随 0.36.0 一并发布）',
    '',
    '上游 `modelPropertiesPart.json` schema 共 25 个字段，mtr-next 此前只读 8 个。本次补上并实现判定逻辑：',
    '',
    '- `renderFromOpeningDoorTime` / `renderUntilOpeningDoorTime` / `renderFromClosingDoorTime` / `renderUntilClosingDoorTime`（毫秒）',
    '- `flashOnTime` / `flashOffTime`（毫秒）',
    '- `PartInfo.shouldRenderAt(doorValue, opening)`：对齐上游 `ModelPropertiesPart.java:342-345`',
    '- `PartInfo.flashOn(currentTimeMillis)`：对齐上游 `ModelPropertiesPart.java:317`',
    '',
    '并修正一个关键常量：**`DOOR_MOVE_TIME = 3200`**（此前误设为 500），定义在 `Transport-Simulation-Core` 仓库的 `core/data/Vehicle.java:59`。',
    '',
    '### 验证',
    '',
    '- ParserSelfTest **254 全绿**（门时序窗口、闪烁、500 ms 插值层的上游语义回归）。',
    '- BbModelTreeTest **46 全绿**（bbmodel → ModelPart 构造语义）。',
    '- 构建 0 警告；jar 103249 B。',
    '',
    '**已知未验证项（诚实标注）**',
    '- 实际贴图采样 / 光照与透明批次分层 / 上游 `OptimizedModel` 优化渲染路径：均需图形客户端目视，离线无法验证（见 docs/client-verification-boundary.md）。',
    '',
    'No gameplay changes; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.2-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.2-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.2 (contact: szkele1145)',
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
