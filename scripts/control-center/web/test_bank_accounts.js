'use strict';
// Offline DOM and API fixtures only; no real accounts, browser storage or database.
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm'), path = require('node:path');
class Node {
  constructor(tag='div', text='') {this.tag=tag;this.textContent=text;this.children=[];this.value='';this.events={};this.attrs={};this.open=false;this.disabled=false;const flags=new Set();this.classList={toggle:(k,on)=>on?flags.add(k):flags.delete(k),contains:k=>flags.has(k)};}
  append(...items){this.children.push(...items);} replaceChildren(...items){this.children=items;this.textContent='';}
  addEventListener(k,fn){this.events[k]=fn;} setAttribute(k,v){this.attrs[k]=v;}
  querySelectorAll(selector){const tags=selector.split(',');return this.children.flatMap(c=>[...(tags.includes(c.tag)?[c]:[]),...c.querySelectorAll(selector)]);}
  showModal(){this.open=true;} close(){if(this.open){this.open=false;this.events.close?.();}}
}
const nodes=new Map(), $=id=>{if(!nodes.has(id))nodes.set(id,new Node());return nodes.get(id);};
const state={profile:'dev',view:'accounts',token:'fixture-token'}, calls=[];
const account={id:'9007199254740993',ownerId:'9223372036854775807',owner:'<img src=x onerror=alert(1)>',email:'fixture@example.invalid',number:'•••• 0001',balance:'12345678901234567.89',currency:'PLN',status:'ACTIVE',userStatus:'ACTIVE',accountNumber:'00000000000000000000000001',role:'USER',emailVerified:true,twoFactorEnabled:false,createdAt:'2026-10-15T10:00:00Z',updatedAt:'2026-10-15T10:00:00Z',userCreatedAt:'2026-10-15T10:00:00Z',lastLoginAt:null};
let fail=false,defer=null;
const context={window:{},state,$,URLSearchParams,Date,el:(tag,text='',cls='')=>new Node(tag,text),pill:text=>new Node('span',text),
 api:async (url,payload)=>{calls.push({url,payload});if(fail)throw Error('fixture error');if(defer)return new Promise(resolve=>{defer.resolve=resolve;});if(url.startsWith('/api/bank-accounts/'))return{account,readOnly:true};const q=new URL(url,'http://fixture').searchParams;return{accounts:[account],next:q.has('before')?null:'9007199254740993',pageSize:50,readOnly:true};}};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'bank-accounts.js'),'utf8'),context);
const text=node=>String(node.textContent)+node.children.map(text).join(' ');
(async()=>{
  const ui=context.window.bankAccounts;
  $('bank-account-status').value='ALL';$('bank-account-query').value='żaneta';$('bank-account-currency').value='pln';
  assert.equal(calls.length,0,'no automatic account read at script initialization');
  await ui.load();assert.ok(calls.at(-1).url.includes('currency=PLN'));assert.equal($('bank-accounts-next').disabled,false);
  assert.ok(text($('bank-accounts-rows')).includes(account.owner),'untrusted names are text, not HTML');
  assert.ok(text($('bank-accounts-rows')).includes('12345678901234567,89 PLN'));
  await $('bank-accounts-next').events.click();assert.ok(calls.at(-1).url.includes('before=9007199254740993'));assert.equal($('bank-accounts-prev').disabled,false);
  await $('bank-accounts-prev').events.click();assert.ok(!calls.at(-1).url.includes('before='));
  await $('bank-accounts-rows').children[0].children[4].children[0].events.click();
  assert.ok(calls.at(-1).url.includes('/9007199254740993?profile=dev'));
  assert.ok(text($('bank-account-fields')).includes(account.accountNumber));assert.ok(text($('bank-account-fields')).includes('Brak danych'));
  $('bank-account-close').events.click();assert.equal($('bank-account-fields').children.length,0,'detail data cleared on close');
  fail=true;await ui.load();assert.equal($('bank-accounts-rows').children.length,0);assert.ok($('bank-accounts-status').textContent.includes('nie traktuj tego jako pustej bazy'));fail=false;
  defer={};const pending=ui.load();const resolve=defer.resolve;state.profile='prod';ui.reset();defer=null;resolve({accounts:[account],next:null,pageSize:50});await pending;
  assert.equal($('bank-accounts-rows').children.length,0,'late DEV response must not populate PROD');
  const count=calls.length;await ui.load();assert.equal(calls.length,count);assert.ok($('bank-accounts-data').classList.contains('hidden'));
  state.profile='test';await ui.load();assert.equal(calls.length,count);
  state.profile='dev';state.view='accounts';await ui.load();ui.reset();assert.equal($('bank-account-query').value,'');assert.equal($('bank-accounts-rows').children.length,0);
  assert.ok(calls.every(c=>c.payload===undefined),'all account requests are GET-only');
  console.log('Bank account UI: paging/int64/decimal, text-only PII, detail cleanup, errors, profile/stale guards and GET-only requests — OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
