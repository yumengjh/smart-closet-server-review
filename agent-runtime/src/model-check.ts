// Explicit opt-in: makes exactly one paid streaming/tool call. No wardrobe data is sent.
import { ChatOpenAI } from '@langchain/openai'
import { HumanMessage, AIMessageChunk } from '@langchain/core/messages'
if (process.env.CLOSET_AGENT_LIVE_VERIFY !== 'true') throw new Error('Set CLOSET_AGENT_LIVE_VERIFY=true to authorize the live check')
const model = new ChatOpenAI({ model: process.env.CLOSET_AGENT_MODEL || 'gpt-6-luna', apiKey: process.env.CLOSET_CHECK_API_KEY, configuration: { baseURL: process.env.CLOSET_CHECK_BASE_URL }, reasoning: { effort: 'none' }, maxTokens: 256, maxRetries: 0, timeout: 90000 })
const bound = model.bindTools([{ type: 'function', function: { name: 'check_capability', description: 'A harmless capability check; does not operate any platform data.', parameters: { type: 'object', properties: { ok: { type: 'boolean' } }, required: ['ok'], additionalProperties: false } } }], { tool_choice: { type: 'function', function: { name: 'check_capability' } } })
try {
 let result: AIMessageChunk | undefined; let chunks = 0
 for await (const chunk of await bound.stream([new HumanMessage('Call check_capability with ok=true. This is only a compatibility check.')])) { chunks++; result = result ? result.concat(chunk) : chunk }
 const call = result?.tool_calls?.[0]
 if (call?.name !== 'check_capability' || call.args.ok !== true) throw new Error('tool result mismatch')
 console.log(JSON.stringify({ model: model.model, streamChunks: chunks, toolCalling: 'passed', usage: result?.usage_metadata ?? null }))
} catch { console.error('Live Agent check failed; request details were suppressed.'); process.exitCode = 1 }
