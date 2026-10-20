const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.2-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.36.2-1.20.1-fabric.jar';
const tag = 'v0.36.2';

const notes = [
  '## MTR Next 0.36.2 - bbmodel → MC 原生模型树 + 门位移插值层',
  '',
  '### 0.36.0 核心变更：不再手算四边面，改为复刻上游构造过程',
  '',
  '上游 MTR 4 没有「逐面手算」这条路：它把 bbmodel 翻译成 MC 原生的 `ModelPart` 树，再走 `ModelPart.render`。逐项对照上游源码（`BlockbenchOutline` / `GroupTransformations` / `BlockbenchElement` / `ModelPartExtension` / `EntityModelExtension`）后，mtr-next 改为复刻**构造过程**而非**手算结果**。',
  '',
  '旧路径丢掉的 bbmodel 语义（本次全部修复）：',
  '',
  '- 元素 `rotation`（旧：**完全忽略**）→ 现在进入变换链',
  '- 元素 `origin`（旋转中心）→ 现在是链的一节',
  '- outliner 嵌套组层级（旧：**扁平化**）→ 现在是 pivot 逐级差分的链',
  '- `shade` / `mirror_uv` / `inflate` → 按上游语义进 `CubeListBuilder`',
  '- box-UV 布局 → 由 MC 自己的 `addBox` 计算，与上游同一套代码路径',
  '',
  '### 0.36.1：修 props-only 层的 null 资源 NPE',
  '',
  '`Properties4xLoader.load` 直接把 `null` 资源路径塞进 `ConcurrentHashMap.computeIfAbsent`，会抛 NPE。现在先做空值短路，并去掉与 `BbModelTree` 重复的一层缓存。',
  '',
  '### 0.36.2：门 Z 位移接上上游 500 ms 插值层',
  '',
  '上游 `ModelPropertiesPart.java:348` 对 Z 位移不直接用曲线值，而是走 `PersistentVehicleData.getInterpolatedDoorValue` → `DoorMovementInterpolation`（`PersistentVehicleData.java:164-178`），内含 500 ms 线性插值，**用于消除开门↔关门方向翻转处的跳变**。',
  '',
  '- 新增 `DoorMovementInterpolation`（对齐上游 `org.mtr.mod.resource.Interpolation` 的线性混合语义）。',
  '- 插值器按上游 key 规则分桶：`doorZMultiplier + "_" + doorAnimationType + "_" + flipped`。',
  '- 每列车一份（上游挂在 `PersistentVehicleData` 上，本实现挂在渲染器实例上）。',
  '- **X 位移不插值**——上游 L347 走 `getDoorAnimationX`，无插值层。',
  '',
  '实测 ERIDU 车包有 42 个门部件（`STANDARD` 18 + `STANDARD_SLOW` 24）走上游这条路径。',
  '',
  '### 数据层补齐（0.35.4 的成果，随 0.36.0 一并发布）',
  '',
  '上游 `modelPropertiesPart.json` schema 共 25 个字段，mtr-next 此前只读 8 个。本次补上并实现了判定逻辑：',
  '',
  '- `renderFromOpeningDoorTime` / `renderUntilOpeningDoorTime` / `renderFromClosingDoorTime` / `renderUntilClosingDoorTime`（毫秒）',
  '- `flashOnTime` / `flashOffTime`（毫秒）',
  '- `PartInfo.shouldRenderAt(doorValue, opening)`：对齐上游 `ModelPropertiesPart.java:342-345`',
  '- `PartInfo.flashOn(currentTimeMillis)`：对齐上游 `ModelPropertiesPart.java:317`',
  '',
  '并修正一个关键常量：**`DOOR_MOVE_TIME = 3200`**（此前误设为 500），定义在 `Transport-Simulation-Core` 仓库的 `core/data/Vehicle.java:59`。',
  '',
  '### 验证',
  '',
  '- ParserSelfTest **254 全绿**（门时序窗口、闪烁、500 ms 插值层的上游语义回归）。',
  '- BbModelTreeTest **46 全绿**（bbmodel → ModelPart 构造语义）。',
  '- 构建 0 警告。',
  '',
  '> 渲染效果需图形客户端肉眼验证（见 `docs/client-verification-boundary.md` 记录的验证边界）。',
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
  // 1) 确保 tag 存在
  const sha = process.argv[2];
  if (!sha) { console.error('用法: node publish-github-0362.js <commit-sha>'); process.exit(1); }
  try {
    await api('GET', '/repos/szkele1145/mtr-next/git/ref/tags/' + tag);
    console.log('TAG_EXISTS');
  } catch (e) {
    console.log('TAG_MISSING -> creating');
    const r = await api('POST', '/repos/szkele1145/mtr-next/git/refs', {
      ref: 'refs/tags/' + tag,
      sha: sha
    });
    console.log('TAG_CREATED ' + r.ref);
  }

  // 2) 创建 release
  let rel;
  try {
    rel = await api('GET', '/repos/szkele1145/mtr-next/releases/tags/' + tag);
    console.log('RELEASE_EXISTS id=' + rel.id);
  } catch (e) {
    rel = await api('POST', '/repos/szkele1145/mtr-next/releases', {
      tag_name: tag,
      name: 'MTR Next 0.36.2 - bbmodel → MC 原生模型树 + 门位移插值层',
      body: notes,
      draft: false,
      prerelease: false
    });
    console.log('RELEASE_CREATED id=' + rel.id + ' url=' + rel.html_url);
  }

  // 3) 上传 jar 资产
  // upload_url 形如 .../assets{?name,label} —— name/label 必须走查询串，
  // 放 multipart form-data 里 GitHub 会报 400 "Invalid name for request"。
  // 用裸 body（不加 multipart 包装），否则 multipart 边界字节会被写进资产
  // （实测导致 size 比本地 jar 大 110 字节且 sha512 不符）。
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
          console.log('UPLOAD_OK id=' + j.id + ' name=' + j.name + ' size=' + j.size + ' state=' + j.state + ' url=' + j.browser_download_url);
          resolve();
        } else reject(new Error('HTTP ' + res.statusCode + ' ' + raw));
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
})().catch(e => { console.error('ERROR:', e.message); process.exit(1); });
