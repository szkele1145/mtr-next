const fs = require('fs');
const https = require('https');
const crypto = require('crypto');
const mr = fs.readFileSync('F:\\vibe coding工程\\sfx\\.mrtoken', 'utf-8').trim();
const jar = 'F:\\vibe coding工程\\mtr-next\\build\\libs\\mtr-next-0.34.2-1.20.1-fabric.jar';
const fileBuf = fs.readFileSync(jar);
const sha512 = 'sha512:' + crypto.createHash('sha512').update(fileBuf).digest('hex');
const data = {
  name: 'MTR Next 0.34.2 - 轨道预览修复',
  version_number: '0.34.2',
  game_versions: ['1.20.1'],
  loaders: ['fabric'],
  featured: false,
  dependencies: [],
  changelog: [
    '## 0.34.2 - Rail placement preview fix (mixin descriptor)',
    '',
    '**Fixed a real bug that existed since 0.33**: the rail placement preview never actually rendered on screen, because its injection never fired:',
    '',
    '- The `@Inject` target descriptor used vanilla-MTR\'s signature (`class_1297` as the first parameter) while this project hooks YMTR, whose static `RenderTrains.render` takes its own `EntitySeat` type - the descriptor never matched, so the preview was dead code since it was introduced.',
    '- The member-level `remap` flag was also wrong for this project\'s Mojang-mappings compile classpath, which made the annotation processor silently skip proper remapping (empty refmap).',
    '',
    '**Both sides are now bytecode-proven**:',
    '',
    '- Compile-time: annotation-processor validation passes (the "Cannot find target method" warning is gone).',
    '- Runtime: the refmap entry resolves to the exact production descriptor `(Lmtr/entity/EntitySeat;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;)V`, verified via `javap` against the production YMTR jar.',
    '',
    'No gameplay changes; parser self-test still passes 70/70.'
  ].join('\n'),
  release_channel: 'release',
  project_id: 'jUmZS1kp',
  file_parts: ['mtr-next-0.34.2-1.20.1-fabric.jar']
};
const boundary = '----DSHBoundary' + Date.now();
const chunks = [];
function str(s) { chunks.push(Buffer.from(s, 'utf8')); }
function raw(buf) { chunks.push(buf); }
str('--' + boundary + '\r\nContent-Disposition: form-data; name="data"\r\n\r\n' + JSON.stringify(data) + '\r\n');
str('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="mtr-next-0.34.2-1.20.1-fabric.jar"\r\nContent-Type: application/java-archive\r\n\r\n');
raw(fileBuf);
str('\r\n--' + boundary + '--\r\n');
const body = Buffer.concat(chunks);
console.log('Body length:', body.length);
console.log('SHA-512:', sha512);
const options = {
  hostname: 'api.modrinth.com', port: 443, path: '/v2/version', method: 'POST',
  headers: {
    'Authorization': mr,
    'User-Agent': 'szkele1145/mtr-next/0.34.2 (contact: szkele1145)',
    'Content-Type': 'multipart/form-data; boundary=' + boundary,
    'Content-Length': body.length
  }
};
const req = https.request(options, res => {
  let raw = '';
  res.on('data', c => raw += c);
  res.on('end', () => { console.log('HTTP_CODE:' + res.statusCode); console.log(raw); });
});
req.on('error', e => console.error('ERROR:', e.message));
req.write(body);
req.end();
