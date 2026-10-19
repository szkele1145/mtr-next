const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.2-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.35.2 - 门重叠离线检测 + flipTextureV 定论',
  version_number: '0.35.2',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.35.2 - 门重叠离线检测 + flipTextureV 定论',
    '',
    '**新增**',
    '- `BbModelLoader.detectDoorOverlaps(boxes)`：上游 `DynamicVehicleModel.testDoors` 的纯数据面移植——模拟门从 t=0 到 t=1 走 5 个时间片，若两扇门"初始不相交、中途相交"，判定门 X/Z 倍率配错（车包作者配置错误，上游打 WARN）。移植为可离线回归的静态方法。',
    '',
    '本次实测得出两条算法事实（已写进测试用例）：',
    '1. **同向同量滑动的两扇门永远不会相撞**——相对距离恒定。能触发告警的错误配置是倍率不等（快门追上慢门），不是"同向"。',
    '2. ERIDU 的 +11/−11 反向对滑配置，两门初始包围盒本就相交（实测 men2 z 跨 [-56.8, 63.5]、men3 z 跨 [-59.5, 40.0] 像素）→ 上游"初始相交则跳过"分支直接放过，不误报。',
    '',
    '> 由此可知上游 `testDoors` 只能发现"倍率明显配错"的极端情形，对 ERIDU 这类正确配置无诊断价值。移植保留其语义，不夸大其作用。',
    '',
    '**定论（开放问题关闭）**',
    '- `flipTextureV` 语义已定论——**不实装是正确的**。上游 `VehicleModel.createModel` 只把该字段传给 `.obj`/`.mqo`/`.mqoz` 路径；bbmodel 路径的 `DynamicVehicleModel` 构造器完全不接收它。即上游对 bbmodel 同样不消费此字段（Blockbench 导出的 UV 原点本就与 MC 一致）。mtr-next 不实装 = 与上游行为一致，不是遗漏。',
    '',
    '**测试**',
    '- ParserSelfTest 155 → 163 全绿（新增门重叠检测 8 项）。',
    '',
    '**0.35.1 回顾（上一版）**',
    '- 4 个 mixin 目标类由 `targets = "字符串"` 改为 `value = 类字面量`——Mixin AP 对 targets 字符串目标不解析目标类，注入点写错也编译通过、运行时静默不命中；改为类字面量后 AP 真正校验注入点，把静默失效提前为编译错误。',
    '',
    '**0.35.0 回顾**',
    '- bbmodel 坐标 /16 尺度换算（上游 BlockbenchElement："16 Blockbench units = 1 Minecraft block"）——此前车体比实际大 16 倍。',
    '- UV 归一化除数改用 bbmodel 自身 resolution（实测 ERIDU 为 512/128/24/64）——此前硬编码 /24 让车体贴图 UV 严重越界错位。',
    '- 门动画接入渲染（上游 DoorAnimationType 12 值移植 + X/Z 双轴位移）。',
    '',
    'No gameplay changes; parser self-test passes 163/163.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.35.2-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.35.2-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.35.2 (contact: szkele1145)',
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