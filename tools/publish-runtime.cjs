const fs = require('node:fs/promises');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { runtimeVersion } = require('./runtime-version.cjs');

async function javaFiles(directory) {
  const files = [];
  for (const item of await fs.readdir(directory, { withFileTypes: true })) {
    const file = path.join(directory, item.name);
    if (item.isDirectory()) files.push(...await javaFiles(file));
    else if (item.name.endsWith('.java')) files.push(file);
  }
  return files;
}

async function main() {
  const root = path.resolve(__dirname, '..');
  const out = path.join(root, 'dist/runtime');
  const classes = path.join(out, 'classes');
  await fs.mkdir(classes, { recursive: true });
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('缺少 ANDROID_HOME 或 ANDROID_SDK_ROOT');
  const platforms = (await fs.readdir(path.join(sdk, 'platforms'))).filter(item => /^android-\d+$/.test(item)).sort((a, b) => Number(b.slice(8)) - Number(a.slice(8)));
  if (!platforms.length) throw new Error('缺少 Android SDK platform');
  const jar = path.join(sdk, 'platforms', platforms[0], 'android.jar');
  const javaBin = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
  execFileSync(javaBin('javac'), ['--release', '17', '-classpath', jar, '-d', classes, ...await javaFiles(path.join(root, 'sdk/bridge/java'))], { stdio: 'inherit', windowsHide: true });
  const sdkFile = path.join(out, 'bridge-sdk.jar');
  execFileSync(javaBin('jar'), ['cf', sdkFile, '-C', classes, '.'], { stdio: 'inherit', windowsHide: true });
  const bytes = await fs.readFile(sdkFile);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const apk = JSON.parse(await fs.readFile(path.join(root, 'config/apk-version.json'), 'utf8'));
  const lock = JSON.parse(await fs.readFile(path.join(root, "package-lock.json"), "utf8"));
  const publicModules = JSON.parse(await fs.readFile(path.join(root, "config/application-platform.json"), "utf8"));
  const shared = Object.fromEntries(publicModules.shared.map(name => [name, lock.packages[`node_modules/${name.split("/")[0]}`].version]));
  const applications = { engineAbi: `rn-${shared["react-native"]}-hermes-${lock.packages["node_modules/hermes-compiler"].version}`, sdk: publicModules.sdk, shared };
  const contract = { format: 1, applications, apk, runtimeVersion: runtimeVersion(root), dynamicRepository: 'jiangfengwhu/zjh-dynamic', bridgeSdk: { sha256, bytes: bytes.length } };
  const file = path.join(out, 'runtime.json');
  await fs.writeFile(file, JSON.stringify(contract));
  const run = args => execFileSync(process.platform === 'win32' ? 'npx.cmd' : 'npx', ['wrangler', ...args], { cwd: root, stdio: 'inherit', windowsHide: true, shell: process.platform === 'win32' });
  run(['r2', 'object', 'put', `zijinhua/v1/sdk/${sha256}`, '--file', sdkFile, '--content-type', 'application/java-archive', '--cache-control', 'max-age=31536000', '--remote', '--force']);
  run(['kv', 'key', 'put', 'v1/runtime', '--path', file, '--namespace-id', 'da19ca26504047a596afd448b36a9d6e', '--remote']);
  console.log(`Runtime contract published: APK ${apk.versionCode}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
