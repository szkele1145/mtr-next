const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.35.0 - 上游实证门动画 + 尺度/UV 修复',
  version_number: '0.35.0',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.35.0 - 上游实证门动画 + 尺度/UV 修复',
    '',
    '本版依据 MTR 4.x 上游 MIT 源码（Minecraft-Transit-Railway/Minecraft-Transit-Railway，master）逐行比对后实施。',
    '',
    '**修复（两项均影响所有 4.0 格式车包的显示）**',
    '- bbmodel 坐标未做 /16 尺度换算：上游 BlockbenchElement 明确 "16 Blockbench units = 1 Minecraft block"，此前车体渲染比实际大 16 倍。',
    '- UV 归一化硬编码 /24：bbmodel 的 UV 除数应为模型自身 resolution（实测 ERIDU 四个模型为 512/128/24/64）。旧代码让车体（resolution 512，UV 最大 448）贴图严重越界错位。',
    '',
    '**新增**',
    '- `DoorAnimation4x`：上游 DoorAnimationType 12 值逐分支移植（含 3.x 没有的 PLUG_SLOW_2/PLUG_SLOW_3），签名带 flipped，copySign 语义与上游一致。',
    '- 门动画接入渲染：`drawBoxFaces` 扩展为 X/Z 双轴位移，`renderCar` 按 parts[] 的门倍率 + 上游曲线驱动门盒（塞拉门 X 外摆 + Z 滑动，滑门 Z 滑动）；非门盒行为不变。编组预览同步（预览门取全开态）。',
    '- 门归属判定与上游 isDoor() 对齐：doorXMultiplier != 0 || doorZMultiplier != 0，按 outliner 组名匹配 parts[].names。',
    '',
    '**文档**',
    '- `docs/door-animation-audit.md` §4 四个开放问题全部关闭：倍率单位=像素（渲染时 /16）、isDoor 判定、flipped 来源（positionDefinitions）、X 轴曲线存在性，均附上游文件行号。',
    '- 新增 `docs/_upstream/`（上游 MIT 源码快照，仅供比对）。',
    '',
    '**测试**',
    '- ParserSelfTest 98 → 155 全绿（门曲线 40+ 项、尺度/UV 13 项、门标注 17 项，期望值由上游分支手算锁定）。',
    '',
    '注：渲染效果需图形客户端肉眼验证（docs/client-verification-boundary.md 记录的验证边界）。',
    'No gameplay changes; parser self-test passes 155/155.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.35.0-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.35.0-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.35.0 (contact: szkele1145)',
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