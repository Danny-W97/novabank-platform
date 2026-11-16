'use strict';
// Offline DOM: no configuration reads, real payments, subprocesses or browser storage.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const all=[],ids=new Map(),writes=[],requests=[];let deferred=null,detail,failed=false;
class Node{
  constructor(tag,text=''){this.tag=tag;this.textContent=text;this.children=[];this.events={};this.value='';this.checked=false;this.disabled=false;const s=new Set();this.classList={toggle:(k,on)=>on?s.add(k):s.delete(k),add:k=>s.add(k),remove:k=>s.delete(k),contains:k=>s.has(k)};all.push(this);}
  append(...items){this.children.push(...items);}replaceChildren(...items){this.children=items;}
  addEventListener(name,fn){this.events[name]=fn;}showModal(){this.open=true;}close(){this.open=false;}
  querySelectorAll(tag){return this.children.flatMap(n=>[...(n.tag===tag?[n]:[]),...n.querySelectorAll(tag)]);}
  focus(options){this.focused=options;}scrollIntoView(options){this.scrolled=options;}
  setAttribute(name,value){this[name]=value;}
  set innerHTML(_){throw new Error('Unsafe HTML must not be used');}
}
const $=id=>{if(!ids.has(id))ids.set(id,new Node('div'));return ids.get(id);};
const context={window:{},document:{body:new Node('body')},$,URLSearchParams,
  el:(tag,text)=>new Node(tag,text),date:v=>v||'date',crypto:{randomUUID:()=> '12345678-1234-4234-8234-123456789abc'},busy:()=>false,
  state:{profile:'dev',view:'paymentCases',actions:['payment-case-plan','payment-case-apply','payment-case-decision','purchase-cancel','purchase-refresh'].map(id=>({id,changes:true}))},
  api:async url=>{requests.push(url);if(failed)throw new Error('Synthetic read failure');return deferred?await deferred:JSON.parse(JSON.stringify(detail));},submit:async(action,confirmed,extra)=>writes.push({action:action.id,confirmed,...extra})};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'payment-cases.js'),'utf8'),context);
const api=context.window.paymentCases,reason=all.find(n=>n.tag==='textarea'),[ack,backup,paused]=all.filter(n=>n.tag==='input');
const run=all.find(n=>n.tag==='button'&&n.textContent==='Wykonaj'),buttons=()=> $('payment-case-actions').children;
const changed=()=>ack.events.input();
function fixture(plan=null){detail={id:'9007199254740993',orderNumber:'<img src=x onerror=alert(1)>',customerId:'opaque',amount:'10',currency:'PLN',localStatus:'FAILED',outcome:'UNKNOWN',workflow:'OPEN',version:'0',unresolved:true,allowNewPurchaseAt:null,attempts:[],refunds:[],events:[],plan};}
(async()=>{
  fixture();detail={cases:[{...detail,allowNewPurchaseAt:'2026-10-20T10:00:00Z'}],next:null};await api.load();
  assert.equal($('payment-case-rows').children[0].children.length,7);
  assert.equal($('payment-case-rows').children[0].children[5].textContent,'Dopuszczony');
  assert.equal($('payment-case-previous').disabled,true);
  fixture();await api.detail(detail.id);assert.ok($('payment-case-order').textContent.includes('<img'));
  assert.equal($('payment-case-list').classList.contains('hidden'),true);
  assert.equal($('payment-case-detail').classList.contains('hidden'),false);
  assert.equal($('payment-case-summary').children.length,6);
  assert.equal($('payment-case-detail').scrolled.block,'start');
  assert.equal($('payment-case-title').focused.preventScroll,true);
  assert.ok($('payment-case-evidence-view').children[0].textContent.includes('Brak planu'));
  api.back();assert.equal($('payment-case-list').classList.contains('hidden'),false);
  assert.equal($('payment-case-detail').classList.contains('hidden'),true);
  await api.detail(detail.id);
  buttons().find(n=>n.textContent==='Zamknij sprawę bez rozstrzygnięcia').onclick();
  reason.value='Audited administrative closure';ack.value='DECIDE';changed();assert.equal(run.disabled,false);await run.onclick();
  assert.equal(writes[0].parameters.decision,'CLOSE_UNRESOLVED');assert.equal(writes[0].parameters.expectedVersion,'0');assert.equal(writes[0].parameters.orderId,'9007199254740993');
  assert.equal('financialOutcome' in writes[0].parameters,false);
  fixture({digest:'a'.repeat(64),resolution:'PAID',status:'READY'});await api.detail(detail.id);
  buttons().find(n=>n.textContent==='Zastosuj potwierdzony wynik').onclick();reason.value='Verified payment evidence';ack.value='RECONCILE';changed();assert.equal(run.disabled,true);
  backup.checked=true;changed();assert.equal(run.disabled,true);paused.checked=true;changed();assert.equal(run.disabled,false);await run.onclick();
  assert.equal(writes[1].parameters.planDigest,'a'.repeat(64));assert.equal(writes[1].parameters.writesPaused,true);
  fixture({digest:'b'.repeat(64),resolution:'PAID',status:'APPLYING',applyRequestId:'old-request',applyReason:'Original approved reason'});await api.detail(detail.id);
  buttons().find(n=>n.textContent==='Wznów zatwierdzone uzgodnienie').onclick();assert.equal(reason.disabled,true);assert.equal(reason.value,'Original approved reason');
  ack.value='RECONCILE';backup.checked=paused.checked=true;changed();await run.onclick();assert.equal(writes[2].parameters.requestId,'old-request');
  fixture({digest:'b'.repeat(64),resolution:'PAID',status:'COMPLETED'});await api.detail(detail.id);assert.equal(buttons().some(n=>n.textContent==='Zastosuj potwierdzony wynik'),false);
  buttons().find(n=>n.textContent==='Dopuść niezależny nowy zakup').onclick();reason.value='Explicit risk acceptance';ack.value='DECIDE';context.state.profile='prod';changed();await run.onclick();assert.equal(writes.length,3);
  context.state.profile='dev';api.reset();$('payment-case-view').value='UNRESOLVED';$('payment-case-query').value='synthetic-customer';
  fixture();const item={...detail};detail={cases:[item],next:'9007199254740990'};await api.load();
  assert.equal($('payment-case-previous').disabled,true);assert.equal($('payment-case-more').disabled,false);
  $('payment-case-query').value='not-yet-submitted';$('payment-case-table-scroll').scrollTop=400;detail={cases:[item],next:null};await $('payment-case-more').onclick();
  assert.equal($('payment-case-table-scroll').scrollTop,0);
  assert.ok(requests.at(-1).includes('before=9007199254740990'));assert.ok(requests.at(-1).includes('q=synthetic-customer'));
  assert.equal($('payment-case-previous').disabled,false);assert.equal($('payment-case-more').disabled,true);assert.ok($('payment-case-page').textContent.includes('Strona 2'));
  fixture();await $('payment-case-rows').children[0].children[6].children[0].onclick();
  assert.equal($('payment-case-list').classList.contains('hidden'),true);api.back();assert.ok($('payment-case-page').textContent.includes('Strona 2'));
  failed=true;await $('payment-case-previous').onclick();assert.ok($('payment-case-page').textContent.includes('Strona 2'));failed=false;
  detail={cases:[item],next:'9007199254740990'};await $('payment-case-previous').onclick();assert.equal(requests.at(-1).includes('before='),false);assert.ok($('payment-case-page').textContent.includes('Strona 1'));
  detail={cases:[],next:null};await api.load();assert.equal($('payment-case-previous').disabled,true);assert.equal($('payment-case-more').disabled,true);assert.equal($('payment-case-rows').children[0].children[0].colSpan,7);
  fixture({digest:'c'.repeat(64),resolution:'UNKNOWN',status:'READY',facts:[{source:'<script>unsafe</script>',result:'UNKNOWN'}]});
  detail.attempts=[{provider:'BANK',state:'UNKNOWN',amount:'10',currency:'PLN'}];detail.refunds=[{id:'1',status:'PENDING',amount:'10',currency:'PLN'}];
  detail.events=[{action:'CHECK',reason:'<img src=x>',operator:'local-cc-dev',requestId:'uuid',version:'1'}];
  await api.detail(detail.id);assert.equal($('payment-case-events').children.length,1);assert.equal($('payment-case-events').children[0].children[1].textContent,'<img src=x>');
  detail.purchaseId='synthetic-purchase';detail.cancellationAvailable=true;await api.detail(detail.id);
  buttons().find(n=>n.textContent==='Potwierdź brak obciążenia i anuluj zakup').onclick();reason.value='Explicit purchase cancellation';ack.value='CANCEL';changed();await run.onclick();
  assert.equal(writes.at(-1).action,'purchase-cancel');assert.equal(writes.at(-1).parameters.confirmation,'CANCEL');assert.equal('decision' in writes.at(-1).parameters,false);
  detail.localStatus='CANCELLED';detail.outcome='CANCELLED';await api.detail(detail.id);
  assert.ok(buttons().some(n=>n.textContent==='Zamknij potwierdzoną rezygnację'));
  assert.equal(buttons().some(n=>n.textContent==='Zamknij sprawę bez rozstrzygnięcia'),false);
  detail.workflow='RESOLVED';await api.detail(detail.id);
  assert.equal(buttons().some(n=>n.textContent==='Zamknij potwierdzoną rezygnację'),false);
  fixture();detail.workflow='CLOSED_UNRESOLVED';await api.detail(detail.id);
  assert.equal(buttons().some(n=>n.textContent==='Zamknij sprawę bez rozstrzygnięcia'),false);
  assert.ok($('payment-case-action-note').textContent.includes('UNKNOWN'));
  detail={cases:[],next:null};await $('payment-case-tab-history').onclick();assert.ok(requests.at(-1).includes('bucket=HISTORY'));assert.equal($('payment-case-view').value,'ALL');
  await $('payment-case-tab-review').onclick();assert.ok(requests.at(-1).includes('bucket=REVIEW'));
  fixture();
  let resolve;deferred=new Promise(r=>{resolve=r;});const pending=api.detail('1');context.state.profile='test';api.reset();resolve(detail);await pending;
  assert.equal($('payment-case-title').textContent,'');assert.equal($('payment-case-detail').classList.contains('hidden'),true);
  assert.equal($('payment-case-previous').disabled,true);
  console.log('Payment cases UI: previous/next, preserved filters/page, detail navigation/focus, empty/errors, structured literal data, explicit decisions and stale/profile guards — OK');
})().catch(error=>{console.error(error);process.exitCode=1;});
