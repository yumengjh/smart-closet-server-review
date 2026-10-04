import test from 'node:test'
import assert from 'node:assert/strict'
import { AvatarPrefix, type AvatarCue } from './avatar.js'
function parse(parts:string[]){let text='';const cues:AvatarCue[]=[];const p=new AvatarPrefix(s=>text+=s,c=>cues.push(c));for(const part of parts)p.push(part);p.finish();return {text,cues}}
test('all split boundaries preserve text and emit exactly one valid cue',()=>{const s='<avatar>{"state":"wink","expression":"happy"}</avatar>你好呀';for(let i=0;i<=s.length;i++){const r=parse([s.slice(0,i),s.slice(i)]);assert.equal(r.text,'你好呀');assert.deepEqual(r.cues,[{state:'wink',expression:'happy'}])}})
test('ordinary text and partial matching prefixes are unchanged',()=>{for(const s of ['你好','<a href="/help">帮助</a>','<ava','  普通文字'])assert.equal(parse([s]).text,s)})
test('invalid enum and malformed metadata are stripped without losing the answer',()=>{for(const value of ['{"state":"evil"}','not-json']){const r=parse(['<avatar>'+value+'</avatar>正常回复']);assert.equal(r.text,'正常回复');assert.equal(r.cues.length,0)}})
test('unterminated control line is dropped while the answer survives',()=>{assert.equal(parse(['<avatar>{"state":"wink"}\n正常回复']).text,'正常回复');assert.equal(parse(['<avatar>{']).text,'')})
test('missing expression defaults to neutral',()=>{assert.deepEqual(parse(['<avatar>{"state":"egg"}</avatar>好']).cues,[{state:'egg',expression:'neutral'}])})

test('oversized control metadata stays hidden across chunks',()=>{const header='<avatar>'+JSON.stringify({state:'wink',expression:'happy',extra:'x'.repeat(600)});const r=parse([header,'</ava','tar>正常回复']);assert.equal(r.text,'正常回复');assert.equal(r.cues.length,0)})
