'use strict';
(() => {
  let plan = null, profile = null, exported = false, running = false;
  const dialog = el('dialog', '', 'administration-dialog retention-dialog');
  const title = el('h2', 'Retencja powiadomień DEV/TEST');
  const description = el('p', 'Wyłącznie stare wpisy kosza i zamknięte archiwum. Aktywne problemy, PROD i trwające naprawy pozostają zachowane. Pobierz eksport przed trwałym usunięciem.');
  const trash = el('input'), archive = el('input');
  for (const [input, days] of [[trash, 30], [archive, 180]]) {input.type = 'number'; input.min = '1'; input.max = '3650'; input.value = String(days);}
  const trashLabel = el('label', 'Kosz — dni'), archiveLabel = el('label', 'Archiwum — dni');
  trashLabel.append(trash); archiveLabel.append(archive);
  const status = el('p', '', 'form-help'), preview = el('button', 'Pokaż plan', 'button outline');
  const download = el('button', 'Pobierz eksport JSON', 'button outline');
  const check = el('input'); check.type = 'checkbox';
  const checkLabel = el('label', 'Pobrałem eksport i potwierdzam trwałe usunięcie wskazanej partii.'); checkLabel.prepend(check);
  const ack = el('input'); ack.placeholder = 'Wpisz USUN'; ack.autocomplete = 'off';
  const purge = el('button', 'Usuń wyeksportowaną partię', 'button danger'), close = el('button', 'Zamknij', 'button outline');
  dialog.append(title, description, trashLabel, archiveLabel, preview, status, download, checkLabel, ack, purge, close);
  document.body.append(dialog);
  function controls() {
    preview.disabled = running || !['dev', 'test'].includes(state.profile);
    download.disabled = running || !plan || profile !== state.profile;
    purge.disabled = running || !plan || !exported || !check.checked || ack.value !== 'USUN' || profile !== state.profile || plan.count === 0;
    trash.disabled = running; archive.disabled = running;
  }
  function reset() {plan = null; exported = false; check.checked = false; ack.value = ''; controls();}
  async function request(operation, extra = {}) {
    if (!['dev', 'test'].includes(state.profile) || (profile && profile !== state.profile)) throw new Error('Profil zmienił się. Otwórz nowy plan DEV/TEST.');
    return api('/api/notifications/retention', {operation, profile: state.profile, ...extra});
  }
  async function run(callback) {if (running) return; running = true; controls(); try {await callback();} catch (e) {status.textContent = e.message;} finally {running = false; controls();}}
  $('notification-retention-open').addEventListener('click', () => {profile = state.profile; reset(); status.textContent = ''; dialog.showModal();});
  preview.addEventListener('click', () => run(async () => {reset(); plan = await request('plan', {trashDays: Number(trash.value), archiveDays: Number(archive.value)}); status.textContent = `${plan.note} Kandydaci: ${plan.count}/${plan.batchLimit}. Baza: ${plan.databaseBytes} B.`;}));
  download.addEventListener('click', () => run(async () => {
    const result = await request('export', {planId: plan.planId});
    const url = URL.createObjectURL(new Blob([result.content], {type: 'application/json;charset=utf-8'}));
    const link = el('a'); link.href = url; link.download = result.filename; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000); exported = true; status.textContent = `Eksport przygotowany. SHA-256: ${result.sha256}. Sprawdź pobrany plik przed potwierdzeniem.`;
  }));
  purge.addEventListener('click', () => run(async () => {const result = await request('purge', {planId: plan.planId, confirmed: check.checked, acknowledgement: ack.value}); reset(); status.textContent = `Usunięto: ${result.removed}. ${result.note}`; await refreshNotifications();}));
  for (const input of [trash, archive]) input.addEventListener('input', reset);
  check.addEventListener('change', controls); ack.addEventListener('input', controls);
  close.addEventListener('click', () => {if (!running) dialog.close();});
  dialog.addEventListener('cancel', event => {if (running) event.preventDefault();});
  controls();
})();
