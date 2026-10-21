const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.9-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.36.9-1.20.1-fabric.jar';
const tag = 'v0.36.9';

const notes = [
  '## MTR Next 0.36.9 - 渲染热路径：缓存键/贴图预计算 + 门位移零分配',
  '',
  '本版**不改渲染行为**（有回归测试证明等价），继续清理渲染热路径上的每帧分配。',
  '',
  '### 1. 模型树缓存键改「加载期预计算」',
  '',
  '原先渲染端每帧、每模型层做一次字符串拼接（`modelResource + "|" + propertiesResource`）来查缓存 ⇒ 每帧产生一个临时 `String`（含 `StringBuilder` 内部 char[] 拷贝）。一列车多层、每层每帧 ⇒ 每秒数千次无谓分配。',
  '',
  '现在键在 `ModelLayer` 构造时算好（`ModelLayer.treeCacheKey`），渲染期零分配。',
  '',
  '★ **正确性关键**：预计算键必须与旧算法**逐字相同**，否则缓存会命中**别的 properties 组合的模型树** —— 上游 `DynamicVehicleModel` 把 properties 的 `modelYOffset` 传进每个元素，单键缓存会让不同车包**串偏移**。新增回归测试 `testTreeCacheKeyPrecompute()`（**8 项**）逐字比对了 6 组输入（含 null / 空串组合）+ 稳定性 + 不同 properties 必须异键。',
  '',
  '### 2. 贴图 `ResourceLocation` 改「加载期预解析」',
  '',
  '原先每帧每模型层 `new ResourceLocation(...)` 一次；现在在 `ModelLayer` 构造时解析好（畸形资源串 → null → 回退白色混凝土）。缺省白色混凝土贴图也改为**常量**。',
  '',
  '### 3. 门位移改「零分配」',
  '',
  '`doorOffsetPixels` 原先每次调用 `new float[2]` 返回结果，每帧每渲染实例一次 ⇒ 每秒数千次短命数组分配（GC 压力）。改为主动写出 X / Z 两个分量（调用方 `drawInstance` 立即读取，期间不跨调用 ⇒ 无重入问题）。',
  '',
  '### 4. 说明：有一处查表**故意保留**',
  '',
  '`renderCar` 里每帧每车厢的 `VehiclePackLoader.getVehicle(trainId)` **不缓存** —— 资源重载时 `VEHICLES.clear()` + `put()` 会**替换 `VehicleResource` 对象**，渲染器必须每帧取新对象，否则会读到陈旧车包数据。',
  '',
  '### 验证',
  '',
  '- `parserSelfTest` PASS=**278** FAIL=0（0.36.8 为 270，+8）；',
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
      name: 'MTR Next 0.36.9 - 渲染热路径优化 + 上游 addCube 字节码实证',
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
