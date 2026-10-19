// Smoke test of an assembled portable package, run by .github/workflows/portable.yml on each target platform with the
// package's own Node: node smoke.mjs <package dir>. Drives the launcher exactly like a user would (CLI commands and the
// interactive menu over a pipe) and checks the real services, including the native image libraries and ONNX models.
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

const root = path.resolve(process.argv[2] || '.')
const launcher = path.join(root, 'app', 'launcher', 'yuda.mjs')
const PORT = 18080, AGENT_PORT = 18091, base = `http://127.0.0.1:${PORT}`
const timings = {}
let step = ''

function run(args, { input, timeout = 400_000 } = {}) {
  const t0 = Date.now()
  const r = spawnSync(process.execPath, [launcher, ...args], { cwd: root, input, encoding: 'utf8', timeout, env: { ...process.env, NO_COLOR: '1' } })
  timings[args.join(' ')] = Date.now() - t0
  if (r.error) throw r.error
  return r
}
function json(args) {
  const r = run([...args, '--json'])
  try { return { status: r.status, body: JSON.parse(r.stdout) } }
  catch { throw new Error(`${args.join(' ')} printed no JSON (exit ${r.status}):\n${r.stdout}\n${r.stderr}`) }
}
function check(cond, message, detail) {
  if (!cond) throw new Error(`${step}: ${message}${detail ? `\n${typeof detail === 'string' ? detail : JSON.stringify(detail, null, 2)}` : ''}`)
  console.log(`  ✔ ${message}`)
}
const section = name => { step = name; console.log(`\n## ${name}`) }
const sleep = ms => new Promise(r => setTimeout(r, ms))

// Minimal browser session: cookies plus the CSRF header the frontend sends.
const jar = new Map()
async function http(method, url, { body, headers = {}, form } = {}) {
  const h = { Accept: 'application/json', ...headers }
  if (jar.size) h.Cookie = [...jar].map(([k, v]) => `${k}=${v}`).join('; ')
  if (jar.has('XSRF-TOKEN') && method !== 'GET') h['X-XSRF-TOKEN'] = csrfToken
  let payload = form
  if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body) }
  const r = await fetch(base + url, { method, headers: h, body: payload, redirect: 'manual' })
  for (const c of r.headers.getSetCookie()) { const [kv] = c.split(';'); const i = kv.indexOf('='); jar.set(kv.slice(0, i), kv.slice(i + 1)) }
  const text = await r.text()
  let data = text; try { data = JSON.parse(text) } catch { /* HTML or empty */ }
  return { status: r.status, data, text }
}
let csrfToken = ''
async function refreshCsrf() { csrfToken = (await http('GET', '/api/auth/csrf')).data.token }

// A 96×96 PNG: grey background with a red "garment" square, enough for U²-Net and GrabCut to return a mask.
function png() {
  const w = 96, h = 96, raw = Buffer.alloc((w * 3 + 1) * h)
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0
    for (let x = 0; x < w; x++) {
      const inside = x > 24 && x < 72 && y > 18 && y < 80, o = y * (w * 3 + 1) + 1 + x * 3
      raw[o] = inside ? 200 : 235; raw[o + 1] = inside ? 30 : 235; raw[o + 2] = inside ? 40 : 235
    }
  }
  const crc = buf => { let c = ~0; for (const b of buf) { c ^= b; for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xedb88320 : c >>> 1 } return ~c >>> 0 }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
    const td = Buffer.concat([Buffer.from(type), data]), c = Buffer.alloc(4); c.writeUInt32BE(crc(td))
    return Buffer.concat([len, td, c])
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
async function segment(method) {
  const form = new FormData()
  form.append('file', new Blob([png()], { type: 'image/png' }), 'smoke.png')
  return http('POST', `/api/segmentations?method=${method}`, { form })
}

async function main() {
  console.log(`Package: ${root}`)
  section('Configure')
  check(json(['config', 'set', 'PORT', String(PORT)]).status === 0, 'config set PORT')
  check(json(['config', 'set', 'CLOSET_AGENT_PORT', String(AGENT_PORT)]).status === 0, 'config set CLOSET_AGENT_PORT')
  // The full profile loads every bundled model, so the test covers all of them whatever the runner's memory is.
  json(['config', 'set', 'YUDA_PROFILE', 'full'])

  section('Environment check')
  const doctor = json(['doctor', '--verify'])
  for (const c of doctor.body.checks) console.log(`    ${c.level.padEnd(4)} ${c.group}/${c.name}: ${c.detail}`)
  check(doctor.status === 0 && doctor.body.summary.fail === 0, 'doctor --verify reports no failures', doctor.body.checks.filter(c => c.level === 'fail'))

  section('Model pressure')
  const stress = json(['stress'])
  check(['full', 'balanced', 'lite'].includes(stress.body.profile), `profile ${stress.body.profile}, peak ${stress.body.peakMb} MB of ${stress.body.totalMb} MB (${stress.body.memory.label})`)

  section('Start')
  const started = json(['start', '--no-browser'])
  check(started.status === 0 && started.body.ok, `start (${started.body.seconds ?? '-'}s)`, started.body)
  const password = json(['config', 'get', 'CLOSET_BOOTSTRAP_PASSWORD']).body.value
  check(password && password.length >= 12, 'first run generated an administrator password')
  // The config was created by "config set" before the first start, so this also covers that case.
  check(started.body.initialPassword?.password === password, 'start reports the unchanged initial password', started.body.initialPassword)
  check(json(['status']).body.initialPassword?.username === 'admin', 'status reports the unchanged initial password')
  const menuBefore = run([], { input: '0\n', timeout: 60_000 })
  check(menuBefore.stdout.includes(password), 'menu header shows the initial password', menuBefore.stdout.slice(0, 800))

  section('Services')
  const health = await http('GET', '/api/health')
  check(health.status === 200 && health.data.status === 'UP', `server health UP, version ${health.data.version}`)
  const agent = await fetch(`http://127.0.0.1:${AGENT_PORT}/health`)
  check(agent.ok, 'agent health')
  // Spring serves the welcome page only to requests that accept HTML, as a browser's do.
  const html = { Accept: 'text/html,application/xhtml+xml' }
  const page = await http('GET', '/', { headers: html })
  check(page.status === 200 && /<div id="app"|<script/i.test(page.text), 'frontend index.html is served', `${page.status} ${page.text.slice(0, 300)}`)
  const route = await http('GET', '/wardrobe', { headers: html })
  check(route.status === 200 && route.text === page.text, 'history route /wardrobe serves the app', `${route.status}`)
  const asset = /src="(\/assets\/[^"]+\.js)"/.exec(page.text)?.[1]
  if (asset) check((await fetch(base + asset)).ok, `frontend asset ${asset}`)
  const status = json(['status'])
  check(status.body.server.running && status.body.agent.running, `status: server PID ${status.body.server.pid}, agent PID ${status.body.agent.pid}`)

  section('Account')
  await refreshCsrf()
  const login = await http('POST', '/api/auth/login', { body: { username: 'admin', password } })
  check(login.status === 200 && login.data.mustChangePassword === true, 'bootstrap admin logs in and must change the password', login.data)
  await refreshCsrf()
  const newPassword = 'Smoke-Test-Password-2026'
  const changed = await http('POST', '/api/auth/password', { body: { currentPassword: password, newPassword } })
  check(changed.status < 300, 'password changed', changed.data)
  check(json(['status']).body.initialPassword === null, 'status no longer shows a password once it is changed')
  check(fs.existsSync(path.join(root, 'data', '.initial-password-changed')), 'launcher remembered the change and stops probing')
  await refreshCsrf()

  section('Local models on this platform')
  const methods = await http('GET', '/api/segmentation-methods')
  const u2net = methods.data.methods?.find(m => m.method === 'U2NET')
  check(u2net?.modelFilePresent, 'U²-Net model file found by the server', methods.data)
  const grabcut = await segment('GRABCUT')
  check(grabcut.status === 201, 'GrabCut segmentation (OpenCV native library)', grabcut.data)
  const u2 = await segment('U2NET')
  check(u2.status === 201, 'U²-Net segmentation (ONNX Runtime native library)', u2.data)
  let embedding = false
  for (let i = 0; i < 60 && !embedding; i++) { embedding = fs.readFileSync(path.join(root, 'logs', 'server.log'), 'utf8').includes('本地向量模型已加载'); if (!embedding) await sleep(1000) }
  check(embedding, 'text embedding model loaded (log line 本地向量模型已加载)')
  const monitor = json(['monitor', '3'])
  check(monitor.body.peakServerMb > 0, `monitor sampled the server at ${monitor.body.peakServerMb} MB`)

  section('Interactive menu over a pipe')
  const menu = run([], { input: '5\n8\n0\n', timeout: 60_000 })
  check(menu.status === 0 && menu.stdout.includes('运行中') && menu.stdout.includes('内存压力'), 'menu shows status and model pressure, 0 exits', menu.stdout.slice(-1500))
  check(!menu.stdout.includes(password) && !menu.stdout.includes('初始账号'), 'menu hides the changed password')
  check((await http('GET', '/api/health')).status === 200, 'menu option 0 leaves the services running')

  section('Restart')
  const restarted = json(['restart'])
  check(restarted.status === 0 && restarted.body.ok, `restart (${restarted.body.seconds ?? '-'}s)`, restarted.body)

  section('Stop')
  const stopped = json(['stop'])
  check(stopped.status === 0 && stopped.body.server === 'graceful', `server stopped gracefully (${stopped.body.server})`, stopped.body)
  check(fs.readFileSync(path.join(root, 'logs', 'server.log'), 'utf8').includes('Portable launcher requested shutdown'), 'server logged the launcher shutdown request')
  let down = false
  for (let i = 0; i < 20 && !down; i++) { try { await fetch(`${base}/api/health`) ; await sleep(500) } catch { down = true } }
  check(down, 'port released')

  section('Reset password and backup (stopped)')
  const reset = json(['reset-password', '--yes'])
  check(reset.status === 0 && reset.body.password, 'reset-password', reset.body)
  const backup = json(['backup', '--yes'])
  check(backup.status === 0 && fs.existsSync(path.join(backup.body.path, 'db')), 'backup copied the database')

  section('Start again with the reset password, then quit from the menu')
  const again = json(['start', '--no-browser'])
  check(again.body.ok && again.body.initialPassword?.password === reset.body.password, 'start reports the reset password until it is changed', again.body.initialPassword)
  jar.clear(); await refreshCsrf()
  const relogin = await http('POST', '/api/auth/login', { body: { username: 'admin', password: reset.body.password } })
  check(relogin.status === 200 && relogin.data.mustChangePassword === true, 'reset password logs in')
  const quit = run([], { input: 'q\n', timeout: 90_000 })
  check(quit.status === 0 && !json(['status']).body.server.running, 'menu option q stops the services')

  console.log('\nTimings (ms):', JSON.stringify(timings, null, 2))
}

main().catch(e => {
  console.error(`\n✘ ${e.stack || e.message}`)
  try { console.error(spawnSync(process.execPath, [launcher, 'logs', 'server', '80'], { encoding: 'utf8' }).stdout) } catch { /* no log */ }
  try { spawnSync(process.execPath, [launcher, 'stop'], { encoding: 'utf8', timeout: 90_000 }) } catch { /* best effort */ }
  process.exit(1)
})
