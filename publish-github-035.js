const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.3-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.35.3-1.20.1-fabric.jar';
const tag = 'v0.35.3';

const notes = [
  '## MTR Next 0.35.3 - 镜像展开（positionsFlipped）+ ERIDU 左半侧齐活',
  '',
  '### 核心修复：左半侧车门/车窗此前完全缺失',
  '',
  '上游 `ModelPropertiesPart.iteratePositions`（L654-656）把位定义的 `positions` 与 `positionsFlipped` **都渲染**：前者 flipped=false，后者 X 取反 + flipped=true，且渲染时绕 Y 轴转 180°（`ModelPropertiesPart.java:364`）。`addBox`（L649-650）同步镜像 X/Z。',
  '',
  '实测 ERIDU 车包（eridudanguei.bbmodel，970 元素）：`men2`/`men3`/`win`/`door` 等顶层组 **几何只建了 +X 半边**（men2 x∈[19,20]px，win 右 24 盒 / 左 0 盒），另一侧完全靠 flipped 副本补齐。',
  '',
  '**⇒ 0.35.2 及以前：左半侧车门、车窗、门灯、门地板全部不渲染。**',
  '',
  '### 实现',
  '',
  '- 新增 `PositionDefinitions4xLoader`：解析 `definition_*.json` → 位定义名 → `PositionGroup{positions, positionsFlipped}`。',
  '- `Properties4xLoader.PartInfo` 增加 `positionDefinitionNames`。',
  '- `VehicleResource.ModelLayer` 增加 `positionDefinitionsResource` 字段并解析位定义名列表。',
  '- `BbModelLoader`：`BbBox` 增加 `topGroup`/`singleSidedGroup`/`mirrorNeeded`/`doorFlipped`；`buildBoxes` 统计各顶层组 X 侧分布；`annotate` 双条件判定镜像（几何单侧 AND 引用含翻转位的位定义）；`loadAnnotated` 增加第三缓存键。',
  '- `BbModelTrainRenderer`：`drawBoxFaces` 增加 mirror 参数（(x,z)→(−x,−z)，每面 winding 反转）；新增 `drawMirrorPair`（本体 flipped=false、镜像 flipped=true，上游 `copySign(abs,(flipped?-1:1)*mult)` 自动反号）。',
  '- `FormationPreviewRenderer` 同步镜像逻辑。',
  '',
  '### 为什么对称组不镜像',
  '',
  '上游对 `end`/`hand` 等本就左右对称的组也会画 flipped 副本（与对面正本重叠）。mtr-next 用 `singleSidedGroup` 跳过这些组，避免 Z-fighting 与重复面。实测 ERIDU：17/36 个顶层组需镜像。',
  '',
  '### 验证',
  '',
  '- ParserSelfTest 163 → **216 全绿**（新增 33 条：位定义解析、镜像判定、对称组跳过、门位移反号、真实 ERIDU 车包端到端）。',
  '- 真实 ERIDU 车包端到端：8 个门/窗组全部判为需镜像；`end`/`end_n`/`hand`/`hand_n` 判为双侧组不镜像；`men2` dz=+11 / `men3` dz=−11 与实测一致。',
  '- 构建 0 警告。',
  '',
  '### 0.35.2 回顾',
  '',
  '- `BbModelLoader.detectDoorOverlaps`：上游 `testDoors` 移植（模拟 5 个时间片检查两扇门是否中途相撞）。',
  '- `flipTextureV` 定论：不实装是正确的（上游对 bbmodel 不消费此字段）。',
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
  try {
    await api('GET', '/repos/szkele1145/mtr-next/git/ref/tags/' + tag);
    console.log('TAG_EXISTS');
  } catch (e) {
    console.log('TAG_MISSING -> creating');
    const r = await api('POST', '/repos/szkele1145/mtr-next/git/refs', {
      ref: 'refs/tags/' + tag,
      sha: '4a64ccfb7c18bd3442a28a800c33c0db2a05981b'
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
      name: 'MTR Next 0.35.3 - 镜像展开（positionsFlipped）+ ERIDU 左半侧齐活',
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
