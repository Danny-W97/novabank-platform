'use strict';
// No polling, storage, export or mutation. Clear account data on leave/profile change.
window.bankAccounts = (() => {
  let generation = 0, detailGeneration = 0, loading = false, trail = [], cursor = null, next = null, applied = null;
  const labels = {ACTIVE: 'Aktywny', BLOCKED: 'Zablokowany', CLOSED: 'Zamknięty'};
  const enabled = () => state.profile === 'dev' && Boolean(state.token);
  const when = value => value ? new Date(value).toLocaleString(window.panelLanguage?.locale || 'pl-PL') : 'Brak danych';
  const money = item => `${String(item.balance).replace('.', window.panelLanguage?.language === 'en' ? '.' : ',')} ${item.currency}`;
  function controls() {
    for (const input of $('bank-account-filters').querySelectorAll('input,select,button')) input.disabled = loading || !enabled();
    $('bank-accounts-reload').disabled = loading || !enabled();
    $('bank-accounts-prev').disabled = loading || !enabled() || !trail.length;
    $('bank-accounts-next').disabled = loading || !enabled() || !next;
    $('bank-accounts-unavailable').classList.toggle('hidden', state.profile === 'dev');
    $('bank-accounts-data').classList.toggle('hidden', state.profile !== 'dev');
  }
  function clearDetail() {detailGeneration++; $('bank-account-fields').replaceChildren(); $('bank-account-feedback').textContent = ''; $('bank-account-title').textContent = 'Szczegóły rachunku';}
  function reset() {
    generation++; loading = false; trail = []; cursor = null; next = null; applied = null;
    $('bank-accounts-rows').replaceChildren(); $('bank-accounts-page').textContent = '';
    $('bank-account-query').value = ''; $('bank-account-status').value = 'ALL'; $('bank-account-currency').value = '';
    $('bank-accounts-status').textContent = 'Otwórz zakładkę, aby pobrać rachunki DEV.';
    $('bank-account-dialog').close(); clearDetail(); controls();
  }
  function render(rows) {
    $('bank-accounts-rows').replaceChildren(...rows.map(item => {
      const row = el('tr'), account = el('td'), owner = el('td'), status = el('td'), action = el('td');
      account.append(el('strong', item.number, 'no-translate'), el('small', `Rachunek #${item.id} · użytkownik #${item.ownerId}`, 'muted'));
      owner.append(el('strong', item.owner, 'no-translate'), el('small', item.email, 'muted no-translate'));
      status.append(pill(labels[item.status] || item.status, item.status === 'ACTIVE' ? 'success' : 'failed'), el('small', `Użytkownik: ${labels[item.userStatus] || item.userStatus}`, 'muted'));
      const button = el('button', 'Szczegóły', 'button outline'); button.type = 'button'; button.setAttribute('aria-label', `Szczegóły rachunku ${item.id}`);
      button.addEventListener('click', () => detail(item.id)); action.append(button);
      row.append(account, owner, el('td', money(item), 'bank-account-balance no-translate'), status, action); return row;
    }));
    if (!rows.length) {const row = el('tr'), cell = el('td', 'Brak rachunków pasujących do filtrów.', 'bank-accounts-empty'); cell.colSpan = 5; row.append(cell); $('bank-accounts-rows').append(row);}
  }
  async function load(direction = 'first') {
    if (state.profile !== 'dev') {reset(); return;}
    if (!enabled() || loading) return;
    let target = null, targetTrail = [];
    const values = direction === 'first' ? {q: $('bank-account-query').value.trim(), status: $('bank-account-status').value, currency: $('bank-account-currency').value.trim().toUpperCase() || 'ALL'} : applied;
    if (!values) return;
    if (direction === 'next') {if (!next) return; target = next; targetTrail = [...trail, cursor];}
    if (direction === 'prev') {if (!trail.length) return; target = trail[trail.length - 1]; targetTrail = trail.slice(0, -1);}
    const mine = ++generation; loading = true; controls(); $('bank-accounts-rows').replaceChildren();
    $('bank-accounts-status').textContent = 'Pobieranie rachunków DEV — tylko odczyt…'; $('bank-accounts-page').textContent = '';
    $('bank-account-dialog').close(); clearDetail();
    const query = new URLSearchParams({profile: 'dev', ...values}); if (target) query.set('before', target);
    try {
      const result = await api(`/api/bank-accounts?${query}`);
      if (mine !== generation || state.profile !== 'dev' || state.view !== 'accounts') return;
      applied = values; cursor = target; trail = targetTrail; next = result.next; render(result.accounts);
      $('bank-accounts-status').textContent = `Liczba rachunków na stronie: ${result.accounts.length}. Dane z ${new Date().toLocaleTimeString(window.panelLanguage?.locale || 'pl-PL')}; odświeżaj przyciskiem, bez automatycznego odpytywania.`;
      $('bank-accounts-page').textContent = `Strona ${trail.length + 1} · do ${result.pageSize} rachunków · od najnowszych`;
    } catch (_) {
      if (mine === generation) {next = null; $('bank-accounts-status').textContent = 'Nie udało się pobrać danych. Poprzednie wyniki ukryto — nie traktuj tego jako pustej bazy. Sprawdź powiadomienie i spróbuj ponownie.';}
    } finally {if (mine === generation) {loading = false; controls();}}
  }
  async function detail(id) {
    if (!enabled() || state.view !== 'accounts' || !/^[1-9][0-9]{0,18}$/.test(id)) return;
    clearDetail(); const mine = detailGeneration;
    $('bank-account-title').textContent = `Rachunek #${id}`; $('bank-account-feedback').textContent = 'Pobieranie aktualnych szczegółów DEV…';
    $('bank-account-dialog').showModal();
    try {
      const result = await api(`/api/bank-accounts/${id}?profile=dev`);
      if (mine !== detailGeneration || !enabled() || state.view !== 'accounts' || !$('bank-account-dialog').open) return;
      const a = result.account;
      const fields = [['Numer rachunku', a.accountNumber], ['Saldo rachunku', money(a)], ['Status rachunku', labels[a.status] || a.status], ['ID właściciela', a.ownerId], ['Właściciel', a.owner], ['E-mail', a.email], ['Status użytkownika', labels[a.userStatus] || a.userStatus], ['Rola', a.role], ['E-mail zweryfikowany', a.emailVerified ? 'Tak' : 'Nie'], ['2FA włączone', a.twoFactorEnabled ? 'Tak' : 'Nie'], ['Utworzenie rachunku', when(a.createdAt)], ['Aktualizacja rachunku', when(a.updatedAt)], ['Rejestracja użytkownika', when(a.userCreatedAt)], ['Ostatnie logowanie', when(a.lastLoginAt)]];
      const privateFields = new Set(['Numer rachunku', 'Saldo rachunku', 'ID właściciela', 'Właściciel', 'E-mail', 'Utworzenie rachunku', 'Aktualizacja rachunku', 'Rejestracja użytkownika', 'Ostatnie logowanie']);
      $('bank-account-fields').replaceChildren(...fields.map(([label, value]) => {const box = el('div'); const shownValue = label === 'Rola' ? (value === 'USER' ? 'Użytkownik' : value) : value; box.append(el('dt', label), el('dd', shownValue, privateFields.has(label) ? 'no-translate' : '')); return box;}));
      $('bank-account-feedback').textContent = 'Odczyt zakończony. Dane osobowe DEV — nie udostępniaj zrzutu ani odpowiedzi API.';
    } catch (_) {if (mine === detailGeneration) $('bank-account-feedback').textContent = 'Nie udało się pobrać szczegółów lub rachunek już nie istnieje. Zamknij okno i odśwież listę.';}
  }
  $('bank-account-filters').addEventListener('submit', event => {event.preventDefault(); load();});
  $('bank-accounts-reload').addEventListener('click', () => load());
  $('bank-accounts-prev').addEventListener('click', () => load('prev')); $('bank-accounts-next').addEventListener('click', () => load('next'));
  $('bank-account-close').addEventListener('click', () => $('bank-account-dialog').close()); $('bank-account-dialog').addEventListener('close', () => {if (!$('bank-account-dialog').open) clearDetail();});
  controls();
  return {load, reset, controls};
})();
