import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer, type Server } from 'node:http'
import { definitions, systemPrompt, runAgent, type Frame } from './graph.js'

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
      assert.equal(inputs[0].detail.promptVersion, 'platform-agent-v4')
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
      if (++builds === 1) { res.statusCode = 400; res.end(JSON.stringify({ message: '理由提到了这套搭配里没有的：米色' })) }
      else res.end(JSON.stringify({ runId: ids[0], shown: [], presented: { mode: 'CARDS', proposal: { id: 'p-1', kind: 'RECOMMENDATION', payload: {} } } }))
      return
    }
    requests.push(body)
    if (requests.length === 2) assert.match(body.messages.find((m: any) => m.role === 'tool').content, /米色/)
    const delta = requests.length < 3
      ? { role: 'assistant', tool_calls: [{ index: 0, id: `present-${requests.length}`, type: 'function', function: { name: 'present_outfits', arguments: JSON.stringify({ runId: ids[0], choices: [{ outfitKey: ids[1], reason: requests.length === 1 ? '米色更温柔' : '' }] }) } }] }
      : { role: 'assistant', content: '已换成你要的那套。' }
    res.setHeader('Content-Type', 'text/event-stream')
    res.end('data: ' + JSON.stringify({ id: 'outfit-test', object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\ndata: [DONE]\n\n')
  })
  const url = await listen(server)
  try {
    await runAgent({ model: 'gpt-6-luna', apiKey: 'fake', baseURL: url + '/v1', permitURL: url + '/permits', callbackURL: url + '/callback', callbackToken: 'test', maxToolCalls: 5, timeoutSeconds: 5, context: { route: '/wardrobe' }, memories: [], messages: [{ role: 'user', content: '搭一套' }] }, frame => frames.push(frame), AbortSignal.timeout(10000))
    assert.equal(builds, 2); assert.equal(requests.length, 3)
    assert.equal(frames.filter(f => f.event === 'error').length, 0)
    assert.equal(frames.filter(f => f.event === 'proposal').length, 1)
    assert.equal(frames.at(-1)?.event, 'done')
  } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
})

test('trace captures every actual model input and tool correlation without adding model calls', async () => { await exercise(8, false, 'basic', true) })

test('recommendation takes two model calls, shows the card and never offers the retired candidate tools', async () => {
  const runId = '11111111-1111-4111-8111-111111111111'; let requests = 0; const callbacks: string[] = []; const frames: Frame[] = []
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const chunk of req) raw += chunk; const body = JSON.parse(raw)
    if(req.url === '/permits'){assert.ok(['acquire','dispatch','heartbeat','finish'].includes(body.action));res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'GRANTED',permit:'fixture-'+body.iteration}));return}
    if (req.url === '/callback') {
      callbacks.push(body.name); res.setHeader('Content-Type', 'application/json')
      assert.equal(body.args.intent.occasion, 'COMMUTE'); assert.equal(body.args.intent.temperatureC, 12)
      res.end(JSON.stringify({ runId, intent: '通勤 · 12°C', shown: [{ rank: 1, name: '白+黑·通勤', items: ['白衬衫（白）'], highlights: ['黑白经典'] }], alternatives: [],
        presented: { mode: 'CARDS', proposal: { id: 'p-1', kind: 'RECOMMENDATION', payload: { runId, candidates: [{ name: '白+黑·通勤', garmentIds: ['g1', 'g2'] }] } } } }))
      return
    }
    requests++
    const names = body.tools?.map((t: any) => t.function.name) || []
    if (requests === 1) { assert.ok(names.includes('recommend_outfits')); for (const retired of ['get_outfit_candidates', 'build_outfits', 'propose_outfits']) assert.ok(!names.includes(retired)) }
    if (requests === 2) { const tool = body.messages.find((m: any) => m.role === 'tool').content; assert.match(tool, /推荐卡片已展示/); assert.doesNotMatch(tool, /"payload"/) }
    const delta = requests === 1
      ? { role: 'assistant', tool_calls: [{ index: 0, id: 'recommend-1', type: 'function', function: { name: 'recommend_outfits', arguments: JSON.stringify({ intent: { occasion: 'COMMUTE', temperatureC: 12 } }) } }] }
      : { role: 'assistant', content: '给你挑了三套通勤搭配，第一套黑白经典。' }
    res.setHeader('Content-Type', 'text/event-stream'); res.end('data: ' + JSON.stringify({ id: 'recommend', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\ndata: [DONE]\n\n')
  })
  const url = await listen(server)
  try {
    await runAgent({ model: 'gpt-6-luna', apiKey: 'fake', baseURL: url + '/v1', permitURL: url + '/permits', callbackURL: url + '/callback', callbackToken: 'test', maxToolCalls: 5, timeoutSeconds: 5, context: { route: '/wardrobe' }, memories: [], messages: [{ role: 'user', content: '明天 12 度，帮我搭一套通勤' }] }, f => frames.push(f), AbortSignal.timeout(10000))
    assert.equal(requests, 2); assert.deepEqual(callbacks, ['recommend_outfits'])
    assert.equal(frames.filter(f => f.event === 'proposal').length, 1)
  } finally { server.closeAllConnections(); await new Promise<void>(resolve => server.close(() => resolve())) }
})

test('read-only binding rejects a fabricated write without a callback and counts the attempt', async () => {
  let models=0;let callbacks=0;const frames:Frame[]=[]
  const server=createServer(async(req,res)=>{
    let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw)
    res.setHeader('Content-Type','application/json')
    if(req.url==='/permits'){res.end(JSON.stringify({status:'GRANTED',permit:'local-only'}));return}
    if(req.url==='/callback'){callbacks++;res.end('{}');return}
    models++
    if(models===1)assert.deepEqual(body.tools.map((t:any)=>t.function.name),['closet_summary'])
    else assert.equal(body.tools,undefined)
    const delta=models===1?{role:'assistant',tool_calls:[{index:0,id:'blocked',type:'function',function:{name:'build_outfits',arguments:'{}'}}]}:{role:'assistant',content:'此轮只查询，不创建搭配。'}
    res.setHeader('Content-Type','text/event-stream');res.end('data: '+JSON.stringify({id:'readonly',choices:[{index:0,delta,finish_reason:null}]})+'\n\ndata: [DONE]\n\n')
  })
  const url=await listen(server)
  try{await runAgent({model:'gpt-6-luna',apiKey:'fake',baseURL:url+'/v1',permitURL:url+'/permits',callbackURL:url+'/callback',callbackToken:'local',maxToolCalls:1,maxModelCalls:2,allowedTools:['closet_summary'],timeoutSeconds:5,context:{route:'/wardrobe'},memories:[],messages:[{role:'user',content:'我有多少衣服'}]},f=>frames.push(f),AbortSignal.timeout(10000));assert.equal(models,2);assert.equal(callbacks,0);assert.deepEqual(frames.at(-1)?.data,{toolCalls:1,usage:null})}
  finally{server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()))}
})

test('memory epoch change ends the turn without another model call or a second action',async()=>{
  let models=0;const callbacks:string[]=[]
  const server=createServer(async(req,res)=>{
    let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw)
    res.setHeader('Content-Type','application/json')
    if(req.url==='/permits'){res.end(JSON.stringify({status:'GRANTED',permit:'local-only'}));return}
    if(req.url==='/callback'){callbacks.push(body.name);res.end(JSON.stringify({contextChanged:true,status:'SUCCEEDED'}));return}
    models++;const delta={role:'assistant',tool_calls:[{index:0,id:'memory',type:'function',function:{name:'remember_preference',arguments:'{"content":"我喜欢蓝色","category":"COLOR","evidence":"记住我喜欢蓝色"}'}},{index:1,id:'later',type:'function',function:{name:'search_help',arguments:'{"query":"搭配"}'}}]}
    res.setHeader('Content-Type','text/event-stream');res.end('data: '+JSON.stringify({id:'memory',choices:[{index:0,delta,finish_reason:null}]})+'\n\ndata: [DONE]\n\n')
  })
  const url=await listen(server)
  try{await runAgent({model:'gpt-6-luna',apiKey:'fake',baseURL:url+'/v1',permitURL:url+'/permits',callbackURL:url+'/callback',callbackToken:'local',maxToolCalls:5,allowedTools:['remember_preference','search_help'],timeoutSeconds:5,context:{route:'/wardrobe'},memories:[],messages:[{role:'user',content:'记住我喜欢蓝色'}]},()=>{},AbortSignal.timeout(10000));assert.equal(models,1);assert.deepEqual(callbacks,['remember_preference'])}
  finally{server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()))}
})

test('recommendation follow-up schema inherits only when explicitly requested', () => {
  const definition = definitions.find(d => d.name === 'recommend_outfits')!
  assert.equal(definition.schema.parse({}).basedOnPrevious, false)
  assert.equal(definition.schema.parse({ basedOnPrevious: true }).count, undefined)
  assert.equal(definition.schema.parse({ count: 2 }).count, 2)
  assert.equal(definition.schema.parse({ basedOnPrevious: true, intent: { avoidColors: [] } }).basedOnPrevious, true)
  assert.match(definition.description, /basedOnPrevious=true/)
  assert.match(definition.description, /本轮明确改变的条件/)
  assert.match(definition.description, /排除上轮展示衣物/)
  assert.match(systemPrompt, /设置 basedOnPrevious=true/)
})
test('closed tools explain a concrete request instead of sending the user to another page', () => {
  assert.match(systemPrompt, /工具本轮未开放时，不要让用户“去别的页面”/)
  assert.match(systemPrompt, /帮我重新搭配一套通勤穿的/)
})

test('follow-up forwards basedOnPrevious and traces the v3 prompt without opening a socket', async () => {
  const originalFetch = globalThis.fetch; const requests: any[] = []; const callbacks: any[] = []; const traces: any[] = []; const frames: Frame[] = []
  globalThis.fetch = async (input, init) => {
    const url = String(input); const body = JSON.parse(String(init?.body))
    if (url.endsWith('/permits')) return Response.json({ status: 'GRANTED', permit: 'fixture' })
    if (url.endsWith('/callback')) {
      if (body.name === '__trace') { traces.push(body.args); return Response.json({}) }
      callbacks.push(body)
      return Response.json({ runId: '11111111-1111-4111-8111-111111111111', intent: '通勤 · 18°C', shown: [], alternatives: [],
        presented: { mode: 'CARDS', proposal: { id: 'next-card', kind: 'RECOMMENDATION', payload: {} } } })
    }
    requests.push(body)
    const delta = requests.length === 1
      ? { role: 'assistant', tool_calls: [{ index: 0, id: 'follow-up', type: 'function', function: { name: 'recommend_outfits', arguments: JSON.stringify({ basedOnPrevious: true, intent: { avoidColors: ['红色'] } }) } }] }
      : { role: 'assistant', content: '继续按通勤条件换一批。' }
    return new Response('data: ' + JSON.stringify({ id: 'follow-up', object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason: null }] }) + '\n\ndata: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream' } })
  }
  try {
    await runAgent({ model: 'fixture', apiKey: 'fake', baseURL: 'http://fixture.invalid/v1', permitURL: 'http://fixture.invalid/permits', callbackURL: 'http://fixture.invalid/callback', callbackToken: 'fixture', maxToolCalls: 5, timeoutSeconds: 5, allowedTools: ['recommend_outfits'], traceEnabled: true, context: {}, memories: [], messages: [{ role: 'assistant', content: '上一轮推荐：通勤 · 18°C' }, { role: 'user', content: '重新搭配，避开红色' }] }, f => frames.push(f), AbortSignal.timeout(10000))
    assert.equal(requests.length, 2); assert.equal(callbacks.length, 1)
    assert.equal(callbacks[0].args.basedOnPrevious, true); assert.deepEqual(callbacks[0].args.intent, { avoidColors: ['红色'] })
    const schema = requests[0].tools.find((t: any) => t.function.name === 'recommend_outfits').function
    assert.equal(schema.parameters.properties.basedOnPrevious.type, 'boolean'); assert.match(schema.description, /basedOnPrevious=true/)
    assert.equal(traces.find(t => t.phase === 'input').detail.promptVersion, 'platform-agent-v4')
    assert.equal(frames.filter(f => f.event === 'proposal').length, 1)
  } finally { globalThis.fetch = originalFetch }
})
