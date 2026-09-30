const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

// APK 和热更新共用同一份版本号。只有发布新 APK 时才递增 versionCode。
function runtimeVersion(repoRoot) {
  const version = JSON.parse(fs.readFileSync(path.join(repoRoot, 'mobile/apk-version.json'), 'utf8'));
  if (!Number.isInteger(version.versionCode) || version.versionCode < 1 || version.versionCode > 2100000000) {
    throw new Error('APK versionCode 必须是 1 到 2100000000 之间的整数');
  }
  if (typeof version.versionName !== 'string' || !version.versionName.trim()) {
    throw new Error('APK versionName 不能为空');
  }
  // 保留清单现有的 64 位十六进制格式，不再扫描代码和依赖。
  return createHash('sha256').update(`apk-version:${version.versionCode}`).digest('hex');
}

module.exports = { runtimeVersion };
if (require.main === module) process.stdout.write(runtimeVersion(path.resolve(__dirname, '..')));
