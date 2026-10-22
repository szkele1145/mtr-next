const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.37.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.37.0 - 渲染热路径：位定义查表提到循环外（顺序不变）',
  version_number: '0.37.0',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.37.0 - 渲染热路径：位定义查表提到循环外（顺序不变）',
    '',
    '本版**不改渲染行为**（渲染顺序逐字不变），继续清理渲染热路径上的重复查表。',
    '',
    '### 1. 位定义查表从「双层循环内」提到「循环外」',
    '',
    '渲染一列车时的循环结构：',
    '',
    '```',
    'for info in properties.parts[]:                   <- 部件记录',
    '  for name in info.names:                         <- 组名',
    '    for defName in info.positionDefinitionNames:  <- 位定义名',
    '      group = positions.get(defName)               <- 查表',
    '```',
    '',
    '`positions.get(defName)` 的结果**与 `name` 无关**',
    '（同一个 `info` 的所有组名共用同一批位定义），',
    '却在 `name` 循环里被**重复查了 `names.length` 次**。',
    '',
    '=> 查表次数：`names x defNames` => **`defNames`**。',
    '',
    '改动：在 `name` 循环**外**一次性把位定义名解析成数组，循环内直接取。',
    '',
    '★ **渲染顺序完全不变**：仍是 name-major / defName-minor，',
    '绘制次序、深度写入、混合顺序与改前逐字一致。',
    '',
    '（ERIDU-DG1000A 实测：39 条部件记录，每条若干组名 x 若干位定义名，',
    '重复查表是每秒数千次量级。）',
    '',
    '### 2. 同步改了编组预览渲染器',
    '',
    '`FormationPreviewRenderer` 有**同一套双层循环结构**，一并处理，',
    '避免主渲染器与预览器行为分叉。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=**278** FAIL=0；',
    '- `bbModelTreeTest` PASS=46 FAIL=0；',
    '- 构建 0 错误。',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.37.0-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.37.0-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.37.0 (contact: szkele1145)',
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
