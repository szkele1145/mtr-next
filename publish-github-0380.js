const fs = require('fs');
const https = require('https');
const crypto = require('crypto');

const tok = fs.readFileSync('F:\\vibe coding工程\\sfx\\.ghtoken', 'utf-8').trim();
const jarPath = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.38.0-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jarPath);
const fileName = 'mtr-next-0.38.0-1.20.1-fabric.jar';
const tag = 'v0.38.0';

const notes = [
  '## MTR Next 0.38.0 - 渲染计划快照：加载期预解析（渲染序列逐字不变）',
  '',
  '本版**不改渲染行为**，把「部件过滤 + 位定义查表」整体挪到加载期，',
  '消灭渲染热路径上**每帧每 NORMAL 部件一次的 `PositionGroup[]` 数组分配**',
  '（0.37.0 的遗留分配：查表虽已提到循环外，但数组本身还是每帧 new）。',
  '',
  '### 1. 新增 LayerRenderCache（渲染计划快照）',
  '',
  '加载期把 properties.parts[] 过滤成「type=NORMAL 且位定义名非空且至少解析到一个组」',
  '的部件列表，每个部件的位定义组**预解析成数组**（含 null 过滤）。',
  '',
  '缓存键 = `properties + "|" + positionDefinitions`（两者任一变化都换键，',
  '不会半新半旧）；跟随资源重载钩子整体失效。渲染期循环变成纯遍历：**零查表、零分配**。',
  '',
  '★ **渲染顺序完全不变**：过滤规则与旧循环的 continue 逐条对应，',
  '绘制序列（name-major / defName-minor、正位在前翻转位在后）逐字一致。',
  '',
  '### 2. 等价性回归测试',
  '',
  '新增 `testLayerRenderPlanEquivalence`：把 0.37.0 的旧循环**原样照抄**为参照实现，',
  '与新的快照遍历对**同一输入**产出「绘制指令序列」逐条比对。',
  '',
  '覆盖 10 组用例：正常部件 / 非 NORMAL / 无位定义 / 位定义查不到 /',
  'null 与非 null 混合 / positions+positionsFlipped 并存 / 多部件多组名多定义 /',
  '同一组被多条记录引用（ERIDU men2 形态）/ 空 parts / 畸形输入，',
  '另含 null 安全与列表不可变断言。',
  '',
  '### 3. 同步改了编组预览渲染器',
  '',
  '`FormationPreviewRenderer` 同一套循环结构一并处理，避免主渲染器与预览器分叉。',
  '',
  '### 验证',
  '',
  '- `parserSelfTest` PASS=**282** FAIL=0（0.37.0 = 278，+4 为本版新增）；',
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
      name: 'MTR Next 0.38.0 - 渲染计划快照：加载期预解析（渲染序列逐字不变）',
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
