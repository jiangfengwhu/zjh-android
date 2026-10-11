const fs = require('node:fs/promises');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { runtimeVersion } = require('./runtime-version.cjs');

async function main() {
  const root = path.resolve(__dirname, '..');
  const out = path.join(root, 'dist/runtime');
  await fs.mkdir(out, { recursive: true });
  const apk = JSON.parse(await fs.readFile(path.join(root, 'config/apk-version.json'), 'utf8'));
  const lock = JSON.parse(await fs.readFile(path.join(root, "package-lock.json"), "utf8"));
  const publicModules = JSON.parse(await fs.readFile(path.join(root, "config/application-platform.json"), "utf8"));
  const shared = Object.fromEntries(publicModules.shared.map(name => [name, lock.packages[`node_modules/${(name.startsWith("@") ? name.split("/").slice(0, 2).join("/") : name.split("/")[0])}`].version]));
  const applications = { engineAbi: `rn-${shared["react-native"]}-hermes-${lock.packages["node_modules/hermes-compiler"].version}`, sdk: publicModules.sdk, shared };
  const previousResponse = await fetch(`${process.env.BUNDLE_EDGE_BASE || "https://zijinhua.297782.xyz"}/v1/runtime`, { signal: AbortSignal.timeout(15000) });
  if (!previousResponse.ok && previousResponse.status !== 404) throw new Error("无法核对原生升级基线");
  const previous = previousResponse.ok ? await previousResponse.json() : undefined;
  if (previous?.applications?.engineAbi !== undefined && previous.applications.engineAbi !== applications.engineAbi && previous.applications.sdk.split(".")[0] === applications.sdk.split(".")[0]) throw new Error("RN/Hermes ABI 已改变，必须升级 SDK 大版本");
  const contract = { format: 1, applications, apk, runtimeVersion: runtimeVersion(root), dynamicRepository: 'jiangfengwhu/zjh-dynamic' };
  const file = path.join(out, 'runtime.json');
  await fs.writeFile(file, JSON.stringify(contract));
  const run = args => execFileSync(process.platform === 'win32' ? 'npx.cmd' : 'npx', ['wrangler', ...args], { cwd: root, stdio: 'inherit', windowsHide: true, shell: process.platform === 'win32' });
  run(['kv', 'key', 'put', 'v1/runtime', '--path', file, '--namespace-id', 'da19ca26504047a596afd448b36a9d6e', '--remote']);
  console.log(`Runtime contract published: APK ${apk.versionCode}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
