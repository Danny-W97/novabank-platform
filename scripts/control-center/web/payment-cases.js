'use strict';
window.paymentCases = (() => {
  let generation=0,selected=null,next=null,loading=false,pending=null;
  let pages=[null],pageIndex=0,appliedFilters=null,returnButton=null;
  let bucket='CURRENT';
  const labels={UNKNOWN:'Nierozstrzygnięta',PAID:'Opłacona',DECLINED:'Potwierdzona odmowa',REFUNDED:'Zwrócona',REFUND_REVIEW:'Zwrot do sprawdzenia',CONFLICT:'Konflikt',
    OPEN:'Otwarta',ESCALATED:'Eskalowana',CLOSED_UNRESOLVED:'Zamknięta bez rozstrzygnięcia',RESOLVED:'Zamknięta z rozstrzygnięciem',FAILED:'Nieudana',PENDING:'Oczekująca',
    READY:'Plan gotowy',APPLYING:'Uzgodnienie w toku',COMPLETED:'Zakończone',SUCCEEDED:'Sukces',CREATED:'Utworzona',PROCESSING:'Przetwarzana',AWAITING_AUTHORIZATION:'Oczekuje na autoryzację',
    NOT_STARTED:'Płatności nie wysłano',CANCELLED:'Potwierdzona rezygnacja',PURCHASE_CANCEL_REQUESTED:'Prośba o rezygnację',PURCHASE_CANCEL_REVIEW:'Rezygnacja do wyjaśnienia',PURCHASE_CANCEL_CANCELLED:'Potwierdzona rezygnacja',PURCHASE_CANCEL_PAID:'Zakup okazał się opłacony',CHECK:'Sprawdzenie dowodów',ESCALATE:'Eskalacja',CLOSE_UNRESOLVED:'Zamknięcie bez rozstrzygnięcia',CLOSE_CANCELLED:'Zamknięcie po potwierdzonej rezygnacji',LATE_PAYMENT_SUCCESS:'Spóźnione potwierdzenie opłacenia',ALLOW_NEW_PURCHASE:'Zgoda na nowy zakup',REVOKE_NEW_PURCHASE:'Cofnięcie zgody'};
  const named=value=>Object.hasOwn(labels,value)?`${labels[value]} · ${value}`:String(value??'—');
  function badge(value){return el('span',named(value),'payment-case-badge '+(['PAID','SUCCEEDED','COMPLETED','RESOLVED','REFUNDED','CANCELLED'].includes(value)?'good':['FAILED','DECLINED','CONFLICT'].includes(value)?'bad':['UNKNOWN','REFUND_REVIEW','APPLYING'].includes(value)?'warning':''));}
  function pairs(entries){const list=el('dl','','payment-case-pairs');for(const [key,value] of entries){list.append(el('dt',key),el('dd',value==null?'—':typeof value==='object'?JSON.stringify(value):String(value)));}return list;}
  function empty(text){return el('p',text,'payment-case-empty');}
  function focus(id){const node=$(id);const surface=$(id==='payment-case-title'?'payment-case-detail':'payment-case-list');surface.scrollIntoView({block:'start',behavior:'auto'});node.focus({preventScroll:true});}
  function showDetail(on){$('payment-case-list').classList.toggle('hidden',on);$('payment-case-detail').classList.toggle('hidden',!on);}
  function clearDetail(){
    for(const id of ['payment-case-title','payment-case-order','payment-case-feedback','payment-case-action-note','payment-case-plan-state','payment-case-event-count','payment-case-evidence','payment-case-payments'])$(id).textContent='';
    for(const id of ['payment-case-summary','payment-case-evidence-view','payment-case-payments-view','payment-case-events','payment-case-actions'])$(id).replaceChildren();
    for(const node of $('payment-case-detail').querySelectorAll('details'))node.open=false;
  }
  const dialog=el('dialog','','administration-dialog'),title=el('h2'),notice=el('p'),reason=el('textarea'),ack=el('input');
  reason.maxLength=1000;reason.rows=4;reason.placeholder='Uzasadnienie decyzji — 8–1000 znaków. Bez sekretów i danych karty.';
  const backup=el('input'),paused=el('input');backup.type=paused.type='checkbox';
  const backupLabel=el('label','Potwierdzam odtworzenie świeżych kopii obu baz powiązanych z planem.');backupLabel.append(backup);
  const pauseLabel=el('label','Potwierdzam zatrzymanie banku, sklepu, listenera i pozostałych zapisów.');pauseLabel.append(paused);
  const reasonLabel=el('label','Uzasadnienie');reasonLabel.append(reason);const ackLabel=el('label','Wpisz wymagane potwierdzenie');ackLabel.append(ack);
  const feedback=el('p'),run=el('button','Wykonaj','button primary'),cancel=el('button','Anuluj','button outline');
  dialog.append(title,notice,reasonLabel,ackLabel,backupLabel,pauseLabel,feedback,run,cancel);document.body.append(dialog);
  const current=()=>state.profile==='dev'&&state.view==='paymentCases';
  function controls(){
    run.disabled=!pending||!current()||busy()||reason.value.trim().length<8||ack.value!==pending.word||
      (pending.action==='payment-case-apply'&&(!backup.checked||!paused.checked));
    $('payment-case-more').disabled=!current()||loading||!next;
    $('payment-case-previous').disabled=!current()||loading||pageIndex===0;
    $('payment-case-reload').disabled=!current()||loading;
    for(const button of $('payment-case-rows').querySelectorAll('button'))button.disabled=!current()||loading;
    for(const button of $('payment-case-actions').querySelectorAll('button'))button.disabled=!current()||busy()||loading;
    for(const [key,suffix] of [['CURRENT','current'],['REVIEW','review'],['HISTORY','history']]){const button=$('payment-case-tab-'+suffix);button.className='button '+(key===bucket?'primary':'outline');button.setAttribute('aria-pressed',String(key===bucket));}
  }
  function reset(){generation++;loading=false;selected=null;next=null;pending=null;pages=[null];pageIndex=0;appliedFilters=null;returnButton=null;dialog.close();reason.value='';ack.value='';backup.checked=paused.checked=false;
    $('payment-case-rows').replaceChildren();clearDetail();showDetail(false);$('payment-case-page').textContent='Strona 1 · do 25 spraw na stronie';controls();}
  async function load(cursor=null,page=0,filters=null){
    const gen=++generation;loading=true;controls();$('payment-case-status').textContent=state.profile==='dev'?'Ładowanie spraw…':'Sprawy dostępne wyłącznie w DEV.';
    if(state.profile!=='dev'){reset();loading=false;return;}
    try{
      const values=filters||{view:$('payment-case-view').value,q:$('payment-case-query').value,bucket};
      const params=new URLSearchParams({profile:'dev',...values});if(cursor)params.set('before',cursor);
      const data=await api('/api/payment-cases?'+params);if(gen!==generation||!current())return;
      pages=page===0?[null]:pages.slice(0,page);pages[page]=cursor;pageIndex=page;appliedFilters={...values};selected=null;clearDetail();showDetail(false);
      next=data.next;const rows=$('payment-case-rows');rows.replaceChildren();
      $('payment-case-table-scroll').scrollTop=0;
      for(const item of data.cases){const row=el('tr');
        const identity=el('td');identity.append(el('strong',`#${item.id}`),el('small',item.orderNumber),el('small',item.customerId));row.append(identity,el('td',`${item.amount} ${item.currency}`));
        for(const status of [item.localStatus,item.outcome,item.workflow]){const cell=el('td');cell.append(badge(status));row.append(cell);}
        row.append(el('td',item.unresolved?(item.allowNewPurchaseAt?'Dopuszczony':'Brak zgody'):'Bez wyjątku ryzyka'));
        const cell=el('td'),button=el('button','Szczegóły','button outline');button.onclick=()=>{returnButton=button;return detail(item.id);};cell.append(button);row.append(cell);rows.append(row);
      }
      if(!data.cases.length){const row=el('tr'),cell=el('td');cell.colSpan=7;cell.append(empty('Brak spraw dla wybranych filtrów.'));row.append(cell);rows.append(row);}
      $('payment-case-page').textContent=`Strona ${pageIndex+1} · ${data.cases.length} spraw · do 25 na stronie`;
      $('payment-case-status').textContent=`Pozycje: ${data.cases.length}. UNKNOWN nie jest potwierdzoną odmową.`;
    }catch(error){if(gen===generation)$('payment-case-status').textContent=error.message;}
    finally{if(gen===generation){loading=false;controls();}}
  }
  async function detail(id,navigate=true){
    const gen=++generation;loading=true;controls();
    $('payment-case-feedback').textContent='Ładowanie szczegółów…';
    try{
      const data=await api(`/api/payment-cases/${encodeURIComponent(id)}?profile=dev`);if(gen!==generation||!current())return;
      selected=data;clearDetail();showDetail(true);$('payment-case-title').textContent=`Sprawa #${data.id}`;
      $('payment-case-order').textContent=`Zamówienie: ${data.orderNumber}\nKlient: ${data.customerId}`;
      const summary=$('payment-case-summary');for(const [label,value] of [['Kwota',`${data.amount} ${data.currency}`],['Wynik finansowy',named(data.outcome)],['Stan zamówienia',named(data.localStatus)],['Obsługa sprawy',named(data.workflow)],['Niezależny nowy zakup',data.allowNewPurchaseAt?'Zgoda operatora':'Brak zgody'],['Wersja sprawy',data.version]]){
        const metric=el('div','','payment-case-metric');metric.append(el('span',label),el('strong',value));summary.append(metric);
      }
      $('payment-case-evidence').textContent=JSON.stringify(data.plan||{note:'Brak planu. Sprawdź dowody.'},null,2);
      $('payment-case-payments').textContent=JSON.stringify({attempts:data.attempts,refunds:data.refunds},null,2);
      const evidence=$('payment-case-evidence-view');$('payment-case-plan-state').textContent=data.plan?named(data.plan.status):'Brak planu';
      if(!data.plan)evidence.append(empty('Nie sprawdzono jeszcze dowodów. Użyj „Sprawdź dowody”, aby przygotować plan. Brak planu nie rozstrzyga płatności.'));
      else{
        evidence.append(pairs([['Ocena',named(data.plan.resolution)],['Ważny do',date(data.plan.expiresAt)],['Identyfikator planu',data.plan.digest]]));
        if(!data.plan.facts?.length)evidence.append(empty('Plan nie zawiera dodatkowych faktów.'));
        for(const fact of data.plan.facts||[]){const card=el('article','','payment-case-record');card.append(el('h4',fact.source||'Dowód'),pairs(Object.entries(fact).filter(([key])=>key!=='source')));evidence.append(card);}
      }
      const payments=$('payment-case-payments-view');
      for(const [heading,items,fields] of [['Próby płatności',data.attempts,[['provider','Operator'],['state','Stan'],['amount','Kwota'],['currency','Waluta'],['failures','Błędne sprawdzenia'],['suspendedAt','Zawieszono'],['updatedAt','Aktualizacja']]],['Zwroty',data.refunds,[['id','ID'],['status','Stan'],['amount','Kwota'],['currency','Waluta'],['reference','Referencja']]]]){
        payments.append(el('h4',`${heading} (${items.length})`));if(!items.length)payments.append(empty(`Brak wpisów: ${heading.toLowerCase()}.`));
        for(const item of items){const card=el('article','','payment-case-record');card.append(pairs(fields.map(([key,label])=>[label,['state','status'].includes(key)?named(item[key]):key.endsWith('At')&&item[key]?date(item[key]):item[key]])));payments.append(card);}
      }
      const events=$('payment-case-events');$('payment-case-event-count').textContent=`Widoczne: ${data.events.length}`;
      if(!data.events.length)events.append(empty('Brak zarejestrowanych decyzji operatora dla tej sprawy.'));
      for(const event of data.events){const card=el('article','','payment-case-event'),header=el('header');header.append(el('strong',named(event.action)),el('time',date(event.at)));card.append(header,el('p',event.reason),el('small',`Operator: ${event.operator} · wersja ${event.version??'—'} · UUID ${event.requestId}`));events.append(card);}
      const actions=$('payment-case-actions');actions.replaceChildren();
      const closed=['CLOSED_UNRESOLVED','RESOLVED'].includes(data.workflow);
      $('payment-case-action-note').textContent=data.workflow==='CLOSED_UNRESOLVED'?'Sprawa jest w Historii. Wynik UNKNOWN pozostaje zapisany; ponawianie płatności tego zamówienia jest zablokowane.':data.workflow==='RESOLVED'?'Sprawa została zamknięta i jest dostępna w Historii.':data.cancellationAvailable?'Anulowanie sprawdzi dowody i ewentualnie zakończy płatność u operatora. Sprawa zostanie zamknięta jako CANCELLED tylko po potwierdzeniu braku obciążenia.':data.outcome==='UNKNOWN'?'Brak potwierdzenia braku obciążenia pozwala jedynie zamknąć sprawę bez rozstrzygnięcia. UNKNOWN pozostanie w historii.':'Dostępne działania zależą od wyniku finansowego. Sprawdź dowody przed dalszą decyzją.';
      const add=(text,action,decision)=>{const b=el('button',text,action==='payment-case-plan'?'button primary':'button outline');b.onclick=()=>open(action,decision);actions.append(b);};
      add('Sprawdź dowody','payment-case-plan');
      if(data.plan&&['READY','APPLYING'].includes(data.plan.status)&&['PAID','DECLINED','REFUNDED'].includes(data.plan.resolution))add(data.plan.status==='APPLYING'?'Wznów zatwierdzone uzgodnienie':'Zastosuj potwierdzony wynik','payment-case-apply');
      add('Eskaluj','payment-case-decision','ESCALATE');
      if(!closed&&data.outcome==='UNKNOWN')add('Zamknij sprawę bez rozstrzygnięcia','payment-case-decision','CLOSE_UNRESOLVED');
      if(data.unresolved)add('Dopuść niezależny nowy zakup','payment-case-decision','ALLOW_NEW_PURCHASE');
      if(data.allowNewPurchaseAt)add('Cofnij zgodę na nowy zakup','payment-case-decision','REVOKE_NEW_PURCHASE');
      if(data.cancellationAvailable&&!(data.localStatus==='CANCELLED'&&data.workflow==='RESOLVED')){
        if(data.localStatus!=='CANCELLED')add('Sprawdź stan operatora','purchase-refresh');
        add(data.localStatus==='CANCELLED'?'Zamknij potwierdzoną rezygnację':'Potwierdź brak obciążenia i anuluj zakup','purchase-cancel');
      }
      if(navigate)focus('payment-case-title');
    }catch(error){if(gen===generation){$('payment-case-status').textContent=error.message;$('payment-case-feedback').textContent=error.message;}}
    finally{if(gen===generation){loading=false;controls();}}
  }
  function back(){generation++;loading=false;selected=null;clearDetail();showDetail(false);controls();
    if(returnButton?.isConnected){returnButton.focus({preventScroll:true});returnButton.scrollIntoView({block:'center',behavior:'auto'});}
    else focus('payment-case-list-title');
  }
  function open(action,decision){
    if(!selected||!current()||busy())return;
    pending={action,decision,word:action==='purchase-cancel'?'CANCEL':action==='purchase-refresh'?'REFRESH':action==='payment-case-apply'?'RECONCILE':action==='payment-case-decision'?'DECIDE':'CHECK',
      orderId:selected.id,version:selected.version,requestId:crypto.randomUUID(),digest:selected.plan?.digest};
    const resume=action==='payment-case-apply'&&selected.plan?.status==='APPLYING';
    if(resume)pending.requestId=selected.plan.applyRequestId;
    reason.value=resume?selected.plan.applyReason:'';reason.disabled=resume;ack.value='';backup.checked=paused.checked=false;feedback.textContent='';
    title.textContent=`${action==='purchase-cancel'?'Anuluj zakup i zamknij sprawę':decision?labels[decision]:action} · zamówienie #${selected.id}`;
    notice.textContent=action==='purchase-cancel'?'Zapisuje prośbę i blokuje ponawianie płatności tego zamówienia. Sprawdza bank lub Stripe i może anulować oczekującą autoryzację albo sesję. Tylko potwierdzony brak obciążenia pozwala zapisać CANCELLED i zamknąć sprawę w Historii. Przy timeout, braku dowodów lub opłaceniu sprawa nie zostanie zamknięta jako anulowana. Produkty pozostają zachowane.':action==='purchase-refresh'?'Sprawdza istniejącą próbę u operatora. Może zapisać potwierdzony wynik; nie wysyła nowego obciążenia, zwrotu ani rezygnacji.':action==='payment-case-apply'?'Nie wysyła obciążenia ani zwrotu. Utrwala wyłącznie dowód zgodny z zatwierdzonym planem; po awarii możliwy częściowy zapis, wznawiaj ten sam UUID.':
      decision==='CLOSE_UNRESOLVED'?'Zamyka sprawę administracyjnie i przenosi ją do Historii. Wynik finansowy pozostaje UNKNOWN. Ponawianie płatności tego zamówienia zostaje zablokowane; istniejąca płatność może nadal otrzymać wynik od operatora.':
      decision==='ALLOW_NEW_PURCHASE'?'Akceptujesz ryzyko starej nierozstrzygniętej płatności. Nie zostaje ona odrzucona. Klient musi odłożyć stary koszyk i jawnie rozpocząć nowy zakup. Zgoda nie obejmuje przyszłych płatności.':'Decyzja administracyjna nie zmienia sald ani wyniku finansowego. CHECK zapisuje dowody i historię odczytu.';
    ack.placeholder=`Wpisz ${pending.word}`;backupLabel.classList.toggle('hidden',action!=='payment-case-apply');pauseLabel.classList.toggle('hidden',action!=='payment-case-apply');controls();dialog.showModal();
  }
  run.onclick=async()=>{
    controls();if(run.disabled)return;const item=pending;
    const parameters={orderId:item.orderId,requestId:item.requestId,reason:reason.value};
    if(item.action==='purchase-cancel'||item.action==='purchase-refresh')parameters.confirmation=item.word;
    if(item.action==='payment-case-decision')Object.assign(parameters,{decision:item.decision,expectedVersion:item.version,confirmation:'DECIDE'});
    if(item.action==='payment-case-apply')Object.assign(parameters,{planDigest:item.digest,confirmation:'RECONCILE',backupsVerified:true,writesPaused:true});
    const action=state.actions.find(a=>a.id===item.action);if(!action){feedback.textContent='Brak operacji. Odśwież panel.';return;}
    pending=null;dialog.close();reason.value='';await submit(action,true,{parameters});
  };
  cancel.onclick=()=>{pending=null;reason.value='';dialog.close();};dialog.addEventListener('cancel',()=>{pending=null;reason.value='';});
  for(const input of [reason,ack,backup,paused]){input.addEventListener('input',controls);input.addEventListener('change',controls);}
  $('payment-case-filters').addEventListener('submit',event=>{event.preventDefault();if(['CLOSED_UNRESOLVED','RESOLVED'].includes($('payment-case-view').value))bucket='HISTORY';load();});
  $('payment-case-more').onclick=()=>next&&!loading&&load(next,pageIndex+1,appliedFilters);
  $('payment-case-previous').onclick=()=>pageIndex>0&&!loading&&load(pages[pageIndex-1],pageIndex-1,appliedFilters);
  $('payment-case-back').onclick=back;$('payment-case-reload').onclick=()=>selected&&detail(selected.id,false);
  $('refresh').addEventListener('click',()=>{if(current()){if(selected)detail(selected.id,false);else load(pages[pageIndex],pageIndex,appliedFilters);}});
  for(const [key,suffix] of [['CURRENT','current'],['REVIEW','review'],['HISTORY','history']])$('payment-case-tab-'+suffix).onclick=()=>{bucket=key;$('payment-case-view').value='ALL';return load();};
  function forCustomer(customer){if(state.profile!=='dev')return;$('payment-case-query').value=customer;bucket='CURRENT';$('payment-case-view').value='ALL';return changeView('paymentCases');}
  $('payment-case-view').value='ALL';
  return {load,reset,controls,detail,back,forCustomer};
})();
