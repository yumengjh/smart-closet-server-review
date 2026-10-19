#!/usr/bin/env node
// 鱼搭便携版启动器（docs/PORTABLE_PACKAGE.md）。只用 Node 内置模块；由包根目录的 start.command / start.sh / start.bat
// 用包内自带的 Node 运行。无参数时进入交互菜单；带命令时供脚本与 CI 使用：
//   start | stop | restart | status | doctor | stress | monitor | logs | open | config | reset-password | backup | menu
// 通用参数：--json 机器可读输出；--yes 不再确认；--no-browser 启动后不打开浏览器；--verify 完整校验模型哈希。
import { spawn, spawnSync } from 'node:child_process'
import { createHash, randomBytes, randomInt } from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(process.env.YUDA_HOME || path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..'))
const WIN = process.platform === 'win32'
const P = {
  buildInfo: path.join(ROOT, 'app', 'build-info.json'),
  jar: path.join(ROOT, 'app', 'server.jar'),
  agent: path.join(ROOT, 'app', 'agent', 'dist', 'server.js'),
  java: path.join(ROOT, 'runtime', 'java', 'bin', WIN ? 'java.exe' : 'java'),
  models: path.join(ROOT, 'models'),
  config: path.join(ROOT, 'config', 'closet.env'),
  data: path.join(ROOT, 'data'),
  run: path.join(ROOT, 'data', 'run'),
  state: path.join(ROOT, 'data', 'run', 'state.json'),
  shutdown: path.join(ROOT, 'data', 'run', 'shutdown'),
  // 记下“管理员已改过初始密码”，之后不再试登录（data/run 在停止时会清空，所以放在 data 下）。
  passwordChanged: path.join(ROOT, 'data', '.initial-password-changed'),
  cache: path.join(ROOT, 'runtime-cache'),
  logs: path.join(ROOT, 'logs'),
  backups: path.join(ROOT, 'backups'),
}
const LOG = { server: path.join(P.logs, 'server.log'), agent: path.join(P.logs, 'agent.log') }
const MB = 1024 * 1024
const GB = 1024 * MB

// ---------- 参数与输出 ----------
const argv = process.argv.slice(2)
const FLAGS = new Set(argv.filter(a => a.startsWith('--')))
const ARGS = argv.filter(a => !a.startsWith('--'))
const JSON_OUT = FLAGS.has('--json')
const YES = FLAGS.has('--yes')
const tty = process.stdout.isTTY && !process.env.NO_COLOR
const c = (code, s) => tty ? `\x1b[${code}m${s}\x1b[0m` : s
const green = s => c(32, s), red = s => c(31, s), yellow = s => c(33, s), cyan = s => c(36, s), dim = s => c(2, s), bold = s => c(1, s)
const say = (...a) => { if (!JSON_OUT) console.log(...a) }
const sleep = ms => new Promise(r => setTimeout(r, ms))
const fmtMB = bytes => bytes >= GB ? `${(bytes / GB).toFixed(1)} GB` : `${Math.round(bytes / MB)} MB`

function readJson(file, fallback = null) { try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return fallback } }
const BUILD = readJson(P.buildInfo, { version: 'dev', commit: '', platform: `${process.platform}-${process.arch}`, models: [] })

// ---------- 配置文件 config/closet.env ----------
// 每行 KEY=VALUE；# 开头为注释。除下列 YUDA_* 启动器选项外，其余键原样作为环境变量交给服务端（例如 CLOSET_YUMG_*）。
const CONFIG_TEMPLATE = (secret, password) => `# 鱼搭便携版配置。修改后在菜单选择「重新启动」生效。也可以在菜单「设置」里修改。
# 文件含密钥，请勿外传。

# 网页端口与 Agent 内部端口
PORT=8080
CLOSET_AGENT_PORT=8091
# 访问范围：local = 只允许本机浏览器；lan = 允许同一局域网的设备（HTTP 明文，只在可信网络使用）
YUDA_ACCESS=local
# 模型档位：auto（按内存自动选）/ full / balanced / lite，见菜单「模型压力评估」
YUDA_PROFILE=auto
# 启动后自动打开浏览器
YUDA_OPEN_BROWSER=true
# 额外的 Java 参数（一般不需要）
YUDA_JAVA_OPTS=

# 首次启动自动创建的管理员。初始密码只在第一次登录时使用，登录后会要求修改；之后改这里不再生效，
# 忘记密码请用菜单「重置管理员密码」。
CLOSET_BOOTSTRAP_USERNAME=admin
CLOSET_BOOTSTRAP_PASSWORD=${password}
# 服务端与 Agent 之间的内部密钥（自动生成）
CLOSET_AGENT_SECRET=${secret}

# AI 服务（可选）。不填时衣物识别、Agent 对话、生图不可用，抠图、搜索、推荐等本地功能照常使用。
# 填服务商 API 根地址（需要时含 /v1），不是具体接口地址。
CLOSET_YUMG_BASE_URL=
CLOSET_YUMG_API_KEY=
CLOSET_YUMG_PROTOCOL=OPENAI_IMAGES
CLOSET_YUMG_DEFAULT_MODEL=
`
const randomPassword = () => {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
  let s = ''; for (let i = 0; i < 16; i++) s += alphabet[randomInt(alphabet.length)]
  return s
}

function loadConfig() {
  const values = {}
  try {
    for (const line of fs.readFileSync(P.config, 'utf8').split(/\r?\n/)) {
      const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/.exec(line)
      if (m) values[m[1]] = m[2].trim().replace(/^(['"])(.*)\1$/, '$2')
    }
  } catch { /* 首次运行还没有配置 */ }
  return values
}
function ensureConfig() {
  if (fs.existsSync(P.config)) return { created: false, config: loadConfig() }
  fs.mkdirSync(path.dirname(P.config), { recursive: true })
  fs.writeFileSync(P.config, CONFIG_TEMPLATE(randomBytes(32).toString('hex'), randomPassword()), { mode: 0o600 })
  return { created: true, config: loadConfig() }
}
function setConfig(key, value) {
  ensureConfig()
  const lines = fs.readFileSync(P.config, 'utf8').split(/\r?\n/)
  const at = lines.findIndex(l => new RegExp(`^\\s*${key}\\s*=`).test(l))
  if (at >= 0) lines[at] = `${key}=${value}`
  else { if (lines.at(-1) === '') lines.pop(); lines.push(`${key}=${value}`, '') }
  fs.writeFileSync(P.config, lines.join(os.EOL === '\r\n' ? '\r\n' : '\n'), { mode: 0o600 })
}
const portOf = cfg => Number(cfg.PORT || 8080)
const agentPortOf = cfg => Number(cfg.CLOSET_AGENT_PORT || 8091)
const urlOf = cfg => `http://127.0.0.1:${portOf(cfg)}`

// ---------- 模型档位与内存估算 ----------
// 估算方式与服务端加载前的检查一致：ONNX 会话常驻内存约为文件大小 × 1.5（docs 与 models/README.md）。
const MODEL_FILES = {
  segmentation: 'u2net.onnx',
  embeddingFp32: 'embedding-fp32.onnx',
  embeddingInt8: 'embedding-int8.onnx',
  image: 'image-model-int8.onnx',
  imageText: 'image-text-model-int8.onnx',
}
const PROFILES = {
  full: { label: '完整', heapMb: 2048, embedding: 'embeddingFp32', clip: true,
    idle: { seg: '10m', image: '10m', text: '30m' }, note: '向量 fp32 + 图文模型，内存充足时效果与速度最好' },
  balanced: { label: '均衡', heapMb: 1280, embedding: 'embeddingInt8', clip: true,
    idle: { seg: '5m', image: '5m', text: '10m' }, note: '向量 int8 + 图文模型，闲置更快释放' },
  lite: { label: '精简', heapMb: 768, embedding: 'embeddingInt8', clip: false,
    idle: { seg: '2m', image: '1m', text: '1m' }, note: '关闭图文模型（相似衣物改用颜色轮廓、文字找图关闭），适合 4 GB 内存以下' },
}
function autoProfile(totalBytes = os.totalmem()) {
  if (totalBytes >= 7.5 * GB) return 'full'
  if (totalBytes >= 3.5 * GB) return 'balanced'
  return 'lite'
}
function resolveProfile(cfg) {
  const wanted = (cfg.YUDA_PROFILE || 'auto').toLowerCase()
  const name = PROFILES[wanted] ? wanted : autoProfile()
  return { name, auto: !PROFILES[wanted], ...PROFILES[name] }
}
const modelSize = key => { try { return fs.statSync(path.join(P.models, MODEL_FILES[key])).size } catch { return 0 } }
function memoryPlan(profile) {
  const parts = [
    { name: '服务端 Java（堆上限 + 非堆）', bytes: Math.min(profile.heapMb, 900) * MB + 220 * MB, resident: true },
    { name: 'Agent（Node）', bytes: 110 * MB, resident: true },
    { name: '抠图模型 U²-Net（录入时加载）', bytes: modelSize('segmentation') * 1.5 },
    { name: `文本向量（${profile.embedding === 'embeddingFp32' ? 'fp32' : 'int8'}，常驻）`, bytes: modelSize(profile.embedding) * 1.5, resident: true },
  ]
  if (profile.clip) {
    parts.push({ name: '图文模型·图像塔（使用时加载）', bytes: modelSize('image') * 1.5 })
    parts.push({ name: '图文模型·文本塔（使用时加载）', bytes: modelSize('imageText') * 1.5 })
  }
  const idle = parts.filter(p => p.resident).reduce((s, p) => s + p.bytes, 0)
  const peak = parts.reduce((s, p) => s + p.bytes, 0)
  return { parts, idle, peak }
}
function rate(peak, total, free) {
  const ratio = peak / total
  if (peak > free + 512 * MB && ratio > 0.5) return { level: 'insufficient', label: '不足', advice: '峰值可能超过可用内存，建议改用精简档或关闭其他程序' }
  if (ratio < 0.25) return { level: 'easy', label: '轻松', advice: '内存充裕，可放心使用全部本地模型' }
  if (ratio < 0.45) return { level: 'moderate', label: '适中', advice: '正常使用无压力；同时批量录入与图片搜索时内存会明显上升' }
  if (ratio < 0.7) return { level: 'tight', label: '吃紧', advice: '建议使用均衡或精简档，避免同时运行大型程序' }
  return { level: 'insufficient', label: '不足', advice: '建议使用精简档；模型加载前服务端会检查可用内存，不够时自动降级' }
}
function stressReport(cfg) {
  const profile = resolveProfile(cfg)
  const total = os.totalmem(), free = os.freemem(), cores = os.cpus().length
  const plan = memoryPlan(profile)
  const threads = inferenceThreads()
  const cpu = cores <= 2 ? { label: '偏慢', advice: '抠图单张约需数秒，建议一次录入少量照片' }
    : cores <= 4 ? { label: '一般', advice: '抠图单张约 1–3 秒' } : { label: '充裕', advice: '本地推理不会成为瓶颈' }
  const options = Object.fromEntries(Object.keys(PROFILES).map(name => {
    const p = memoryPlan({ ...PROFILES[name] })
    return [name, { label: PROFILES[name].label, peakMb: Math.round(p.peak / MB), idleMb: Math.round(p.idle / MB), rating: rate(p.peak, total, free).label }]
  }))
  return {
    profile: profile.name, profileLabel: profile.label, auto: profile.auto, recommended: autoProfile(total),
    totalMb: Math.round(total / MB), freeMb: Math.round(free / MB), cores, inferenceThreads: threads,
    idleMb: Math.round(plan.idle / MB), peakMb: Math.round(plan.peak / MB),
    parts: plan.parts.map(p => ({ name: p.name, mb: Math.round(p.bytes / MB), resident: !!p.resident })),
    memory: rate(plan.peak, total, free), cpu, options,
  }
}
function inferenceThreads() { return Math.max(1, Math.min(4, Math.floor(os.cpus().length / 2))) }

// ---------- 进程管理 ----------
function readState() { return readJson(P.state, null) }
function writeState(s) { fs.mkdirSync(P.run, { recursive: true }); fs.writeFileSync(P.state, JSON.stringify(s, null, 2)) }
function alive(pid) {
  if (!pid) return false
  try { process.kill(pid, 0); return true } catch (e) { return e.code === 'EPERM' }
}
// 记录的 PID 可能已被系统复用，所以核对命令行里是否带着本包的程序路径（Windows 只核对进程名）。
// 不能用 Linux 的 comm：Node 24 会把主线程改名为 MainThread。
function ours(pid, kind) {
  if (!alive(pid)) return false
  const marker = kind === 'java' ? P.jar : P.agent
  try {
    if (WIN) {
      const r = spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'], { encoding: 'utf8', windowsHide: true })
      return ((/^"([^"]+)"/.exec(r.stdout.trim()) || [])[1] || '').toLowerCase().includes(kind)
    }
    if (process.platform === 'linux') return fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').includes(marker)
    return spawnSync('ps', ['-ww', '-p', String(pid), '-o', 'command='], { encoding: 'utf8' }).stdout.includes(marker)
  } catch { return false }
}
async function httpOk(url, ms = 2000) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(ms) })
    return r.ok ? await r.json().catch(() => ({})) : null
  } catch { return null }
}
function portFree(port, host = '127.0.0.1') {
  return new Promise(resolve => {
    const s = net.createServer().once('error', () => resolve(false)).once('listening', () => s.close(() => resolve(true)))
    s.listen(port, host)
  })
}
async function services(cfg = loadConfig()) {
  const st = readState() || {}
  const server = { pid: st.serverPid || null, running: ours(st.serverPid, 'java'), port: st.port || portOf(cfg) }
  const agent = { pid: st.agentPid || null, running: ours(st.agentPid, 'node'), port: st.agentPort || agentPortOf(cfg) }
  server.health = server.running ? await httpOk(`http://127.0.0.1:${server.port}/api/health`) : null
  agent.health = agent.running ? await httpOk(`http://127.0.0.1:${agent.port}/health`) : null
  return { server, agent, startedAt: st.startedAt || null, profile: st.profile || null, url: `http://127.0.0.1:${server.port}` }
}
function rotate(file) {
  try { if (fs.statSync(file).size > 10 * MB) fs.renameSync(file, file + '.1') } catch { /* 还没有日志 */ }
}
function tail(file, lines = 40) {
  try {
    const fd = fs.openSync(file, 'r'), size = fs.fstatSync(fd).size, len = Math.min(size, 64 * 1024)
    const buf = Buffer.alloc(len); fs.readSync(fd, buf, 0, len, size - len); fs.closeSync(fd)
    return buf.toString('utf8').split(/\r?\n/).slice(-lines - 1).join('\n')
  } catch { return '' }
}
function logSince(file, offset) {
  try {
    const fd = fs.openSync(file, 'r'), size = fs.fstatSync(fd).size, start = size < offset ? 0 : offset
    const buf = Buffer.alloc(Math.min(size - start, 4 * MB)); fs.readSync(fd, buf, 0, buf.length, start); fs.closeSync(fd)
    return buf.toString('utf8')
  } catch { return '' }
}
function spawnDetached(cmd, args, env, logFile) {
  fs.mkdirSync(P.logs, { recursive: true }); rotate(logFile)
  const out = fs.openSync(logFile, 'a')
  fs.writeSync(out, `\n===== ${new Date().toISOString()} 启动 =====\n`)
  const child = spawn(cmd, args, { cwd: ROOT, env, detached: true, windowsHide: true, stdio: ['ignore', out, out] })
  child.unref(); fs.closeSync(out)
  return child.pid
}
function javaOptions(cfg, profile) {
  const opts = [
    `-Xmx${profile.heapMb}m`, '-XX:+ExitOnOutOfMemoryError', '-Djava.awt.headless=true',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    `-Djava.io.tmpdir=${path.join(P.cache, 'tmp')}`, `-Dorg.bytedeco.javacpp.cachedir=${path.join(P.cache, 'javacpp')}`,
  ]
  if (cfg.YUDA_JAVA_OPTS) opts.push(...cfg.YUDA_JAVA_OPTS.split(/\s+/).filter(Boolean))
  return opts
}
function serverEnv(cfg, profile) {
  const m = name => path.join(P.models, name)
  const disabled = m('disabled-in-lite-profile.onnx')
  const port = portOf(cfg), agentPort = agentPortOf(cfg)
  const passthrough = Object.fromEntries(Object.entries(cfg).filter(([k, v]) => !k.startsWith('YUDA_') && v !== ''))
  return {
    ...process.env,
    SERVER_ADDRESS: cfg.YUDA_ACCESS === 'lan' ? '0.0.0.0' : '127.0.0.1',
    CLOSET_STORAGE_DIR: P.data,
    CLOSET_MODEL_PATH: m(MODEL_FILES.segmentation),
    CLOSET_EMBEDDING_MODEL_PATH: m(MODEL_FILES[profile.embedding]),
    CLOSET_EMBEDDING_VOCAB_PATH: m('embedding-vocab.txt'),
    CLOSET_IMAGE_MODEL_PATH: profile.clip ? m(MODEL_FILES.image) : disabled,
    CLOSET_IMAGE_TEXT_MODEL_PATH: profile.clip ? m(MODEL_FILES.imageText) : disabled,
    CLOSET_IMAGE_TEXT_VOCAB_PATH: m('image-text-vocab.txt'),
    CLOSET_SEGMENTATION_IDLE_UNLOAD: profile.idle.seg,
    CLOSET_IMAGE_MODEL_IDLE_UNLOAD: profile.idle.image,
    CLOSET_IMAGE_TEXT_MODEL_IDLE_UNLOAD: profile.idle.text,
    CLOSET_VERSION: BUILD.version, CLOSET_COMMIT: BUILD.commit || '',
    ...passthrough,
    PORT: String(port),
    CLOSET_AGENT_RUNTIME_URL: `http://127.0.0.1:${agentPort}`,
    CLOSET_AGENT_CALLBACK_URL: `http://127.0.0.1:${port}`,
  }
}

// ---------- 初始密码提示 ----------
// 管理员还没改过初始密码时，启动成功与菜单顶部都显示账号和初始密码。判断办法：用初始密码试登录一次，看服务端是否
// 回答“需要修改密码”，随即退出登录释放会话（每个账号最多 5 个会话，多出的会挤掉最旧的）。一旦登录失败就说明已改过，
// 写下标记，以后不再试。结果：{ username, password } = 仍是初始密码；false = 已修改；null = 无法判断（不显示）。
async function initialPassword(cfg = loadConfig(), port = portOf(cfg)) {
  const username = cfg.CLOSET_BOOTSTRAP_USERNAME, password = cfg.CLOSET_BOOTSTRAP_PASSWORD
  if (!username || !password) return null
  if (fs.existsSync(P.passwordChanged)) return false
  const base = `http://127.0.0.1:${port}`, cookies = new Map()
  const call = async (method, url, body) => {
    const headers = { Accept: 'application/json' }
    if (cookies.size) headers.Cookie = [...cookies].map(([k, v]) => `${k}=${v}`).join('; ')
    if (method !== 'GET') { headers['Content-Type'] = 'application/json'; headers['X-XSRF-TOKEN'] = token }
    const r = await fetch(base + url, { method, headers, body: body && JSON.stringify(body), signal: AbortSignal.timeout(5000) })
    for (const c of r.headers.getSetCookie()) { const kv = c.split(';')[0], i = kv.indexOf('='); cookies.set(kv.slice(0, i), kv.slice(i + 1)) }
    return { status: r.status, data: await r.json().catch(() => null) }
  }
  let token = ''
  try {
    token = (await call('GET', '/api/auth/csrf')).data?.token || ''
    const login = await call('POST', '/api/auth/login', { username, password })
    if (login.status === 401) {
      // 只有确认服务端已创建过管理员（本次启动日志里出现过）之后，401 才说明密码已改。
      if (readState()?.adminReady) { fs.mkdirSync(P.data, { recursive: true }); fs.writeFileSync(P.passwordChanged, new Date().toISOString()) }
      return false
    }
    if (login.status !== 200) return null
    token = (await call('GET', '/api/auth/csrf')).data?.token || ''
    await call('POST', '/api/auth/logout')
    return login.data?.mustChangePassword ? { username, password } : false
  } catch { return null }
}
function showInitialPassword(info, firstRun) {
  if (!info) return
  say(yellow(`\n${firstRun ? '首次启动已创建管理员' : '管理员尚未修改初始密码'}：账号 ${bold(info.username)}  初始密码 ${bold(info.password)}`))
  say(yellow('首次登录后会要求修改密码；修改前这里每次启动都会显示（也写在 config/closet.env）。'))
}

async function start({ browser = true } = {}) {
  const { created, config: cfg } = ensureConfig()
  const now = await services(cfg)
  if (now.server.running && now.agent.running) {
    say(green(`已在运行：${now.url}`))
    const info = await initialPassword(cfg, now.server.port); showInitialPassword(info, false)
    return { ok: true, already: true, url: now.url, initialPassword: info || null }
  }
  if (now.server.running || now.agent.running) { say(yellow('检测到上次只剩部分服务在运行，先停止再启动。')); await stop({ quiet: true }) }
  for (const [file, what] of [[P.java, 'Java 运行时'], [P.jar, '服务端程序'], [P.agent, 'Agent 程序']])
    if (!fs.existsSync(file)) return fail(`缺少${what}：${path.relative(ROOT, file)}。压缩包可能没有完整解压，请重新解压。`)
  const port = portOf(cfg), agentPort = agentPortOf(cfg)
  const host = cfg.YUDA_ACCESS === 'lan' ? '0.0.0.0' : '127.0.0.1'
  if (!await portFree(port, host)) return fail(`端口 ${port} 已被其他程序占用。可在「设置」里改用其他端口（例如 ${port + 1}）。`)
  if (!await portFree(agentPort)) return fail(`Agent 端口 ${agentPort} 已被其他程序占用。可在「设置」里修改 CLOSET_AGENT_PORT。`)
  const profile = resolveProfile(cfg)
  for (const dir of [P.data, P.run, path.join(P.cache, 'tmp'), path.join(P.cache, 'javacpp')]) fs.mkdirSync(dir, { recursive: true })
  try { fs.rmSync(P.shutdown, { force: true }) } catch { /* 无残留 */ }

  say(`${cyan('▶')} 启动 Agent（端口 ${agentPort}）…`)
  const agentPid = spawnDetached(process.execPath, [P.agent], {
    ...process.env, NODE_ENV: 'production', CLOSET_AGENT_HOST: '127.0.0.1', CLOSET_AGENT_PORT: String(agentPort),
    CLOSET_AGENT_SECRET: cfg.CLOSET_AGENT_SECRET || '',
  }, LOG.agent)
  writeState({ agentPid, agentPort, port, startedAt: new Date().toISOString(), profile: profile.name })
  const agentReady = await waitFor(() => httpOk(`http://127.0.0.1:${agentPort}/health`), 30_000, () => alive(agentPid))
  if (!agentReady) { await stop({ quiet: true }); return fail('Agent 没有启动成功。', LOG.agent) }

  say(`${cyan('▶')} 启动服务端（端口 ${port}，模型档位：${profile.label}${profile.auto ? '·自动' : ''}，首次启动约 20–60 秒）…`)
  const args = [...javaOptions(cfg, profile), '-jar', P.jar,
    `--closet.portable.shutdown-file=${P.shutdown}`, `--closet.inference-threads=${inferenceThreads()}`]
  let logOffset = 0; try { logOffset = fs.statSync(LOG.server).size } catch { /* 新日志 */ }
  const serverPid = spawnDetached(P.java, args, serverEnv(cfg, profile), LOG.server)
  writeState({ agentPid, agentPort, serverPid, port, startedAt: new Date().toISOString(), profile: profile.name })
  const t0 = Date.now()
  const health = await waitFor(() => httpOk(`http://127.0.0.1:${port}/api/health`), 300_000, () => alive(serverPid), true)
  if (!health) { await stop({ quiet: true }); return fail('服务端没有启动成功。', LOG.server) }
  const url = urlOf(cfg)
  say(green(`✔ 已启动（${Math.round((Date.now() - t0) / 1000)} 秒）：${bold(url)}`))
  if (cfg.YUDA_ACCESS === 'lan') for (const ip of lanAddresses()) say(`  局域网访问：http://${ip}:${port}`)
  const seconds = Math.round((Date.now() - t0) / 1000)
  // 健康检查在服务端创建管理员之前就会通过；等到本次日志里出现创建完成的记录再判断初始密码。
  const adminReady = await waitFor(async () => logSince(LOG.server, logOffset).includes('Bootstrap administrator is ready'), 20_000, () => alive(serverPid))
  writeState({ ...readState(), adminReady: !!adminReady })
  const info = adminReady ? await initialPassword(cfg, port) : null
  writeState({ ...readState(), initialPassword: !!info })
  showInitialPassword(info, created)
  if (browser && cfg.YUDA_OPEN_BROWSER !== 'false') openBrowser(url)
  return { ok: true, url, serverPid, agentPid, version: health.version, seconds, firstRun: created, initialPassword: info || null }
}
async function waitFor(probe, timeout, stillAlive, showProgress = false) {
  const end = Date.now() + timeout; let n = 0
  while (Date.now() < end) {
    const r = await probe(); if (r) { if (showProgress && tty && !JSON_OUT) process.stdout.write('\r\x1b[K'); return r }
    if (!stillAlive()) return null
    if (showProgress && tty && !JSON_OUT) process.stdout.write(`\r  ${'⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'[n++ % 10]} 等待服务就绪… ${Math.round((timeout - (end - Date.now())) / 1000)} 秒`)
    await sleep(500)
  }
  return null
}
async function stop({ quiet = false } = {}) {
  const st = readState()
  if (!st) { if (!quiet) say('服务未在运行。'); return { ok: true, stopped: false } }
  const result = { ok: true, stopped: true, server: 'not-running', agent: 'not-running' }
  if (ours(st.serverPid, 'java')) {
    if (!quiet) say(`${cyan('■')} 正在停止服务端（会先保存数据）…`)
    // 优先让服务端自己有序退出：写入停止文件，服务端每秒检查一次（PortableShutdownWatcher）。
    fs.mkdirSync(P.run, { recursive: true }); fs.writeFileSync(P.shutdown, 'stop')
    if (await waitGone(st.serverPid, 40_000)) result.server = 'graceful'
    else {
      if (!WIN) { try { process.kill(st.serverPid, 'SIGTERM') } catch { /* 已退出 */ } }
      if (await waitGone(st.serverPid, WIN ? 0 : 15_000)) result.server = 'terminated'
      else { kill(st.serverPid); await waitGone(st.serverPid, 10_000); result.server = 'killed' }
    }
  }
  if (ours(st.agentPid, 'node')) {
    if (!quiet) say(`${cyan('■')} 正在停止 Agent…`)
    if (WIN) kill(st.agentPid); else try { process.kill(st.agentPid, 'SIGTERM') } catch { /* 已退出 */ }
    if (!await waitGone(st.agentPid, 8_000)) { kill(st.agentPid); await waitGone(st.agentPid, 5_000) }
    result.agent = 'stopped'
  }
  try { fs.rmSync(P.state, { force: true }); fs.rmSync(P.shutdown, { force: true }) } catch { /* 忽略 */ }
  if (!quiet) say(green('✔ 已停止。'))
  return result
}
async function waitGone(pid, ms) {
  const end = Date.now() + ms
  while (alive(pid)) { if (Date.now() >= end) return false; await sleep(300) }
  return true
}
function kill(pid) {
  if (WIN) spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
  else try { process.kill(pid, 'SIGKILL') } catch { /* 已退出 */ }
}
function fail(message, logFile) {
  say(red(`✘ ${message}`))
  if (logFile) { const t = tail(logFile, 25); if (t) say(dim(`--- ${path.relative(ROOT, logFile)} 最后几行 ---\n${t}`)) }
  return { ok: false, error: message, log: logFile ? tail(logFile, 60) : undefined }
}
function lanAddresses() {
  return Object.values(os.networkInterfaces()).flat().filter(i => i && i.family === 'IPv4' && !i.internal).map(i => i.address)
}
function openBrowser(url) {
  const [cmd, args] = WIN ? ['cmd', ['/c', 'start', '', url]] : process.platform === 'darwin' ? ['open', [url]] : ['xdg-open', [url]]
  try { spawn(cmd, args, { detached: true, stdio: 'ignore', windowsHide: true }).on('error', () => {}).unref() } catch { /* 没有图形界面 */ }
}

// ---------- 状态与资源 ----------
function usage(pids) {
  const out = {}
  const list = pids.filter(Boolean)
  if (!list.length) return out
  try {
    if (WIN) {
      const r = spawnSync('powershell', ['-NoProfile', '-Command',
        `Get-Process -Id ${list.join(',')} -ErrorAction SilentlyContinue | Select-Object Id,WorkingSet64,CPU | ConvertTo-Json -Compress`],
      { encoding: 'utf8', windowsHide: true })
      const rows = [].concat(JSON.parse(r.stdout || '[]'))
      for (const row of rows) out[row.Id] = { rssMb: Math.round(row.WorkingSet64 / MB), cpuSeconds: Math.round(row.CPU || 0) }
    } else {
      const r = spawnSync('ps', ['-o', 'pid=,rss=,%cpu=', '-p', list.join(',')], { encoding: 'utf8' })
      for (const line of r.stdout.trim().split('\n')) {
        const [pid, rss, cpu] = line.trim().split(/\s+/)
        if (pid) out[pid] = { rssMb: Math.round(Number(rss) / 1024), cpuPercent: Number(cpu) }
      }
    }
  } catch { /* 无法读取资源占用不影响其他功能 */ }
  return out
}
async function status() {
  const cfg = loadConfig(), s = await services(cfg)
  const use = usage([s.server.pid, s.agent.pid].filter((p, i) => [s.server.running, s.agent.running][i]))
  const info = s.server.health ? await initialPassword(cfg, s.server.port) : null
  const res = { ...s, usage: use, version: BUILD.version, platform: BUILD.platform, initialPassword: info || null }
  if (JSON_OUT) return res
  const line = (name, x, ok) => {
    const u = use[x.pid]
    const extra = u ? dim(`  内存 ${u.rssMb} MB${u.cpuPercent !== undefined ? `  CPU ${u.cpuPercent}%` : ''}`) : ''
    return `  ${name}  ${x.running ? (ok ? green('运行中') : yellow('启动中/无响应')) : dim('未运行')}  ${x.running ? `PID ${x.pid}  端口 ${x.port}` : ''}${extra}`
  }
  say(line('服务端', s.server, !!s.server.health))
  say(line('Agent ', s.agent, !!s.agent.health))
  if (s.server.health) say(`  访问地址  ${bold(s.url)}${s.startedAt ? dim(`  （启动于 ${new Date(s.startedAt).toLocaleString()}）`) : ''}`)
  if (info) say(`  初始账号  ${info.username} / ${info.password}  ${yellow('（尚未修改，首次登录后需修改）')}`)
  return res
}

// ---------- 环境检查 ----------
function sha256(file) {
  const hash = createHash('sha256'), fd = fs.openSync(file, 'r'), buf = Buffer.alloc(4 * MB)
  let n; while ((n = fs.readSync(fd, buf, 0, buf.length, null)) > 0) hash.update(buf.subarray(0, n))
  fs.closeSync(fd); return hash.digest('hex')
}
async function doctor({ verify = FLAGS.has('--verify') } = {}) {
  const checks = []
  const add = (group, name, level, detail) => checks.push({ group, name, level, detail })
  const cfg = loadConfig()
  const expected = BUILD.platform, actual = `${process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'macos' : process.platform}-${process.arch}`
  add('系统', '安装包平台', expected === actual || BUILD.version === 'dev' ? 'ok' : 'fail',
    expected === actual ? `${expected}` : `安装包是 ${expected}，本机是 ${actual}，请下载对应平台的压缩包`)
  add('系统', '操作系统', 'ok', `${os.type()} ${os.release()}（${os.arch()}）`)
  const cpus = os.cpus()
  add('系统', 'CPU', cpus.length >= 2 ? 'ok' : 'warn', `${cpus[0]?.model?.trim() || '未知'} × ${cpus.length} 核${cpus.length < 2 ? '，本地推理会较慢' : ''}`)
  const total = os.totalmem(), free = os.freemem()
  add('系统', '内存', total >= 3.5 * GB ? 'ok' : total >= 2 * GB ? 'warn' : 'fail', `共 ${fmtMB(total)}，可用 ${fmtMB(free)}`)
  try {
    const st = fs.statfsSync(ROOT), avail = st.bavail * st.bsize
    add('系统', '磁盘空间', avail >= 2 * GB ? 'ok' : avail >= 500 * MB ? 'warn' : 'fail', `所在磁盘可用 ${fmtMB(avail)}${avail < 2 * GB ? '，衣物图片较多时可能不够' : ''}`)
  } catch { add('系统', '磁盘空间', 'warn', '无法读取') }
  try {
    fs.mkdirSync(P.data, { recursive: true }); const probe = path.join(P.data, '.write-test'); fs.writeFileSync(probe, 'ok'); fs.rmSync(probe)
    add('系统', '目录可写', 'ok', ROOT)
  } catch { add('系统', '目录可写', 'fail', `${ROOT} 不可写，请解压到有写权限的位置（例如「文档」或桌面）`) }
  if (/\s/.test(ROOT)) add('系统', '安装路径', 'warn', '路径含空格，一般没有问题；若启动失败请换到不含空格的目录')
  if (WIN && /[^\x00-\x7f]/.test(ROOT)) add('系统', '安装路径', 'warn', '路径含中文等非英文字符，个别本地库可能加载失败；若抠图报错请移到纯英文路径，如 D:\\yuda')
  if (process.platform === 'darwin') {
    const q = spawnSync('xattr', ['-p', 'com.apple.quarantine', P.java], { encoding: 'utf8' })
    add('系统', 'macOS 隔离标记', q.status === 0 ? 'warn' : 'ok', q.status === 0 ? '文件带有下载隔离标记；通过 start.command 启动会自动解除' : '无')
  }

  add('运行时', 'Node.js', 'ok', `${process.version}（包内自带）`)
  if (fs.existsSync(P.java)) {
    const t0 = Date.now(), r = spawnSync(P.java, ['-version'], { encoding: 'utf8', windowsHide: true })
    const ver = (r.stderr || '').split(/\r?\n/)[0]
    add('运行时', 'Java', r.status === 0 ? 'ok' : 'fail', r.status === 0 ? `${ver}（包内自带，${Date.now() - t0} ms）` : `无法运行：${(r.error?.message || r.stderr || '').slice(0, 200)}`)
  } else add('运行时', 'Java', 'fail', '缺少 runtime/java，请重新解压完整的压缩包')
  add('程序', '服务端', fs.existsSync(P.jar) ? 'ok' : 'fail', fs.existsSync(P.jar) ? `server.jar ${fmtMB(fs.statSync(P.jar).size)}` : '缺少 app/server.jar')
  add('程序', 'Agent', fs.existsSync(P.agent) ? 'ok' : 'fail', fs.existsSync(P.agent) ? '已就绪' : '缺少 app/agent')
  add('程序', '版本', 'ok', `${BUILD.version}${BUILD.commit ? ` (${BUILD.commit.slice(0, 7)})` : ''}，构建于 ${BUILD.builtAt || '未知'}`)

  const listed = BUILD.models?.length ? BUILD.models : Object.values(MODEL_FILES).map(file => ({ file }))
  for (const m of listed) {
    const file = path.join(P.models, m.file)
    if (!fs.existsSync(file)) { add('模型', m.file, m.optional ? 'warn' : 'fail', '缺失，相关功能会自动降级'); continue }
    const size = fs.statSync(file).size
    if (m.size && m.size !== size) { add('模型', m.file, 'fail', `大小不符（${size} ≠ ${m.size}），文件可能损坏，请重新解压`); continue }
    if (verify && m.sha256) {
      const ok = sha256(file) === m.sha256
      add('模型', m.file, ok ? 'ok' : 'fail', ok ? `${fmtMB(size)}，SHA-256 校验通过` : 'SHA-256 不符，文件已损坏'); continue
    }
    add('模型', m.file, 'ok', `${fmtMB(size)}${m.role ? `，${m.role}` : ''}`)
  }

  const s = await services(cfg)
  for (const [name, port, running] of [['网页端口', portOf(cfg), s.server.running], ['Agent 端口', agentPortOf(cfg), s.agent.running]]) {
    if (running) add('网络', name, 'ok', `${port}（鱼搭正在使用）`)
    else add('网络', name, await portFree(port) ? 'ok' : 'fail', await portFree(port) ? `${port} 可用` : `${port} 被其他程序占用，请在「设置」里修改`)
  }
  if (cfg.CLOSET_YUMG_BASE_URL && cfg.CLOSET_YUMG_API_KEY) {
    let detail = '已配置'
    try {
      const r = await fetch(cfg.CLOSET_YUMG_BASE_URL, { method: 'HEAD', signal: AbortSignal.timeout(5000) })
      detail = `已配置，服务地址可连通（HTTP ${r.status}）`
      add('网络', 'AI 服务', 'ok', detail)
    } catch (e) { add('网络', 'AI 服务', 'warn', `已配置，但连不上 ${cfg.CLOSET_YUMG_BASE_URL}（${e.cause?.code || e.name}）`) }
  } else add('网络', 'AI 服务', 'warn', '未配置：识别、Agent、生图不可用；本地抠图、搜索、推荐正常。可在「设置」里填写')

  const stress = stressReport(cfg)
  add('模型压力', '当前档位', stress.memory.level === 'insufficient' ? 'warn' : 'ok',
    `${stress.profileLabel}${stress.auto ? '（自动）' : ''}：峰值约 ${stress.peakMb} MB / 总内存 ${stress.totalMb} MB，${stress.memory.label}`)

  const summary = { ok: checks.filter(x => x.level === 'ok').length, warn: checks.filter(x => x.level === 'warn').length, fail: checks.filter(x => x.level === 'fail').length }
  if (!JSON_OUT) {
    let group = ''
    for (const x of checks) {
      if (x.group !== group) { group = x.group; say(bold(`\n[${group}]`)) }
      const tag = x.level === 'ok' ? green('通过') : x.level === 'warn' ? yellow('注意') : red('失败')
      say(`  ${tag}  ${x.name.padEnd(14)} ${x.detail}`)
    }
    say(`\n合计：${green(`${summary.ok} 通过`)}  ${yellow(`${summary.warn} 注意`)}  ${red(`${summary.fail} 失败`)}${verify ? '' : dim('   （模型完整校验：doctor --verify 或菜单 7 后选择）')}`)
  }
  return { ok: summary.fail === 0, summary, checks, platform: actual }
}

function printStress(r) {
  say(bold('\n本机'))
  say(`  内存 共 ${r.totalMb} MB，当前可用 ${r.freeMb} MB；CPU ${r.cores} 核（推理线程 ${r.inferenceThreads}）`)
  say(bold(`\n当前档位：${r.profileLabel}${r.auto ? '（自动选择）' : ''}`) + dim(`   推荐：${PROFILES[r.recommended].label}`))
  for (const p of r.parts) say(`  ${p.resident ? '常驻' : '按需'}  ${String(p.mb).padStart(5)} MB  ${p.name}`)
  say(`  闲置约 ${r.idleMb} MB，全部模型同时加载峰值约 ${r.peakMb} MB`)
  const col = { easy: green, moderate: green, tight: yellow, insufficient: red }[r.memory.level]
  say(`  内存压力：${col(r.memory.label)} — ${r.memory.advice}`)
  say(`  CPU 压力：${r.cpu.label} — ${r.cpu.advice}`)
  say(bold('\n各档位对比'))
  for (const [name, o] of Object.entries(r.options))
    say(`  ${o.label}（${name.padEnd(8)}）峰值 ${String(o.peakMb).padStart(5)} MB  闲置 ${String(o.idleMb).padStart(5)} MB  ${o.rating}  ${dim(PROFILES[name].note)}`)
  say(dim('\n说明：模型推理占用的是 Java 堆外内存；服务端加载图文模型前会检查可用内存，不够时自动降级而不是崩溃。'))
}

async function monitor(seconds = Number(ARGS[1]) || 0, { inMenu = false } = {}) {
  const end = seconds ? Date.now() + seconds * 1000 : Infinity
  const samples = []
  let stopRequested = false
  const onKey = () => { stopRequested = true }
  const keyStop = process.stdin.isTTY && !JSON_OUT
  if (keyStop) { say(dim('按回车结束监控…')); process.stdin.once('data', onKey); if (!inMenu) process.stdin.resume() }
  while (!stopRequested && Date.now() < end) {
    const s = await services()
    const pids = [s.server.running && s.server.pid, s.agent.running && s.agent.pid].filter(Boolean)
    const u = usage(pids)
    const row = { at: new Date().toISOString(), freeMb: Math.round(os.freemem() / MB), load: os.loadavg()[0],
      serverMb: u[s.server.pid]?.rssMb ?? null, serverCpu: u[s.server.pid]?.cpuPercent ?? null, agentMb: u[s.agent.pid]?.rssMb ?? null }
    samples.push(row)
    say(`${new Date().toLocaleTimeString()}  服务端 ${row.serverMb ?? '-'} MB${row.serverCpu !== null ? ` CPU ${row.serverCpu}%` : ''}  Agent ${row.agentMb ?? '-'} MB  系统可用 ${row.freeMb} MB${WIN ? '' : `  负载 ${row.load.toFixed(2)}`}`)
    if (!s.server.running) { say(dim('服务端未运行。')); break }
    await sleep(2000)
  }
  if (keyStop) { process.stdin.off('data', onKey); if (!inMenu) process.stdin.pause() }
  const peak = Math.max(0, ...samples.map(x => x.serverMb || 0))
  return { ok: true, samples, peakServerMb: peak }
}

// ---------- 账号与数据 ----------
async function resetPassword(ask) {
  const cfg = ensureConfig().config
  const username = ARGS[1] || cfg.CLOSET_BOOTSTRAP_USERNAME || 'admin'
  const wasRunning = (await services(cfg)).server.running
  if (wasRunning) {
    if (!YES && !(await ask?.('重置密码需要先停止服务，完成后自动重新启动。继续？(y/N) '))?.match(/^y/i)) return { ok: false, cancelled: true }
    await stop()
  }
  const password = randomPassword()
  const r = spawnSync(P.java, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-jar', P.jar,
    '--closet-command=reset-password', `--username=${username}`, `--initial-password=${password}`],
  { cwd: ROOT, env: { ...process.env, CLOSET_STORAGE_DIR: P.data }, encoding: 'utf8', windowsHide: true })
  if (r.status !== 0) return fail(`重置失败：${(r.stderr || r.stdout || '').trim().split(/\r?\n/).slice(-3).join(' ')}`)
  say(green(`✔ ${username} 的密码已重置为：${bold(password)}`)); say('登录后会要求修改密码。')
  // 重置后的密码同样在修改前每次启动都提示；CLOSET_BOOTSTRAP_* 只在账号不存在时用于创建，改它没有其他影响。
  if (username === cfg.CLOSET_BOOTSTRAP_USERNAME) { setConfig('CLOSET_BOOTSTRAP_PASSWORD', password); fs.rmSync(P.passwordChanged, { force: true }) }
  if (wasRunning) await start({ browser: false })
  return { ok: true, username, password }
}
async function backup(ask) {
  const wasRunning = (await services()).server.running
  if (wasRunning) {
    if (!YES && !(await ask?.('备份需要先停止服务以保证数据库完整，完成后自动重新启动。继续？(y/N) '))?.match(/^y/i)) return { ok: false, cancelled: true }
    await stop()
  }
  if (!fs.existsSync(P.data)) return fail('还没有数据可以备份。')
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15)
  const dest = path.join(P.backups, `data-${stamp}`)
  fs.cpSync(P.data, dest, { recursive: true, filter: src => !src.startsWith(P.run) })
  if (fs.existsSync(P.config)) fs.copyFileSync(P.config, path.join(dest, 'closet.env'))
  say(green(`✔ 已备份到 ${path.relative(ROOT, dest)}`))
  say(dim('恢复：停止服务，用备份目录替换 data 目录（closet.env 放回 config 目录），再启动。'))
  if (wasRunning) await start({ browser: false })
  return { ok: true, path: dest }
}

// ---------- 交互菜单 ----------
async function menu() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, terminal: !!process.stdin.isTTY })
  // 自己排队读取行：管道输入（CI 测试菜单）时，所有行可能在第一次提问前就已到达。
  let closed = false
  const lines = []
  let waiting = null
  rl.on('line', l => { if (waiting) { const w = waiting; waiting = null; w(l) } else lines.push(l) })
  rl.on('close', () => { closed = true; if (waiting) { const w = waiting; waiting = null; w(null) } })
  const ask = q => {
    process.stdout.write(q)
    if (lines.length) { const l = lines.shift(); if (!process.stdin.isTTY) process.stdout.write(l + '\n'); return Promise.resolve(l) }
    if (closed) { process.stdout.write('\n'); return Promise.resolve(null) }
    return new Promise(resolve => { waiting = resolve })
  }
  const pause = async () => { if (process.stdin.isTTY) await ask(dim('\n按回车返回菜单…')) }
  const titled = t => say(bold(cyan(`\n── ${t} ${'─'.repeat(Math.max(0, 40 - t.length * 2))}`)))

  if (!fs.existsSync(P.config)) {
    say(bold('\n欢迎使用鱼搭便携版！'))
    say('首次使用建议先做一次「环境检查」，然后「启动」。')
  }
  for (;;) {
    const cfg = loadConfig(), s = await services(cfg)
    const state = s.server.health ? green(`● 运行中  ${s.url}`) : s.server.running ? yellow('● 启动中…') : dim('○ 未运行')
    say(`\n${bold('鱼搭 · 便携版')} ${dim(`${BUILD.version}  ${BUILD.platform}`)}`)
    say(`状态：${state}`)
    // 只有上次判断仍是初始密码时才再试，已修改的账号不会因为刷新菜单而被反复登录。
    const info = s.server.health && readState()?.initialPassword ? await initialPassword(cfg, s.server.port) : null
    if (s.server.health && readState() && !info) writeState({ ...readState(), initialPassword: false })
    if (info) say(yellow(`初始账号：${info.username} / ${info.password}（尚未修改，首次登录后需修改）`))
    say(dim('──────────────────────────────────────────────'))
    say(' 1. 启动              2. 停止              3. 重新启动')
    say(' 4. 打开浏览器        5. 运行状态          6. 查看日志')
    say(' 7. 环境检查          8. 模型压力评估      9. 实时资源监控')
    say('10. 设置             11. 重置管理员密码   12. 备份数据')
    say(' 0. 退出（服务保持后台运行）               q. 停止服务并退出')
    const choice = await ask('\n请选择：')
    if (choice === null) break
    const pick = choice.trim().toLowerCase()
    try {
      if (pick === '1') { titled('启动'); await start(); await pause() }
      else if (pick === '2') { titled('停止'); await stop(); await pause() }
      else if (pick === '3') { titled('重新启动'); await stop(); await start(); await pause() }
      else if (pick === '4') { if (s.server.health) openBrowser(s.url); else say(yellow('服务未运行，请先启动。')) }
      else if (pick === '5') { titled('运行状态'); await status(); await pause() }
      else if (pick === '6') { titled('日志'); await showLogs(ask); }
      else if (pick === '7') {
        titled('环境检查'); const r = await doctor({ verify: false })
        if (r.ok && (await ask('\n是否完整校验模型文件哈希（约 10–30 秒）？(y/N) '))?.match(/^y/i)) await doctor({ verify: true })
        await pause()
      }
      else if (pick === '8') { titled('模型压力评估'); printStress(stressReport(cfg)); await pause() }
      else if (pick === '9') { titled('实时资源监控'); if (!s.server.running) say(yellow('服务未运行。')); else await monitor(process.stdin.isTTY ? 0 : 4, { inMenu: true }) }
      else if (pick === '10') await settings(ask, titled)
      else if (pick === '11') { titled('重置管理员密码'); await resetPassword(ask); await pause() }
      else if (pick === '12') { titled('备份数据'); await backup(ask); await pause() }
      else if (pick === '0') { if (s.server.running) say(dim('服务继续在后台运行；再次打开启动器可以停止。')); break }
      else if (pick === 'q') { await stop(); break }
      else if (pick) say(yellow('无效的选项。'))
    } catch (e) { say(red(`出错了：${e.message}`)) }
  }
  rl.close()
  return { ok: true }
}
async function showLogs(ask) {
  for (const [name, file] of [['服务端', LOG.server], ['Agent', LOG.agent]]) {
    say(bold(`\n[${name}] ${path.relative(ROOT, file)}`)); say(tail(file, name === 'Agent' ? 10 : 30) || dim('（暂无日志）'))
  }
  if (!process.stdin.isTTY) return
  const a = await ask(dim('\n输入 f 持续跟踪服务端日志，回车返回：'))
  if (a?.trim().toLowerCase() !== 'f') return
  say(dim('跟踪中，按回车停止…'))
  let pos = fs.existsSync(LOG.server) ? fs.statSync(LOG.server).size : 0
  const timer = setInterval(() => {
    try {
      const size = fs.statSync(LOG.server).size; if (size < pos) pos = 0
      if (size > pos) { const fd = fs.openSync(LOG.server, 'r'), b = Buffer.alloc(size - pos); fs.readSync(fd, b, 0, b.length, pos); fs.closeSync(fd); pos = size; process.stdout.write(b.toString('utf8')) }
    } catch { /* 日志轮转中 */ }
  }, 500)
  await ask('')
  clearInterval(timer)
}
async function settings(ask, titled) {
  for (;;) {
    const cfg = loadConfig()
    const key = cfg.CLOSET_YUMG_API_KEY ? `${cfg.CLOSET_YUMG_API_KEY.slice(0, 4)}…（已设置）` : '未设置'
    titled('设置')
    say(` 1. 网页端口           ${cfg.PORT || 8080}`)
    say(` 2. Agent 端口         ${cfg.CLOSET_AGENT_PORT || 8091}`)
    say(` 3. 访问范围           ${cfg.YUDA_ACCESS === 'lan' ? '局域网' : '仅本机'}`)
    say(` 4. 模型档位           ${cfg.YUDA_PROFILE || 'auto'}（当前生效：${resolveProfile(cfg).label}）`)
    say(` 5. AI 服务地址        ${cfg.CLOSET_YUMG_BASE_URL || '未设置'}`)
    say(` 6. AI 密钥            ${key}`)
    say(` 7. AI 默认模型        ${cfg.CLOSET_YUMG_DEFAULT_MODEL || '未设置'}`)
    say(` 8. 启动后打开浏览器   ${cfg.YUDA_OPEN_BROWSER === 'false' ? '否' : '是'}`)
    say(` 9. 用编辑器打开配置文件`)
    say(' 0. 返回' + dim('   （修改后需「重新启动」生效）'))
    const a = (await ask('请选择：'))?.trim()
    if (a === null || a === undefined || a === '0' || a === '') return
    const portInput = async (label, k) => {
      const v = (await ask(`${label}（1024–65535）：`))?.trim()
      if (/^\d+$/.test(v) && +v >= 1024 && +v <= 65535) { setConfig(k, v); say(green('已保存')) } else if (v) say(yellow('端口无效'))
    }
    if (a === '1') await portInput('新的网页端口', 'PORT')
    else if (a === '2') await portInput('新的 Agent 端口', 'CLOSET_AGENT_PORT')
    else if (a === '3') {
      const v = (await ask('1 = 仅本机（推荐）  2 = 局域网（HTTP 明文，同一网络的人都能访问登录页）：'))?.trim()
      if (v === '1') setConfig('YUDA_ACCESS', 'local'); else if (v === '2') setConfig('YUDA_ACCESS', 'lan')
    } else if (a === '4') {
      printStress(stressReport(cfg))
      const v = (await ask('\n输入 auto / full / balanced / lite：'))?.trim().toLowerCase()
      if (['auto', ...Object.keys(PROFILES)].includes(v)) { setConfig('YUDA_PROFILE', v); say(green('已保存')) }
    } else if (a === '5') { const v = (await ask('API 根地址（如 https://api.example.com/v1，留空取消）：'))?.trim(); if (v) setConfig('CLOSET_YUMG_BASE_URL', v) }
    else if (a === '6') { const v = (await ask('API 密钥（留空取消）：'))?.trim(); if (v) setConfig('CLOSET_YUMG_API_KEY', v) }
    else if (a === '7') { const v = (await ask('默认模型名（留空取消）：'))?.trim(); if (v) setConfig('CLOSET_YUMG_DEFAULT_MODEL', v) }
    else if (a === '8') setConfig('YUDA_OPEN_BROWSER', cfg.YUDA_OPEN_BROWSER === 'false' ? 'true' : 'false')
    else if (a === '9') {
      ensureConfig()
      const [cmd, args] = WIN ? ['notepad', [P.config]] : process.platform === 'darwin' ? ['open', ['-t', P.config]] : ['xdg-open', [P.config]]
      spawn(cmd, args, { detached: true, stdio: 'ignore' }).on('error', () => say(`请手动编辑：${P.config}`)).unref()
    }
  }
}

// ---------- 入口 ----------
async function main() {
  const cmd = ARGS[0] || 'menu'
  let result
  switch (cmd) {
    case 'menu': result = await menu(); break
    case 'start': result = await start({ browser: !FLAGS.has('--no-browser') }); break
    case 'stop': result = await stop(); break
    case 'restart': await stop(); result = await start({ browser: false }); break
    case 'status': result = await status(); break
    case 'doctor': result = await doctor(); break
    case 'stress': result = stressReport(loadConfig()); if (!JSON_OUT) printStress(result); break
    case 'monitor': result = await monitor(); break
    case 'logs': say(tail(ARGS[1] === 'agent' ? LOG.agent : LOG.server, Number(ARGS[2]) || 60)); result = { ok: true }; break
    case 'open': openBrowser(urlOf(loadConfig())); result = { ok: true }; break
    case 'config': {
      ensureConfig()
      if (ARGS[1] === 'set' && ARGS[2]) { setConfig(ARGS[2], ARGS.slice(3).join(' ')); result = { ok: true } }
      else if (ARGS[1] === 'get' && ARGS[2]) { result = { ok: true, value: loadConfig()[ARGS[2]] ?? null }; say(result.value ?? '') }
      else { result = { ok: true, path: P.config }; say(P.config) }
      break
    }
    case 'reset-password': result = await resetPassword(); break
    case 'backup': result = await backup(); break
    case 'help': case '-h':
      say('用法：start | stop | restart | status | doctor [--verify] | stress | monitor [秒] | logs [server|agent] [行数] | open | config [get|set KEY VALUE] | reset-password [账号] | backup | menu')
      say('参数：--json  --yes  --no-browser'); result = { ok: true }; break
    default: say(red(`未知命令：${cmd}（help 查看用法）`)); result = { ok: false }
  }
  if (JSON_OUT) console.log(JSON.stringify(result, null, 2))
  process.exitCode = result?.ok === false ? 1 : 0
}
main().catch(e => { console.error(red(e.stack || e.message)); process.exitCode = 1 })
