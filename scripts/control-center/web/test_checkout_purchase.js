'use strict';
// Execute the real checkout script against an offline DOM and in-memory synthetic responses.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const html=fs.readFileSync(path.join(__dirname,'../../../shop-app/src/main/resources/static/checkout.html'),'utf8');
const source=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]).join('\n');
const nodes=new Map(),requests=[];let confirmed=false,fixtureItems=[];
const node=id=>{if(!nodes.has(id))nodes.set(id,{value:'PLN',children:[],textContent:'',className:'',disabled:false,hidden:false,events:{},classList:{add(){},remove(){},toggle(){}},append(...items){this.children.push(...items);},appendChild(item){this.children.push(item);},replaceChildren(...items){this.children=items;},addEventListener(name,fn){this.events[name]=fn;}});return nodes.get(id);};
let identity={purchaseId:'old-purchase',canStartIndependent:true,blocked:true,requiresIndependentAcknowledgement:true,orders:[]};
const context={console,Headers,Intl,Number,Array,JSON,encodeURIComponent,decodeURIComponent,crypto:{randomUUID:()=> '12345678-1234-4234-8234-123456789abc'},
  document:{getElementById:node,createElement:tag=>node('created-'+nodes.size),cookie:'SHOP-XSRF-TOKEN=fixture'},
  window:{location:{href:''},confirm:()=>confirmed,addEventListener(){},open:()=>null},
  fetch:async(url,options={})=>{requests.push({url,options});let body={};if(url==='/api/cart')body={items:fixtureItems,total:fixtureItems.length,currency:'PLN'};
    if(url==='/api/checkout/quote')body={amount:1,currency:'PLN',rate:1};
    if(url==='/api/cart/purchase')body=identity;if(url==='/api/checkout/card-provider')body={provider:'bank-demo'};
    if(url==='/api/cart/independent-purchase'){identity={...identity,purchaseId:'new-purchase',blocked:false,requiresIndependentAcknowledgement:false};body=identity;}
    if(url==='/api/orders/41/cancel-purchase'){identity={...identity,orders:[]};body={status:'CANCELLED',message:'Produkty zachowano w koszyku.'};}
    return {ok:true,status:200,json:async()=>body};}};
vm.createContext(context);vm.runInContext(source,context);
(async()=>{
  // Flush the asynchronous page load without clocks or live network.
  for(let n=0;n<15;n++)await new Promise(r=>setImmediate(r));
  const click=node('independentPurchase').events.click;
  await click();assert.equal(requests.some(r=>r.url==='/api/cart/independent-purchase'),false);
  confirmed=true;await click();const write=requests.find(r=>r.url==='/api/cart/independent-purchase');
  assert.equal(write.options.method,'POST');assert.equal(write.options.headers.get('X-XSRF-TOKEN'),'fixture');
  assert.deepEqual(JSON.parse(write.options.body),{purchaseId:'old-purchase',acknowledged:true});
  assert.equal(requests.some(r=>r.url.startsWith('/api/orders')),false);
  assert.ok(node('message').textContent.includes('Dodaj produkty ponownie'));
  identity={...identity,requiresIndependentAcknowledgement:false,blocked:false,orders:[{id:'41',status:'PENDING',provider:null,cancelRequestedAt:null}]};await context.refreshCart();
  assert.equal(node('pendingPurchase').hidden,false);assert.equal(node('purchaseIdentity').hidden,true);
  confirmed=false;await node('cancelPurchase').events.click();assert.equal(requests.some(r=>r.url==='/api/orders/41/cancel-purchase'),false);
  confirmed=true;await node('cancelPurchase').events.click();const cancellation=requests.find(r=>r.url==='/api/orders/41/cancel-purchase');
  assert.equal(cancellation.options.headers.get('X-XSRF-TOKEN'),'fixture');assert.equal(JSON.parse(cancellation.options.body).confirmed,true);
  assert.equal(node('pendingPurchase').hidden,true);assert.equal(requests.filter(r=>r.url==='/api/cart/independent-purchase').length,1);
  assert.equal(node('bankPay').disabled,false);
  fixtureItems=[{name:'Synthetic product',quantity:1,unitPrice:1,lineTotal:1,currency:'PLN'}];
  identity={...identity,orders:[{id:'41',status:'PENDING',provider:null,cancelRequestedAt:null}]};await context.refreshCart();context.selectMethod('card');
  for(let n=0;n<10;n++)await new Promise(r=>setImmediate(r));
  await node('cancelPurchase').events.click();assert.equal(node('cardPay').disabled,false);assert.equal(node('bankPay').disabled,false);
  assert.ok(node('message').textContent.includes('Produkty zachowano'));assert.equal(fixtureItems.length,1);
  console.log('Checkout identity: explicit independent purchase confirmation, original identity, CSRF, empty cart and no payment dispatch — OK');
})().catch(error=>{console.error(error);process.exitCode=1;});
