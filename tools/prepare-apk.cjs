const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { runtimeVersion } = require('./runtime-version.cjs');

const matches = (bytes, item) => bytes.length === item.bytes && createHash('sha256').update(bytes).digest('hex') === item.sha256;
async function readFile(file) { try { return await fs.readFile(file); } catch (error) { if (error.code === 'ENOENT') return undefined; throw error; } }
async function writeChanged(file, bytes) {
  const previous = await readFile(file);
  if (previous?.equals(bytes)) return;
  const temporary = `${file}.tmp`;
  await fs.writeFile(temporary, bytes);
  await fs.rename(temporary, file);
}
async function request(url) {
  return fetch(url, { signal: AbortSignal.timeout(60000) });
}
async function packageBytes(root, cdn, kind, item, destination) {
  if (!/^[a-f0-9]{64}$/.test(item?.sha256) || !Number.isSafeInteger(item.bytes) || item.bytes < 1) throw new Error('包体身份无效');
  const cache = path.join(root, '.cache/apk', item.sha256);
  const local = kind === 'vendor' ? path.join(root, '../zjh-dynamic/dist/bundles/vendor.bundle')
    : destination.endsWith('app.bundle') ? path.join(root, '../zjh-dynamic/dist/bundles/app.bundle')
    : path.join(root, '../zjh-plugin/dist/applications/jolly/application.bundle');
  for (const file of [destination, cache, local]) {
    const bytes = await readFile(file);
    if (bytes && matches(bytes, item)) { console.log(`APK cache hit: ${kind}/${item.sha256.slice(0, 12)}`); return bytes; }
  }
  const response = await request(`${cdn}/v1/${kind}/${item.sha256}`);
  if (!response.ok) throw new Error(`包体下载失败：${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!matches(bytes, item)) throw new Error('包体校验失败');
  await fs.mkdir(path.dirname(cache), { recursive: true });
  await writeChanged(cache, bytes);
  return bytes;
}

async function main() {
  const root = path.resolve(__dirname, '..');
  const edge = process.env.BUNDLE_EDGE_BASE || 'https://zijinhua.297782.xyz';
  const cdn = process.env.BUNDLE_CDN_BASE || 'https://zjh-cdn.297782.xyz';
  const response = await request(`${edge}/v1/release`);
  if (!response.ok) throw new Error(`发布清单读取失败：${response.status}`);
  const release = await response.json();
  if (release.format !== 3 || release.runtimeVersion !== runtimeVersion(root)) {
    throw new Error('线上 RN 包与当前 APK 版本不一致，请先发布对应运行时的 RN 包');
  }
  const seedResponse = await request(`${edge}/v1/applications/jolly/public`);
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
  const prepared = [];
  for (const [kind, filename] of [['vendor', 'index.android.bundle'], ['app', 'app.bundle']]) {
    const item = release[kind];
    if (!item || !/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error(`无效的 ${kind} 清单`);
    const destination = path.join(assets, filename);
    const bytes = await packageBytes(root, cdn, kind, item, destination);
    prepared.push({ destination, bytes });
  }
  const applicationDir = path.join(assets, 'applications/jolly');
  await fs.mkdir(applicationDir, { recursive: true });
  for (const item of [application.bundle, ...(application.assets || [])]) {
    if (!/^[a-f0-9]{64}$/.test(item.sha256)) throw new Error('默认应用包体哈希无效');
    const destination = path.join(applicationDir, `${item.sha256}.bundle`);
    const bytes = await packageBytes(root, cdn, 'app', item, destination);
    prepared.push({ destination, bytes });
  }
  for (const { destination, bytes } of prepared) await writeChanged(destination, bytes);
  await writeChanged(path.join(assets, 'runtime-version.txt'), Buffer.from(release.runtimeVersion));
  await writeChanged(path.join(applicationDir, 'manifest.json'), Buffer.from(JSON.stringify(application)));
  console.log(`APK seed ready: release=${release.id}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
