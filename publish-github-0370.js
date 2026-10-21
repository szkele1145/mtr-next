const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.37.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.37.0-1.20.1-fabric.jar';
const tag = 'v0.37.0';

const notes = [
  '## MTR Next 0.37.0 - 渲染热路径：位定义查表提到循环外（顺序不变）',
  '',
  '本版**不改渲染行为**（渲染顺序逐字不变），继续清理渲染热路径上的重复查表。',
  '',
  '### 1. 位定义查表从「双层循环内」提到「循环外」',
  '',
  '渲染一列车时的循环结构：',
  '',
  '```',
  'for info in properties.parts[]:                   <- 部件记录',
  '  for name in info.names:                         <- 组名',
  '    for defName in info.positionDefinitionNames:  <- 位定义名',
  '      group = positions.get(defName)               <- 查表',
  '```',
  '',
  '`positions.get(defName)` 的结果**与 `name` 无关**（同一个 `info` 的所有组名共用同一批位定义），却在 `name` 循环里被**重复查了 `names.length` 次**。',
  '',
  '=> 查表次数：`names x defNames` => **`defNames`**。',
  '',
  '改动：在 `name` 循环**外**一次性把位定义名解析成数组，循环内直接取。',
  '',
  '★ **渲染顺序完全不变**：仍是 name-major / defName-minor，绘制次序、深度写入、混合顺序与改前逐字一致。',
  '',
  '（ERIDU-DG1000A 实测：39 条部件记录，每条若干组名 x 若干位定义名，重复查表是每秒数千次量级。）',
  '',
  '### 2. 同步改了编组预览渲染器',
  '',
  '`FormationPreviewRenderer` 有**同一套双层循环结构**，一并处理，避免主渲染器与预览器行为分叉。',
  '',
  '### 验证',
  '',
  '- `parserSelfTest` PASS=**278** FAIL=0；',
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
      name: 'MTR Next 0.37.0 - 渲染热路径优化 + 上游 addCube 字节码实证',
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
