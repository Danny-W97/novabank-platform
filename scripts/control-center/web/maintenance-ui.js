'use strict';
window.maintenanceUI = (() => {
  let action = null, profile = null;
  const dialog = el('dialog', '', 'administration-dialog maintenance-dialog'), title = el('h2'), note = el('p');
  const after = el('input'), limit = el('input'), digest = el('input'), ack = el('input'), check = el('input');
  const paused = el('input'); paused.type = 'checkbox';
  const pauseLabel = el('label', 'Potwierdzam zatrzymanie banku, sklepu, listenera i innych zapisów na obu bazach na czas migracji.');pauseLabel.append(paused);
  after.type = 'text'; after.inputMode = 'numeric'; after.value = '0'; after.maxLength = 19;
  limit.type = 'number'; limit.min = '1'; limit.max = '100'; limit.value = '100';
  digest.type = 'text'; digest.maxLength = 64; digest.autocomplete = 'off'; ack.placeholder = 'Wpisz ROTATE';
  check.type = 'checkbox';
  const fields = [];
  for (const [text, input] of [['Checkpoint — ID po ostatniej zatwierdzonej partii', after], ['Rozmiar partii', limit],
      ['planDigest z wyniku planu w Testy i logi', digest], ['Potwierdzenie operacji', ack],
      ['Potwierdzam zakres, sprawdzoną kopię i zachowanie starych kluczy. Wszystkie instancje aplikacji wczytały nowy keyring; sama edycja env nie wystarcza.', check]]) {
    const label = el('label', text); label.append(input); fields.push(label);
  }
  const run = el('button', 'Uruchom', 'button primary'), close = el('button', 'Anuluj', 'button outline'), error = el('p');
  dialog.append(title, note, ...fields, pauseLabel, error, run, close); document.body.append(dialog);
  const importing = () => action?.id === 'payment-history-migration-apply';
  function controls() {run.disabled = busy() || !action || profile !== state.profile || !['dev', 'test'].includes(profile)
      || (action.changes && (!check.checked || ack.value !== (importing() ? 'MIGRATE' : 'ROTATE') || !/^[a-f0-9]{64}$/.test(digest.value)))
      || (importing() && !paused.checked);
  }
  function open(selected) {
    action = selected; profile = state.profile; title.textContent = `${selected.title} · ${profile.toUpperCase()}`;
    note.textContent = selected.description; error.textContent = ''; digest.value = ''; ack.value = ''; check.checked = false;paused.checked=false;
    ack.placeholder=importing()?'Wpisz MIGRATE':'Wpisz ROTATE';
    fields[4].replaceChildren(document.createTextNode(importing() ? 'Potwierdzam świeże kopie obu baz wykonane po zatrzymaniu zapisów, ich odtworzenie w izolacji i przegląd planu. Sumy nie zastępują testu odtworzenia.' : 'Potwierdzam zakres, odtworzoną kopię i zachowanie starych kluczy. Wszystkie instancje wczytały nowy keyring.'),check);
    fields[0].classList.toggle('hidden',importing());fields[1].classList.toggle('hidden',importing());pauseLabel.classList.toggle('hidden',!importing());
    for (const field of fields.slice(2)) field.classList.toggle('hidden', !selected.changes);
    controls(); dialog.showModal();
  }
  for (const input of [after, limit, digest, ack, check, paused]) {input.addEventListener('input', controls); input.addEventListener('change', controls);}
  run.addEventListener('click', async () => {
    controls(); if (run.disabled) return;
    if(importing()) {
      const selected=action;const parameters={planDigest:digest.value,confirmation:ack.value,backupsVerified:check.checked,writesPaused:paused.checked};
      action=null;dialog.close();await submit(selected,true,{parameters});return;
    }
    if (!/^(0|[1-9][0-9]{0,18})$/.test(after.value) || !Number.isInteger(Number(limit.value)) || Number(limit.value)<1 || Number(limit.value)>100) {error.textContent = 'Sprawdź checkpoint i rozmiar partii.'; return;}
    const parameters = {afterId: after.value, limit: Number(limit.value)};
    if (action.changes) Object.assign(parameters, {planDigest: digest.value, confirmation: ack.value});
    const selected = action; action = null; dialog.close(); await submit(selected, selected.changes, {parameters});
  });
  close.addEventListener('click', () => {action = null; digest.value = ''; ack.value = ''; dialog.close();});
  return {open};
})();
