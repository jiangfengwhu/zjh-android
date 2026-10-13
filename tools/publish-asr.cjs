const fs = require('node:fs/promises');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { prepareAsr } = require('./prepare-asr.cjs');
async function main() {
  const root = path.resolve(__dirname, '..');
  const config = JSON.parse(await fs.readFile(path.join(root, 'config/asr.json'), 'utf8'));
  await prepareAsr(root, { models: true });
  for (const [name, bytes] of Object.entries(config.files)) {
    const key = `v1/asr/${config.model}/${name}`;
    const url = `https://zjh-cdn.297782.xyz/${key}`;
    let present = false;
    try { const response = await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(15000) }); present = response.ok && Number(response.headers.get('content-length')) === bytes; } catch {}
    if (!present) {
      execFileSync(process.platform === 'win32' ? 'npx.cmd' : 'npx', ['wrangler', 'r2', 'object', 'put', `zijinhua/${key}`, '--file', path.join(root, '.cache/asr/models', config.model, name), '--content-type', 'application/octet-stream', '--cache-control', 'public,max-age=31536000,immutable', '--remote', '--force'], { cwd: root, stdio: 'inherit', shell: process.platform === 'win32', windowsHide: true });
      const response = await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(15000) });
      if (!response.ok || Number(response.headers.get('content-length')) !== bytes) throw new Error(`ASR model upload unavailable: ${name}`);
    }
  }
  console.log(JSON.stringify({ published: true, model: config.model, bytes: Object.values(config.files).reduce((sum, value) => sum + value, 0) }));
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
