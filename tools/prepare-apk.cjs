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
  const seedResponse = await fetch(`${edge}/v1/applications/jolly/public`);
  if (!seedResponse.ok) throw new Error(`Jolly 默认应用清单读取失败：${seedResponse.status}`);
  const application = await seedResponse.json();
  if (application.appId !== 'jolly' || application.format !== 1) throw new Error('Jolly 默认应用清单无效');
  const lock = JSON.parse(await fs.readFile(path.join(root, 'package-lock.json'), 'utf8'));
  const platform = JSON.parse(await fs.readFile(path.join(root, 'config/application-platform.json'), 'utf8'));
  const shared = Object.fromEntries(platform.shared.map(name => { const pkg = name.startsWith('@') ? name.split('/').slice(0,2).join('/') : name.split('/')[0]; return [name, lock.packages['node_modules/' + pkg].version]; }));
  const local = { engineAbi: 'rn-' + shared['react-native'] + '-hermes-' + lock.packages['node_modules/hermes-compiler'].version, sdk: platform.sdk, shared };
  const host = release.applications;
  if (!host || host.engineAbi !== local.engineAbi || host.sdk !== local.sdk || Object.keys(host.shared).length !== Object.keys(shared).length || Object.entries(shared).some(([name, version]) => host.shared[name] !== version)) throw new Error('宿主包与本次 APK 的应用契约不匹配');
  const required = application.requires;
  const sdkCompatible = (needed, available) => { if (!/^\d+\.\d+\.\d+$/.test(needed) || !/^\d+\.\d+\.\d+$/.test(available)) return false; const a=needed.split('.').map(Number),b=available.split('.').map(Number); return a[0]===b[0] && (b[1]>a[1] || b[1]===a[1] && b[2]>=a[2]); };
  if (!required || required.engineAbi !== local.engineAbi || !sdkCompatible(required.sdk, host.sdk) || !required.shared || Object.entries(required.shared).some(([name, version]) => shared[name] !== version)) throw new Error('Jolly 公开版本与 APK/宿主不兼容');
  const { releaseId, ...content } = application;
  if (createHash('sha256').update(JSON.stringify(content)).digest('hex') !== releaseId || application.source?.dirty !== false || application.modules?.base !== 2000000 || application.modules?.size !== 100000) throw new Error('Jolly 清单身份或模块范围无效');
  const assets = path.join(root, 'android/app/src/main/assets');
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
  const applicationDir = path.join(assets, 'applications/jolly');
  await fs.mkdir(applicationDir, { recursive: true });
  for (const item of [application.bundle, ...(application.assets || [])]) {
    if (!/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error('默认应用包体哈希无效');
    const response = await fetch(`${cdn}/v1/app/${item.sha256}`);
    if (!response.ok) throw new Error(`默认应用下载失败：${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    if (bytes.length !== item.bytes || createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw new Error('默认应用包体校验失败');
    await fs.writeFile(path.join(applicationDir, `${item.sha256}.bundle`), bytes);
  }
  for (const filename of ['index.android.bundle', 'app.bundle']) {
    await fs.rename(path.join(assets, `${filename}.tmp`), path.join(assets, filename));
  }
  await fs.writeFile(path.join(assets, 'runtime-version.txt'), release.runtimeVersion);
  await fs.writeFile(path.join(applicationDir, 'manifest.json'), JSON.stringify(application));
  console.log(`APK seed ready: release=${release.id}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
