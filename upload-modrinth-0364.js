const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.4-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.4 - 门时序窗口与闪烁接入渲染 + opening 语义对齐',
  version_number: '0.36.4',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.4 - 门时序窗口与闪烁接入渲染 + opening 语义对齐',
    '',
    '上游 `ModelPropertiesPart.java:315-349` 的 `renderNormal` 有三处 mtr-next 未对齐，本版全部补上。',
    '',
    '### 1. `opening` 是门**运动方向**，不是「门当前是否打开」',
    '',
    '上游 L338：`opening = getAdjustedDoorMultiplier(...) > 0`（+1 开门 / -1 关门）。',
    '',
    'mtr-next 此前传的是 `doorsOpen`（门当前是否打开），后果有两个：',
    '',
    '- **关门专用分段曲线永远取不到**。`STANDARD_SLOW` 在 `!opening` 时走',
    '  `DoorAnimationType.java:142-151` 的关门曲线（t<0.2 区间是 0→1→doorMax 的分段），',
    '  开门分支则 fall through 到 default 的 `smoothEnds(0, doorMax, 0, 0.5)`。',
    '  两者在 t=0.15 处分别是 1 和 3.3 —— 传错 `opening` 会让关门时门**跳一下**。',
    '- **门时序窗口选错分支**（opening 窗口 vs closing 窗口）。',
    '',
    '现在改为由门开度的**变化方向**推导（比上一帧大 = 开门中），每车保存一份上一帧状态。',
    '',
    '### 2. 曲线的时间参数就是门开度本身',
    '',
    '上游 L347 传的是 `Math.max(doorValue, doorOverrideValue)`，不是布尔门开度。',
    'mtr-next 此前 `time = doorsOpen ? doorValue : 0`，门关闭期间曲线被钉死在 0。',
    '现在 `time = |doorValue|`，门开度随时间递减 → 曲线自然回退。',
    '',
    '### 3. 门时序窗口与闪烁接入渲染',
    '',
    '0.35.4 已实现 `PartInfo.shouldRenderAt` / `flashOn`，但**渲染路径从未调用**——',
    '此前所有部件无条件渲染。现在接上：',
    '',
    '- 上游 L339-345：门行程落在 `[from, until]` 毫秒窗口外 → 该部件不渲染',
    '  （ERIDU 的 `doordengguan_n` from=100、`doordengshan_n` from=50 + flash 500/500、',
    '  `lcdp_dooryincang` from=1）。',
    '- 上游 L317/L320：闪烁熄灭 → `y` 设为 `Integer.MAX_VALUE`。',
    '',
    '上游用「**推离视野**」而非跳过绘制（保持渲染批次与光照计算一致），本实现照做。',
    '层级闪烁取第一条带闪烁配置的部件（上游整层共享一份配置）。',
    '',
    '### 其他',
    '',
    '- `BbModelTrainRenderer` 与 `FormationPreviewRenderer` 同步（预览与实车共用同一套判定）。',
    '- 新增 5 条关门曲线断言（`parserSelfTest` 254 → 259）。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=259 FAIL=0；`bbModelTreeTest` PASS=46 FAIL=0。',
    '- 构建 0 错误；jar 104278 B。',
    '',
    '### 上游快照核对',
    '',
    '- `ModelPropertiesPart.java:315-349`（renderNormal 全流程）',
    '- `DoorAnimationType.java:142-151`（STANDARD_SLOW 关门分支）',
    '- `core_Utilities.java:61-63`（isBetween 含边界、支持倒序区间）',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
    '',
    'No gameplay changes; both offline self-test suites pass.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.4-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.4-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.4 (contact: szkele1145)',
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
