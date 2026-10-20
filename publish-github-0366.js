const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.36.6-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.36.6-1.20.1-fabric.jar';
const tag = 'v0.36.6';

const notes = [
  '## MTR Next 0.36.6 - 上游二进制实证：Box 归一化与 Vector3d 旋转语义核实',
  '',
  '本版不改渲染行为，**更正一处此前写错的文档结论**，并把结论固化为可回归测试。',
  '',
  '### 1. 更正：「上游 testDoors 永不报重叠」是错的',
  '',
  '0.36.5 曾断言：上游 `BlockbenchElement` 构造 `Box` 时对 X/Y/Z 全部取负，导致三轴倒序；MC 的 `Box.intersects` 要求每轴 `min <= max`，所以上游 `testDoors` 对 bbmodel 车体**永远不会报重叠**。',
  '',
  '**该断言是错的。** 本版取到上游发行 jar 并反汇编核实：',
  '',
  '- 上游 `org.mtr.mapping.holder.Box(double×6)` 的字节码就是 `new class_238(x1,y1,z1,x2,y2,z2)`，直接把 6 个参数交给 MC 的 `AABB`（`class_238`），**自己不排序**；',
  '- 而 MC 1.20.1 的 `AABB` 六参构造器**内部自行归一化**：`minX=Math.min(x1,x2)`、`maxX=Math.max(x1,x2)`（X/Y/Z 三轴同）；',
  '- ⇒ 上游拿到的 `Box` **始终有序**，**`testDoors` 能正常检测门重叠**。',
  '',
  'mtr-next 的 `Element.upstreamBox()` 同样做归一化，**与上游行为等效**（不是"有意偏离"）。渲染行为**无变化**。',
  '',
  '### 2. 闭合：上游 `Vector3d` 的旋转数学',
  '',
  '此前「`rotateX/Y/Z` 的具体实现」是未闭合的开放问题。本版核实到：上游 `Vector3d.rotateX/Y/Z` 分别转发到 MC `Vec3` 的 `method_1037` / `method_1024` / `method_31033`（即 `xRot` / `yRot` / `zRot`），都是**绕原点**的标准右手旋转：',
  '',
  '| 方法 | 公式 |',
  '|---|---|',
  '| `xRot(θ)` | `y\' = y·cosθ − z·sinθ`；`z\' = y·sinθ + z·cosθ`；`x\' = x` |',
  '| `yRot(θ)` | `x\' = x·cosθ + z·sinθ`；`z\' = z·cosθ − x·sinθ`；`y\' = y` |',
  '| `zRot(θ)` | `x\' = x·cosθ − y·sinθ`；`y\' = x·sinθ + y·cosθ`；`z\' = z` |',
  '',
  '上游组合顺序为 `rotateX → rotateY → rotateZ`（先 X 后 Y 后 Z），角度取 `rx=rad(-r0)`、`ry=rad(-r1)`、`rz=rad(+r2)`。',
  '',
  '⇒ mtr-next `Element.rotate()` **与上游逐项一致**（此前是按调用顺序推测，现已实证）。',
  '',
  '### 3. 新增回归测试',
  '',
  '- `ParserSelfTest.testUpstreamBoxRotation()`：绕 X / Y / Z 各 90° 的盒坐标 + 倒序输入的归一化等效性，共 14 项断言；',
  '- 把上游 class 文件存进 `docs/_upstream/binary-evidence/`，附来源与复现命令。',
  '',
  '### 验证',
  '',
  '- `parserSelfTest` PASS=**273** FAIL=0（0.36.5 为 245）；',
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
  // 1) 确保 tag 存在
  const sha = process.argv[2];
  if (!sha) { console.error('用法: node publish-github-0366.js <commit-sha>'); process.exit(1); }
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
      name: 'MTR Next 0.36.6 - 上游二进制实证：Box 归一化与 Vector3d 旋转语义核实',
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
