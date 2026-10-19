// Assembles one portable package (docs/PORTABLE_PACKAGE.md). Runs in .github/workflows/portable.yml on the target
// platform's own runner, under the Node that gets bundled (process.execPath), with JAVA_HOME pointing at the JDK whose
// jmods build the trimmed Java runtime.
//   node portable/ci/assemble.mjs --platform macos-arm64 --version 1.2.0 --commit <sha> --jar server.jar \
//        --agent <dir with dist, node_modules, package.json> --models <dir> --out <package dir>
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const portable = path.resolve(here, '..')
const opt = {}
for (let i = 2; i < process.argv.length; i += 2) opt[process.argv[i].replace(/^--/, '')] = process.argv[i + 1]
for (const k of ['platform', 'version', 'jar', 'agent', 'models', 'out']) if (!opt[k]) throw new Error(`missing --${k}`)
const out = path.resolve(opt.out)
const win = opt.platform.startsWith('windows'), mac = opt.platform.startsWith('macos')

function sh(cmd, args, options = {}) {
  console.log(`$ ${cmd} ${args.join(' ')}`)
  const r = spawnSync(cmd, args, { stdio: 'inherit', ...options })
  if (r.status !== 0) throw new Error(`${cmd} exited with ${r.status}`)
}
const copy = (from, to) => { fs.mkdirSync(path.dirname(to), { recursive: true }); fs.cpSync(from, to, { recursive: true }) }
function sha256(file) {
  const hash = createHash('sha256'), fd = fs.openSync(file, 'r'), buf = Buffer.alloc(4 << 20)
  let n; while ((n = fs.readSync(fd, buf, 0, buf.length, null)) > 0) hash.update(buf.subarray(0, n))
  fs.closeSync(fd); return hash.digest('hex')
}

fs.rmSync(out, { recursive: true, force: true })
fs.mkdirSync(out, { recursive: true })

// Launch files: only the one for this platform, so a user cannot pick the wrong one.
const wrapper = win ? 'start.bat' : mac ? 'start.command' : 'start.sh'
copy(path.join(portable, 'wrappers', wrapper), path.join(out, wrapper))
if (!win) fs.chmodSync(path.join(out, wrapper), 0o755)
copy(path.join(portable, 'README.txt'), path.join(out, 'README.txt'))
copy(path.join(portable, 'THIRD_PARTY_NOTICES.txt'), path.join(out, 'LICENSES', 'THIRD_PARTY_NOTICES.txt'))

// Programs.
// The onnxruntime jar carries every platform's library plus macOS debug symbols; keep only this platform's.
const ortDir = { 'linux-x64': 'linux-x64', 'linux-arm64': 'linux-aarch64', 'macos-arm64': 'osx-aarch64', 'macos-x64': 'osx-x64', 'windows-x64': 'win-x64' }[opt.platform]
fs.mkdirSync(path.join(out, 'app'), { recursive: true })
sh(path.join(process.env.JAVA_HOME || '', 'bin', win ? 'java.exe' : 'java'), [path.join(here, 'SlimJar.java'), path.resolve(opt.jar), path.join(out, 'app', 'server.jar'), ortDir])
copy(path.join(portable, 'launcher', 'yuda.mjs'), path.join(out, 'app', 'launcher', 'yuda.mjs'))
for (const name of ['dist', 'node_modules', 'package.json']) copy(path.join(path.resolve(opt.agent), name), path.join(out, 'app', 'agent', name))

// Node: the binary running this script is the official build for this platform (actions/setup-node).
const nodeDir = path.join(out, 'runtime', 'node')
copy(process.execPath, path.join(nodeDir, win ? 'node.exe' : path.join('bin', 'node')))
const nodeLicense = path.join(path.dirname(process.execPath), win ? 'LICENSE' : path.join('..', 'LICENSE'))
if (fs.existsSync(nodeLicense)) copy(nodeLicense, path.join(out, 'LICENSES', 'node-LICENSE.txt'))

// Java: a jlink runtime holding only the modules the server uses (about a third of a full JDK).
const javaHome = process.env.JAVA_HOME
if (!javaHome) throw new Error('JAVA_HOME is not set')
const modules = [
  'java.se', 'jdk.unsupported', 'jdk.crypto.ec', 'jdk.crypto.cryptoki', 'jdk.zipfs', 'jdk.charsets', 'jdk.localedata',
  'jdk.management', 'jdk.net', 'jdk.naming.dns', 'jdk.security.auth', 'jdk.security.jgss', 'jdk.httpserver', 'jdk.jfr',
]
const javaDir = path.join(out, 'runtime', 'java')
sh(path.join(javaHome, 'bin', win ? 'jlink.exe' : 'jlink'), ['--add-modules', modules.join(','), '--include-locales=en,zh',
  '--strip-debug', '--no-man-pages', '--no-header-files', '--compress=zip-6', '--output', javaDir])
const javaLicense = path.join(javaHome, 'NOTICE')
if (fs.existsSync(javaLicense)) copy(javaLicense, path.join(out, 'LICENSES', 'java-NOTICE.txt'))
if (mac) {
  // Apple Silicon refuses to run unsigned code; an ad-hoc signature is enough once the quarantine flag is cleared.
  const verify = spawnSync('codesign', ['--verify', path.join(javaDir, 'bin', 'java')])
  if (verify.status !== 0) {
    console.log('jlink output is not validly signed; applying ad-hoc signatures')
    const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)])
    for (const f of walk(javaDir)) {
      const head = Buffer.alloc(4); const fd = fs.openSync(f, 'r'); fs.readSync(fd, head, 0, 4, 0); fs.closeSync(fd)
      if ([0xfeedfacf, 0xcffaedfe, 0xcafebabe, 0xbebafeca].includes(head.readUInt32BE(0))) sh('codesign', ['--force', '--sign', '-', f])
    }
  }
}
const javaVersion = spawnSync(path.join(javaDir, 'bin', win ? 'java.exe' : 'java'), ['-version'], { encoding: 'utf8' })
if (javaVersion.status !== 0) throw new Error(`bundled java does not run:\n${javaVersion.stderr}`)

// Models: platform independent, prepared once by the workflow's models job.
const MODELS = [
  { file: 'u2net.onnx', role: '抠图（U²-Net）' },
  { file: 'embedding-fp32.onnx', role: '文本向量 fp32（完整档）' },
  { file: 'embedding-int8.onnx', role: '文本向量 int8（均衡、精简档）' },
  { file: 'embedding-vocab.txt', role: '文本向量词表' },
  { file: 'image-model-int8.onnx', role: '图文模型·图像塔（完整、均衡档）', optional: true },
  { file: 'image-text-model-int8.onnx', role: '图文模型·文本塔（完整、均衡档）', optional: true },
  { file: 'image-text-vocab.txt', role: '图文模型词表', optional: true },
  { file: 'image-model-manifest.json', role: '图文模型导出记录', optional: true },
]
const models = []
for (const m of MODELS) {
  const from = path.join(path.resolve(opt.models), m.file)
  if (!fs.existsSync(from)) throw new Error(`model missing: ${from}`)
  copy(from, path.join(out, 'models', m.file))
  models.push({ ...m, size: fs.statSync(from).size, sha256: sha256(from) })
}
fs.writeFileSync(path.join(out, 'models', 'SHA256SUMS'), models.map(m => `${m.sha256}  ${m.file}`).join('\n') + '\n')

const info = {
  name: 'yuda-portable', version: opt.version, commit: opt.commit || '', platform: opt.platform, builtAt: new Date().toISOString(),
  java: javaVersion.stderr.split(/\r?\n/)[0], node: process.version, models,
}
fs.writeFileSync(path.join(out, 'app', 'build-info.json'), JSON.stringify(info, null, 2) + '\n')

const size = dir => fs.readdirSync(dir, { withFileTypes: true }).reduce((s, e) => s + (e.isDirectory() ? size(path.join(dir, e.name)) : fs.statSync(path.join(dir, e.name)).size), 0)
console.log('\nPackage layout (MB):')
for (const part of ['app', 'runtime/java', 'runtime/node', 'models']) console.log(`  ${part.padEnd(14)} ${(size(path.join(out, part)) / 1048576).toFixed(1)}`)
console.log(`  ${'total'.padEnd(14)} ${(size(out) / 1048576).toFixed(1)}`)
