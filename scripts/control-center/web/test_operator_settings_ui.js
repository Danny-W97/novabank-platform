'use strict';
// Offline DOM fixtures only: no systemd, history files, timers, storage or real requests.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const nodes=new Map();
class Node{
 constructor(tag='div',text=''){this.tag=tag;this.textContent=text;this.children=[];this.value='';this.checked=false;this.open=false;this.disabled=false;this.events={};this.dataset={};const flags=new Set();this.classList={toggle:(k,on)=>on?flags.add(k):flags.delete(k),contains:k=>flags.has(k)};}
 set id(value){this._id=value;nodes.set(value,this);}get id(){return this._id;}
 append(...items){this.children.push(...items);}replaceChildren(...items){this.children=items;this.textContent='';}
 addEventListener(k,fn){this.events[k]=fn;}querySelectorAll(selector){const tags=selector.split(',');return this.children.flatMap(c=>[...(tags.includes(c.tag)?[c]:[]),...c.querySelectorAll(selector)]);}
 showModal(){this.open=true;}close(){if(this.open){this.open=false;this.events.close?.();}}
}
const $=id=>{if(!nodes.has(id))nodes.set(id,new Node());return nodes.get(id);};
const values={backupDirectory:'.novabank/backups/dev',backupRetentionDays:30,backupAt:'02:00',drWeekday:'SUN',drAt:'04:00',artifactRetentionDays:30,tempRetentionDays:7};
const state={view:'maintenance',profile:'dev',token:'fixture',jobs:[],loading:false,actions:['backup-schedule-install','dr-schedule-install','backup-create','artifacts-cleanup','import-cli-history','migrate-cli-backup','vault-ca-diagnostics'].map(id=>({id,title:id}))};
const plan={kind:'backup',revision:'c'.repeat(64),legacyTimer:'novabank-backup.timer',newTimer:'novabank-web-dev-backup.timer',canMigrate:true,legacyExists:true,newExists:false,legacyEnabled:true,legacyActive:true,serviceRunning:false,legacyBackupDirectory:'<script>literal path</script>',settings:values,reason:'Gotowy plan'};
const absentDr={...plan,kind:'dr',legacyTimer:'novabank-dr-test.timer',newTimer:'novabank-web-dev-dr-test.timer',canMigrate:false,legacyExists:false,legacyEnabled:false,legacyActive:false,legacyBackupDirectory:null};
const calls=[],submitted=[],requested=[];let confirm=true,deferred=null,scheduleFixture=null,legacyError=false;
const context={window:{confirm:()=>confirm},state,$,Date,busy:()=>state.loading||Boolean(context.window.operatorUI?.saving),el:(tag,text='',cls='')=>new Node(tag,text),actionButton:a=>new Node('button',a.title),renderActions:()=>{},requestAction:a=>requested.push(a.id),submit:(...args)=>submitted.push(args),
 api:async(url,payload)=>{calls.push({url,payload});if(deferred)return new Promise(resolve=>{deferred.resolve=resolve;});if(url.includes('/legacy')){if(legacyError)throw new Error('fixture read failure');return {schedules:scheduleFixture||{available:true,timers:state.profile==='dev'?[plan,absentDr]:[],managed:state.profile==='dev'?[{kind:'backup',timer:'fixture.timer',installed:true,enabled:true,waiting:true,configuration:'VALID',configurationNote:'Konfiguracja systemd zweryfikowana',execution:'NEVER',lastStarted:null,lastFinished:null,exitCode:null,nextRun:'fixture date'}]:[],note:'fixture'},history:{sourceExists:true,note:'CLI',items:[{created:'2026-10-16T08:00:00Z',result:'OK',title:'<img src=x onerror=alert(1)>'}]}};}return{profile:state.profile,revision:(payload?'b':'a').repeat(64),values:payload?.values||(state.profile==='dev'?{...values}:{artifactRetentionDays:30,tempRetentionDays:7}),exists:Boolean(payload)};}};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'operator-settings.js'),'utf8'),context);
const text=node=>String(node.textContent)+node.children.map(text).join(' ');
(async()=>{
 const ui=context.window.operatorUI;assert.equal(calls.length,0,'no automatic writes or reads at initialization');await ui.load();
 assert.equal($('operator-fields').children.length,7);assert.ok(text($('operator-history-rows')).includes('<img src=x'),'untrusted history remains literal text');
 assert.ok(text($('operator-managed')).includes('Jeszcze nie wykonano'));assert.ok(!text($('operator-managed')).includes('zakończone sukcesem'),'enabled timer is not a successful execution');
 assert.equal($('operator-cli-section').classList.contains('hidden'),false);assert.equal($('operator-timers').children.length,1,'only the relevant legacy timer is shown');
 const migrate=$('operator-timers').children[0].children.at(-1);migrate.events.click();assert.equal($('operator-migration-dialog').open,true);assert.equal($('operator-migration-run').disabled,true);assert.equal(submitted.length,0);
 $('operator-migration-confirm').checked=true;$('operator-migration-confirm').events.change();$('operator-migration-run').events.click();assert.equal(submitted.length,1);assert.equal(submitted[0][0].id,'migrate-cli-backup');assert.equal(submitted[0][2].parameters.revision,plan.revision);
 $('operator-backupAt').value='23:10';$('operator-backupAt').events.input();assert.equal(ui.dirty,true);assert.equal($('operator-save').disabled,true);migrate.events.click();assert.equal($('operator-migration-dialog').open,false,'dirty settings cannot use a prior plan');
 confirm=false;assert.equal(ui.leave(),false);assert.equal(ui.dirty,true);confirm=true;
 const before=calls.length;await $('operator-form').events.submit({preventDefault(){}});assert.equal(calls.length,before,'no save without confirmation');
 $('operator-confirm').checked=true;$('operator-confirm').events.change();await $('operator-form').events.submit({preventDefault(){}});assert.equal(calls.at(-1).url,'/api/operations/settings');assert.equal(calls.at(-1).payload.values.backupAt,'23:10');assert.equal(calls.at(-1).payload.confirmed,true);assert.equal(ui.dirty,false);
 const retired={...plan,canMigrate:false,newExists:true,legacyEnabled:false,legacyActive:false};
 const legacy=(timers,available=true)=>({available,timers,managed:[],note:'fixture'});
 for(const legacyExists of [true,false]){scheduleFixture=legacy([{...retired,legacyExists},{...absentDr,newExists:true}]);await ui.load();assert.equal($('operator-cli-section').classList.contains('hidden'),true,'retired CLI is hidden even if disabled files remain');assert.equal($('operator-timers').children.length,0);}
 scheduleFixture=legacy([{...absentDr,kind:'backup'},absentDr]);await ui.load();assert.equal($('operator-cli-section').classList.contains('hidden'),true,'no legacy installation needs no migration section');
 for(const change of [{legacyEnabled:true},{legacyActive:true},{serviceRunning:true},{newExists:false}]){scheduleFixture=legacy([{...retired,...change},absentDr]);await ui.load();assert.equal($('operator-cli-section').classList.contains('hidden'),false,'active/unresolved legacy must remain visible');assert.equal($('operator-timers').children.length,1);assert.equal($('operator-timers').children[0].children.at(-1).disabled,true,'visibility never grants migration permission');}
 scheduleFixture=legacy([{...retired,legacyExists:false,serviceRunning:true},absentDr]);await ui.load();assert.equal($('operator-cli-section').classList.contains('hidden'),true,'new service activity without legacy files is not CLI migration');
 for(const uncertain of [legacy([],false),legacy([retired]),legacy([retired,{...absentDr,newExists:undefined}])]){scheduleFixture=uncertain;await ui.load();assert.equal($('operator-cli-section').classList.contains('hidden'),false,'unknown or incomplete read is not successful migration');assert.equal($('operator-cli-state').classList.contains('hidden'),false);}
 legacyError=true;await ui.load();assert.equal($('operator-cli-state').classList.contains('hidden'),false,'read failure remains visible');assert.equal($('operator-timers').children.length,0,'no stale plans after failure');legacyError=false;scheduleFixture=null;
 state.profile='test';await ui.load();assert.equal($('operator-fields').children.length,2);assert.equal($('operator-timers').children.length,0);assert.equal($('operator-managed').children.length,0);assert.equal($('operator-cli-section').classList.contains('hidden'),true);
 const count=calls.length;state.profile='prod';await ui.load();assert.equal(calls.length,count,'PROD must not fetch DEV settings/timers');assert.ok($('operator-form').classList.contains('hidden'));
 state.profile='dev';deferred={};const pending=ui.load(),resolve=deferred.resolve;state.profile='prod';ui.reset();deferred=null;resolve({profile:'dev',revision:'a'.repeat(64),values,exists:false});await pending;assert.equal($('operator-fields').children.length,0,'late DEV response ignored');
 assert.equal(calls.filter(c=>c.payload!==undefined).length,1,'only explicit settings save, no job API execution');
 console.log('Operator settings UI: conditional CLI visibility, unknown/error safety, confirmation, dirty/stale/profile guards, TEST isolation and plan-bound migration — OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
