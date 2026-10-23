'use strict';
const state = {profile: 'dev', view: 'dashboard', category: 'Wszystkie', actions: [], jobs: [], services: [], documentation: {}, token: '', selectedJob: null, pendingAction: null, loading: false};
const cashState = {action: null, accounts: [], requestId: '', parameters: null, submitting: false};
const configurationState = {data: null, changes: {}, loading: false, saving: false, generation: 0};
const notificationsState = {items: [], total: 0, next: null, open: false, refreshing: false, view: 'active', counts: {active: 0, archived: 0, deleted: 0}, revision: null, generation: 0, refreshAgain: false, selected: null, changing: false, pendingDelete: null};
const $ = id => document.getElementById(id);
window.panelConfirm = message => window.confirm(window.panelLanguage?.translate(message) || message);
const titles = {dashboard: ['Centrum operacyjne', 'Bank, sklep i infrastruktura. Wszystko w jednym miejscu.', 'Pulpit'], operations: ['Operacje środowiska', 'Uruchamiaj wyłącznie nazwane, kontrolowane operacje.', 'Operacje'], config: ['Konfiguracja DEV', 'Utwórz pliki, uzupełnij dane i przygotuj bank oraz sklep z jednego miejsca.', 'Konfiguracja DEV'], tests: ['Weryfikacja i dziennik zadań', 'Rzeczywiste wyniki skryptów, bez symulowanych statusów.', 'Testy i logi'], docs: ['Dokumentacja projektu', 'Kontrakty API, instrukcje i zasady pracy z lokalnym środowiskiem.', 'Dokumentacja']};
titles.files = ['Pliki i sekrety', 'Edytuj lokalne ustawienia DEV/TEST; podgląd sekretów wymaga świadomego potwierdzenia.', 'Pliki i sekrety'];
titles.guide = ['Przewodnik uruchomienia', 'Kolejne jawne kroki dla wybranego profilu; bez automatycznego wykonania.', 'Przewodnik'];
titles.accounts = ['Konta bankowe', 'Wyszukuj rachunki i sprawdzaj dane lokalnego banku bez ręcznych zapytań SQL.', 'Konta bankowe'];
titles.shopAccounts = ['Konta sklepu', 'Sprawdzaj konta klientów i administratorów NovaTech Shop bez ręcznych zapytań SQL.', 'Konta sklepu'];
titles.paymentCases = ['Uzgadnianie płatności', 'Dowody finansowe, zwroty, konflikty i audytowane decyzje operatora DEV.', 'Uzgadnianie płatności'];
titles.maintenance = ['Ustawienia operacji', 'Katalog kopii, retencje, harmonogramy i kontrolowana migracja terminalowej Centrali.', 'Ustawienia operacji'];
const statuses = {running: 'W toku', success: 'Zakończono', failed: 'Błąd', cancelled: 'Przerwano', interrupted: 'Przerwane wcześniej'};
const busy = () => state.loading || configurationState.saving || window.fileEditor?.saving || window.operatorUI?.saving || state.jobs.some(job => job.status === 'running');
const available = action => action.profiles.includes(state.profile) && !['repair-metrics-dev', 'backup-totp-verify','purchase-cancel','purchase-refresh'].includes(action.id) && !action.id.startsWith('payment-case-');
const el = (tag, text = '', className = '') => {const node = document.createElement(tag); node.textContent = text; node.className = className; return node;};
const date = value => value ? new Date(value).toLocaleString(window.panelLanguage?.locale || 'pl-PL', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'}) : '—';
const pill = (label, status) => el('span', label, `pill ${status}`);
const localizedAction = action => window.panelLanguage?.action(action) || action;
const localizedJobTitle = job => localizedAction(state.actions.find(action => action.id === job.action) || {id: job.action, title: job.title, group: '', description: ''}).title;
document.addEventListener('panel-language-change', () => {
  const heading = titles[state.view];
  if (heading) [$('page-title').textContent, $('page-description').textContent, $('breadcrumb').textContent] = heading.map(text => window.panelLanguage?.translate(text) || text);
  renderActions();
  if (state.view === 'guide') window.guidedUI?.render();
  if (state.pendingAction && $('confirm-dialog').open) {
    const shown = localizedAction(state.pendingAction);
    $('confirm-title').textContent = `${shown.title} · ${state.profile.toUpperCase()}`;
    $('confirm-description').textContent = shown.description;
  }
});
function createJobListLayout(list) {
  const visibleRows = 8;
  let pending = false;
  function measure() {
    pending = false;
    if (!list.getClientRects().length) return; // Re-measure when the hidden view becomes visible.
    const rows = list.querySelectorAll('.job-item');
    list.classList.toggle('scrollable', rows.length > visibleRows);
    if (rows.length <= visibleRows) {list.style.removeProperty('max-height'); return;}
    const height = rows[visibleRows - 1].getBoundingClientRect().bottom - rows[0].getBoundingClientRect().top;
    if (height > 0) list.style.maxHeight = `${Math.ceil(height)}px`;
  }
  function schedule() {
    if (pending) return;
    pending = true;
    window.requestAnimationFrame(measure);
  }
  const observer = new ResizeObserver(schedule);
  function refresh() {
    observer.disconnect();
    observer.observe(list);
    // Width, font and wrapped-title changes must still fit exactly eight complete rows.
    for (const row of Array.from(list.querySelectorAll('.job-item')).slice(0, visibleRows)) observer.observe(row);
    schedule();
  }
  return {refresh};
}
const jobListLayout = createJobListLayout($('job-list'));
function banner(message) {$('banner').textContent = window.panelLanguage?.translate(message) || message; $('banner').classList.toggle('hidden', !message); if (!message) window.problemUI?.clearNotice(); else window.problemUI?.decorateBanner();}
async function api(path, payload) {
  const options = {headers: state.token ? {'X-Control-Center-Token': state.token} : {}};
  if (payload !== undefined) {options.method = 'POST'; options.headers = {'Content-Type': 'application/json', 'X-Control-Center-Token': state.token}; options.body = JSON.stringify(payload);}
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) {window.problemUI?.notice(data.notificationId); const error = new Error(data.error || `HTTP ${response.status}`); error.notificationId = data.notificationId; error.issueCode = data.issueCode; throw error;}
  return data;
}
function changeView(view) {
  if (!titles[view]) return;
  if (state.view === 'maintenance' && view !== 'maintenance' && window.operatorUI && !window.operatorUI.leave()) return;
  if (state.view === 'files' && view !== 'files') {
    if (window.fileEditor?.saving) return;
    if (window.fileEditor?.dirty && !window.panelConfirm('Odrzucić niezapisane zmiany edytora?')) return;
    window.fileEditor?.reset();
  }
  if (state.view === 'accounts' && view !== 'accounts') window.bankAccounts?.reset();
  if (state.view === 'shopAccounts' && view !== 'shopAccounts') window.shopAccounts?.reset();
  if (state.view === 'paymentCases' && view !== 'paymentCases') window.paymentCases?.reset();
  state.view = view;
  document.querySelectorAll('.view').forEach(node => node.classList.toggle('hidden', node.id !== `view-${view}`));
  document.querySelectorAll('.nav-item').forEach(node => {node.classList.toggle('active', node.dataset.view === view); if (node.dataset.view === view) node.setAttribute('aria-current', 'page'); else node.removeAttribute('aria-current');});
  [$('page-title').textContent, $('page-description').textContent, $('breadcrumb').textContent] = titles[view].map(text => window.panelLanguage?.translate(text) || text);
  renderActions();
  if (view === 'docs') renderDocumentation();
  if (view === 'config') loadConfiguration();
  if (view === 'files') window.fileEditor?.load();
  if (view === 'tests') {jobListLayout.refresh(); refreshJobs();}
  if (view === 'guide') window.guidedUI?.render();
  if (view === 'accounts') window.bankAccounts?.load();
  if (view === 'shopAccounts') window.shopAccounts?.load();
  if (view === 'paymentCases') window.paymentCases?.load();
  if (view === 'maintenance') window.operatorUI?.load();
}
function renderDocumentation() {
  for (const name of ['bank', 'shop']) {
    const container = $(`${name}-api-links`); container.replaceChildren();
    if (state.profile !== 'dev') {container.append(el('p', 'Linki do lokalnych aplikacji są dostępne w widoku DEV.', 'muted small')); continue;}
    const documentation = state.documentation[name];
    if (!documentation?.swagger) {container.append(el('p', documentation?.note || 'Adres nie został odczytany. Uruchom ponownie serwer panelu.', 'muted small')); continue;}
    for (const [key, title, style] of [['swagger', 'Otwórz Swagger ↗', 'button primary'], ['openapi', 'OpenAPI JSON ↗', 'text-button']]) {
      const url = new URL(documentation[key]);
      if (url.protocol !== 'http:' || url.hostname !== 'localhost' || url.username || url.password) continue;
      const link = el('a', title, style); link.href = url.href; link.target = '_blank'; link.rel = 'noopener'; container.append(link);
    }
    container.append(el('small', documentation.swagger, 'muted'));
  }
  $('api-links-note').textContent = state.profile === 'dev' ? 'Linki prowadzą wyłącznie do lokalnych aplikacji HTTP/DEV. Swagger banku i sklepu może wykonywać żądania — operacje zmieniające dane uruchamiaj świadomie. Samo otwarcie dokumentacji nie zmienia danych. Konfiguracja DEV aktualizuje adresy po zapisie portów; po ręcznej edycji plików odśwież całą stronę.' : 'TEST nie uruchamia osobnego banku/sklepu. PROD ma jawne procedury Vault/TLS/build, bez linków do zdalnego API banku/sklepu. Dokumentacja API panelu pozostaje dostępna.';
}
function actionButton(action, style = 'button outline') {
  const button = el('button', action.changes ? 'Uruchom' : 'Sprawdź', style);
  button.disabled = busy(); button.addEventListener('click', () => requestAction(action));
  return button;
}
function requestAction(action) {
  if(action.id==='payment-cases'||action.id.startsWith('payment-case-'))return changeView('paymentCases');
  if (busy() || !available(action)) return;
  banner('');
  if (action.id === 'first-run-guide') return changeView('guide');
  if (action.id === 'dev-env') return changeView('config');
  if (action.id === 'operator-settings' || action.id.startsWith('migrate-cli-')) return changeView('maintenance');
  if (action.id.startsWith('cash-')) return openCash(action);
  if (action.id.startsWith('totp-rotation-')) return window.maintenanceUI.open(action);
  if (action.id === 'payment-history-migration-apply') return window.maintenanceUI.open(action);
  if (!action.changes && state.profile !== 'prod') return submit(action, false);
  state.pendingAction = action;
  const shownAction = localizedAction(action);
  $('confirm-title').textContent = `${shownAction.title} · ${state.profile.toUpperCase()}`;
  $('confirm-description').textContent = shownAction.description;
  $('confirm-check').checked = false; $('confirm-run').disabled = true;
  $('confirm-dialog').returnValue = 'cancel';
  window.productionUI?.configure(action);
  $('confirm-dialog').showModal();
}
async function submit(action, confirmed, extra = {}) {
  state.loading = true; renderActions();
  try {
    const job = await api('/api/jobs', {action: action.id, profile: state.profile, confirmed, ...extra});
    await openJob(job);
  } catch (error) {banner(error.message);}
  finally {state.loading = false; renderActions();}
}
function operationCard(action) {
  const shownAction = localizedAction(action);
  const card = el('article', '', 'card operation-card');
  if (state.profile === 'prod') {card.classList.add('production-card'); card.append(el('span', 'PROD · dodatkowe potwierdzenie', 'production-badge'));}
  card.append(el('span', {Testy: '☷', Tests: '☷', Build: '⌁', Docker: '▱', Środowisko: '◇', Environment: '◇', Diagnostyka: '⌕', Diagnostics: '⌕'}[action.group] || '◇', 'operation-icon'), el('span', shownAction.group.toUpperCase(), 'operation-group'), el('h3', shownAction.title), el('p', shownAction.description), actionButton(action));
  return card;
}
function renderActions() {
  const actions = state.actions.filter(available);
  const groups = ['Wszystkie', ...new Set([...actions.filter(action => action.group !== 'Testy').map(action => action.group), 'Vault'])];
  if (!groups.includes(state.category)) state.category = 'Wszystkie';
  $('categories').replaceChildren(...groups.map(group => {const shown = window.panelLanguage?.translate(group) || group; const button = el('button', shown, `category ${group === state.category ? 'selected' : ''}`); button.setAttribute('aria-pressed', String(group === state.category)); button.addEventListener('click', () => {state.category = group; renderActions();}); return button;}));
  const locale = window.panelLanguage?.locale || 'pl-PL';
  const search = $('action-search').value.toLocaleLowerCase(locale);
  const operationActions = actions.filter(action => action.group !== 'Testy' && (state.category === 'Wszystkie' || action.group === state.category) && `${action.title} ${action.description} ${localizedAction(action).title} ${localizedAction(action).description}`.toLocaleLowerCase(locale).includes(search));
  $('operation-grid').replaceChildren(...operationActions.map(operationCard));
  const vaultLanding = window.productionUI && (state.category === 'Wszystkie' || state.category === 'Vault') && (!search || 'vault hashicorp prod'.includes(search));
  if (vaultLanding) $('operation-grid').prepend(window.productionUI.landing());
  if (!operationActions.length && !vaultLanding) $('operation-grid').append(el('p', 'Brak operacji dla tego filtra.', 'empty-state'));
  const tests = actions.filter(action => action.group === 'Testy');
  $('test-actions').replaceChildren(...tests.map(operationCard));
  if (!tests.length) $('test-actions').append(el('p', 'PROD: procedury Vault/TLS i buildy wybierzesz w Operacjach. Automatyczne testy banku/sklepu są dostępne w DEV/TEST. Historia zadań pozostaje poniżej.', 'scope-note'));
  const quickIds = state.profile === 'dev' ? ['environment-check', 'apps-start', 'unit-all'] : state.profile === 'test' ? ['environment-check', 'unit-bank', 'it-bank'] : ['prod-check', 'environment-check'];
  $('quick-actions').replaceChildren(...quickIds.map(id => actions.find(action => action.id === id)).filter(Boolean).map(action => {
    const button = el('button', '', 'quick-action'); button.disabled = busy();
    const content = el('span'); content.append(el('b', localizedAction(action).title), el('small', action.group === 'Testy' ? 'Wynik i logi po zakończeniu' : action.changes ? 'Wymaga potwierdzenia' : 'Diagnostyka bez sekretów'));
    button.append(el('span', '◇', 'quick-icon'), content, el('span', '›', 'arrow')); button.addEventListener('click', () => requestAction(action)); return button;
  }));
  renderConfigurationControls();
  window.fileEditor?.controls();
  window.problemUI?.controls();
  window.bankAccounts?.controls();
  window.shopAccounts?.controls();
  window.paymentCases?.controls();
  window.operatorUI?.controls();
}
function renderServices(data) {
  const monitoring = $('monitoring-links');
  monitoring.classList.toggle('hidden', state.profile !== 'dev');
  if (state.profile !== 'dev') monitoring.open = false;
  state.services = data.services;
  $('services').replaceChildren(...data.services.map(service => {
    const row = el('tr'); const name = el('td'); name.append(el('span', '◇', 'service-symbol'), document.createTextNode(service.name));
    const status = el('td'); const badge = pill(service.up ? 'Otwarty port' : 'Niedostępny', service.up ? 'up' : 'down'); badge.title = service.detail; status.append(badge);
    row.append(name, el('td', String(service.port ?? '—')), status, el('td', service.group, 'muted')); return row;
  }));
  if (!data.services.length) {const row = el('tr'); const cell = el('td', data.note, 'empty-state'); cell.colSpan = 4; row.append(cell); $('services').append(row);}
  for (const [id, group] of [['apps-count', 'Aplikacja'], ['infra-count', 'Infrastruktura']]) {const services = data.services.filter(service => service.group === group); $(id).textContent = services.length ? `${services.filter(service => service.up).length} / ${services.length}` : '—';}
  $('status-note').textContent = data.note; $('checked-at').textContent = date(data.checked);
  $('app-links').replaceChildren();
  if (state.profile === 'dev') for (const [name, path, label, kind, description] of [['NovaBank', '/login', 'Otwórz bank', 'bank', 'Panel klienta'], ['NovaTech Shop', '/novatech_shop.html', 'Otwórz sklep', 'shop', 'NovaTech Shop']]) {
    const service = data.services.find(item => item.name === name);
    if (service?.port) {
      const link = el('a', '', `app-link app-link-${kind}`);
      link.href = `http://localhost:${service.port}${path}`; link.target = '_blank'; link.rel = 'noopener';
      link.setAttribute('aria-label', `${label} — nowa karta`);
      const icon = el('span', '', 'app-link-icon'); icon.setAttribute('aria-hidden', 'true');
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      for (const [attribute, value] of Object.entries({viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.7', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', focusable: 'false'})) svg.setAttribute(attribute, value);
      const drawing = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      drawing.setAttribute('d', kind === 'bank' ? 'M3 10h18M4 21h16M6 10v8m6-8v8m6-8v8M3 7l9-4 9 4v3H3z' : 'M5 7h14l1 14H4L5 7zM8 8V6a4 4 0 0 1 8 0v2');
      svg.append(drawing); icon.append(svg);
      const content = el('span', '', 'app-link-content'); content.append(el('strong', label), el('small', description));
      const arrow = el('span', '↗', 'app-link-arrow'); arrow.setAttribute('aria-hidden', 'true');
      link.append(icon, content, arrow); $('app-links').append(link);
    }
  }
}
function renderJobs() {
  const jobs = state.jobs;
  const last = jobs[0]; $('last-task-status').textContent = last ? statuses[last.status] || last.status : 'Brak zadań'; $('last-task-title').textContent = last ? `${localizedJobTitle(last)} · ${last.profile.toUpperCase()}` : 'Gotowe do pracy';
  $('recent-jobs').replaceChildren(...jobs.slice(0, 4).map(job => {
    const row = el('button', '', 'recent-row'); const time = el('time', date(job.started)); time.dateTime = job.started;
    row.append(time, el('strong', localizedJobTitle(job)), el('span', job.profile.toUpperCase(), 'muted recent-profile'), pill(statuses[job.status] || job.status, job.status)); row.addEventListener('click', () => selectJob(job.id)); return row;
  }));
  if (!jobs.length) $('recent-jobs').append(el('p', 'Jeszcze niczego nie uruchomiono. Zacznij od sprawdzenia środowiska.', 'empty-state'));
  $('job-count').textContent = String(jobs.length);
  const list = $('job-list'), scrollTop = list.scrollTop;
  list.replaceChildren(...jobs.map(job => {
    const button = el('button', '', `job-item ${job.id === state.selectedJob ? 'selected' : ''}`);
    button.setAttribute('aria-pressed', String(job.id === state.selectedJob));
    const details = el('span', '', 'job-detail'); const time = el('time', `${date(job.started)} · ${job.profile.toUpperCase()}`); time.dateTime = job.started;
    details.append(time, pill(statuses[job.status] || job.status, job.status)); button.append(el('b', localizedJobTitle(job)), details); button.addEventListener('click', () => selectJob(job.id)); return button;
  }));
  if (!jobs.length) list.append(el('p', 'Brak zadań.', 'empty-state'));
  jobListLayout.refresh();
  list.scrollTop = scrollTop;
  renderActions();
}
async function openJob(job, {navigate = true} = {}) {
  // Render the POST result immediately, even if the next GET contains identical data.
  jobsRevision++;
  const index = state.jobs.findIndex(item => item.id === job.id);
  if (index < 0) state.jobs.unshift(job); else state.jobs[index] = job;
  if (navigate) {
    $('job-list').scrollTop = 0;
    await selectJob(job.id, true);
  } else renderJobs();
  await refreshJobs();
}
async function selectJob(id, reveal = false) {
  if (id !== state.selectedJob) {
    const job = state.jobs.find(item => item.id === id);
    $('log-title').textContent = job?.title || 'Dziennik zadania';
    $('log-meta').textContent = 'Wczytywanie bieżącego zadania…';
    $('log-output').textContent = 'Oczekiwanie na wynik…';
    $('log-output').scrollTop = 0;
    $('cancel-job').classList.add('hidden');
    $('retry-cash')?.classList.add('hidden');
  }
  state.selectedJob = id;
  changeView('tests');
  renderJobs();
  if (reveal) window.requestAnimationFrame(() => {
    if (state.view !== 'tests' || state.selectedJob !== id) return;
    const heading = $('log-title'); heading.tabIndex = -1;
    heading.focus({preventScroll: true});
    heading.closest('.jobs-card')?.scrollIntoView({block: 'start'});
  });
  if (state.view === 'tests') await refreshLog();
}
let logRequest = 0;
async function refreshLog() {
  if (!state.selectedJob) return;
  const id = state.selectedJob, request = ++logRequest;
  try {
    const job = await api(`/api/jobs/${id}`);
    if (id !== state.selectedJob || request !== logRequest || state.view !== 'tests') return;
    $('log-title').textContent = job.title;
    $('log-meta').textContent = `${job.profile.toUpperCase()} · ${statuses[job.status] || job.status} · ${date(job.started)}${job.exitCode !== null ? ` · kod ${job.exitCode}` : ''}`;
    const log = $('log-output'); const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 60;
    const content = job.log || 'Oczekiwanie na wynik…';
    if (log.textContent !== content) {log.textContent = content; if (atBottom) log.scrollTop = log.scrollHeight;}
    $('cancel-job').classList.toggle('hidden', job.status !== 'running' || job.action.startsWith('cash-') || /^vault-|^prod-|^migrate-cli-|^(backup|dr)-schedule-(install|run-now)$/.test(job.action));
    let retry = $('retry-cash');
    if (!retry) {retry = el('button', 'Ponów tę samą korektę', 'button outline'); retry.id = 'retry-cash'; document.querySelector('.log-actions').append(retry);}
    retry.classList.toggle('hidden', state.profile !== 'dev' || !job.parameters || !job.action.startsWith('cash-') || !['failed', 'interrupted'].includes(job.status));
    retry.disabled = busy(); retry.onclick = () => openCash(state.actions.find(action => action.id === job.action), job.parameters);
    window.totpRecoveryUI?.observe(job);
  } catch (error) {if (id === state.selectedJob && request === logRequest && state.view === 'tests') banner(error.message);}
}
let jobsRefreshing = false, jobsRefreshAgain = false, jobsRevision = 0;
async function refreshJobs() {
  if (!state.token) return;
  if (jobsRefreshing) {jobsRefreshAgain = true; return;}
  jobsRefreshing = true;
  const revision = jobsRevision;
  try {
    const jobs = (await api('/api/jobs')).jobs;
    // A GET started before a successful POST must not remove the newly opened job.
    if (revision !== jobsRevision) {jobsRefreshAgain = true; return;}
    let changed = JSON.stringify(jobs) !== JSON.stringify(state.jobs);
    state.jobs = jobs;
    if (!state.selectedJob && jobs.length) {state.selectedJob = jobs[0].id; changed = true;}
    if (changed) renderJobs();
    if (state.view === 'tests') await refreshLog();
    $('connection-state').textContent = 'Połączono z lokalnym panelem';
  }
  catch (error) {
    if (revision !== jobsRevision) jobsRefreshAgain = true;
    else {$('connection-state').textContent = 'Brak połączenia z panelem'; banner(error.message);}
  }
  finally {jobsRefreshing = false; if (jobsRefreshAgain) {jobsRefreshAgain = false; void refreshJobs();}}
}
let statusRefreshing = false;
async function refreshStatus() {
  if (!state.token || statusRefreshing) return;
  statusRefreshing = true; const profile = state.profile;
  try {const data = await api(`/api/status?profile=${profile}`); if (profile === state.profile) renderServices(data);}
  catch (error) {banner(error.message);}
  finally {statusRefreshing = false; if (profile !== state.profile) refreshStatus();}
}
function profileChanged() {
  if (window.operatorUI && !window.operatorUI.leave()) {$('profile').value = state.profile; return;}
  if (window.fileEditor?.saving) {$('profile').value = state.profile; return;}
  if (window.fileEditor?.dirty && !window.panelConfirm('Zmienić profil i odrzucić niezapisane zmiany pliku?')) {$('profile').value = state.profile; return;}
  window.fileEditor?.reset();
  state.profile = $('profile').value;
  window.bankAccounts?.reset();
  window.shopAccounts?.reset();
  window.paymentCases?.reset();
  state.category = 'Wszystkie';
  $('action-search').value = '';
  $('profile-badge').textContent = state.profile === 'prod' ? 'PROD · OSTROŻNIE' : state.profile.toUpperCase();
  $('scope-note').textContent = state.profile === 'dev' ? 'DEV · lokalne operacje. Zmiany wymagają potwierdzenia; panel nie uruchamia niczego automatycznie.' : state.profile === 'test' ? 'TEST · testy Maven i Testcontainers, bez osobnego startu banku i bez zmian infrastruktury DEV.' : 'PROD · procedury lokalnego PROD-like mogą zmieniać Vault, AppRole, bootstrap i TLS. Każde uruchomienie wymaga ostrzeżenia i wpisania PROD. Bez automatycznego startu/deploy aplikacji.';
  $('confirm-dialog').close('cancel'); $('cash-dialog').close(); renderActions(); renderDocumentation();
  window.guidedUI?.render();
  configurationState.generation++; configurationState.data = null; configurationState.changes = {}; configurationState.loading = false;
  $('configuration-fields').replaceChildren(); $('configuration-confirm').checked = false; renderConfigurationControls();
  if (state.view === 'config') loadConfiguration();
  if (state.view === 'files') window.fileEditor?.load();
  if (state.view === 'accounts') window.bankAccounts?.load();
  if (state.view === 'shopAccounts') window.shopAccounts?.load();
  if (state.view === 'paymentCases') window.paymentCases?.load();
  if (state.view === 'maintenance') window.operatorUI?.load();
  renderServices({services: [], note: 'Odświeżanie wybranego widoku…', checked: new Date().toISOString()}); refreshStatus();
}

function renderConfigurationControls() {
  const enabled = state.profile === 'dev';
  $('configuration-unavailable').classList.toggle('hidden', enabled);
  $('configuration-form').classList.toggle('hidden', !enabled);
  $('configuration-reload').disabled = configurationState.loading || configurationState.saving;
  $('configuration-save').disabled = !enabled || !configurationState.data || busy() || configurationState.loading || !$('configuration-confirm').checked;
  $('configuration-save').textContent = configurationState.saving ? 'Zapisywanie…' : configurationState.data?.files.every(file => file.exists) ? 'Zapisz dane / uzupełnij braki' : 'Utwórz pliki i zapisz dane';
  for (const input of $('configuration-fields').querySelectorAll('input')) input.disabled = configurationState.saving || !!configurationState.data?.fields.find(field => field.key === input.name)?.locked;
  const nextActions = ['infra-up', 'build-bank', 'build-shop', 'apps-start'].map(id => state.actions.find(item => item.id === id)).filter(Boolean);
  const nextContainer = $('configuration-next-actions'); const ids = nextActions.map(action => action.id).join(',');
  if (nextContainer.dataset.actions !== ids) {
    nextContainer.dataset.actions = ids;
    nextContainer.replaceChildren(...nextActions.map((action, index) => {
      const button = el('button', `${index + 1}. ${action.title}`, 'button outline'); button.type = 'button';
      button.addEventListener('click', () => requestAction(action)); return button;
    }));
  }
  for (const button of nextContainer.querySelectorAll('button')) button.disabled = !enabled || busy() || !configurationState.data?.ready || Object.keys(configurationState.changes).length > 0;
}
function renderConfiguration() {
  const data = configurationState.data;
  $('configuration-fields').replaceChildren(); $('configuration-files').replaceChildren(); $('configuration-warnings').replaceChildren();
  if (!data) return renderConfigurationControls();
  $('configuration-status').textContent = data.message || (data.ready ? 'Pliki gotowe do uruchomienia lokalnego DEV.' : 'Uzupełnij dane i zapisz — brakujące sekrety zostaną wygenerowane.');
  $('configuration-note').textContent = `${data.note} ${data.paymentsReady ? 'Tożsamość i routing sprzedawcy skonfigurowane.' : 'Płatności wymagają rachunku sklepu, SHOP_MERCHANT_ID, BANK_API_KEY_MERCHANTS i BANK_PAYMENT_MERCHANTS. Uzupełnij je w Pliki i sekrety; nie przypisuj historycznych płatności automatycznie.'}`;
  for (const file of data.files) {const row = el('div'); row.append(el('code', file.path), pill(file.exists ? 'Istnieje' : 'Do utworzenia', file.exists ? 'up' : 'down')); $('configuration-files').append(row);}
  for (const warning of data.warnings) $('configuration-warnings').append(el('p', warning, 'dialog-warning'));
  const groups = new Map();
  for (const field of data.fields) {
    if (!groups.has(field.group)) {const group = el('fieldset', '', 'configuration-group'); group.append(el('legend', field.group)); groups.set(field.group, group); $('configuration-fields').append(group);}
    const cell = el('div', '', 'configuration-field'); const label = el('label', field.label); label.htmlFor = `configuration-${field.key}`; cell.append(label);
    if (field.kind === 'managed') {cell.append(el('p', field.configured ? 'Ustawiono · wartość ukryta · bez rotacji' : 'Zostanie wygenerowany przy zapisie', 'configuration-managed'));}
    else {
      const input = el('input'); input.id = label.htmlFor; input.name = field.key;
      input.type = ['secret', 'account'].includes(field.kind) ? 'password' : 'text'; input.autocomplete = input.type === 'password' ? 'new-password' : 'off';
      input.value = field.value ?? ''; input.disabled = field.locked;
      input.maxLength = field.kind === 'account' ? 26 : field.kind === 'port' ? 5 : field.kind === 'name' ? 63 : 256;
      if (['port', 'account'].includes(field.kind)) input.inputMode = 'numeric';
      input.required = !['secret', 'account'].includes(field.kind);
      if (input.type === 'password') input.placeholder = field.configured ? 'Ustawiono — pozostaw puste, aby zachować' : field.kind === 'secret' ? 'Puste = wygeneruj bezpieczne hasło' : 'Wpisz numer rachunku utworzonego w banku';
      input.addEventListener('input', () => {if (input.value === (field.value ?? '')) delete configurationState.changes[field.key]; else configurationState.changes[field.key] = input.value; renderConfigurationControls();}); cell.append(input);
    }
    const hint = field.kind === 'port' ? 'Port 1024–65535, różny od pozostałych.' : field.kind === 'host' ? 'Tylko localhost lub 127.0.0.1.' : field.kind === 'secret' ? 'Nowe hasło: min. 16 znaków; litery/cyfry i _+=./!:@%-; bez spacji, $ i cudzysłowów.' : field.kind === 'account' ? 'Zapisany numer pozostaje ukryty. Puste pole zachowuje wartość.' : field.locked ? 'Chroniona tożsamość istniejącej konfiguracji bazy; zmiana wymaga osobnej procedury.' : 'Wartości spoza tego formularza zostają zachowane.';
    cell.append(el('small', field.kind === 'managed' ? 'Klucze są stabilne. Rotacja mogłaby unieważnić dane 2FA lub komunikację bank–sklep.' : hint), el('code', field.key, 'configuration-property')); groups.get(field.group).append(cell);
  }
  renderConfigurationControls();
}
async function loadConfiguration() {
  if (configurationState.saving) return;
  if (Object.keys(configurationState.changes).length && !window.panelConfirm('Wczytać pliki ponownie i odrzucić niezapisane dane formularza?')) return;
  const generation = ++configurationState.generation; configurationState.data = null; configurationState.changes = {}; $('configuration-confirm').checked = false;
  renderConfiguration();
  if (state.profile !== 'dev' || !state.token) return;
  configurationState.loading = true; renderConfigurationControls(); $('configuration-status').textContent = 'Odczytywanie stanu plików — sekrety pozostają ukryte…';
  try {const data = await api('/api/config/dev?profile=dev'); if (generation === configurationState.generation && state.profile === 'dev') {configurationState.data = data; renderConfiguration();}}
  catch (error) {if (generation === configurationState.generation) {$('configuration-status').textContent = error.message;}}
  finally {if (generation === configurationState.generation) {configurationState.loading = false; renderConfigurationControls();}}
}
async function saveConfiguration(event) {
  event.preventDefault(); if (busy() || !configurationState.data || state.profile !== 'dev' || !$('configuration-confirm').checked) return;
  configurationState.saving = true; $('profile').disabled = true; renderConfigurationControls(); renderActions();
  const generation = configurationState.generation;
  try {
    const data = await api('/api/config/dev', {profile: 'dev', revision: configurationState.data.revision, values: configurationState.changes, confirmed: true});
    if (generation === configurationState.generation) {configurationState.data = data; configurationState.changes = {}; $('configuration-confirm').checked = false; renderConfiguration();}
    const bootstrap = await api('/api/bootstrap'); state.documentation = bootstrap.documentation; renderDocumentation();
    refreshStatus();
  } catch (error) {$('configuration-status').textContent = error.message;}
  finally {configurationState.saving = false; $('profile').disabled = false; renderConfigurationControls(); renderActions();}
}

function cashError(message) {$('cash-error').textContent = message; $('cash-error').classList.toggle('hidden', !message);}
function cashParameters() {
  if (cashState.parameters) return cashState.parameters;
  const amount = $('cash-amount').value.trim().replace(',', '.');
  if (!/^(?:0|[1-9]\d{0,8})(?:\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) return null;
  const accountId = Number($('cash-account').value);
  if (!Number.isSafeInteger(accountId) || accountId < 1) return null;
  return {accountId, amount, requestId: cashState.requestId};
}
function cashChanged() {
  if (!cashState.parameters) cashState.requestId = crypto.randomUUID();
  $('cash-check').checked = false; cashError(''); updateCash();
}
function updateCash() {
  const parameters = cashParameters();
  const account = parameters && cashState.accounts.find(item => item.id === parameters.accountId);
  const operation = cashState.action?.id === 'cash-credit' ? 'Dodanie' : 'Odjęcie';
  $('cash-summary').textContent = account ? `${operation} ${parameters.amount.replace('.', ',')} PLN · ${account.owner} · ${account.number}. Saldo odczytane: ${account.balance} PLN. Baza ponownie sprawdzi saldo przy zapisie.` : 'Wybierz konto i podaj dodatnią kwotę, maksymalnie dwa miejsca po przecinku.';
  $('cash-request').textContent = `Identyfikator korekty: ${cashState.requestId}`;
  $('cash-run').disabled = !account || !$('cash-check').checked || busy() || cashState.submitting;
}
async function openCash(action, previous = null) {
  if (!action || busy() || state.profile !== 'dev') return;
  cashState.action = action; cashState.accounts = []; cashState.parameters = previous; cashState.requestId = previous?.requestId || crypto.randomUUID();
  $('cash-title').textContent = action.title;
  $('cash-amount').value = previous?.amount || ''; $('cash-amount').disabled = Boolean(previous);
  $('cash-account').disabled = true; $('cash-account').replaceChildren(el('option', 'Ładowanie kont…'));
  $('cash-check').checked = false; cashError(''); updateCash(); $('cash-dialog').showModal();
  try {
    const data = await api('/api/accounts?profile=dev');
    if (! $('cash-dialog').open || cashState.action !== action) return;
    cashState.accounts = data.accounts;
    const placeholder = el('option', data.accounts.length ? 'Wybierz konto…' : 'Brak aktywnych kont PLN'); placeholder.value = '';
    $('cash-account').replaceChildren(placeholder, ...data.accounts.map(account => {const option = el('option', `${account.owner} · ${account.number} · ${account.balance} PLN · ID ${account.id}`); option.value = String(account.id); return option;}));
    if (previous) $('cash-account').value = String(previous.accountId);
    $('cash-account').disabled = Boolean(previous) || !data.accounts.length;
    if (data.truncated) cashError('Wyświetlono pierwszych 500 kont. Pozostałe nie są dostępne w tym formularzu.');
    updateCash();
  } catch (error) {cashError(error.message);}
}
async function submitCash(event) {
  event.preventDefault();
  const parameters = cashParameters();
  if (!parameters || !$('cash-check').checked || busy() || cashState.submitting || state.profile !== 'dev') return;
  cashState.submitting = true; cashState.parameters = parameters;
  $('cash-account').disabled = true; $('cash-amount').disabled = true; updateCash();
  try {
    const job = await api('/api/jobs', {action: cashState.action.id, profile: 'dev', confirmed: true, parameters});
    $('cash-dialog').close(); await openJob(job);
  } catch (error) {cashError(`${error.message} Dane i identyfikator pozostają bez zmian. Ponowienie tej samej korekty nie naliczy jej drugi raz.`);}
  finally {cashState.submitting = false; updateCash(); renderActions();}
}
function toggleNotifications(open) {
  notificationsState.open = open; $('notifications-panel').classList.toggle('hidden', !open);
  $('notifications-toggle').setAttribute('aria-expanded', String(open));
  if (open) refreshNotifications();
}
function renderNotifications() {
  const active = notificationsState.counts.active;
  $('notifications-count').textContent = String(active);
  $('notifications-toggle').setAttribute('aria-label', `Powiadomienia, aktywne ${active}`);
  $('notifications-toggle').classList.toggle('has-notifications', active > 0);
  for (const [view, count] of Object.entries(notificationsState.counts)) {
    $(`notification-${view}-count`).textContent = String(count);
    const tab = $(`notification-tab-${view}`); const selected = notificationsState.view === view;
    tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1;
  }
  $('notifications-list').setAttribute('aria-labelledby', `notification-tab-${notificationsState.view}`);
  $('notifications-delete-all').disabled = active === 0 || notificationsState.changing;
  const list = $('notifications-list'); const scroll = list.scrollTop;
  list.replaceChildren(...notificationsState.items.map(item => {
    const entry = el('button', '', `notification-entry ${item.level.toLowerCase()}`); entry.type = 'button';
    entry.setAttribute('aria-label', `Powiadomienie #${item.id} — ${item.level === 'ERROR' ? 'Błąd' : 'Ostrzeżenie'} · ${item.source}`);
    const header = el('div', '', 'notification-meta'); const time = el('time', date(item.created)); time.dateTime = item.created;
    header.append(pill(item.level === 'ERROR' ? 'Błąd' : 'Ostrzeżenie', item.level === 'ERROR' ? 'failed' : 'running'), el('strong', item.source), time);
    entry.append(header, el('p', item.message, 'notification-preview'), el('span', 'Szczegóły i opcje →', 'notification-open')); entry.addEventListener('click', () => openNotification(item)); return entry;
  }));
  if (!notificationsState.items.length) list.append(el('p', {active: 'Brak aktywnych powiadomień.', archived: 'Archiwum jest puste.', deleted: 'Kosz jest pusty.'}[notificationsState.view], 'empty-state'));
  list.scrollTop = scroll;
  $('notifications-more').classList.toggle('hidden', !notificationsState.next);
}
async function refreshNotifications(older = false) {
  if (!state.token || (older && !notificationsState.next)) return;
  if (notificationsState.refreshing) {if (!older) notificationsState.refreshAgain = true; return;}
  notificationsState.refreshing = true; $('notifications-more').disabled = true;
  const view = notificationsState.view; const generation = notificationsState.generation;
  try {
    const data = await api(`/api/notifications?view=${view}${older ? `&before=${notificationsState.next}` : ''}`);
    if (view !== notificationsState.view || generation !== notificationsState.generation) return;
    const changed = data.revision !== notificationsState.revision;
    let items = older ? [...notificationsState.items, ...data.items] : data.items;
    let next = data.next;
    if (!older && changed) {
      // Refresh already-loaded pages only when data changed; don't retain stale archived/deleted rows.
      const wanted = Math.max(50, notificationsState.items.length);
      while (next && items.length < wanted) {
        const page = await api(`/api/notifications?view=${view}&before=${next}`);
        if (page.revision !== data.revision) {notificationsState.refreshAgain = true; return;}
        items.push(...page.items); next = page.next;
      }
    }
    if (view !== notificationsState.view || generation !== notificationsState.generation) return;
    const redraw = older || changed;
    if (redraw) {
      notificationsState.items = [...new Map(items.map(item => [item.id, item])).values()].sort((a, b) => b.id - a.id);
      notificationsState.next = next;
    }
    notificationsState.total = data.total;
    notificationsState.counts = data.counts; notificationsState.revision = data.revision;
    if (redraw) renderNotifications();
    $('notifications-status').textContent = `${{active: 'Aktywne', archived: 'Archiwum', deleted: 'Kosz'}[view]}: ${data.total} · widoczne ${notificationsState.items.length}. ${data.sourcesAvailable ? 'Odczyt logów DEV aktywny.' : 'Brak plików logów DEV.'}`;
    $('notifications-note').textContent = data.note;
    if (changed && notificationsState.selected && $('notification-drawer').open) {
      const selectedId = notificationsState.selected.id;
      const detail = await api(`/api/notifications/${selectedId}`);
      if (notificationsState.selected?.id === selectedId) {notificationsState.selected = detail.notification; renderNotificationDetail();}
    }
  } catch (error) {$('notifications-status').textContent = `Odczyt powiadomień niedostępny: ${error.message}`;}
  finally {notificationsState.refreshing = false; $('notifications-more').disabled = false; if (notificationsState.refreshAgain) {notificationsState.refreshAgain = false; refreshNotifications();}}
}
function selectNotificationView(view) {
  if (!['active', 'archived', 'deleted'].includes(view) || view === notificationsState.view) return;
  notificationsState.view = view; notificationsState.items = []; notificationsState.next = null; notificationsState.revision = null; notificationsState.generation++;
  $('notifications-list').scrollTop = 0; renderNotifications(); $('notifications-status').textContent = 'Ładowanie wybranego widoku…'; refreshNotifications();
}
function renderNotificationDetail() {
  const item = notificationsState.selected; if (!item) return;
  const meta = $('notification-drawer-meta'); const time = el('time', date(item.created)); time.dateTime = item.created;
  meta.replaceChildren(pill(item.level === 'ERROR' ? 'Błąd' : 'Ostrzeżenie', item.level === 'ERROR' ? 'failed' : 'running'), el('strong', item.source), time);
  $('notification-drawer-state').textContent = `#${item.id} · ${{active: 'Aktywne powiadomienie', archived: 'W archiwum', deleted: 'W koszu'}[item.state]}${item.state_changed_at ? ` · zmiana ${date(item.state_changed_at)}` : ''}`;
  $('notification-drawer-message').textContent = item.message;
  $('notification-archive').classList.toggle('hidden', item.state !== 'active');
  $('notification-delete').classList.toggle('hidden', item.state === 'deleted');
  $('notification-restore').classList.toggle('hidden', item.state === 'active');
  $('notification-restore').textContent = item.state === 'deleted' ? 'Przywróć z kosza' : 'Przywróć z archiwum';
  for (const id of ['notification-archive', 'notification-delete', 'notification-restore']) $(id).disabled = notificationsState.changing;
  window.problemUI?.load(item);
}
async function openNotification(item) {
  if (notificationsState.changing) return;
  notificationsState.selected = item;
  $('notification-drawer-feedback').textContent = ''; $('notification-drawer-error').classList.add('hidden');
  renderNotificationDetail(); $('notification-drawer').showModal();
  try {const detail = await api(`/api/notifications/${item.id}`); if (notificationsState.selected?.id === item.id) {notificationsState.selected = detail.notification; renderNotificationDetail();}}
  catch (error) {notificationError(error.message);}
}
function notificationError(message) {$('notification-drawer-error').textContent = message; $('notification-drawer-error').classList.toggle('hidden', !message);}
function confirmNotificationDelete(all = false) {
  if (notificationsState.changing || (!all && !notificationsState.selected)) return;
  notificationsState.pendingDelete = all ? {operation: 'delete-active'} : {operation: 'delete', id: notificationsState.selected.id};
  $('notification-delete-dialog').returnValue = 'cancel';
  $('notification-delete-description').textContent = all ? `Przenieść wszystkie aktywne powiadomienia do kosza? Obecnie: ${notificationsState.counts.active}. Archiwum pozostanie bez zmian. Operacja obejmuje wpisy aktywne w chwili wykonania.` : `Przenieść powiadomienie #${notificationsState.selected.id} do kosza?`;
  $('notification-delete-dialog').showModal();
}
async function changeNotification(operation, notificationId = null) {
  if (notificationsState.changing) return;
  notificationsState.changing = true; notificationError(''); renderNotificationDetail(); $('notifications-delete-all').disabled = true;
  const payload = {operation, confirmed: operation.startsWith('delete')};
  if (notificationId !== null) payload.id = notificationId;
  try {
    const result = await api('/api/notifications/action', payload);
    notificationsState.generation++; notificationsState.revision = null; notificationsState.counts = result.counts;
    if (result.notification && notificationsState.selected?.id === result.notification.id) notificationsState.selected = result.notification;
    const message = {archive: 'Powiadomienie przeniesiono do archiwum.', delete: 'Powiadomienie przeniesiono do kosza.', restore: 'Powiadomienie przywrócono.', 'delete-active': `Przeniesiono do kosza ${result.changed} aktywnych powiadomień.`}[operation];
    $('notification-drawer-feedback').textContent = message; $('notifications-status').textContent = message;
    await refreshNotifications();
  } catch (error) {notificationError(error.message); $('notifications-status').textContent = error.message;}
  finally {notificationsState.changing = false; renderNotifications(); renderNotificationDetail();}
}
document.querySelectorAll('.nav-item').forEach(button => button.addEventListener('click', () => changeView(button.dataset.view)));
document.querySelector('.brand').addEventListener('click', () => changeView('dashboard'));
$('profile').addEventListener('change', profileChanged);
$('action-search').addEventListener('input', renderActions);
$('refresh').addEventListener('click', () => {banner(''); refreshStatus(); refreshJobs(); refreshNotifications(); if (state.view === 'config') loadConfiguration(); if (state.view === 'files') window.fileEditor?.load(); if (state.view === 'accounts') window.bankAccounts?.load(); if (state.view === 'shopAccounts') window.shopAccounts?.load(); if (state.view === 'maintenance') window.operatorUI?.load();});
$('configuration-form').addEventListener('submit', saveConfiguration);
$('configuration-confirm').addEventListener('change', renderConfigurationControls);
$('configuration-reload').addEventListener('click', loadConfiguration);
$('configuration-dev-view').addEventListener('click', () => {$('profile').value = 'dev'; profileChanged();});
$('docs-open-configuration').addEventListener('click', () => changeView('config'));
$('cash-form').addEventListener('submit', submitCash);
for (const id of ['cash-close', 'cash-cancel']) $(id).addEventListener('click', () => {if (!cashState.submitting) $('cash-dialog').close();});
$('cash-dialog').addEventListener('cancel', event => {if (cashState.submitting) event.preventDefault();});
$('cash-account').addEventListener('change', cashChanged); $('cash-amount').addEventListener('input', cashChanged); $('cash-check').addEventListener('change', updateCash);
$('notifications-toggle').addEventListener('click', () => toggleNotifications(!notificationsState.open));
$('notifications-close').addEventListener('click', () => {toggleNotifications(false); $('notifications-toggle').focus();});
$('notifications-more').addEventListener('click', () => refreshNotifications(true));
$('notifications-delete-all').addEventListener('click', () => confirmNotificationDelete(true));
document.querySelectorAll('[data-notification-view]').forEach(tab => tab.addEventListener('click', () => selectNotificationView(tab.dataset.notificationView)));
document.querySelector('.notification-tabs').addEventListener('keydown', event => {
  if (event.ctrlKey || event.metaKey || event.altKey) return;
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault(); const views = ['active', 'archived', 'deleted']; const index = views.indexOf(notificationsState.view);
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (event.key === 'ArrowRight' ? 1 : 2)) % 3;
  selectNotificationView(views[next]); $(`notification-tab-${views[next]}`).focus();
});
$('notification-drawer-close').addEventListener('click', () => $('notification-drawer').close());
$('notification-drawer').addEventListener('close', () => {notificationsState.selected = null; $('notifications-toggle').focus();});
$('notification-archive').addEventListener('click', () => changeNotification('archive', notificationsState.selected?.id));
$('notification-restore').addEventListener('click', () => changeNotification('restore', notificationsState.selected?.id));
$('notification-delete').addEventListener('click', () => confirmNotificationDelete());
$('notification-delete-dialog').addEventListener('close', () => {const pending = notificationsState.pendingDelete; notificationsState.pendingDelete = null; if ($('notification-delete-dialog').returnValue === 'delete' && pending) changeNotification(pending.operation, pending.id ?? null);});
document.addEventListener('click', event => {
  const insideNotificationUi = ['notifications-control', 'notification-drawer', 'notification-delete-dialog'].some(id => $(id).contains(event.target));
  if (notificationsState.open && !insideNotificationUi && !$('notification-drawer').open && !$('notification-delete-dialog').open) toggleNotifications(false);
});
document.addEventListener('keydown', event => {if (event.key === 'Escape' && notificationsState.open && !$('notification-drawer').open && !$('notification-delete-dialog').open) {toggleNotifications(false); $('notifications-toggle').focus();}});
$('show-all-jobs').addEventListener('click', () => changeView('tests'));
$('confirm-check').addEventListener('change', () => {if (window.productionUI) window.productionUI.update(); else $('confirm-run').disabled = !$('confirm-check').checked;});
$('confirm-dialog').addEventListener('close', () => {const action = state.pendingAction; state.pendingAction = null; try {if ($('confirm-dialog').returnValue === 'run' && $('confirm-check').checked && action) {const extra = window.productionUI?.collect(action) || {}; window.productionUI?.clear(); submit(action, true, extra);}} catch (error) {banner(error.message);} finally {window.productionUI?.clear();}});
$('copy-log').addEventListener('click', async () => {try {await navigator.clipboard.writeText($('log-output').textContent); $('copy-log').textContent = 'Skopiowano'; setTimeout(() => {$('copy-log').textContent = 'Kopiuj';}, 1800);} catch {banner('Przeglądarka nie zezwoliła na kopiowanie. Zaznacz tekst logu ręcznie.');}});
$('cancel-job').addEventListener('click', async () => {if (!state.selectedJob || !window.panelConfirm('Zatrzymać proces zadania? Wykonane zmiany nie zostaną cofnięte.')) return; try {await api('/api/cancel', {id: state.selectedJob}); await refreshJobs();} catch (error) {banner(error.message);}});
(async () => {
  try {const data = await api('/api/bootstrap'); state.token = data.token; state.actions = data.actions; state.jobs = data.jobs; state.documentation = data.documentation || {}; profileChanged(); renderDocumentation(); renderJobs(); renderNotifications(); refreshNotifications(); $('connection-state').textContent = 'Połączono z lokalnym panelem'; setInterval(refreshJobs, 2500); setInterval(refreshNotifications, 5000); setInterval(() => {if (state.view === 'dashboard') refreshStatus();}, 12000);}
  catch (error) {banner(`Nie udało się uruchomić panelu: ${error.message}`); $('connection-state').textContent = 'Brak połączenia';}
})();
