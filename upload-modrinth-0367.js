const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.7-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.7 - 门动画预解析优化 + 上游 addCube 字节码实证',
  version_number: '0.36.7',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.7 - 渲染热路径优化 + 上游 `addCube` 字节码实证',
    '',
    '本版**不改渲染行为**（有回归测试证明等价），只做性能优化 + 文档实证闭合。',
    '',
    '### 1. 性能：门动画曲线类型改「加载期预解析」',
    '',
    '原先渲染端每帧、每门部件都要：',
    '',
    '1. 调 `DoorAnimation4x.parse(String)` —— 内部 `values()` **克隆整个枚举数组** + 13 次字符串比较；',
    '2. 调 `DoorMovementInterpolation.key(...)` —— **每帧拼一个新字符串**（GC 压力）。',
    '',
    'ERIDU 车包实测有 **42 个门部件** ⇒ 60 FPS 下**每秒 5040 次枚举解析 + 5040 次字符串分配**。',
    '',
    '现在两者的结果在**加载期**一次性算好（`PartInfo.doorAnimation` / `PartInfo.interpolationKeyPrefix`），',
    '渲染期**零解析成本**。',
    '',
    '### 2. 新增回归测试（7 项）',
    '',
    '`ParserSelfTest.testDoorAnimationPreparse()` 证明：',
    '',
    '- 全部 13 个枚举值的预解析结果 == `parse()` 结果；',
    '- 未知值回退 `STANDARD`（与 `parse()` 一致）；',
    '- 缺省字段 → `STANDARD`；',
    '- 预拼的 key 前缀 + flipped 与 `key()` **逐字相同**（正负倍率都验）。',
    '',
    '### 3. 闭合：上游 `OptimizedModel.MaterialGroup.addCube`（字节码实证）',
    '',
    '此前只知道「优化渲染走 `addCube` 抽取 cube」，但**从未取到该类源码**。',
    '本版取到上游发行 jar 并反汇编，**纠正了一个此前的错误理解**：',
    '',
    '> **`addCube` 并不抽取 cube 几何。** 它把整个 `ModelPart` 挂到一个',
    '> `CapturingVertexConsumer`（MC `VertexConsumer` 的实现）上，**照常走 `ModelPart.render`**。',
    '> 所谓「优化」只是**顶点批次合并**（把顶点攒进 `RawMesh`/`RawModel`，减少 draw call）。',
    '',
    '上游消费者做的事 = 「translate →（flipped 时）绕 Y 轴转 180° → `modelPart.render(...)`」，',
    '与 mtr-next 的 `drawInstance` **逐步一致**。',
    '',
    '⇒ **渲染语义与 mtr-next 等价**，mtr-next 仅少了批次合并这一层性能优化。',
    '',
    '证据（含 class 文件与 javap 全文）已存进 `docs/_upstream/binary-evidence/optimized-model-addcube-4.0.6/`。',
    '',
    '### 4. ⚠️ 更正上一版的自测数字',
    '',
    '0.36.6 的更新日志写「`parserSelfTest` PASS=**273**」—— **这是错的**。',
    '2026-10-21 实跑复核：',
    '',
    '- 0.36.5 及之前 = **245**；',
    '- 0.36.6 实测 = **256**（不是 273）；',
    '- 0.36.7（本版）= **263**（256 + 新增 7 项预解析回归）。',
    '',
    '源码与提交历史核对确认：**不是代码回退，是当初记录写错了**。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=**263** FAIL=0；',
    '- `bbModelTreeTest` PASS=46 FAIL=0；',
    '- 构建 0 错误。',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
    '',
    'No rendering behaviour change; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.7-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.7-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.7 (contact: szkele1145)',
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
