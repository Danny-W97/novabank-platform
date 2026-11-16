'use strict';
window.operatorUI = (() => {
  const confirmAction = message => window.confirm(window.panelLanguage?.translate(message) || message);
  const fields = {
    backupDirectory: ['Katalog kopii DEV', 'text', 'Podkatalog .novabank/backups albo dedykowany pusty katalog WSL novabank-dev-backups[-nazwa].'],
    backupRetentionDays: ['Retencja kopii — dni', 'number', '1–3650. Retencja usuwa stare kopie dopiero w osobno potwierdzonej operacji backupu z retencją.'],
    backupAt: ['Codzienny backup — godzina', 'time', 'Czas lokalny systemu WSL. Istniejący timer wymaga ponownej instalacji.'],
    drWeekday: ['DR — dzień tygodnia', 'select', 'Cotygodniowy test odzyskiwania w izolowanym środowisku.'],
    drAt: ['DR — godzina', 'time', 'Czas lokalny WSL; komputer/systemd musi działać. Persistent może nadrobić pominięte zadanie.'],
    artifactRetentionDays: ['Raporty i rotowane logi — dni', 'number', '1–3650. Zamknięty zakres katalogów, bez sekretów, kodu i aktywnych logów.'],
    tempRetentionDays: ['Wygenerowane pliki tymczasowe — dni', 'number', '1–365. Nie rozszerza zakresu kasowania na inne katalogi.'],
  };
  let data=null, loading=false, saving=false, dirty=false, generation=0, pending=null, legacy=null;
  const active=()=>state.view==='maintenance', editable=()=>['dev','test'].includes(state.profile);
  function clearCli(){
    $('operator-timers').replaceChildren();$('operator-cli-state').textContent='';
    $('operator-cli-state').classList.toggle('hidden',true);$('operator-cli-section').classList.toggle('hidden',true);
  }
  function renderCli(schedules){
    clearCli();if(state.profile!=='dev')return;
    const plans=Array.isArray(schedules.timers)?schedules.timers:[];
    const flags=['canMigrate','legacyExists','legacyEnabled','legacyActive','newExists','serviceRunning'];
    const complete=schedules.available===true&&plans.length===2&&new Set(plans.map(p=>p.kind)).size===2&&plans.every(p=>['backup','dr'].includes(p.kind)&&flags.every(key=>typeof p[key]==='boolean'));
    const relevant=plans.filter(p=>p.canMigrate===true||p.legacyEnabled===true||p.legacyActive===true||(p.legacyExists===true&&(p.newExists!==true||p.serviceRunning===true)));
    $('operator-cli-section').classList.toggle('hidden',complete&&relevant.length===0);
    if(!complete){$('operator-cli-state').textContent='Nie potwierdzono pełnego stanu dawnych harmonogramów. Sprawdź komunikat powyżej i odśwież po zakończeniu zadania; nie uruchamiaj drugiego timera.';$('operator-cli-state').classList.toggle('hidden',false);}
    $('operator-timers').replaceChildren(...relevant.map(plan=>{const box=el('article','','operator-timer');box.append(el('h3',plan.kind==='backup'?'Backup CLI':'Disaster Recovery CLI'),el('code',`${plan.legacyTimer} → ${plan.newTimer}`),el('p',plan.reason),el('p',`CLI: ${plan.legacyEnabled?'włączony':'wyłączony'}; ${plan.legacyActive?'aktywny':'nieaktywny'}. Usługa: ${plan.serviceRunning?'trwa praca':'nie pracuje'}.`));if(plan.legacyBackupDirectory)box.append(el('p',`Kopie CLI pozostają w: ${plan.legacyBackupDirectory}`));const b=el('button','Pokaż plan migracji','button outline');b.type='button';b.dataset.unavailable=String(plan.canMigrate!==true);b.addEventListener('click',()=>openPlan(plan));box.append(b);return box;}));
  }
  function controls(){
    const blocked=loading||saving||busy()||!editable();
    for(const input of $('operator-form').querySelectorAll('input,select,button')) input.disabled=blocked;
    $('operator-save').disabled=blocked||!data||!dirty||!$('operator-confirm').checked;
    $('operator-reload').disabled=loading||saving||busy()||!editable();
    $('operator-legacy-reload').disabled=loading||saving||!editable()||!data;
    $('operator-history-import').disabled=blocked||dirty||!legacy?.history?.sourceExists;
    $('operator-form').classList.toggle('hidden',!editable()); $('operator-next').classList.toggle('hidden',!editable());
    $('operator-legacy').classList.toggle('hidden',!editable()); $('operator-history').classList.toggle('hidden',!editable()); $('operator-prod').classList.toggle('hidden',editable());
    for(const button of $('operator-actions').querySelectorAll('button')) button.disabled=blocked||dirty;
    for(const button of $('operator-timers').querySelectorAll('button')) button.disabled=blocked||dirty||button.dataset.unavailable==='true';
    for(const button of $('operator-vault-action').querySelectorAll('button')) button.disabled=loading||saving||busy();
    $('operator-migration-run').disabled=blocked||dirty||!pending||!$('operator-migration-confirm').checked||state.profile!=='dev';
  }
  function reset(){generation++;data=null;legacy=null;loading=false;dirty=false;pending=null;$('operator-fields').replaceChildren();clearCli();$('operator-managed').replaceChildren();$('operator-history-rows').replaceChildren();$('operator-actions').replaceChildren();$('operator-confirm').checked=false;$('operator-migration-dialog').close();$('operator-migration-plan').replaceChildren();controls();}
  function leave(){if(saving)return false;if(dirty&&!confirmAction('Odrzucić niezapisane ustawienia operacji?'))return false;reset();return true;}
  function render(){
    $('operator-title').textContent=`Parametry operacji ${state.profile.toUpperCase()}`;
    $('operator-fields').replaceChildren(...Object.entries(data.values).map(([key,value])=>{
      const [title,type,hint]=fields[key],label=el('label',title,`operator-field${key==='backupDirectory'?' wide':''}`),input=el(type==='select'?'select':'input');
      input.id=`operator-${key}`;input.name=key;
      if(type==='select'){for(const [val,text] of [['MON','Poniedziałek'],['TUE','Wtorek'],['WED','Środa'],['THU','Czwartek'],['FRI','Piątek'],['SAT','Sobota'],['SUN','Niedziela']]){const o=el('option',text);o.value=val;input.append(o);}}
      else{input.type=type;input.required=true;if(type==='number'){input.min='1';input.max=key==='tempRetentionDays'?'365':'3650';input.step='1';}if(type==='text'){input.maxLength=400;input.autocomplete='off';}}
      input.value=String(value);input.addEventListener('input',()=>{dirty=true;$('operator-confirm').checked=false;legacy=null;pending=null;clearCli();controls();});label.append(input,el('small',hint));return label;
    }));
    const ids=state.profile==='dev'?['backup-schedule-install','dr-schedule-install','backup-create','artifacts-cleanup']:['artifacts-cleanup'];
    $('operator-actions').replaceChildren(...ids.map(id=>state.actions.find(a=>a.id===id)).filter(Boolean).map(a=>{const b=actionButton(a);b.textContent=a.title;return b;}));
  }
  async function load(){
    if(saving||!active())return;
    if(dirty&&!confirmAction('Wczytać ponownie i odrzucić niezapisane ustawienia?'))return;
    reset();if(!editable()){$('operator-status').textContent='PROD: jawna diagnostyka Vault/CA, bez edycji retencji i timerów.';$('operator-vault-action').replaceChildren();const a=state.actions.find(x=>x.id==='vault-ca-diagnostics');if(a){const b=actionButton(a);b.textContent=a.title;$('operator-vault-action').append(b);}return;}
    if(!state.token)return;const mine=generation,profile=state.profile;loading=true;controls();$('operator-status').textContent='Odczytywanie ustawień…';
    try{const result=await api(`/api/operations/settings?profile=${profile}`);if(mine!==generation||!active()||profile!==state.profile)return;data=result;render();$('operator-status').textContent='Ustawienia wczytane. Zapis nie zmienia istniejących timerów; instalację/aktualizację wykonasz osobno.';}
    catch(error){if(mine===generation)$('operator-status').textContent=error.message;}
    finally{if(mine===generation){loading=false;controls();}}
    if(data&&mine===generation)await loadLegacy();
  }
  async function loadLegacy(){
    if(!data||dirty||loading||saving||!editable()||!active())return;
    const mine=generation,profile=state.profile;loading=true;controls();$('operator-legacy-note').textContent='Sprawdzanie znanych jednostek CLI…';
    try{const result=await api(`/api/operations/legacy?profile=${profile}`);if(mine!==generation||profile!==state.profile||!active())return;legacy=result;$('operator-legacy-note').textContent=result.schedules.note;
      const executionLabels={NEVER:'Jeszcze nie wykonano',RUNNING:'Wykonanie w toku',SUCCESS:'Ostatnie wykonanie zakończone sukcesem',FAILED:'Ostatnie wykonanie nieudane',UNKNOWN:'Nie potwierdzono wykonania'};
      $('operator-managed').replaceChildren(...(result.schedules.managed||[]).map(item=>{const box=el('article','','operator-timer');box.dataset.configuration=item.configuration;box.append(el('h3',item.kind==='backup'?'Backup WWW':'Disaster Recovery WWW'),el('code',item.timer),el('p',`Instalacja: ${item.installed?'jednostki obecne':'brak potwierdzonej pary'}. Timer: ${item.enabled?'włączony':'wyłączony'}; ${item.waiting?'aktywny':'nieaktywny'}.`),el('p',item.configurationNote),el('strong',executionLabels[item.execution]||executionLabels.UNKNOWN),el('p',`Ostatni start: ${item.lastStarted||'brak'}. Koniec: ${item.lastFinished||'brak'}. Kod: ${item.exitCode??'brak'}.`),el('p',`Następny termin: ${item.nextRun||'nie ustalono'}.`));return box;}));
      if(profile==='dev'&&!result.schedules.managed?.length)$('operator-managed').append(el('p','Nie otrzymano weryfikacji harmonogramów WWW. Uruchom aktualny backend Centrali i odśwież.'));
      renderCli(result.schedules);
      $('operator-history-note').textContent=result.history.note;
      $('operator-history-rows').replaceChildren(...result.history.items.map(item=>{const row=el('tr');row.append(el('td',new Date(item.created).toLocaleString(window.panelLanguage?.locale||'pl-PL')),el('td',item.result==='OK'?'OK':'Błąd'),el('td',item.title));return row;}));
      if(!result.history.items.length){const row=el('tr'),cell=el('td','Brak zaimportowanych wpisów CLI.');cell.colSpan=3;row.append(cell);$('operator-history-rows').append(row);}
    }catch(error){if(mine===generation){legacy=null;renderCli({available:false,timers:[]});$('operator-managed').replaceChildren();$('operator-history-rows').replaceChildren();$('operator-legacy-note').textContent=error.message;}}
    finally{if(mine===generation){loading=false;controls();}}
  }
  async function save(event){
    event.preventDefault();if(!data||saving||loading||busy()||!dirty||!editable()||!$('operator-confirm').checked)return;
    const values={};for(const key of Object.keys(data.values)){const input=$(`operator-${key}`);values[key]=fields[key][1]==='number'?Number(input.value):input.value;}
    saving=true;controls();$('profile').disabled=true;
    try{const result=await api('/api/operations/settings',{profile:state.profile,revision:data.revision,values,confirmed:true});data=result;dirty=false;legacy=null;$('operator-confirm').checked=false;render();$('operator-status').textContent='Zapisano ustawienia. Nie przeniesiono kopii ani nie zmieniono timerów. Zainstaluj/aktualizuj harmonogram osobną operacją.';}
    catch(error){$('operator-status').textContent=error.message;}
    finally{saving=false;$('profile').disabled=false;controls();renderActions();}
  }
  function openPlan(plan){
    if(!plan.canMigrate||dirty||busy()||state.profile!=='dev')return;pending=plan;$('operator-migration-title').textContent=`Migracja ${plan.kind==='backup'?'backupu':'DR'} CLI → WWW`;
    const list=el('ol');for(const text of [`Kopia jednostek ${plan.legacyTimer} i powiązanej usługi.`,`Wyłączenie starego timera ${plan.legacyTimer}; bez usuwania jednostek CLI i kopii baz.`,`Instalacja i włączenie ${plan.newTimer}.`,`Nowy katalog: ${plan.settings.backupDirectory}; retencja: ${plan.settings.backupRetentionDays} dni.`,`Termin: ${plan.kind==='backup'?'codziennie '+plan.settings.backupAt:plan.settings.drWeekday+' '+plan.settings.drAt}; według czasu lokalnego WSL.`])list.append(el('li',text));
    $('operator-migration-plan').replaceChildren(list);$('operator-migration-confirm').checked=false;$('operator-migration-dialog').showModal();controls();
  }
  $('operator-form').addEventListener('submit',save);$('operator-confirm').addEventListener('change',controls);$('operator-reload').addEventListener('click',()=>load());$('operator-legacy-reload').addEventListener('click',()=>loadLegacy());
  $('operator-history-import').addEventListener('click',()=>{const a=state.actions.find(x=>x.id==='import-cli-history');if(a&&!dirty)requestAction(a);});
  $('operator-migration-close').addEventListener('click',()=>$('operator-migration-dialog').close());$('operator-migration-confirm').addEventListener('change',controls);
  $('operator-migration-dialog').addEventListener('close',()=>{if(!$('operator-migration-dialog').open){pending=null;$('operator-migration-confirm').checked=false;$('operator-migration-plan').replaceChildren();}});
  $('operator-migration-run').addEventListener('click',()=>{if(!pending||dirty||busy()||state.profile!=='dev'||!$('operator-migration-confirm').checked)return;const plan=pending,a=state.actions.find(x=>x.id===`migrate-cli-${plan.kind}`);if(!a)return;$('operator-migration-dialog').close();submit(a,true,{parameters:{revision:plan.revision}});});
  controls();return{load,reset,controls,leave,get dirty(){return dirty;},get saving(){return saving;}};
})();
