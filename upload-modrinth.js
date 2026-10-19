const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.1-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.35.1 - mixin 注入点编译期校验加固',
  version_number: '0.35.1',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.35.1 - mixin 注入点编译期校验加固',
    '',
    '**修复（静默失效类）**',
    '- 4 个 mixin（CustomResourcesReloadMixin / ItemBlockClickingBaseMixin / RenderTrainsPreviewMixin / SidingScreenMixin）的目标类由 `targets = "字符串"` 改为 `value = 类字面量`。',
    '',
    '两者运行时等价，但 Mixin AP 对 `targets` 字符串目标**不解析目标类**——注入方法名/描述符写错也照样编译通过，运行时静默不命中（0.34.2 修的轨道预览正是这类"永不命中"）。改为类字面量后 AP 真正解析 YMTR 目标类并校验注入点，把这类缺陷从运行时静默失效提前为编译错误。',
    '',
    '目标类经 javap 实证均为 public class（YMTR 3.6.4.2 发布 jar）：`mtr.client.CustomResources`、`mtr.item.ItemBlockClickingBase`、`mtr.render.RenderTrains`、`mtr.screen.SidingScreen`。`remap = false` 保留——`mtr.*` 是 YMTR 自有包名，不被 intermediary 重映射。',
    '',
    '**验证**',
    '- 编译通过即证明 4 个注入点的签名全部正确（此前无任何编译期保障）。',
    '- 构建日志的 4 条 "Mixin target ... is public and should be specified in value" 警告全部消除。',
    '- `mtr-next.refmap.json` 内容不变（RailPreview 的 Mojmap→intermediary 桥接完好）。',
    '- ParserSelfTest 155/155 全绿。',
    '',
    '**0.35.0 回顾（上一版）**',
    '- bbmodel 坐标 /16 尺度换算（上游 BlockbenchElement："16 Blockbench units = 1 Minecraft block"）——此前车体比实际大 16 倍。',
    '- UV 归一化除数改用 bbmodel 自身 resolution（实测 ERIDU 为 512/128/24/64）——此前硬编码 /24 让车体贴图 UV 严重越界错位。',
    '- 门动画接入渲染（上游 DoorAnimationType 12 值移植 + X/Z 双轴位移）。',
    '',
    'No gameplay changes; parser self-test passes 155/155.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.35.1-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.35.1-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.35.1 (contact: szkele1145)',
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