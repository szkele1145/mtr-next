const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.1-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.35.1-1.20.1-fabric.jar';
const tag = 'v0.35.1';

const notes = [
  '## MTR Next 0.35.1 - mixin 注入点编译期校验加固',
  '',
  '### 修复（静默失效类）',
  '',
  '- 4 个 mixin（`CustomResourcesReloadMixin` / `ItemBlockClickingBaseMixin` / `RenderTrainsPreviewMixin` / `SidingScreenMixin`）的目标类由 `targets = "字符串"` 改为 `value = 类字面量`。',
  '',
  '两者运行时等价，但 Mixin AP 对 `targets` 字符串目标**不解析目标类**——注入方法名/描述符写错也照样编译通过，运行时静默不命中（0.34.2 修的轨道预览正是这类"永不命中"）。改为类字面量后 AP 真正解析 YMTR 目标类并校验注入点，把这类缺陷从运行时静默失效提前为编译错误。',
  '',
  '目标类经 javap 实证均为 public class（YMTR 3.6.4.2 发布 jar）：`mtr.client.CustomResources`、`mtr.item.ItemBlockClickingBase`、`mtr.render.RenderTrains`、`mtr.screen.SidingScreen`。`remap = false` 保留——`mtr.*` 是 YMTR 自有包名，不被 intermediary 重映射。',
  '',
  '### 验证',
  '',
  '- 编译通过即证明 4 个注入点的签名全部正确（此前无任何编译期保障）。',
  '- 构建日志的 4 条 "Mixin target ... is public and should be specified in value" 警告全部消除。',
  '- `mtr-next.refmap.json` 内容不变（RailPreview 的 Mojmap→intermediary 桥接完好）。',
  '- ParserSelfTest 155/155 全绿。',
  '',
  '### 0.35.0 回顾（上一版）',
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
      sha: '__HEAD_SHA__'
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
      name: 'MTR Next 0.35.1 - mixin 注入点编译期校验加固',
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
