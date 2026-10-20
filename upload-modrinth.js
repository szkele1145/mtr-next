const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.3-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.35.3 - 镜像展开（positionsFlipped）+ ERIDU 左半侧齐活',
  version_number: '0.35.3',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.35.3 - 镜像展开（positionsFlipped）+ ERIDU 左半侧齐活',
    '',
    '**核心修复：左半侧车门/车窗此前完全缺失**',
    '',
    '上游 `ModelPropertiesPart.iteratePositions`（L654-656）把位定义的 `positions` 与 `positionsFlipped` **都渲染**：前者 flipped=false，后者 X 取反 + flipped=true，且渲染时绕 Y 轴转 180°（`ModelPropertiesPart.java:364`）。`addBox`（L649-650）同步镜像 X/Z。',
    '',
    '实测 ERIDU 车包（eridudanguei.bbmodel，970 元素）：`men2`/`men3`/`win`/`door` 等顶层组 **几何只建了 +X 半边**（men2 x∈[19,20]px，win 右 24 盒 / 左 0 盒），另一侧完全靠 flipped 副本补齐。',
    '',
    '⇒ 0.35.2 及以前：**左半侧车门、车窗、门灯、门地板全部不渲染**。',
    '',
    '**实现**',
    '- 新增 `PositionDefinitions4xLoader`：解析 `definition_*.json` → 位定义名 → `PositionGroup{positions, positionsFlipped}`。',
    '- `Properties4xLoader.PartInfo` 增加 `positionDefinitionNames`。',
    '- `VehicleResource.ModelLayer` 增加 `positionDefinitionsResource` 字段并解析位定义名列表。',
    '- `BbModelLoader`：`BbBox` 增加 `topGroup`/`singleSidedGroup`/`mirrorNeeded`/`doorFlipped`；`buildBoxes` 统计各顶层组 X 侧分布；`annotate` 双条件判定镜像（几何单侧 AND 引用含翻转位的位定义）；`loadAnnotated` 增加第三缓存键。',
    '- `BbModelTrainRenderer`：`drawBoxFaces` 增加 mirror 参数（(x,z)→(−x,−z)，每面 winding 反转）；新增 `drawMirrorPair`（本体 flipped=false、镜像 flipped=true，上游 `copySign(abs,(flipped?-1:1)*mult)` 自动反号）。',
    '- `FormationPreviewRenderer` 同步镜像逻辑。',
    '',
    '**为什么对称组不镜像**',
    '',
    '上游对 `end`/`hand` 等本就左右对称的组也会画 flipped 副本（与对面正本重叠）。mtr-next 用 `singleSidedGroup` 跳过这些组，避免 Z-fighting 与重复面。实测 ERIDU：17/36 个顶层组需镜像。',
    '',
    '**验证**',
    '- 离线自测 PASS=216 FAIL=0（新增 33 条）。',
    '- 真实 ERIDU 车包端到端：8 个门/窗组全部判为需镜像；`end`/`end_n`/`hand`/`hand_n` 判为双侧组不镜像；`men2` dz=+11 / `men3` dz=−11 与实测一致。',
    '- 构建 0 警告；jar 83216 B。',
    '',
    '**0.35.2 回顾**',
    '- `BbModelLoader.detectDoorOverlaps`：上游 `testDoors` 移植（模拟 5 个时间片检查两扇门是否中途相撞）。',
    '- `flipTextureV` 定论：不实装是正确的（上游对 bbmodel 不消费此字段）。',
    '',
    'No gameplay changes; parser self-test passes 216/216.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.35.3-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.35.3-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.35.3 (contact: szkele1145)',
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