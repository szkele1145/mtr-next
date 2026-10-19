const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.35.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.35.0-1.20.1-fabric.jar';
const tag = 'v0.35.0';

const notes = [
  '## MTR Next 0.35.0 - 上游实证门动画 + 尺度/UV 修复',
  '',
  '本版依据 MTR 4.x 上游 MIT 源码（`Minecraft-Transit-Railway/Minecraft-Transit-Railway`，master 分支）逐行比对后实施。',
  '',
  '### 修复（两项均影响所有 4.0 格式车包的显示）',
  '',
  '- **bbmodel 坐标未做 /16 尺度换算**：上游 `BlockbenchElement.setModelPart` 明确 "Normalize dimensions (16 Blockbench units = 1 Minecraft block)"，此前车体渲染比实际大 16 倍。',
  '- **UV 归一化硬编码 /24**：bbmodel 的 UV 除数应为模型自身 `resolution`（实测 ERIDU 四个模型为 512/128/24/64）。旧代码让车体（resolution 512，UV 最大 u=384 / v=448）贴图严重越界错位。',
  '',
  '### 新增',
  '',
  '- `DoorAnimation4x`：上游 `DoorAnimationType` 12 值逐分支移植（含 3.x 没有的 `PLUG_SLOW_2` / `PLUG_SLOW_3`），签名带 `flipped`，`copySign` 语义与上游一致。',
  '- 门动画接入渲染：`drawBoxFaces` 扩展为 X/Z 双轴位移，`renderCar` 按 `parts[]` 的门倍率 + 上游曲线驱动门盒（塞拉门 X 外摆 + Z 滑动，滑门 Z 滑动）；非门盒行为不变。编组预览同步（预览门取全开态）。',
  '- 门归属判定与上游 `isDoor()` 对齐：`doorXMultiplier != 0 || doorZMultiplier != 0`，按 outliner 组名匹配 `parts[].names`。',
  '',
  '### 文档',
  '',
  '- `docs/door-animation-audit.md` §4 四个开放问题全部关闭：倍率单位=像素（渲染时 /16）、`isDoor` 判定、`flipped` 来源（`positionDefinitions`）、X 轴曲线存在性，均附上游文件行号。',
  '- 新增 `docs/_upstream/`（上游 MIT 源码快照，仅供比对，不参与构建）。',
  '',
  '### 测试',
  '',
  '- ParserSelfTest 98 → 155 全绿（门曲线 40+ 项、尺度/UV 13 项、门标注 17 项，期望值由上游分支手算锁定）。',
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
      sha: '3ee41d9cf29ba3eadd8ef1c3dcae34ad7c20c96a'
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
      name: 'MTR Next 0.35.0 - 上游实证门动画 + 尺度/UV 修复',
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
