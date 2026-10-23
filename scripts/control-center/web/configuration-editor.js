'use strict';
// Values are kept only in this tab's memory. Never localStorage, URL or console.
window.fileEditor = (() => {
  let data = null, selected = null, changes = {}, remove = new Set(), generation = 0, saving = false, loading = false;
  let entryFilter = 'all', filterGeneration = 0;
  const confirmAction = message => window.confirm(window.panelLanguage?.translate(message) || message);
  const filters = [
    {value: 'all', id: 'file-filter-all', count: 'file-count-all'},
    {value: 'secret', id: 'file-filter-secret', count: 'file-count-secret'},
    {value: 'setting', id: 'file-filter-setting', count: 'file-count-setting'},
  ];
  const timers = new Map();
  let pendingReveal = null;
  const dirty = () => Object.keys(changes).length > 0 || remove.size > 0;
  function status(message) {$('file-editor-status').textContent = message;}
  function hideSecrets() {
    for (const [input, timer] of timers) {
      clearTimeout(timer); input.type = 'password';
      if (!Object.hasOwn(changes, input.name)) input.value = '';
    }
    timers.clear();
  }
  function applyFilter() {
    const counts = {all: 0, secret: 0, setting: 0};
    let visible = 0, hiddenChanges = 0;
    for (const row of $('file-entries').children) {
      const kind = row.classList.contains('secret-entry') ? 'secret' : 'setting';
      counts.all++; counts[kind]++;
      const hidden = entryFilter !== 'all' && entryFilter !== kind;
      row.classList.toggle('hidden', hidden);
      if (!hidden) visible++;
      else if (Object.hasOwn(changes, row.dataset.key) || remove.has(row.dataset.key)) hiddenChanges++;
    }
    for (const filter of filters) {
      $(filter.id).setAttribute('aria-pressed', String(filter.value === entryFilter));
      $(filter.id).classList.toggle('selected', filter.value === entryFilter);
      $(filter.count).textContent = String(counts[filter.value]);
    }
    $('file-filter-summary').textContent = selected ? `Widoczne: ${visible} z ${counts.all} pól.${hiddenChanges ? ` Ukryte pola z niezapisanymi zmianami: ${hiddenChanges}. Zapis obejmie również te pola.` : ''}` : '';
    $('file-filter-empty').textContent = entryFilter === 'all' ? 'Ten plik nie zawiera jeszcze ustawień. Dodaj pierwsze pole poniżej.' : `Brak ${entryFilter === 'secret' ? 'sekretów' : 'ustawień innych niż sekrety'} w tym pliku. Wybierz „Pokaż wszystko” lub dodaj nowe pole.`;
    $('file-filter-empty').classList.toggle('hidden', !selected || visible > 0);
  }
  function setFilter(value) {
    if (busy() || loading || saving || state.profile === 'prod' || entryFilter === value || !filters.some(filter => filter.value === value)) return;
    hideSecrets(); filterGeneration++; entryFilter = value; applyFilter();
  }
  function reset() {
    if ($('file-secret-dialog').open) $('file-secret-dialog').close('cancel');
    generation++; hideSecrets(); data = null; selected = null; changes = {}; remove.clear(); loading = false;
    entryFilter = 'all'; filterGeneration++;
    $('file-list').replaceChildren(); $('file-entries').replaceChildren(); $('file-editor-form').classList.add('hidden');
    $('file-new-key').value = ''; $('file-new-value').value = ''; $('file-save-check').checked = false; $('file-risk-check').checked = false;
    applyFilter();
  }
  function confirmReveal(key) {
    $('file-secret-description').textContent = `Ujawnić ${key} z ${selected.path} w tym oknie?`;
    $('file-secret-dialog').returnValue = 'cancel';
    $('file-secret-dialog').showModal();
    return new Promise(resolve => {pendingReveal = resolve;});
  }
  function controls() {
    const locked = busy() || loading || state.profile === 'prod';
    $('files-reload').disabled = locked;
    $('file-save').disabled = locked || !data || !selected || !$('file-save-check').checked;
    $('file-save').textContent = saving ? 'Zapisywanie…' : selected?.exists ? 'Zapisz zmiany' : 'Utwórz plik';
    for (const input of $('file-editor-form').querySelectorAll('input, button')) {
      if (input.id === 'file-save') continue;
      input.disabled = locked || input.dataset.readonly === 'true' || (input.name && remove.has(input.name));
    }
    for (const button of $('file-list').querySelectorAll('button')) button.disabled = locked;
  }
  async function load(preferred = null) {
    if (saving || (dirty() && !confirmAction('Wczytać pliki i odrzucić niezapisane zmiany?'))) return;
    reset();
    if (state.profile === 'prod') {status('PROD: edycja i podgląd sekretów są zablokowane. Wybierz DEV lub TEST.'); return;}
    const current = generation, profile = state.profile;
    loading = true; controls(); status('Odczyt listy plików — sekrety pozostają ukryte…');
    try {
      const result = await api(`/api/config/files?profile=${profile}`);
      if (generation !== current || state.profile !== profile || state.view !== 'files') return;
      data = result;
      for (const file of data.files) {
        const button = el('button', '', 'file-select-button'); button.type = 'button';
        button.append(el('b', file.path.split('/').at(-1)), el('code', file.path), pill(file.exists ? 'Istnieje' : 'Do utworzenia', file.exists ? 'up' : 'down'));
        button.addEventListener('click', () => choose(file.id)); $('file-list').append(button);
      }
      choose(preferred && data.files.some(file => file.id === preferred) ? preferred : data.files[0]?.id);
      status(data.note);
    } catch (error) {if (generation === current) status(error.message);}
    finally {if (generation === current) {loading = false; controls();}}
  }
  function choose(id) {
    if (dirty() && !confirmAction('Odrzucić niezapisane zmiany bieżącego pliku?')) return;
    hideSecrets(); changes = {}; remove.clear(); selected = data?.files.find(file => file.id === id);
    $('file-save-check').checked = false; $('file-risk-check').checked = false; $('file-new-key').value = ''; $('file-new-value').value = '';
    $('file-editor-form').classList.toggle('hidden', !selected);
    $('file-entries').replaceChildren();
    if (!selected) return;
    $('file-editor-title').textContent = selected.path.split('/').at(-1);
    $('file-editor-meta').textContent = `${selected.path} · ${selected.exists ? `${selected.size} B · ${selected.entries.length} ustawień` : 'Plik zostanie utworzony po potwierdzeniu zapisu'}`;
    for (const entry of selected.entries) addRow(entry);
    applyFilter();
    const suggestions = state.profile === 'test' ? ['TEST_BANK_API_KEY', 'TEST_TOTP_ENCRYPTION_KEY', 'TEST_POSTGRES_URL', 'TEST_POSTGRES_USER', 'TEST_POSTGRES_PASSWORD', 'TEST_SHOP_POSTGRES_URL', 'TEST_SHOP_POSTGRES_USER', 'TEST_SHOP_POSTGRES_PASSWORD', 'TEST_REDIS_HOST', 'TEST_REDIS_PORT', 'TEST_BANK_BASE_URL', 'TEST_BANK_API_KEY_ID', 'TEST_SHOP_MERCHANT_ACCOUNT'] : ['BANK_API_KEY', 'TOTP_ENCRYPTION_KEY', 'SHOP_PAYMENT_PROVIDER', 'SHOP_PAYMENT_API_KEY', 'SHOP_PAYMENT_WEBHOOK_SECRET', 'SHOP_MERCHANT_ACCOUNT_NUMBER', 'METRICS_USERNAME', 'METRICS_PASSWORD', 'GRAFANA_ADMIN_PASSWORD'];
    $('file-key-suggestions').replaceChildren(...suggestions.map(key => {const option = el('option'); option.value = key; return option;}));
    for (const button of $('file-list').querySelectorAll('button')) button.classList.toggle('selected', button.querySelector('code')?.textContent === selected.path);
    controls();
  }
  function addRow(entry, fresh = false) {
    const row = el('div', '', 'file-entry'); row.dataset.key = entry.key;
    row.classList.toggle('secret-entry', entry.secret);
    row.classList.toggle('readonly-entry', !entry.editable);
    const label = el('label', entry.key); const input = el('input'); input.id = `file-entry-${entry.key}`; input.name = entry.key; label.htmlFor = input.id;
    const heading = el('div', '', 'file-entry-heading');
    heading.append(label, el('span', entry.secret ? 'Sekret' : 'Ustawienie', 'file-entry-kind'));
    input.type = entry.secret ? 'password' : 'text'; input.autocomplete = entry.secret ? 'new-password' : 'off'; input.maxLength = 2048;
    input.value = fresh ? changes[entry.key] : entry.value ?? ''; input.dataset.readonly = String(!entry.editable);
    input.placeholder = entry.secret ? entry.configured ? 'Ustawiono · puste zachowuje sekret' : 'Wpisz nowy sekret' : 'Wartość ustawienia';
    input.addEventListener('input', () => {
      if (!fresh && (entry.secret ? input.value === '' : input.value === entry.value)) delete changes[entry.key]; else changes[entry.key] = input.value;
      $('file-save-check').checked = false; controls(); applyFilter();
    });
    const actions = el('div', '', 'file-entry-actions');
    if (entry.secret && entry.configured && !fresh) {
      const show = el('button', 'Pokaż na 45 s', 'button subtle'); show.type = 'button'; show.name = entry.key;
      show.addEventListener('click', async () => {
        if (!await confirmReveal(entry.key)) return;
        show.disabled = true; const current = generation, currentFilter = filterGeneration, file = selected.id;
        try {
          const revealed = await api('/api/config/files/reveal', {profile: state.profile, file, key: entry.key, revision: data.revision, confirmed: true});
          if (current !== generation || currentFilter !== filterGeneration || selected?.id !== file || state.view !== 'files' || row.classList.contains('hidden')) return;
          if (!Object.hasOwn(changes, entry.key)) input.value = revealed.value;
          input.type = 'text'; clearTimeout(timers.get(input));
          timers.set(input, setTimeout(() => {input.type = 'password'; if (!Object.hasOwn(changes, entry.key)) input.value = ''; timers.delete(input);}, 45000));
        } catch (error) {status(error.message);}
        finally {show.disabled = false; controls();}
      }); actions.append(show);
    }
    if (entry.editable) {
      const deletion = el('label', '', 'file-remove'); const check = el('input'); check.type = 'checkbox';
      let parkedChange, hadChange = false;
      check.addEventListener('change', () => {
        if (check.checked) {hadChange = Object.hasOwn(changes, entry.key); parkedChange = changes[entry.key]; remove.add(entry.key); delete changes[entry.key]; input.disabled = true;}
        else {remove.delete(entry.key); if (hadChange) changes[entry.key] = parkedChange; input.disabled = false;}
        row.classList.toggle('marked-remove', check.checked); $('file-save-check').checked = false; controls(); applyFilter();
      }); deletion.append(check, document.createTextNode('Usuń pole')); actions.append(deletion);
    } else actions.append(el('small', 'Tylko odczyt · profil/system/PROD', 'muted'));
    row.append(heading, input, actions); $('file-entries').append(row);
    applyFilter();
  }
  async function save(event) {
    event.preventDefault(); if (busy() || !selected || !$('file-save-check').checked) return;
    saving = true; $('profile').disabled = true; controls(); renderActions();
    const file = selected.id;
    try {
      const result = await api('/api/config/files/save', {profile: state.profile, file, revision: data.revision, values: changes, remove: [...remove], confirmed: true, acknowledgeRisk: $('file-risk-check').checked});
      hideSecrets(); changes = {}; remove.clear(); saving = false; await load(file); status(result.message);
      const bootstrap = await api('/api/bootstrap'); state.documentation = bootstrap.documentation; renderDocumentation(); refreshStatus(); refreshNotifications();
    } catch (error) {status(error.message);}
    finally {saving = false; $('profile').disabled = false; controls(); renderActions();}
  }
  $('file-editor-form').addEventListener('submit', save);
  $('file-secret-dialog').addEventListener('close', () => {const resolve = pendingReveal; pendingReveal = null; resolve?.($('file-secret-dialog').returnValue === 'reveal');});
  $('files-reload').addEventListener('click', () => load(selected?.id));
  for (const filter of filters) $(filter.id).addEventListener('click', () => setFilter(filter.value));
  $('file-save-check').addEventListener('change', controls);
  $('file-add-entry').addEventListener('click', () => {
    const key = $('file-new-key').value.trim(), value = $('file-new-value').value;
    if (!/^[A-Z][A-Z0-9_]{0,95}$/.test(key) || selected.entries.some(entry => entry.key === key) || Object.hasOwn(changes, key)) return status('Podaj nową, niepowtarzającą się nazwę pola wielkimi literami.');
    changes[key] = value; addRow({key, value: null, secret: /password|secret|token|api.?key|encryption|account|merchant/i.test(key), configured: false, editable: true}, true);
    $('file-new-key').value = ''; $('file-new-value').value = ''; $('file-save-check').checked = false; controls();
    const hidden = [...$('file-entries').children].find(row => row.dataset.key === key)?.classList.contains('hidden');
    status(`Dodano pole ${key} do formularza.${hidden ? ' Kafelek jest ukryty przez filtr — wybierz „Pokaż wszystko”, aby go zobaczyć.' : ''} Zapis wymaga potwierdzenia.`);
  });
  document.addEventListener('visibilitychange', () => {if (document.hidden) hideSecrets();});
  window.addEventListener('pagehide', reset);
  return {load, reset, controls, get saving() {return saving;}, get dirty() {return dirty();}};
})();
