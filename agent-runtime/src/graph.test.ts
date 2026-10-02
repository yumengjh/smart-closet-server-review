import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer, type Server } from 'node:http'
import { runAgent, type Frame } from './graph.js'

async function listen(server: Server) {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`
}
async function exercise(budget = 8, navigation = false, avatarMode?: string) {
  const requests: any[] = []; const callbacks: any[] = []; const frames: Frame[] = []
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk
    const body = JSON.parse(raw)
    if (req.url === '/callback') {
      assert.equal(req.headers.authorization, 'Bearer turn-only-token'); callbacks.push(body)
      res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body.name === 'navigate' ? { path: '/help' } : { known: false, articles: [], unknownAnswer: '暂时不知道' })); return
    }
    assert.equal(req.url, '/v1/chat/completions'); requests.push(body)
    assert.equal(body.reasoning_effort, 'none'); assert.equal(body.stream, true)
    res.setHeader('Content-Type', 'text/event-stream')
    const delta = requests.length === 1 ? { role: 'assistant', tool_calls: [{ index: 0, id: 'call_help', type: 'function', function: { name: 'search_help', arguments: '{"query":"天气预报功能"}' } }] } : navigation && requests.length === 2 ? { role: 'assistant', tool_calls: [{ index: 0, id: 'call_nav', type: 'function', function: { name: 'navigate', arguments: '{\"path\":\"/help\"}' } }] } : { role: 'assistant', content: (avatarMode==='personal'?'<avatar>{"state":"wink","expression":"happy"}</avatar>':'')+'暂时不知道，可以向平台反馈。' }
    res.write('data: ' + JSON.stringify({ id: 'chat-test', object: 'chat.completion.chunk', model: 'gpt-6-luna', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\n')
    res.write('data: ' + JSON.stringify({ id: 'chat-test', object: 'chat.completion.chunk', model: 'gpt-6-luna', choices: [{ index: 0, delta: {}, finish_reason: requests.length === 1 ? 'tool_calls' : 'stop' }] }) + '\n\n')
    res.end('data: [DONE]\n\n')
  })
  const url = await listen(server)
  try {
    await runAgent({ avatarMode, model: 'gpt-6-luna', apiKey: 'fake-key', baseURL: url + '/v1', callbackURL: url + '/callback', callbackToken: 'turn-only-token', maxToolCalls: budget, timeoutSeconds: 5, context: { route: '/wardrobe' }, memories: [], messages: [{ role: 'user', content: '天气预报怎么用？' }] }, f => frames.push(f), AbortSignal.timeout(10000))
    assert.equal(requests.length, navigation ? 3 : 2); assert.equal(callbacks.length, navigation ? 2 : 1)
    const returned = requests[1].messages.find((m: any) => m.role === 'tool')
    assert.equal(JSON.parse(returned.content).known, false)
    assert.deepEqual(frames.find(f => f.event === 'sources')?.data, { articles: [] })
    assert.match(JSON.stringify(frames.find(f => f.event === 'token')), /暂时不知道/)
    assert.deepEqual(frames.at(-1), { event: 'done', data: { toolCalls: navigation ? 2 : 1, usage: null } })
    if(avatarMode==='personal'){assert.equal(frames.filter(f=>f.event==='avatar').length,1);assert.deepEqual(frames.find(f=>f.event==='avatar')?.data,{state:'wink',expression:'happy'});assert.ok(requests[0].messages[0].content.includes('<avatar>'));assert.ok(!JSON.stringify(frames.filter(f=>f.event==='token')).includes('<avatar>'))}
    else {assert.equal(frames.filter(f=>f.event==='avatar').length,0);assert.ok(!requests[0].messages[0].content.includes('<avatar>'))}
    return requests
  } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
}
test('LangGraph streams real tool results and final reply', async () => { await exercise() })
test('last model call loses tools when tool budget is exhausted', async () => { const requests = await exercise(1); assert.ok(requests[0].tools.length > 0); assert.equal(requests[1].tools, undefined) })

test('reused upstream completion IDs do not skip later tools', async () => { await exercise(8, true) })

test("personal expression does not add requests or tool calls",async()=>{await exercise(8,false,"personal")})
test("basic mode excludes the expression prompt",async()=>{await exercise(8,false,"basic")})
