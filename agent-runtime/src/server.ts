import { createServer } from 'node:http'
import { createHash, timingSafeEqual } from 'node:crypto'
import { runAgent, type RunInput } from './graph.js'

const secret = process.env.CLOSET_AGENT_SECRET ?? ''
if (secret.length < 32) throw new Error('CLOSET_AGENT_SECRET must contain at least 32 characters')
const digest = (value: string) => createHash('sha256').update(value).digest()
const server = createServer(async (req, res) => {
  if (req.url === '/health' && req.method === 'GET') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"status":"ok","framework":"langgraph"}'); return }
  if (req.url !== '/run' || req.method !== 'POST') { res.writeHead(404); res.end(); return }
  if (!timingSafeEqual(digest(String(req.headers['x-agent-secret'] ?? '')), digest(secret))) { res.writeHead(403); res.end(); return }
  const abort = new AbortController()
  res.on('close', () => abort.abort())
  let timer: NodeJS.Timeout | undefined
  try {
    const chunks: Buffer[] = []; let size = 0
    for await (const chunk of req) { size += chunk.length; if (size > 2_000_000) { res.writeHead(413); res.end(); return }; chunks.push(chunk) }
    const input = JSON.parse(Buffer.concat(chunks).toString('utf8')) as RunInput
    if (!input.model || !input.apiKey || !Array.isArray(input.messages) || input.messages.length > 20 || !Number.isInteger(input.maxToolCalls) || input.maxToolCalls < 1 || input.maxToolCalls > 20) throw new Error('Invalid run input')
    const callback = new URL(input.callbackURL)
    if (!['http:', 'https:'].includes(callback.protocol)) throw new Error('Invalid callback')
    timer = setTimeout(() => abort.abort(), Math.min(300, input.timeoutSeconds) * 1000)
    res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Cache-Control': 'no-store' }); res.flushHeaders()
    await runAgent(input, frame => { if (!res.destroyed) res.write(JSON.stringify(frame) + '\n') }, abort.signal)
  } catch {
    // Provider SDK errors can contain request details; never log or relay those objects.
    if (!res.headersSent) res.writeHead(400, { 'Content-Type': 'application/x-ndjson' })
    if (!res.destroyed) res.write(JSON.stringify({ event: 'error', data: { code: 'AGENT_RUNTIME_ERROR', message: '模型或工具请求未完成。' } }) + '\n')
  } finally { if (timer) clearTimeout(timer); res.end() }
})
server.listen(Number(process.env.CLOSET_AGENT_PORT ?? 8091), process.env.CLOSET_AGENT_HOST ?? '127.0.0.1', () => console.log('LangGraph agent runtime ready'))
for (const signal of ['SIGTERM', 'SIGINT'] as const) process.on(signal, () => { server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 5000).unref() })
