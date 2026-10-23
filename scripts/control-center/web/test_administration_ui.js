'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
class Node {
  constructor(tag, text='') {this.tag=tag;this.textContent=text;this.children=[];this.events={};this.value='';this.checked=false;this.disabled=false;this.classList={toggle(){}};}
  append(...items){this.children.push(...items);} prepend(...items){this.children.unshift(...items);}
  replaceChildren(...items){this.children=items;}
  addEventListener(name,handler){this.events[name]=handler;} showModal(){this.open=true;} close(){this.open=false;}
  click(){this.clicked=true;} remove(){}
}
function fixture(file) {
  const nodes=new Map(), requests=[], submits=[], body=new Node('body'), state={profile:'dev'};
  const context={window:{},document:{body,createTextNode:text=>new Node('#text',text)},state,busy:()=>false,Blob,URL:{createObjectURL:()=> 'blob:fixture',revokeObjectURL(){}},setTimeout:()=>0,
    $:id=>{if(!nodes.has(id))nodes.set(id,new Node('button'));return nodes.get(id);},el:(tag,text='')=>new Node(tag,text),refreshNotifications:async()=>{},
    submit:async(...args)=>submits.push(args),api:async(route,payload)=>{requests.push(payload);return payload.operation==='plan'?{planId:'fixture',count:1,batchLimit:1000,databaseBytes:10,note:'plan'}:payload.operation==='export'?{content:'{}',filename:'fixture.json',sha256:'hash'}:{removed:1,note:'done'};}};
  vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,file),'utf8'),context);
  return {context,nodes,requests,submits,dialog:body.children[0],state};
}
(async()=>{
  const f=fixture('notification-retention.js'), d=f.dialog.children;
  f.nodes.get('notification-retention-open').events.click();
  const [preview,download,purge]=[d[4],d[6],d[9]], check=d[7].children[0],ack=d[8];
  await preview.events.click(); check.checked=true; ack.value='USUN'; ack.events.input();
  assert.equal(purge.disabled,true);
  await download.events.click(); assert.equal(purge.disabled,false);
  f.state.profile='prod';await purge.events.click();assert.equal(f.requests.length,2);
  f.state.profile='dev';await purge.events.click();assert.equal(f.requests[2].operation,'purge');
  const m=fixture('maintenance-ui.js');
  m.context.window.maintenanceUI.open({id:'totp-rotation-apply',title:'Rotate',description:'fixture',changes:true});
  const [after,limit,digest,acknowledge,confirmed]=m.dialog.children.slice(2,7).map(label=>label.children.find(child=>child.tag==='input'));
  const run=m.dialog.children.find(child=>child.tag==='button'&&child.textContent==='Uruchom');
  digest.value='a'.repeat(64);acknowledge.value='ROTATE';confirmed.checked=true;confirmed.events.change();
  assert.equal(run.disabled,false);
  m.state.profile='prod';await run.events.click();assert.equal(m.submits.length,0);
  m.state.profile='dev';await run.events.click();assert.equal(m.submits[0][2].parameters.afterId,'0');
  assert.equal(m.submits[0][2].parameters.confirmation,'ROTATE');
})().catch(error=>{console.error(error);process.exitCode=1;});
