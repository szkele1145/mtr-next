const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.9-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.9 - 渲染热路径：缓存键/贴图预计算 + 门位移零分配',
  version_number: '0.36.9',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.9 - 渲染热路径：缓存键/贴图预计算 + 门位移零分配',
    '',
    '本版**不改渲染行为**（有回归测试证明等价），继续清理渲染热路径上的每帧分配。',
    '',
    '### 1. 模型树缓存键改「加载期预计算」',
    '',
    '原先渲染端每帧、每模型层都要做一次字符串拼接',
    '（`modelResource + "|" + propertiesResource`）来查缓存 ⇒',
    '每帧产生一个临时 `String`（含 `StringBuilder` 内部 char[] 拷贝）。',
    '',
    '一列车多层、每层每帧 ⇒ 每秒数千次无谓分配。',
    '',
    '现在键在 `ModelLayer` 构造时算好（`ModelLayer.treeCacheKey`），渲染期零分配。',
    '',
    '★ **正确性关键**：预计算键必须与旧算法**逐字相同**，',
    '否则缓存会命中**别的 properties 组合的模型树** ——',
    '上游 `DynamicVehicleModel` 把 properties 的 `modelYOffset` 传进每个元素，',
    '单键缓存会让不同车包**串偏移**。',
    '新增回归测试 `testTreeCacheKeyPrecompute()`（**8 项**）逐字比对了 6 组输入',
    '（含 null / 空串组合）+ 稳定性 + 不同 properties 必须异键。',
    '',
    '### 2. 贴图 `ResourceLocation` 改「加载期预解析」',
    '',
    '原先每帧每模型层 `new ResourceLocation(...)` 一次；',
    '现在在 `ModelLayer` 构造时解析好（畸形资源串 → null → 回退白色混凝土）。',
    '缺省白色混凝土贴图也改为**常量**，不再每次 new。',
    '',
    '### 3. 门位移改「零分配」',
    '',
    '`doorOffsetPixels` 原先每次调用 `new float[2]` 返回结果，',
    '每帧每渲染实例一次 ⇒ 每秒数千次短命数组分配（GC 压力）。',
    '',
    '改为主动写出 X / Z 两个分量（调用方 `drawInstance` 立即读取，',
    '期间不跨调用 ⇒ 无重入问题）。',
    '',
    '### 4. 说明：有一处查表**故意保留**',
    '',
    '`renderCar` 里每帧每车厢的 `VehiclePackLoader.getVehicle(trainId)` **不缓存** ——',
    '资源重载时 `VEHICLES.clear()` + `put()` 会**替换 `VehicleResource` 对象**，',
    '渲染器必须每帧取新对象，否则会读到陈旧车包数据。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=**278** FAIL=0（0.36.8 为 270，+8）；',
    '- `bbModelTreeTest` PASS=46 FAIL=0；',
    '- 构建 0 错误。',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
    '',
    'Fixes: door opening direction was constant-true on multi-car trains; vehicle registry map was not thread-safe.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.9-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.9-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.9 (contact: szkele1145)',
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
