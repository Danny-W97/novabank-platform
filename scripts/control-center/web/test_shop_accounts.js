'use strict';
// Offline DOM/API fixtures, no actual customer data or browser storage.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
class Node {
  constructor(tag='div',text=''){this.tag=tag;this.textContent=text;this.children=[];this.value='';this.events={};this.attrs={};this.open=false;this.disabled=false;const flags=new Set();this.classList={toggle:(k,on)=>on?flags.add(k):flags.delete(k),contains:k=>flags.has(k)};}
  append(...items){this.children.push(...items);} replaceChildren(...items){this.children=items;this.textContent='';}
  addEventListener(k,fn){this.events[k]=fn;}setAttribute(k,v){this.attrs[k]=v;}
  querySelectorAll(selector){const tags=selector.split(',');return this.children.flatMap(c=>[...(tags.includes(c.tag)?[c]:[]),...c.querySelectorAll(selector)]);}
  showModal(){this.open=true;} close(){if(this.open){this.open=false;this.events.close?.();}}
}
const nodes=new Map(),$=id=>{if(!nodes.has(id))nodes.set(id,new Node());return nodes.get(id);};
const state={profile:'dev',view:'shopAccounts',token:'fixture-token'},calls=[];
const account={id:'9007199254740993',customerId:'client-fixture',email:'fixture@example.invalid',firstName:'<img src=x onerror=alert(1)>',lastName:null,active:false,role:'ADMIN',createdAt:'2026-10-15T10:00:00Z',updatedAt:'2026-10-15T10:00:00Z',hasPhone:true,hasAddress:false};
let fail=false,defer=null,empty=false;
const context={window:{},state,$,URLSearchParams,Date,el:(tag,text='')=>new Node(tag,text),pill:text=>new Node('span',text),
 api:async(url,payload)=>{calls.push({url,payload});if(fail)throw Error('fixture failure');if(defer)return new Promise(resolve=>{defer.resolve=resolve;});if(url.startsWith('/api/shop-accounts/'))return{account,readOnly:true};const q=new URL(url,'http://fixture').searchParams;return{accounts:empty?[]:[account],next:q.has('before')||empty?null:account.id,pageSize:50,readOnly:true};}};
vm.createContext(context);vm.runInContext(fs.readFileSync(path.join(__dirname,'shop-accounts.js'),'utf8'),context);
const text=node=>String(node.textContent)+node.children.map(text).join(' ');
(async()=>{
 const ui=context.window.shopAccounts;
 $('shop-account-status').value='ALL';$('shop-account-role').value='ADMIN';$('shop-account-query').value='Żaneta';
 assert.equal(calls.length,0,'no read on initialization');await ui.load();assert.ok(calls.at(-1).url.includes('role=ADMIN'));
 assert.ok(text($('shop-accounts-rows')).includes(account.firstName),'HTML is displayed as text');assert.ok(text($('shop-accounts-rows')).includes('Nieaktywne'));
 $('shop-account-role').value='CUSTOMER';await $('shop-accounts-next').events.click();assert.ok(calls.at(-1).url.includes('role=ADMIN'),'pagination uses applied filters');assert.ok(calls.at(-1).url.includes('before=9007199254740993'));
 await $('shop-accounts-prev').events.click();assert.ok(!calls.at(-1).url.includes('before='));
 await $('shop-accounts-rows').children[0].children[4].children[0].events.click();assert.ok(calls.at(-1).url.includes('/9007199254740993?profile=dev'));
 assert.ok(text($('shop-account-fields')).includes('Nie uzupełniono'));assert.ok(text($('shop-account-fields')).includes('Tak — treść ukryta'));
 let purchaseCustomer=null;context.window.paymentCases={forCustomer:value=>{purchaseCustomer=value;}};assert.equal($('shop-account-purchases').disabled,false);$('shop-account-purchases').onclick();assert.equal(purchaseCustomer,account.customerId);assert.equal($('shop-account-dialog').open,false);
 $('shop-account-close').events.click();assert.equal($('shop-account-fields').children.length,0);
 empty=true;await ui.load();assert.ok(text($('shop-accounts-rows')).includes('Brak kont sklepu'));empty=false;
 fail=true;await ui.load();assert.equal($('shop-accounts-rows').children.length,0);assert.ok($('shop-accounts-status').textContent.includes('nie traktuj tego jako pustej bazy'));fail=false;
 defer={};const pending=ui.load(),resolve=defer.resolve;state.profile='prod';ui.reset();defer=null;resolve({accounts:[account],next:null,pageSize:50});await pending;
 assert.equal($('shop-accounts-rows').children.length,0,'stale DEV data never appear in PROD');const count=calls.length;await ui.load();state.profile='test';await ui.load();assert.equal(calls.length,count);
 state.profile='dev';state.view='shopAccounts';await ui.load();defer={};const pendingDetail=$('shop-accounts-rows').children[0].children[4].children[0].events.click(),resolveDetail=defer.resolve;ui.reset();defer=null;resolveDetail({account});await pendingDetail;assert.equal($('shop-account-fields').children.length,0,'closed detail ignores late response');
 assert.ok(calls.every(c=>c.payload===undefined),'all account requests are GET-only');
 console.log('Shop account UI: paging/string IDs, literal text, missing profile, details, empty/errors and stale/profile guards — OK');
})().catch(e=>{console.error(e);process.exitCode=1;});
