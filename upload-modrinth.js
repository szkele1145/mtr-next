const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.34.3-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.34.3 - 4.0 车包门动画数据层 + outliner 解析',
  version_number: '0.34.3',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.34.3 - 4.0 车包门动画数据层 + outliner 解析',
    '',
    '本版不改渲染行为，把门动画的"死代码"前置链路全部打通为可回归数据层：',
    '',
    '**修复**',
    '- props-only 模型层（ERIDU-DG1000A 的 models[0]，modelResource 为空，仅承载 gangway/barrier properties）此前每帧触发 `new ResourceLocation("")` 异常 + LOGGER.error，且 computeIfAbsent 不缓存 null → 每帧重演。现已短路为 `Collections.emptyList()`，渲染循环的 isEmpty() 分支自然跳过。',
    '',
    '**新增**',
    '- `Properties4xLoader` 解析 `parts[]`：doorXMultiplier / doorZMultiplier / doorAnimationType / condition / renderStage / type / names[]（实测 ERIDU 包 42 个部件带非零门倍率，men2=+11.0 vs men3=-11.0，左右门符号相反）。',
    '- `BbModelLoader` 递归解析 outliner，为每个 element 记录祖先组名链 `groupPath`（children 是 uuid 字符串 + 子组对象的混合数组，已按类型分支）。',
    '- `BbModelLoader.isDoorByGroup(box, parts)`：组名归属门判定，带非零门倍率的 parts[].names 匹配祖先组名即视为门；falls back 到 element.name contains "door"（3.x/YMTR 导出模型旧约定）。',
    '',
    '**文档**',
    '- 新建 `docs/mtr4-pack-format.md`（4.0 车包格式实证：层表、properties 键、parts[] 枚举、outliner 结构）。',
    '- 重写 `docs/door-animation-audit.md`，记录 4 个 open question 作为 blocker（multiplier 单位/左右映射/ accessory 过滤/flipTextureV 语义），渲染端接入被有意推迟。',
    '',
    '**测试**',
    '- ParserSelfTest 70 → 98 全绿（parts[] 缺省/畸形隔离/ERIDU 风格取值 + outliner→groupPath + isDoorByGroup）。',
    '',
    'No gameplay changes; parser self-test passes 98/98.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.34.3-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.34.3-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.34.3 (contact: szkele1145)',
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