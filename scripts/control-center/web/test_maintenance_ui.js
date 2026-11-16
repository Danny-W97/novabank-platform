'use strict';
// Offline fixture: no user configuration, SQL, subprocess or browser side effects.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const nodes=[],writes=[];
class Node {
  constructor(tag,text=''){this.tag=tag;this.textContent=text;this.children=[];this.events={};this.checked=false;this.value='';const classes=new Set();this.classList={toggle:(k,on)=>on?classes.add(k):classes.delete(k),contains:k=>classes.has(k)};nodes.push(this);}
  append(...items){this.children.push(...items);}
  replaceChildren(...items){this.children=items;}
  addEventListener(name,fn){this.events[name]=fn;}
  showModal(){this.open=true;}
  close(){this.open=false;}
}
const context={window:{},state:{profile:'dev'},busy:()=>false,
  document:{body:new Node('body'),createTextNode:t=>t},el:(tag,text)=>new Node(tag,text),
  submit:async(action,confirmed,extra)=>writes.push({action:action.id,confirmed,...extra})};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'maintenance-ui.js'),'utf8'),context);
const fields=nodes.filter(n=>n.tag==='input');const [after,limit,digest,ack,check,paused]=fields;
const run=nodes.find(n=>n.tag==='button'&&n.textContent==='Uruchom');
const open=(id,changes=true)=>context.window.maintenanceUI.open({id,title:'Fixture',description:'Fixture',changes});
const changed=input=>input.events.input();
(async()=>{
  open('payment-history-migration-apply');
  assert.equal(run.disabled,true);assert.equal(ack.placeholder,'Wpisz MIGRATE');
  digest.value='a'.repeat(64);ack.value='MIGRATE';check.checked=true;changed(check);
  assert.equal(run.disabled,true); // A backup acknowledgement alone must not authorize live writers.
  paused.checked=true;changed(paused);assert.equal(run.disabled,false);
  await run.events.click();assert.equal(writes.length,1);
  assert.deepEqual(JSON.parse(JSON.stringify(writes[0])),{action:'payment-history-migration-apply',confirmed:true,
    parameters:{planDigest:'a'.repeat(64),confirmation:'MIGRATE',backupsVerified:true,writesPaused:true}});
  open('totp-rotation-apply');assert.equal(run.disabled,true);assert.equal(ack.placeholder,'Wpisz ROTATE');
  assert.equal(paused.checked,false);digest.value='b'.repeat(64);ack.value='MIGRATE';check.checked=true;changed(check);
  assert.equal(run.disabled,true);ack.value='ROTATE';changed(ack);assert.equal(run.disabled,false);
  await run.events.click();assert.equal(writes[1].parameters.confirmation,'ROTATE');assert.equal(writes[1].parameters.afterId,'0');
  open('payment-history-migration-apply');context.state.profile='prod';changed(check);assert.equal(run.disabled,true);
  await run.events.click();assert.equal(writes.length,2);
  console.log('Maintenance UI: explicit MIGRATE/ROTATE, both migration acknowledgements, reset and profile gate — OK');
})().catch(error=>{console.error(error);process.exitCode=1;});
