const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.8-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.36.8 - 修：多车厢列车的门运动方向串味 + 车辆表并发安全',
  version_number: '0.36.8',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.36.8 - 修：多车厢列车的门运动方向串味 + 车辆表并发安全',
    '',
    '本版修掉**两个真实缺陷**（都有回归测试证明），并含 0.36.7 的预解析优化。',
    '',
    '### 1. ★真 bug：多车厢列车的 `opening` 恒为「开门中」',
    '',
    '**症状**：门关闭时，关门时段的门时序窗口完全失效，门的 Z 位移走错曲线分支。',
    '',
    '**根因**（粒度不匹配）：',
    '',
    '| 项 | 粒度 |',
    '|---|---|',
    '| `renderCar(int carNumber, …)` | **每车厢**调一次 |',
    '| `previousDoorValue`（旧实现） | **每列车**一份 |',
    '',
    '⇒ 第 2 辆车厢读到的已是第 1 辆车厢刚写回的**同一个**门开度',
    '⇒ 判定 `doorValue >= previousDoorValue` 恒为 `true`',
    '⇒ **`opening` 恒为「开门中」**。',
    '',
    '后果：① 关门时段的 `renderFromClosingDoorTime` / `renderUntilClosingDoorTime`',
    '整组失效（仍在用开门窗口）；② `getDoorAnimationZ(…, opening)` 走**开门曲线**',
    '而非关门曲线 ⇒ 关门动画不正确。',
    '',
    '**修法**：',
    '',
    '1. **优先**用 YMTR 的权威 `TrainClient.isDoorOpening()`。',
    '   它是 **TrainClient（车辆）级**、每帧一次算出的',
    '   （字节码实证：`doorOpening = doorValue > oldDoorValue`，紧接着才调 `renderCar`），',
    '   **与上游 `getAdjustedDoorMultiplier(...) > 0` 的车辆级语义一致**。',
    '   （已核实 `isDoorOpening()` 在 fabric+forge 的 6 个目标 jar 上全部存在。）；',
    '2. **回退路径**用帧标记保证 `previousDoorValue` **一帧只更新一次**，',
    '   同帧多车厢共用同一 `opening`。',
    '',
    '**回归测试**（`ParserSelfTest.testDoorOpeningDirection()`，7 项）——',
    '其中一项**直接复现了旧 bug**（「第 2 辆车厢被误判为开门中」），',
    '证明这不是假想问题。',
    '',
    '### 2. 并发安全：车辆表改 `ConcurrentHashMap`',
    '',
    '`VehiclePackLoader.VEHICLES` 原来是 `LinkedHashMap`：',
    '',
    '- **写**：资源重载线程（`clear()` + `put()`）；',
    '- **读**：渲染线程（`getVehicle(id)` **每帧每车厢**调一次）。',
    '',
    '`HashMap` / `LinkedHashMap` **不是线程安全**的：`put` 触发 `resize` 时，',
    '读线程可能看到**撕裂的链表结构** ⇒ 可能返回错误值，甚至在损坏的桶里死循环。',
    '`/reload` 与「正好有列车在渲染」重叠时即可触发。',
    '',
    '⇒ 改 `ConcurrentHashMap`。插入顺序不再保证，但两个消费方（车型注册 / 编组 UI）',
    '都只遍历、不依赖顺序 ⇒ **无行为变化**。',
    '',
    '（顺带核实：仓库其余 6 处静态 Map 本来就是 `ConcurrentHashMap`，',
    '这一处是**唯一的例外**。）',
    '',
    '### 3. 含 0.36.7 的内容',
    '',
    '- 门动画曲线类型 + 插值器 key 改**加载期预解析**',
    '  （ERIDU 42 个门部件 ⇒ 60 FPS 下原本每秒 5040 次枚举解析 + 5040 次字符串分配）；',
    '- 闭合上游 `OptimizedModel.MaterialGroup.addCube` 开放问题：',
    '  **它并不抽取 cube 几何**，只是把同一个 `ModelPart` 挂到',
    '  `CapturingVertexConsumer` 上照常渲染 ⇒ **渲染语义与 mtr-next 等价**。',
    '',
    '### 验证',
    '',
    '- `parserSelfTest` PASS=**270** FAIL=0（0.36.7 为 263，+7）；',
    '- `bbModelTreeTest` PASS=46 FAIL=0；',
    '- 构建 0 错误。',
    '',
    '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
    '',
    'Fixes: door opening direction was constant-true on multi-car trains; vehicle registry map was not thread-safe.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.36.8-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.36.8-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
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
    'User-Agent': 'szkele1145/mtr-next/0.36.8 (contact: szkele1145)',
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
