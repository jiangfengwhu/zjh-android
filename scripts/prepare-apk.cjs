const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { runtimeVersion } = require('./runtime-version.cjs');

async function main() {
  const root = path.resolve(__dirname, '..');
  const edge = process.env.BUNDLE_EDGE_BASE || 'https://zijinhua.297782.xyz';
  const cdn = process.env.BUNDLE_CDN_BASE || 'https://zjh-cdn.297782.xyz';
  const response = await fetch(`${edge}/v1/release`);
  if (!response.ok) throw new Error(`发布清单读取失败：${response.status}`);
  const release = await response.json();
  if (release.format !== 3 || release.runtimeVersion !== runtimeVersion(root)) {
    throw new Error('线上 RN 包与当前 APK 版本不一致，请先发布对应运行时的 RN 包');
  }
  const assets = path.join(root, 'mobile/android/app/src/main/assets');
  await fs.mkdir(assets, { recursive: true });
  for (const [kind, filename] of [['vendor', 'index.android.bundle'], ['app', 'app.bundle']]) {
    const item = release[kind];
    if (!item || !/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error(`无效的 ${kind} 清单`);
    const blob = await fetch(`${cdn}/v1/${kind}/${item.sha256}`);
    if (!blob.ok) throw new Error(`${kind} 下载失败：${blob.status}`);
    const bytes = Buffer.from(await blob.arrayBuffer());
    if (bytes.length !== item.bytes || createHash('sha256').update(bytes).digest('hex') !== item.sha256) {
      throw new Error(`${kind} 校验失败`);
    }
    await fs.writeFile(path.join(assets, `${filename}.tmp`), bytes);
  }
  for (const filename of ['index.android.bundle', 'app.bundle']) {
    await fs.rename(path.join(assets, `${filename}.tmp`), path.join(assets, filename));
  }
  await fs.writeFile(path.join(assets, 'runtime-version.txt'), release.runtimeVersion);
  console.log(`APK seed ready: release=${release.id}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
