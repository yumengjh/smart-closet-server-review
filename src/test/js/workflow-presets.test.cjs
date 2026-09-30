const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const code=fs.readFileSync('src/main/resources/static/workflow/app.js','utf8');
const fn=code.slice(code.indexOf('function buildSteps()'),code.indexOf('function status('));
const controls={preset:{value:'before'},precut:{checked:false},postcut:{checked:true},policy:{value:'AUTO'},params:{value:'{}'},provider:{value:'test'},model:{value:'test-image'},prompt:{value:'shirt'},intent:{value:'faithful'},'intent-option':{},'restage-note':{},'mixed-note':{},steps:{value:'custom JSON'}};
const context={$:id=>controls[id]};vm.createContext(context);vm.runInContext(code.slice(code.indexOf('const promptPresets ='),code.indexOf('async function api(')),context);vm.runInContext(fn,context);
vm.runInContext('globalThis.presets=promptPresets',context);
function types(){return Array.from(context.buildSteps(),s=>s.type)}
assert.deepEqual(types(),['GENERATE','ENSURE_TRANSPARENCY','STICKER']);
controls.precut.checked=true;assert.deepEqual(types(),['SEGMENT','GENERATE','ENSURE_TRANSPARENCY','STICKER']);
controls.preset.value='text';assert.deepEqual(types(),['GENERATE','ENSURE_TRANSPARENCY','STICKER']);
controls.preset.value='mixed';assert.equal(types().length,6);
controls.preset.value='local';assert.deepEqual(types(),['ENSURE_TRANSPARENCY','STICKER']);
controls.policy.value='PRESERVE';assert.equal(context.buildSteps()[0].policy,'PRESERVE');
const html=fs.readFileSync('src/main/resources/static/workflow/index.html','utf8');
assert(!html.match(/<input[^>]*id="precut"[^>]*>/)[0].includes('checked'));
console.log('7 preset/default checks passed');

const diagnostics=code.slice(code.indexOf('function errorMessage('),code.indexOf('function render('));
vm.runInContext(diagnostics,context);
assert(context.errorMessage({code:'PROVIDER_HTTP_504',message:'请核对余额'}).includes('网关等待上游超时'));
assert(!context.errorMessage({code:'PROVIDER_HTTP_504',message:'请核对余额'}).includes('余额'));
assert.equal(context.errorMessage({code:'TRANSPARENCY_NEEDS_REVIEW',message:'检查透明度'}),'检查透明度');
console.log('3 historical error display checks passed');

// Mode changes must retain user edits and never overwrite advanced JSON steps.
controls.preset.value='before';context.syncPrompt();
const faithful=controls.prompt.value;
assert(faithful.includes('只修改背景'));
assert(faithful.includes('人物'));
assert(faithful.includes('非主体衣物'));
assert(faithful.includes('内衬'));
assert(faithful.includes('全部扣子'));
assert(context.presets.text.includes('第二件衣物'));
assert(context.presets.text.includes('领口朝上'));
controls.prompt.value='保留我写的要求';
controls.intent.value='restage';context.syncPrompt();
assert.equal(controls['restage-note'].hidden,false);
assert(controls.prompt.value.includes('唯一一件主体衣物'));
assert(controls.prompt.value.includes('正上方平铺拍摄角度'));
assert(controls.prompt.value.includes('结构褶皱'));
assert(controls.prompt.value.includes('扣合状态'));
controls.prompt.value='我的平铺要求';
controls.preset.value='text';context.syncPrompt();
assert.equal(controls['intent-option'].hidden,true);
assert(controls.prompt.value.startsWith('生成一件'));
controls.prompt.value='我的文生图要求';
controls.preset.value='before';context.syncPrompt();
assert.equal(controls.prompt.value,'我的平铺要求');
controls.intent.value='faithful';context.syncPrompt();
assert.equal(controls.prompt.value,'保留我写的要求');
assert.equal(controls['restage-note'].hidden,true);
controls.preset.value='mixed';context.syncPrompt();
assert.equal(controls['mixed-note'].hidden,false);
const generations=Array.from(context.buildSteps()).filter(s=>s.type==='GENERATE');
assert.equal(generations[0].generation.prompt,'保留我写的要求');
assert.equal(generations[1].generation.prompt,faithful);
controls.preset.value='text';context.syncPrompt();
assert.equal(context.buildSteps()[0].generation.prompt,'我的文生图要求');
assert.equal(context.buildSteps()[0].mode,'TEXT_TO_IMAGE');
assert.equal(controls.steps.value,'custom JSON');
// A later transparency failure must not hide the warning on a successful AI image.
for(const status of ['SUCCEEDED','NEEDS_REVIEW','FAILED','RUNNING']) {
  assert.equal(context.needsAiReview({status,steps:[{type:'GENERATE',status:'SUCCEEDED'}]}),true);
}
assert.equal(context.needsAiReview({steps:[{type:'GENERATE',status:'FAILED'}]}),false);
assert.equal(context.needsAiReview({steps:[{type:'SEGMENT',status:'SUCCEEDED'}]}),false);
console.log('Prompt draft switching, mixed-step prompts, custom JSON preservation and AI review checks passed');

controls['recognize']={checked:true};controls['recognition-provider']={value:'vision'};controls['recognition-model']={value:'luna'};
controls.preset.value='before';controls.precut.checked=false;assert.deepEqual(types(),['RECOGNIZE','GENERATE','ENSURE_TRANSPARENCY','STICKER']);
assert.equal(context.buildSteps()[0].recognition.model,'luna');
controls.preset.value='recognize';assert.deepEqual(types(),['RECOGNIZE']);
controls.preset.value='text';assert.deepEqual(types(),['GENERATE','ENSURE_TRANSPARENCY','STICKER']);
assert(html.match(/<input[^>]*id="recognize"[^>]*>/)[0].includes('checked'));
console.log('Recognition default, standalone, model replacement and text-only exclusion passed');

controls.preset.value='local';assert.deepEqual(types(),['ENSURE_TRANSPARENCY','STICKER'],'local mode never silently enables cloud recognition');

// Both optional cuts must affect the submitted request, including reprocessing.
vm.runInContext(code.slice(code.indexOf('const labels ='),code.indexOf('const promptPresets =')),context);
controls.steps.value='';controls.preset.value='before';controls.policy.value='AUTO';
for(const pre of [false,true]) for(const post of [false,true]) {
  controls.precut.checked=pre;controls.postcut.checked=post;
  const steps=context.configuredSteps();
  assert.deepEqual(Array.from(steps,s=>s.type),['RECOGNIZE',...(pre?['SEGMENT']:[]),'GENERATE','ENSURE_TRANSPARENCY','STICKER']);
  assert.equal(steps.at(-2).policy,post?'AUTO':'PRESERVE','disabling background removal must never cut automatically');
  assert.deepEqual(JSON.parse(JSON.stringify(context.imageReprocessingSteps())),JSON.parse(JSON.stringify(steps.slice(1))));
  assert.equal(context.describeSteps(steps).includes('不抠图'),!post);
}
controls.preset.value='local';controls.postcut.checked=false;
assert.deepEqual(types(),['ENSURE_TRANSPARENCY','STICKER']);assert.equal(context.buildSteps()[0].policy,'PRESERVE');
controls.preset.value='recognize';assert.throws(()=>context.imageReprocessingSteps(),/重新识别/);
controls.preset.value='text';assert.throws(()=>context.imageReprocessingSteps(),/已有衣物/);
controls.params.value='invalid';controls.preset.value='local';assert.equal(context.configuredSteps().length,2,'local flow ignores cloud parameters');
controls.preset.value='recognize';assert.equal(context.configuredSteps().length,1);
controls.preset.value='before';assert.throws(()=>context.configuredSteps());controls.params.value='{}';
// Advanced JSON takes precedence consistently in preview, initial submit and reprocessing.
controls.steps.value=JSON.stringify([{type:'SEGMENT',method:'GRABCUT'},{type:'STICKER'}]);
assert.deepEqual(Array.from(context.configuredSteps(),s=>s.type),['SEGMENT','STICKER']);
assert.equal(context.imageReprocessingSteps()[0].method,'GRABCUT');
assert.equal(context.describeSteps(context.configuredSteps()),'照片 → 抠图 → 贴纸');
for(const invalid of ['{}','[]','[null]','[{"type":"TYPO"}]',JSON.stringify(Array(7).fill({type:'STICKER'}))]) {
  controls.steps.value=invalid;assert.throws(()=>context.configuredSteps());
}
controls.steps.value='';
// Real mode transitions preserve preferences and visibly skip unavailable steps.
for(const id of ['upload','ai','rotation-option','image-pipeline','recognition-options','recognition-settings','precut-option','precut-state','generation-step','generation-state','generation-hint','postcut-option','postcut-settings','postcut-state','custom-note','flow-path','flow-note','flow-summary','recognition-label','workflow-settings']) {
  controls[id] ||= {}; controls[id].classList={toggle(){},add(){},remove(){}};
}
vm.runInContext(code.slice(code.indexOf('function preset()'),code.indexOf('function buildSteps()')),context);
controls.recognize.checked=false;controls.preset.value='recognize';context.preset();
assert.equal(controls['image-pipeline'].hidden,true);assert.equal(controls.recognize.hidden,true);
assert.equal(context.buildSteps()[0].type,'RECOGNIZE');
controls.preset.value='before';context.preset();assert.equal(controls.recognize.checked,false);assert.equal(controls.recognize.disabled,false);assert.equal(controls.recognize.hidden,false);
controls.preset.value='local';context.preset();assert.equal(controls.precut.disabled,true);assert.equal(controls['intent-option'].hidden,true);assert.equal(controls['generation-state'].textContent,'已跳过');
assert.match(controls['flow-note'].textContent,/全部在本地/);
controls.preset.value='text';context.preset();assert.equal(controls.upload.hidden,true);assert.equal(controls['workflow-settings'].open,true);
controls.steps.value='bad';context.refreshFlow();assert.match(controls['flow-path'].textContent,/不能提交/);assert.equal(controls['custom-note'].hidden,false);
console.log('Optional cuts, shared rerun payloads, custom overrides, preview errors and mode transitions passed');
