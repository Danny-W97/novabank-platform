'use strict';
// The backup supplies only required key IDs. Key values live in transient masked inputs, never storage/URL/logs.
window.totpRecoveryUI = (() => {
  const opened = new Set();
  let selected = null, current = null, sending = false;
  const dialog = el('dialog', '', 'administration-dialog maintenance-dialog');
  const title = el('h2', 'Klucze do testu odzyskania 2FA'), note = el('p'), fields = el('div');
  const check = el('input'); check.type = 'checkbox';
  const label = el('label', 'Potwierdzam test na kopii. Klucze nie będą zapisane w plikach ani historii; nie wykonuję rotacji.'); label.append(check);
  const error = el('p', '', 'dialog-warning'), run = el('button', 'Sprawdź odszyfrowanie kopii', 'button primary');
  const close = el('button', 'Anuluj', 'button outline');
  dialog.setAttribute('aria-label', 'Klucze do testu odzyskania sekretów 2FA');
  dialog.append(title, note, fields, label, error, run, close); document.body.append(dialog);
  const reopen = el('button', 'Podaj klucze do tej kopii', 'button primary hidden');
  document.querySelector('.log-actions').append(reopen);
  function clear() {for (const input of fields.querySelectorAll('input')) input.value = ''; check.checked = false;}
  function controls() {
    run.disabled = sending || busy() || state.profile !== 'dev' || !current || !check.checked ||
      [...fields.querySelectorAll('input')].some(input => !input.checkValidity());
    close.disabled = sending;
  }
  function open(job) {
    if (sending || busy() || state.profile !== 'dev' || job.profile !== 'dev') return;
    const plan = job.result?.recoveryPlan;
    if (!plan || !Array.isArray(plan.keyIds) || !plan.keyIds.length || plan.keyIds.length > 32 ||
      plan.keyIds.some(id => !/^[A-Za-z0-9_-]{1,32}$/.test(id))) return;
    current = job; clear(); fields.replaceChildren(); error.textContent = '';
    note.textContent = `Kopia: ${plan.backup}. Rekordy: ${plan.records}. SHA-256: ${plan.sha256}. `+
      'Podaj zachowane klucze odpowiadające tej kopii, nie nowo wygenerowane. To weryfikacja odszyfrowania, nie logowania. Plan ważny 20 minut.';
    for (const identifier of plan.keyIds) {
      const text = identifier === 'legacy' ? 'Klucz legacy — zapisany TOTP_ENCRYPTION_KEY' : `Klucz keyringu: ${identifier}`;
      const cell = el('label', text, 'production-field'), input = el('input');
      input.type = 'password'; input.name = identifier; input.autocomplete = 'new-password'; input.required = true;
      input.maxLength = 128; input.pattern = '[A-Za-z0-9+/]+={0,2}'; input.spellcheck = false;
      cell.append(input); fields.append(cell); input.addEventListener('input', controls);
    }
    controls(); dialog.showModal(); return true;
  }
  function observe(job) {
    if (dialog.open) controls();
    selected = job;
    const eligible = job.action === 'backup-totp-inspect' && job.status === 'success' && job.result?.recoveryPlan && state.profile === 'dev';
    reopen.classList.toggle('hidden', !eligible); reopen.disabled = busy();
    if (eligible && !opened.has(job.id) && Date.now()-Date.parse(job.finished) < 1200_000) {
      // Do not open over another modal or steal the view if the operator changed tasks meanwhile.
      window.requestAnimationFrame(() => {
        if (state.view === 'tests' && state.selectedJob === job.id && !document.querySelector('dialog[open]') && open(job)) opened.add(job.id);
      });
    }
  }
  reopen.addEventListener('click', () => {if (selected) open(selected);});
  check.addEventListener('change', controls);
  close.addEventListener('click', () => dialog.close());
  dialog.addEventListener('cancel', event => {if (sending) event.preventDefault();});
  dialog.addEventListener('close', () => {clear(); current = null;});
  run.addEventListener('click', async () => {
    controls(); if (run.disabled) return;
    const planId = current.result.recoveryPlan.planId;
    const keys = Object.fromEntries([...fields.querySelectorAll('input')].map(input => [input.name, input.value]));
    sending = true; state.loading = true; controls(); renderActions(); error.textContent = '';
    try {
      const job = await api('/api/jobs', {action: 'backup-totp-verify', profile: 'dev', confirmed: true, parameters: {planId, keys}});
      clear(); dialog.close(); await openJob(job);
    } catch (failure) {clear(); error.textContent = failure.message;}
    finally {for (const id of Object.keys(keys)) delete keys[id]; sending = false; state.loading = false; controls(); renderActions();}
  });
  document.addEventListener('visibilitychange', () => {if (document.hidden) {clear(); controls();}});
  window.addEventListener('pagehide', clear);
  return {observe};
})();
