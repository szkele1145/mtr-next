const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.6-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.6 - 上游二进制实证：Box 归一化与 Vector3d 旋转语义核实',
  version_number: '0.36.6',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.6 - 上游二进制实证（Box 归一化 / Vector3d 旋转）',
    '',
    '本版不改渲染行为，**更正一处此前写错的文档结论**，并把结论固化为可回归测试。',
    '',
    '### 1. 更正：「上游 testDoors 永不报重叠」是错的',
    '',
    '0.36.5 曾断言：上游 `BlockbenchElement` 构造 `Box` 时对 X/Y/Z 全部取负，',
    '导致三轴倒序；MC 的 `Box.intersects` 要求每轴 `min <= max`，',
    '所以上游 `testDoors` 对 bbmodel 车体**永远不会报重叠**。',
    '',
    '**该断言是错的。** 本版取到上游发行 jar 并反汇编核实：',
    '',
    '- 上游 `org.mtr.mapping.holder.Box(double×6)` 的字节码就是 `new class_238(x1,y1,z1,x2,y2,z2)`，',
    '  直接把 6 个参数交给 MC 的 `AABB`（`class_238`），**自己不排序**；',
    '- 而 MC 1.20.1 的 `AABB` 六参构造器**内部自行归一化**：',
    '  `minX=Math.min(x1,x2)`、`maxX=Math.max(x1,x2)`（X/Y/Z 三轴同）；',
    '- ⇒ 上游拿到的 `Box` **始终有序**，**`testDoors` 能正常检测门重叠**。',
    '',
    'mtr-next 的 `Element.upstreamBox()` 同样做归一化，',
    '**与上游行为等效**（不是"有意偏离"）。渲染行为**无变化**。',
    '',
    '### 2. 闭合：上游 `Vector3d` 的旋转数学',
    '',
    '此前「`rotateX/Y/Z` 的具体实现」是未闭合的开放问题。',
    '本版核实到：上游 `Vector3d.rotateX/Y/Z` 分别转发到 MC `Vec3` 的',
    '`method_1037` / `method_1024` / `method_31033`（即 `xRot` / `yRot` / `zRot`），',
    '都是**绕原点**的标准右手旋转：',
    '',
    '| 方法 | 公式 |',
    '|---|---|',
    '| `xRot(θ)` | `y\' = y·cosθ − z·sinθ`；`z\' = y·sinθ + z·cosθ`；`x\' = x` |',
    '| `yRot(θ)` | `x\' = x·cosθ + z·sinθ`；`z\' = z·cosθ − x·sinθ`；`y\' = y` |',
    '| `zRot(θ)` | `x\' = x·cosθ − y·sinθ`；`y\' = x·sinθ + y·cosθ`；`z\' = z` |',
    '',
    '上游组合顺序为 `rotateX → rotateY → rotateZ`（先 X 后 Y 后 Z），',
    '角度取 `rx=rad(-r0)`、`ry=rad(-r1)`、`rz=rad(+r2)`。',
    '',
    '⇒ mtr-next `Element.rotate()` **与上游逐项一致**（此前是按调用顺序推测，现已实证）。',
    '',
    '### 3. 新增回归测试',
    '',
    '- `ParserSelfTest.testUpstreamBoxRotation()`：绕 X / Y / Z 各 90° 的盒坐标 +',
    '  倒序输入的归一化等效性，共 14 项断言；',
    '- 把上游 class 文件存进 `docs/_upstream/binary-evidence/`，附来源与复现命令。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=**273** FAIL=0（0.36.5 为 245）；',
    '- `bbModelTreeTest` PASS=46 FAIL=0；',
    '- 构建 0 错误。',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
    '',
    'No rendering behaviour change; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.6-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.6-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.6 (contact: szkele1145)',
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
