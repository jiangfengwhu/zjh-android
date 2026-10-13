const fs = require('node:fs/promises');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

async function prepareAsr(root) {
  const config = JSON.parse(await fs.readFile(path.join(root, 'config/asr.json'), 'utf8'));
  const cache = path.join(root, '.cache/asr');
  await fs.mkdir(cache, { recursive: true });
  const matches = async (file, bytes) => { try { return (await fs.stat(file)).size === bytes; } catch { return false; } };
  const download = async (url, destination, bytes) => {
    if (await matches(destination, bytes)) return;
    const temporary = destination + '.tmp';
    const args = ['--fail', '--location', '--max-time', '600', '--retry', '2', '--silent', '--show-error', url, '--output', temporary];
    if (process.env.HTTPS_PROXY) args.unshift('--proxy', process.env.HTTPS_PROXY);
    execFileSync(process.platform === 'win32' ? 'curl.exe' : 'curl', args, { stdio: 'inherit', windowsHide: true });
    if (!await matches(temporary, bytes)) throw new Error('ASR resource length mismatch');
    await fs.rename(temporary, destination);
  };
  await download(`https://github.com/k2-fsa/sherpa-onnx/releases/download/v${config.sherpaVersion}/sherpa-onnx-${config.sherpaVersion}.aar`, path.join(cache, `sherpa-onnx-${config.sherpaVersion}.aar`), config.aarBytes);
  const models = path.join(cache, 'models');
  const modelRoot = path.join(models, config.model);
  if (!(await Promise.all(Object.entries(config.files).map(([name, bytes]) => matches(path.join(modelRoot, name), bytes)))).every(Boolean)) {
    const archive = path.join(cache, `${config.model}.tar.bz2`);
    await download(`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${config.model}.tar.bz2`, archive, config.archiveBytes);
    await fs.mkdir(models, { recursive: true });
    execFileSync('tar', ['-xf', archive, '-C', models, ...Object.keys(config.files).map(name => `${config.model}/${name}`)], { stdio: 'inherit', windowsHide: true });
  }
  const assets = path.join(cache, 'assets/asr');
  await fs.mkdir(assets, { recursive: true });
  const identity = JSON.stringify(config);
  let sameModel = false;
  try { sameModel = (await fs.readFile(path.join(assets, 'MODEL.json'), 'utf8')) === identity; } catch {}
  for (const [name, bytes] of Object.entries(config.files)) {
    const source = path.join(modelRoot, name);
    if (!await matches(source, bytes)) throw new Error(`Incomplete ASR model: ${name}`);
    const destination = path.join(assets, name);
    if (!sameModel || !await matches(destination, bytes)) { await fs.copyFile(source, destination + '.tmp'); await fs.rename(destination + '.tmp', destination); }
  }
  await fs.writeFile(path.join(assets, 'MODEL.txt'), `${config.model}\nhttps://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models\nhttps://github.com/Gilgamesh-J/X-ASR\n`);
  await fs.writeFile(path.join(assets, 'MODEL.json'), identity);
  console.log(`Local ASR ready: Sherpa-ONNX ${config.sherpaVersion}, ${config.model}`);
}
module.exports = { prepareAsr };
if (require.main === module) prepareAsr(path.resolve(__dirname, '..')).catch(error => { console.error(error.message); process.exitCode = 1; });
