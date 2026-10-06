import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer, type Server } from 'node:http'
import { runAgent, type Frame } from './graph.js'

async function listen(server: Server) {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`
}
async function exercise(budget = 8, navigation = false, avatarMode?: string, traceEnabled = false) {
  const requests: any[] = []; const callbacks: any[] = []; const frames: Frame[] = []; const traces: any[] = []
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk
    const body = JSON.parse(raw)
    if(req.url === '/permits'){assert.ok(['acquire','dispatch','heartbeat','finish'].includes(body.action));res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'GRANTED',permit:'fixture-'+body.iteration}));return}
    if (req.url === '/callback') {
      assert.equal(req.headers.authorization, 'Bearer turn-only-token'); if (body.name === '__trace') { traces.push(body.args); res.setHeader('Content-Type', 'application/json'); res.end('{}'); return }; callbacks.push(body)
      res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body.name === 'navigate' ? { path: '/help' } : { known: false, articles: [], unknownAnswer: '暂时不知道' })); return
    }
    assert.equal(req.url, '/v1/chat/completions'); requests.push(body)
    assert.equal(body.reasoning_effort, 'low'); assert.equal(body.stream, true)
    res.setHeader('Content-Type', 'text/event-stream')
    const delta = requests.length === 1 ? { role: 'assistant', tool_calls: [{ index: 0, id: 'call_help', type: 'function', function: { name: 'search_help', arguments: '{"query":"天气预报功能"}' } }] } : navigation && requests.length === 2 ? { role: 'assistant', tool_calls: [{ index: 0, id: 'call_nav', type: 'function', function: { name: 'navigate', arguments: '{\"path\":\"/help\"}' } }] } : { role: 'assistant', content: (avatarMode==='personal'?'<avatar>{"state":"wink","expression":"happy"}</avatar>':'')+'暂时不知道，可以向平台反馈。' }
    res.write('data: ' + JSON.stringify({ id: 'chat-test', object: 'chat.completion.chunk', model: 'gpt-6-luna', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\n')
    res.write('data: ' + JSON.stringify({ id: 'chat-test', object: 'chat.completion.chunk', model: 'gpt-6-luna', choices: [{ index: 0, delta: {}, finish_reason: requests.length === 1 ? 'tool_calls' : 'stop' }] }) + '\n\n')
    res.end('data: [DONE]\n\n')
  })
  const url = await listen(server)
  try {
    await runAgent({ traceEnabled, avatarMode, model: 'gpt-6-luna', apiKey: 'fake-key', baseURL: url + '/v1', permitURL: url + '/permits', callbackURL: url + '/callback', callbackToken: 'turn-only-token', maxToolCalls: budget, timeoutSeconds: 5, context: { route: '/wardrobe' }, memories: [], messages: [{ role: 'user', content: '天气预报怎么用？' }] }, f => frames.push(f), AbortSignal.timeout(10000))
    assert.equal(requests.length, navigation ? 3 : 2); assert.equal(callbacks.length, navigation ? 2 : 1)
    const returned = requests[1].messages.find((m: any) => m.role === 'tool')
    assert.equal(JSON.parse(returned.content).known, false)
    assert.deepEqual(frames.find(f => f.event === 'sources')?.data, { articles: [] })
    assert.match(JSON.stringify(frames.find(f => f.event === 'token')), /暂时不知道/)
    assert.deepEqual(frames.at(-1), { event: 'done', data: { toolCalls: navigation ? 2 : 1, usage: null } })
    if(avatarMode==='personal'){assert.equal(frames.filter(f=>f.event==='avatar').length,1);assert.deepEqual(frames.find(f=>f.event==='avatar')?.data,{state:'wink',expression:'happy'});assert.ok(requests[0].messages[0].content.includes('<avatar>'));assert.ok(!JSON.stringify(frames.filter(f=>f.event==='token')).includes('<avatar>'))}
    else {assert.equal(frames.filter(f=>f.event==='avatar').length,0);assert.ok(!requests[0].messages[0].content.includes('<avatar>'))}
    if (traceEnabled) {
      assert.equal(traces.filter(t => t.phase === 'input').length, requests.length)
      assert.equal(traces.filter(t => t.phase === 'model_output').length, requests.length)
      const inputs = traces.filter(t => t.phase === 'input')
      assert.equal(inputs[0].detail.messages[0].content, requests[0].messages[0].content)
      assert.equal(inputs[0].detail.tools[0].parameters.type, 'object')
      assert.equal(inputs[1].detail.messages.find((m: any) => m.role === 'tool').content, returned.content)
      assert.equal(callbacks[0].callId, 'call_help')
      assert.ok(!JSON.stringify(traces).includes('fake-key')); assert.ok(!JSON.stringify(traces).includes('turn-only-token'))
    }
    return requests
  } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
}
test('LangGraph streams real tool results and final reply', async () => { await exercise() })
test('last model call loses tools when tool budget is exhausted', async () => { const requests = await exercise(1); assert.ok(requests[0].tools.length > 0); assert.equal(requests[1].tools, undefined) })

test('reused upstream completion IDs do not skip later tools', async () => { await exercise(8, true) })

test("personal expression does not add requests or tool calls",async()=>{await exercise(8,false,"personal")})
test("basic mode excludes the expression prompt",async()=>{await exercise(8,false,"basic")})

test('a rejected outfit returns its cause to the model and can be corrected without ending the run', async () => {
  const requests: any[] = []; const frames: Frame[] = []; let builds = 0
  const ids = ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk
    const body = JSON.parse(raw)
    if(req.url === '/permits'){assert.ok(['acquire','dispatch','heartbeat','finish'].includes(body.action));res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'GRANTED',permit:'fixture-'+body.iteration}));return}
    if (req.url === '/callback') {
      res.setHeader('Content-Type', 'application/json')
      if (++builds === 1) { res.statusCode = 400; res.end(JSON.stringify({ message: '一套日常搭配最多一件外搭' })) }
      else res.end(JSON.stringify({ status: 'COMPLETE', message: '尚未保存' }))
      return
    }
    requests.push(body)
    if (requests.length === 2) assert.match(body.messages.find((m: any) => m.role === 'tool').content, /最多一件外搭/)
    const delta = requests.length < 3
      ? { role: 'assistant', tool_calls: [{ index: 0, id: `build-${requests.length}`, type: 'function', function: { name: 'build_outfits', arguments: JSON.stringify({ candidates: [{ name: '基础搭配', reason: '修正冲突', candidateId: ids[requests.length === 1 ? 0 : 1] }] }) } }] }
      : { role: 'assistant', content: '已修正外搭冲突，临时搭配尚未保存。' }
    res.setHeader('Content-Type', 'text/event-stream')
    res.end('data: ' + JSON.stringify({ id: 'outfit-test', object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\ndata: [DONE]\n\n')
  })
  const url = await listen(server)
  try {
    await runAgent({ model: 'gpt-6-luna', apiKey: 'fake', baseURL: url + '/v1', permitURL: url + '/permits', callbackURL: url + '/callback', callbackToken: 'test', maxToolCalls: 5, timeoutSeconds: 5, context: { route: '/wardrobe' }, memories: [], messages: [{ role: 'user', content: '搭一套' }] }, frame => frames.push(frame), AbortSignal.timeout(10000))
    assert.equal(builds, 2); assert.equal(requests.length, 3)
    assert.equal(frames.filter(f => f.event === 'error').length, 0)
    assert.equal(frames.at(-1)?.event, 'done')
  } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
})

test('trace captures every actual model input and tool correlation without adding model calls', async () => { await exercise(8, false, 'basic', true) })

test('outfit selection uses server candidate IDs without arbitrary garment composition or retry', async () => {
  const candidateId = '11111111-1111-4111-8111-111111111111'; let requests = 0; const callbacks: string[] = []
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk; const body = JSON.parse(raw)
    if(req.url === '/permits'){assert.ok(['acquire','dispatch','heartbeat','finish'].includes(body.action));res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'GRANTED',permit:'fixture-'+body.iteration}));return}
    if (req.url === '/callback') {
      callbacks.push(body.name); res.setHeader('Content-Type', 'application/json')
      if (body.name === 'get_outfit_candidates') res.end(JSON.stringify({ candidates: [{ candidateId, garmentIds: ['g1', 'g2'] }], garments: [], ruleVersion: 'daily-evidence-v3' }))
      else { assert.equal(body.args.candidates[0].candidateId, candidateId); assert.equal(body.args.candidates[0].garmentIds, undefined); res.end(JSON.stringify({ status: 'COMPLETE' })) }
      return
    }
    requests++
    const schema = body.tools.find((t: any) => t.function.name === 'build_outfits').function.parameters.properties.candidates.items.properties
    assert.ok(schema.candidateId); assert.equal(schema.garmentIds, undefined)
    const name = requests === 1 ? 'get_outfit_candidates' : 'build_outfits'
    const args = requests === 1 ? { requiredGarmentIds: [] } : { candidates: [{ candidateId, name: '通勤', reason: '从合法候选选择' }] }
    const delta = requests < 3 ? { role: 'assistant', tool_calls: [{ index: 0, id: `candidate-${requests}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] } : { role: 'assistant', content: '已搭建，尚未保存。' }
    res.setHeader('Content-Type', 'text/event-stream'); res.end('data: ' + JSON.stringify({ id: 'selection', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\ndata: [DONE]\n\n')
  })
  const url = await listen(server)
  try {
    await runAgent({ model: 'gpt-6-luna', apiKey: 'fake', baseURL: url + '/v1', permitURL: url + '/permits', callbackURL: url + '/callback', callbackToken: 'test', maxToolCalls: 5, timeoutSeconds: 5, context: { route: '/wardrobe' }, memories: [], messages: [{ role: 'user', content: '搭一套' }] }, () => {}, AbortSignal.timeout(10000))
    assert.equal(requests, 3); assert.deepEqual(callbacks, ['get_outfit_candidates', 'build_outfits'])
  } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
})
