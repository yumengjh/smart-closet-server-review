const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const code=fs.readFileSync('src/main/resources/static/workflow/app.js','utf8');
class Element {
  constructor(tag='div'){this.tag=tag;this.children=[];this.value='';this.hidden=false;this.disabled=false;this.className='';this.events={};this.classList={toggle(){}};}
  append(...items){this.children.push(...items);}
  replaceChildren(...items){this.children=items;}
  addEventListener(name,fn){this.events[name]=fn;}
}
const elements=new Map(),storage=new Map();const $=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
const ctx={$,document:{createElement:tag=>new Element(tag),querySelectorAll:()=>[]},localStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},crypto:{randomUUID:()=> 'same-key'},setTimeout:()=>1,clearTimeout(){},console};
vm.createContext(ctx);
vm.runInContext(`let currentJob=null,outputSignature=null,actionBusy=false,viewEpoch=0,historyEpoch=0,historyPage=1,historyTotal=0,timer;const states={SUCCEEDED:'已完成',UNKNOWN:'调用结果未知'};const labels={GENERATE:'生成',STICKER:'贴纸'};function status(){}function errorMessage(e){return e.message;}function needsAiReview(j){return j.requiresReview;}function renderDecisions(){};`,ctx);
vm.runInContext(code.slice(code.indexOf('function render(job)'),code.indexOf('const actions =')),ctx);
vm.runInContext(code.slice(code.indexOf('function selectJob('),code.indexOf("$('history-filter').addEventListener")),ctx);
const job={id:'job-1',status:'SUCCEEDED',steps:[],requiresReview:true,selectedAssetId:'asset-2',assets:[{id:'asset-1',kind:'INPUT',url:'/input.png',width:100,height:100,reviewStatus:'PENDING'},{id:'asset-2',kind:'STICKER',url:'/step-1.png',width:100,height:100,reviewStatus:'APPROVED'}],attempts:[{stepIndex:0,provider:'stub',model:'test',status:'UNKNOWN'}]};
ctx.render(job);assert.equal($('outputs').children.length,2);assert.match($('outputs').children[1].className,/chosen/);assert.equal($('review').hidden,true);assert.equal($('cancel-job').hidden,true);assert.equal($('continue-options').hidden,false);assert.equal($('attempt-details').hidden,false);assert.match($('attempts').children[0].textContent,/可能已计费/);
const previous=$('outputs').children[0];ctx.render(job);assert.equal($('outputs').children[0],previous,'unchanged polling preserves DOM');
ctx.render({...job,status:'CANCEL_REQUESTED'});assert.equal($('cancel-job').disabled,true);
(async()=>{
  // Switching tasks must prevent a delayed response from rendering an older selection.
  let finishOld,finishNew;ctx.api=url=>new Promise(resolve=>{if(url.endsWith('/old'))finishOld=resolve;else finishNew=resolve;});
  vm.runInContext('loadHistory=async()=>{}',ctx);
  ctx.selectJob('old');ctx.selectJob('new');finishNew({...job,id:'new'});await new Promise(r=>setImmediate(r));finishOld({...job,id:'old'});await new Promise(r=>setImmediate(r));
  assert.equal(vm.runInContext('currentJob.id',ctx),'new');
  // Failed continuation keeps its idempotency key; deliberate later success can start a new task.
  $('continue-policy').value='FORCE';$('continue-method').value='GRABCUT';
  const sent=[];ctx.api=async(url,options)=>{sent.push({url,options});throw Error('lost response');};
  await ctx.continueAsset(job,job.assets[1]);await ctx.continueAsset(job,job.assets[1]);
  assert.equal(sent.length,2);assert.equal(sent[0].options.headers['Idempotency-Key'],sent[1].options.headers['Idempotency-Key']);
  assert.equal(JSON.parse(sent[0].options.body).assetId,'asset-2');assert.equal(JSON.parse(sent[0].options.body).method,'GRABCUT');
  assert(!sent.some(r=>r.url==='/api/workflows'),'continuation uses dedicated local-only endpoint');
  console.log('Workflow UI: asset actions, selection, cancellation, stale-response protection and continuation idempotency passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
