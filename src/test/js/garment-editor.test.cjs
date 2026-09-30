const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element{
  constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.value='';this.events={};this.classList={toggle(){}};}
  append(...items){this.children.push(...items);}replaceChildren(...items){this.children=items;}
  addEventListener(name,fn){this.events[name]=fn;}dispatchEvent(e){this.events[e.type]?.(e);}setAttribute(k,v){this[k]=v;}
}
const elements=new Map(),$=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
const fields=[{key:'primaryColor',label:'主色',multiple:false,options:['蓝色','红色']},{key:'brand',label:'品牌',multiple:false,options:[]}];
const initial={id:'garment-a',revision:1,sourceOrigin:'USER_UPLOAD',displayUrl:'/display.png',effective:{primaryColor:['蓝色'],brand:[]},overrides:{},automatic:null,runs:[]};
let view=structuredClone(initial),requests=[];
const storage=new Map();
const ctx={$ ,document:{createElement:tag=>new Element(tag)},Event:class{constructor(type){this.type=type;}},states:{},currentJob:{request:{garmentId:'garment-a'}},crypto:{randomUUID:()=> 'key-1'},
  localStorage:{getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},selectJob(){},loadHistory:async()=>{},
  api:async(url,options)=>{
    requests.push({url,options});
    if(url==='/api/recognition/providers')return [{id:'test',defaultModel:'luna',preferred:true,configured:true}];
    if(url==='/api/recognition/schema')return {fields};
    if(options?.method==='PATCH'){const body=JSON.parse(options.body);assert.equal(body.revision,view.revision);view={...view,revision:view.revision+1,overrides:{...view.overrides,...body.fields},effective:{...view.effective,...body.fields}};}
    return structuredClone(view);
  }};
vm.createContext(ctx);vm.runInContext(fs.readFileSync('src/main/resources/static/workflow/garment.js','utf8'),ctx);
const settle=()=>new Promise(r=>setImmediate(r));
(async()=>{
  await settle();await settle();assert.equal($('metadata-grid').children.length,2);
  assert.equal($('metadata-empty').hidden,true);
  assert.equal($('garment-panel').hidden,false);
  assert.equal($('metadata-summary').children[0].children[0].textContent,'主色');
  assert.equal($('metadata-summary').children[0].children[1].textContent,'蓝色','metadata is readable without opening the editor');
  const input=$('metadata-grid').children[1].children.find(e=>e.tagName==='INPUT');input.value='';input.events.input();
  assert.equal(vm.runInContext('metadataDirty',ctx),true);
  // Polling must not overwrite an in-progress edit even if the background result changed.
  view={...view,revision:2};await ctx.renderGarment(ctx.currentJob);
  assert.equal($('metadata-grid').children[1].children.find(e=>e.tagName==='INPUT'),input);
  const before=requests.length;$('rerecognize').events.click();await settle();assert.equal(requests.length,before);
  assert.match($('metadata-notice').textContent,/先保存/);
  await $('reload-metadata').events.click();
  $('metadata-form').events.submit({preventDefault(){}});await settle();await settle();
  const patch=requests.find(r=>r.options?.method==='PATCH');assert.deepEqual(JSON.parse(patch.options.body).fields,{brand:[]},'clearing a field is an explicit override');
  assert.equal(vm.runInContext('metadataDirty',ctx),false);assert.match($('metadata-notice').textContent,/已保存/);
  // Reprocessing must submit the shared image pipeline, including optional cuts.
  const rerunSteps=[{type:'SEGMENT',method:'U2NET'},{type:'GENERATE',mode:'IMAGE_TO_IMAGE'},{type:'ENSURE_TRANSPARENCY',policy:'PRESERVE'},{type:'STICKER'}];
  ctx.imageReprocessingSteps=()=>rerunSteps;
  $('rerender').events.click();await settle();await settle();
  const rerun=requests.find(r=>r.url==='/api/garments/garment-a/workflows');
  assert.deepEqual(JSON.parse(rerun.options.body).steps,rerunSteps);
  // A failed paid action keeps its idempotency key.
  ctx.api=async(url,options)=>{requests.push({url,options});throw Error('lost response');};
  await assert.rejects(()=>ctx.submitGarmentRun('garment-a','recognize',{model:'luna'}));
  await assert.rejects(()=>ctx.submitGarmentRun('garment-a','recognize',{model:'luna'}));
  const last=requests.slice(-2);assert.equal(last[0].options.headers['Idempotency-Key'],last[1].options.headers['Idempotency-Key']);
  ctx.drawMetadata({...initial,automatic:{attributes:{primaryColor:{status:'INFERRED',values:['蓝色']}},issues:[]}});
  assert.equal($('metadata-summary').children[0].children[1].children[0].textContent,'推测');
  ctx.drawMetadata({...initial,overrides:{primaryColor:[]},effective:{primaryColor:[],brand:[]}});
  assert.equal($('metadata-summary').children[0].children[1].textContent,'已清空','explicit empty override must not show the previous model value');
  ctx.updateMetadataStatus(initial,{steps:[{type:'RECOGNIZE',status:'FAILED'}]});
  assert.match($('garment-status').textContent,/本次识别未完成/);
  await ctx.renderGarment({request:{},steps:[]});
  assert.equal($('garment-panel').hidden,true);assert.equal($('metadata-empty').hidden,false);
  assert.match($('metadata-empty-message').textContent,/此任务没有/);
  ctx.drawMetadata({...initial,effective:{primaryColor:['浅蓝色'],brand:[]}});
  const colorInput=$('metadata-grid').children[0].children.find(e=>e.tagName==='INPUT');
  assert.equal(colorInput.value,'浅蓝色','unlisted model values remain visible');
  colorInput.value='灰蓝色';colorInput.events.input();
  assert.equal(vm.runInContext('metadataPatch.primaryColor[0]',ctx),'灰蓝色','single values allow new names');
  console.log('Visible metadata, inference labels, cleared overrides, failure status and legacy empty state passed');
  console.log('Garment editor: polling preserves drafts, stale revisions, explicit clearing, unsaved edit guard and action idempotency passed');
})().catch(e=>{console.error(e);process.exitCode=1;});
