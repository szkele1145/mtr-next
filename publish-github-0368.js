const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.8-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.36.8-1.20.1-fabric.jar';
const tag = 'v0.36.8';

const notes = [
  '## MTR Next 0.36.8 - 修：多车厢列车的门运动方向串味 + 车辆表并发安全',
  '',
  '本版修掉**两个真实缺陷**（都有回归测试证明），并含 0.36.7 的预解析优化。',
  '',
  '### 1. ★真 bug：多车厢列车的 `opening` 恒为「开门中」',
  '',
  '**根因**（粒度不匹配）：',
  '',
  '| 项 | 粒度 |',
  '|---|---|',
  '| `renderCar(int carNumber, …)` | **每车厢**调一次 |',
  '| `previousDoorValue`（旧实现） | **每列车**一份 |',
  '',
  '⇒ 第 2 辆车厢读到的已是第 1 辆车厢刚写回的**同一个**门开度 ⇒ 判定 `doorValue >= previousDoorValue` 恒为 `true` ⇒ **`opening` 恒为「开门中」**。',
  '',
  '后果：① 关门时段的 `renderFromClosingDoorTime` / `renderUntilClosingDoorTime` 整组失效；② `getDoorAnimationZ(…, opening)` 走**开门曲线**而非关门曲线。',
  '',
  '**修法**：',
  '',
  '1. **优先**用 YMTR 的权威 `TrainClient.isDoorOpening()` —— **TrainClient（车辆）级**、每帧一次算出的（字节码实证：`doorOpening = doorValue > oldDoorValue`，紧接着才调 `renderCar`），**与上游 `getAdjustedDoorMultiplier(...) > 0` 的车辆级语义一致**（已核实 6 个目标 jar 全部有该方法）；',
  '2. **回退路径**用帧标记保证 `previousDoorValue` **一帧只更新一次**，同帧多车厢共用同一 `opening`。',
  '',
  '**回归测试** `ParserSelfTest.testDoorOpeningDirection()`（7 项），其中一项**直接复现了旧 bug**（「第 2 辆车厢被误判为开门中」），证明这不是假想问题。',
  '',
  '### 2. 并发安全：车辆表改 `ConcurrentHashMap`',
  '',
  '`VehiclePackLoader.VEHICLES` 原来是 `LinkedHashMap`：**写**在资源重载线程（`clear()` + `put()`），**读**在渲染线程（`getVehicle(id)` 每帧每车厢一次）。`HashMap` / `LinkedHashMap` **不是线程安全**的 —— `put` 触发 `resize` 时读线程可能看到**撕裂的链表结构** ⇒ 可能返回错误值甚至死循环。`/reload` 与「正好有列车在渲染」重叠时即可触发。',
  '',
  '⇒ 改 `ConcurrentHashMap`；两个消费方（车型注册 / 编组 UI）都只遍历、不依赖顺序 ⇒ **无行为变化**。（仓库其余 6 处静态 Map 本来就是 `ConcurrentHashMap`，这一处是**唯一的例外**。）',
  '',
  '### 3. 含 0.36.7 的内容',
  '',
  '- 门动画曲线类型 + 插值器 key 改**加载期预解析**（ERIDU 42 个门部件 ⇒ 60 FPS 下原本每秒 5040 次枚举解析 + 5040 次字符串分配）；',
  '- 闭合上游 `OptimizedModel.MaterialGroup.addCube` 开放问题：**它并不抽取 cube 几何**，只是把同一个 `ModelPart` 挂到 `CapturingVertexConsumer` 上照常渲染 ⇒ **渲染语义与 mtr-next 等价**。',
  '',
  '### 验证',
  '',
  '- `parserSelfTest` PASS=**270** FAIL=0（0.36.7 为 263，+7）；',
  '- `bbModelTreeTest` PASS=46 FAIL=0；',
  '- 构建 0 错误。',
  '',
  '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md`）。',
  '',
  'SHA-512 (`' + crypto.createHash('sha512').update(fileBuf).digest('hex') + '`)'
].join('\n');

function api(method, path, bodyObj) {
  return new Promise((resolve, reject) => {
    const data = bodyObj ? Buffer.from(JSON.stringify(bodyObj), 'utf8') : null;
    const req = https.request({
      hostname: 'api.github.com', port: 443, method, path,
      headers: {
        'Authorization': 'token ' + tok,
        'User-Agent': 'szkele1145',
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'Content-Length': data ? data.length : 0
      }
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        let parsed = null;
        try { parsed = JSON.parse(raw); } catch (e) { parsed = raw; }
        if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
        else reject(new Error('HTTP ' + res.statusCode + ' ' + raw));
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

(async () => {
  const sha = process.argv[2];
  if (!sha) { console.error('用法: node publish-github-0367.js <commit-sha>'); process.exit(1); }
  try {
    await api('GET', '/repos/szkele1145/mtr-next/git/ref/tags/' + tag);
    console.log('TAG_EXISTS');
  } catch (e) {
    console.log('TAG_MISSING -> creating');
    const r = await api('POST', '/repos/szkele1145/mtr-next/git/refs', {
      ref: 'refs/tags/' + tag,
      sha: sha
    });
    console.log('TAG_CREATED ' + r.ref + ' -> ' + r.object.sha);
  }

  let rel;
  try {
    rel = await api('GET', '/repos/szkele1145/mtr-next/releases/tags/' + tag);
    console.log('RELEASE_EXISTS id=' + rel.id);
  } catch (e) {
    rel = await api('POST', '/repos/szkele1145/mtr-next/releases', {
      tag_name: tag,
      name: 'MTR Next 0.36.8 - 渲染热路径优化 + 上游 addCube 字节码实证',
      body: notes,
      draft: false,
      prerelease: false
    });
    console.log('RELEASE_CREATED id=' + rel.id + ' url=' + rel.html_url);
  }

  // 裸 body 上传（multipart 会把边界字节写进资产，导致 size/sha512 不符）
  const uploadUrl = rel.upload_url.replace(/\{.*\}$/, '') + '?name=' + encodeURIComponent(fileName);
  const body = fileBuf;

  await new Promise((resolve, reject) => {
    const u = new URL(uploadUrl);
    const req = https.request({
      hostname: u.hostname, port: 443, method: 'POST', path: u.pathname + u.search,
      headers: {
        'Authorization': 'token ' + tok,
        'User-Agent': 'szkele1145',
        'Content-Type': 'application/octet-stream',
        'Content-Length': body.length
      }
    }, res => {
      let raw = '';
      res.on('data', c => raw += c);
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          const j = JSON.parse(raw);
          console.log('UPLOAD_OK id=' + j.id + ' name=' + j.name + ' size=' + j.size + ' state=' + j.state);
          // 复核：远端资产大小 vs 本地
          const local = fileBuf.length;
          console.log('verify: local=' + local + ' remote=' + j.size + ' ' + (local === j.size ? 'MATCH' : 'MISMATCH'));
          resolve();
        } else reject(new Error('HTTP ' + res.statusCode + ' ' + raw));
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
