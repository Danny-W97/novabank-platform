'use strict';
// DEV-only, explicit reads. No account mutation, polling, export or browser storage.
window.shopAccounts = (() => {
  let generation = 0, detailGeneration = 0, loading = false, trail = [], cursor = null, next = null, applied = null;
  const enabled = () => state.profile === 'dev' && Boolean(state.token);
  const when = value => value ? new Date(value).toLocaleString(window.panelLanguage?.locale || 'pl-PL') : 'Brak danych';
  const role = value => ({CUSTOMER: 'Klient', ADMIN: 'Administrator'}[value] || value);
  const name = item => [item.firstName, item.lastName].filter(Boolean).join(' ') || (window.panelLanguage?.language === 'en' ? 'Profile not completed' : 'Nie uzupełniono profilu');
  function controls() {
    for (const input of $('shop-account-filters').querySelectorAll('input,select,button')) input.disabled = loading || !enabled();
    $('shop-accounts-reload').disabled = loading || !enabled();
    $('shop-accounts-prev').disabled = loading || !enabled() || !trail.length;
    $('shop-accounts-next').disabled = loading || !enabled() || !next;
    $('shop-accounts-unavailable').classList.toggle('hidden', state.profile === 'dev');
    $('shop-accounts-data').classList.toggle('hidden', state.profile !== 'dev');
  }
  function clearDetail() {detailGeneration++; $('shop-account-purchases').disabled=true;$('shop-account-purchases').onclick=null; $('shop-account-fields').replaceChildren(); $('shop-account-feedback').textContent = ''; $('shop-account-title').textContent = 'Szczegóły konta sklepu';}
  function reset() {
    generation++; loading = false; trail = []; cursor = null; next = null; applied = null;
    $('shop-accounts-rows').replaceChildren(); $('shop-accounts-page').textContent = '';
    $('shop-account-query').value = ''; $('shop-account-status').value = 'ALL'; $('shop-account-role').value = 'ALL';
    $('shop-accounts-status').textContent = 'Otwórz zakładkę, aby pobrać konta sklepu DEV.';
    $('shop-account-dialog').close(); clearDetail(); controls();
  }
  function render(rows) {
    $('shop-accounts-rows').replaceChildren(...rows.map(item => {
      const row = el('tr'), user = el('td'), customer = el('td'), active = el('td'), action = el('td');
      user.append(el('strong', name(item), item.firstName || item.lastName ? 'no-translate' : ''), el('small', item.email, 'muted no-translate'));
      customer.append(el('strong', item.customerId, 'no-translate'), el('small', `ID konta sklepu: ${item.id}`, 'muted'));
      active.append(pill(item.active ? 'Aktywne' : 'Nieaktywne', item.active ? 'success' : 'failed'));
      const button = el('button', 'Szczegóły', 'button outline'); button.type = 'button'; button.setAttribute('aria-label', `Szczegóły konta sklepu ${item.id}`);
      button.addEventListener('click', () => detail(item.id)); action.append(button);
      row.append(user, customer, el('td', role(item.role)), active, action); return row;
    }));
    if (!rows.length) {const row = el('tr'), cell = el('td', 'Brak kont sklepu pasujących do filtrów.', 'bank-accounts-empty'); cell.colSpan = 5; row.append(cell); $('shop-accounts-rows').append(row);}
  }
  async function load(direction = 'first') {
    if (state.profile !== 'dev') {reset(); return;}
    if (!enabled() || loading) return;
    let target = null, targetTrail = [];
    const values = direction === 'first' ? {q: $('shop-account-query').value.trim(), status: $('shop-account-status').value, role: $('shop-account-role').value} : applied;
    if (!values) return;
    if (direction === 'next') {if (!next) return; target = next; targetTrail = [...trail, cursor];}
    if (direction === 'prev') {if (!trail.length) return; target = trail[trail.length - 1]; targetTrail = trail.slice(0, -1);}
    const mine = ++generation; loading = true; controls(); $('shop-accounts-rows').replaceChildren();
    $('shop-accounts-status').textContent = 'Pobieranie kont sklepu DEV — tylko odczyt…'; $('shop-accounts-page').textContent = '';
    $('shop-account-dialog').close(); clearDetail();
    const query = new URLSearchParams({profile: 'dev', ...values}); if (target) query.set('before', target);
    try {
      const result = await api(`/api/shop-accounts?${query}`);
      if (mine !== generation || !enabled() || state.view !== 'shopAccounts') return;
      applied = values; cursor = target; trail = targetTrail; next = result.next; render(result.accounts);
      $('shop-accounts-status').textContent = `Liczba kont na stronie: ${result.accounts.length}. Dane z ${new Date().toLocaleTimeString(window.panelLanguage?.locale || 'pl-PL')}; odświeżaj przyciskiem, bez automatycznego odpytywania.`;
      $('shop-accounts-page').textContent = `Strona ${trail.length + 1} · do ${result.pageSize} kont · od najnowszych`;
    } catch (_) {
      if (mine === generation) {next = null; $('shop-accounts-status').textContent = 'Nie udało się pobrać danych. Poprzednie wyniki ukryto — nie traktuj tego jako pustej bazy. Sprawdź powiadomienie i spróbuj ponownie.';}
    } finally {if (mine === generation) {loading = false; controls();}}
  }
  async function detail(id) {
    if (!enabled() || state.view !== 'shopAccounts' || !/^[1-9][0-9]{0,18}$/.test(id)) return;
    clearDetail(); const mine = detailGeneration;
    $('shop-account-title').textContent = `Konto sklepu #${id}`; $('shop-account-feedback').textContent = 'Pobieranie aktualnych szczegółów DEV…'; $('shop-account-dialog').showModal();
    try {
      const result = await api(`/api/shop-accounts/${id}?profile=dev`);
      if (mine !== detailGeneration || !enabled() || state.view !== 'shopAccounts' || !$('shop-account-dialog').open) return;
      const a = result.account;
      $('shop-account-purchases').disabled=false;$('shop-account-purchases').onclick=()=>{const customer=a.customerId;$('shop-account-dialog').close();window.paymentCases?.forCustomer(customer);};
      const fields = [['ID konta sklepu', a.id], ['Identyfikator klienta', a.customerId], ['E-mail', a.email], ['Aktywność konta', a.active ? 'Aktywne' : 'Nieaktywne'], ['Imię', a.firstName || (window.panelLanguage?.language === 'en' ? 'Not provided' : 'Nie uzupełniono')], ['Nazwisko', a.lastName || (window.panelLanguage?.language === 'en' ? 'Not provided' : 'Nie uzupełniono')], ['Rola', role(a.role)], ['Utworzenie konta', when(a.createdAt)], ['Aktualizacja konta', when(a.updatedAt)], ['Telefon zapisany', a.hasPhone ? 'Tak — treść ukryta' : 'Nie'], ['Adres zapisany', a.hasAddress ? 'Tak — treść ukryta' : 'Nie']];
      const privateFields = new Set(['ID konta sklepu', 'Identyfikator klienta', 'E-mail', 'Utworzenie konta', 'Aktualizacja konta']);
      $('shop-account-fields').replaceChildren(...fields.map(([label, value]) => {const box = el('div'); box.append(el('dt', label), el('dd', value, privateFields.has(label) ? 'no-translate' : '')); return box;}));
      $('shop-account-feedback').textContent = 'Odczyt zakończony. Konto sklepu nie jest rachunkiem bankowym. Dane osobowe DEV — nie udostępniaj zrzutu ani odpowiedzi API.';
    } catch (_) {if (mine === detailGeneration) $('shop-account-feedback').textContent = 'Nie udało się pobrać szczegółów lub konto już nie istnieje. Zamknij okno i odśwież listę.';}
  }
  $('shop-account-filters').addEventListener('submit', event => {event.preventDefault(); load();});
  $('shop-accounts-reload').addEventListener('click', () => load());
  $('shop-accounts-prev').addEventListener('click', () => load('prev')); $('shop-accounts-next').addEventListener('click', () => load('next'));
  $('shop-account-close').addEventListener('click', () => $('shop-account-dialog').close()); $('shop-account-dialog').addEventListener('close', () => {if (!$('shop-account-dialog').open) clearDetail();});
  controls();
  return {load, reset, controls};
})();
