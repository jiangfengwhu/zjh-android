const fs = require('node:fs/promises');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

async function buildApk(root, { reuseOnly = false } = {}) {
  const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8', windowsHide: true }).trim();
  const clean = () => !git(['status', '--porcelain']);
  await require('./prepare-asr.cjs').prepareAsr(root);
  execFileSync(process.execPath, [path.join(root, 'tools/prepare-apk.cjs')], { cwd: root, stdio: 'inherit', windowsHide: true });
  const inputs = async () => JSON.stringify({
    tree: git(['ls-tree', '-r', 'HEAD', '--', 'android', 'specs', 'patches', 'config', 'package.json', 'package-lock.json', 'tools']),
    seed: await fs.readFile(path.join(root, 'android/app/src/main/assets/release-seed.json'), 'utf8'),
    runtime: await fs.readFile(path.join(root, 'android/app/src/main/assets/runtime-version.txt'), 'utf8'),
  });
  const before = await inputs();
  const file = path.join(root, 'android/app/build/outputs/apk/release/app-release.apk');
  const stamp = path.join(root, '.cache/apk-build.json');
  if (clean()) {
    try {
      const cached = JSON.parse(await fs.readFile(stamp, 'utf8'));
      const artifact = await fs.stat(file);
      if (cached.inputs === before && cached.bytes === artifact.size && cached.modified === artifact.mtimeMs) {
        console.log('APK build cache hit; reuse existing release APK');
        return file;
      }
    } catch { /* No reusable build record. */ }
  }
  if (reuseOnly) throw new Error('没有匹配当前源码和宿主包的 APK，请先运行 npm run build:apk');
  // Never leave a reusable stamp when a build is interrupted or fails.
  await fs.rm(stamp, { force: true });
  const gradle = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  execFileSync(gradle, [':app:assembleRelease'], { cwd: path.join(root, 'android'), stdio: 'inherit', shell: process.platform === 'win32', windowsHide: true });
  if (clean() && await inputs() === before) {
    const artifact = await fs.stat(file);
    await fs.mkdir(path.dirname(stamp), { recursive: true });
    await fs.writeFile(stamp, JSON.stringify({ inputs: before, bytes: artifact.size, modified: artifact.mtimeMs }));
  }
  return file;
}

module.exports = { buildApk };
if (require.main === module) buildApk(path.resolve(__dirname, '..')).catch(error => { console.error(error.message); process.exitCode = 1; });
