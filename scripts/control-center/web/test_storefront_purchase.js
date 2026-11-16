'use strict';
// Real storefront script, synthetic API/DOM only; no browser or customer data.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../../../shop-app/src/main/resources/static/novatech_shop.html'),'utf8');
const source=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]).join('\n');
const nodes=new Map(),calls=[],toasts=[];
function node(id){if(!nodes.has(id)){const flags=new Set();nodes.set(id,{children:[],style:{},hidden:true,textContent:'',classList:{add:k=>flags.add(k),remove:k=>flags.delete(k),contains:k=>flags.has(k)},append(...n){this.children.push(...n);},appendChild(n){this.children.push(n);},replaceChildren(){this.children=[];},addEventListener(){},setAttribute(){},querySelector(){return node('button-text');}});}return nodes.get(id);}
let purchase={orders:[{id:'41',cancelRequestedAt:null}],blocked:false,requiresIndependentAcknowledgement:false};
let fail=false;
const context={console,Headers,Intl,setTimeout:()=>0,clearTimeout(){},
  document:{getElementById:node,createElement:()=>node('created-'+nodes.size),addEventListener(){},body:{style:{}}},
  window:{addEventListener(){},location:{href:''}}};
vm.createContext(context);vm.runInContext(source,context);
context.apiFetch=async(url,options={})=>{calls.push({url,options});if(fail)throw Error('Synthetic unavailable');return{ok:true,json:async()=>url==='/api/cart/purchase'?purchase:{items:[]}};};
context.showToast=(...args)=>toasts.push(args);
vm.runInContext('currentUser={customerId:"synthetic"};',context);
const flush=async()=>{for(let n=0;n<8;n++)await new Promise(resolve=>setImmediate(resolve));};
(async()=>{
  await context.syncCart();assert.equal(node('cartPurchaseNotice').hidden,false);
  assert.match(node('cartPurchaseMessage').textContent,/produkty pozostaną/);
  const button=node('add');await context.addToCart({id:1,name:'Fixture',price:1},button);await flush();
  assert.equal(calls.some(call=>call.options.method),false);assert.equal(node('cartSidebar').classList.contains('open'),true);assert.equal(toasts.length,0);
  await context.changeQty(1,2);await context.removeFromCart(1);await flush();assert.equal(calls.some(call=>call.options.method),false);
  purchase.orders[0].cancelRequestedAt='2026-10-21T10:00:00Z';await context.syncCart();assert.match(node('cartPurchaseMessage').textContent,/czeka na potwierdzenie/);
  purchase={orders:[],blocked:false,requiresIndependentAcknowledgement:false};await context.addToCart({id:1,name:'Fixture',price:1},button);await flush();
  assert.equal(calls.filter(call=>call.options.method==='POST').length,1);assert.equal(node('cartPurchaseNotice').hidden,true);assert.equal(node('checkoutBtn').textContent,'Przejdź do kasy');
  fail=true;const before=calls.filter(call=>call.options.method).length;await context.addToCart({id:1,name:'Fixture',price:1},button);
  assert.equal(calls.filter(call=>call.options.method).length,before);assert.equal(toasts.at(-1)[2],'error');
  assert.match(html,/href="\/checkout.html">Przejdź do obsługi zakupu/);
  console.log('Storefront: proactive unfinished-purchase notice, guarded mutations, cancellation pending, editable after release, read failure — OK');
})().catch(error=>{console.error(error);process.exitCode=1;});
