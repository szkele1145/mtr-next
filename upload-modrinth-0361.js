const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\_wt0361\\build\\libs\\mtr-next-0.36.1-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.1 - 修 props-only 层 null 资源 NPE',
  version_number: '0.36.1',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.1 - 修 props-only 层 null 资源 NPE',
    '',
    '**修掉的运行时崩溃**',
    '',
    '`Properties4xLoader.load` 直接把资源路径塞进 `ConcurrentHashMap.computeIfAbsent`，',
    '而 `ConcurrentHashMap` 不接受 null key —— 遇到 **null** 会抛 NPE。',
    '',
    '4.0 车包允许某一层**只挂 properties 而不带 model 资源**（props-only 层），',
    '这类层的 `propertiesResource` 为 null，于是走进 NPE 路径。',
    'ERIDU 实包恰好含这样一层（1 层 props-only + body + LCD + dashboard + roof）。',
    '',
    '⇒ 0.36.0 及以前：加载这类车包会 NPE。',
    '',
    '**实现**',
    '- `Properties4xLoader.load`：入口处做 null / 空串短路，返回默认 `GangwayProperties`。',
    '- `BbModelTreeCache`：去掉与 `BbModelTree` 重复的第二张缓存表',
    '  （同一棵模型树此前被缓存两份，内存翻倍）。',
    '',
    '**验证**',
    '- 离线自测 `parserSelfTest` PASS=243 FAIL=0；`bbModelTreeTest` PASS=39 FAIL=0',
    '  （新增 5 项 null 资源安全断言）。',
    '- 构建 0 错误；jar 100696 B。',
    '',
    '**0.36.0 回顾**',
    '- bbmodel → MC 原生 ModelPart 树（复刻上游 MTR 4 的构造过程），详见 0.36.0 的说明。',
    '',
    'No gameplay changes; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.1-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.1-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.1 (contact: szkele1145)',
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
