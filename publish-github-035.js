const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.2-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.35.2-1.20.1-fabric.jar';
const tag = 'v0.35.2';

const notes = [
  '## MTR Next 0.35.2 - 门重叠离线检测 + flipTextureV 定论',
  '',
  '### 新增',
  '',
  '- `BbModelLoader.detectDoorOverlaps(boxes)`：上游 `DynamicVehicleModel.testDoors` 的纯数据面移植——模拟门从 t=0 到 t=1 走 5 个时间片，若两扇门"初始不相交、中途相交"，判定门 X/Z 倍率配错（车包作者配置错误，上游打 WARN）。移植为可离线回归的静态方法。',
  '',
  '**本次实测得出的两条算法事实**（已写进测试用例）：',
  '',
  '1. **同向同量滑动的两扇门永远不会相撞**——相对距离恒定。故能触发告警的错误配置是**倍率不等**（快门追上慢门），而不是"同向"。',
  '2. ERIDU 的 +11/−11 反向对滑配置，两门**初始包围盒本就相交**（实测 men2 z 跨 [-56.8, 63.5]、men3 z 跨 [-59.5, 40.0] 像素）→ 上游"初始相交则跳过"分支会直接放过，不误报。',
  '',
  '> 由此可知上游 `testDoors` 的实际覆盖能力有限：只能发现"倍率明显配错"的极端情形，对 ERIDU 这类正确配置无诊断价值。移植保留其语义，不夸大其作用。',
  '',
  '### 定论（开放问题关闭）',
  '',
  '- **`flipTextureV` 语义已定论——不实装是正确的**。上游 `VehicleModel.createModel` 只把该字段传给 `.obj`/`.mqo`/`.mqoz` 路径（`ModelResourceLoader.loadModel`）；bbmodel 路径的 `DynamicVehicleModel` 构造器完全不接收它。即上游对 bbmodel 同样不消费此字段（Blockbench 导出的 UV 原点本就与 MC 一致）。mtr-next 不实装 = 与上游行为一致，不是遗漏。',
  '',
  '### 测试',
  '',
  '- ParserSelfTest 155 → 163 全绿（新增门重叠检测 8 项）。',
  '',
  '### 0.35.1 回顾（上一版）',
  '',
  '- 4 个 mixin 目标类由 `targets = "字符串"` 改为 `value = 类字面量`——Mixin AP 对 targets 字符串目标不解析目标类，注入点写错也编译通过、运行时静默不命中；改为类字面量后 AP 真正校验注入点，把静默失效提前为编译错误。',
  '',
  '### 0.35.0 回顾',
  '',
  '- bbmodel 坐标 /16 尺度换算（上游 `BlockbenchElement`："16 Blockbench units = 1 Minecraft block"）——此前车体比实际大 16 倍。',
  '- UV 归一化除数改用 bbmodel 自身 `resolution`（实测 ERIDU 为 512/128/24/64）——此前硬编码 /24 让车体贴图 UV 严重越界错位。',
  '- 门动画接入渲染（上游 `DoorAnimationType` 12 值移植 + X/Z 双轴位移）。',
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
      sha: '4c79f5dc622000447cdefed8af5a500b5a95d144'
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
      name: 'MTR Next 0.35.2 - 门重叠离线检测 + flipTextureV 定论',
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
