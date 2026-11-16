'use strict';
// Server-authored codes/plans only. Log text is never interpreted as a command.
window.problemUI = (() => {
  const confirmAction = message => window.confirm(window.panelLanguage?.translate(message) || message);
  let generation = 0, data = null, pending = false, lastNotice = null, requestId = null;
  const attempts = new Map();
  const attemptKey = () => data?.plan ? `${data.notificationId}:${data.plan.revision}` : '';
  const outcomes = {open: 'Wymaga sprawdzenia', configuration_verified: 'Konfiguracja zweryfikowana · sprawdź przeładowanie usług', condition_cleared: 'Warunek techniczny ustąpił', manual: 'Wymagana kontrola operatora'};
  const dirty = () => window.fileEditor?.dirty || Object.keys(configurationState.changes).length > 0;
  function feedback(text) {$('problem-feedback').textContent = text;}
  function controls() {
    const mismatch = data?.profile && state.profile !== data.profile;
    const locked = pending || busy() || mismatch || dirty();
    $('problem-run').disabled = locked || !data?.plan || !$('problem-confirm').checked;
    $('problem-check').disabled = pending || busy() || !data || !data.problem.check || mismatch;
    $('problem-new-attempt').classList.toggle('hidden', !attempts.has(attemptKey()));
    $('problem-new-attempt').disabled = locked;
    for (const button of $('problem-actions').querySelectorAll('button')) button.disabled = locked;
    $('problem-profile-note').textContent = mismatch ? `Aby wykonać działanie, wybierz widok ${data.profile.toUpperCase()}. Czytanie instrukcji nie zmienia profilu.` : dirty() ? 'Masz niezapisane zmiany. Przed naprawą lub przejściem uzgodnij je; nie zostaną automatycznie odrzucone.' : '';
  }
  function reset() {generation++; data = null; pending = false; requestId = null; $('problem-confirm').checked = false; $('problem-plan').classList.add('hidden'); $('problem-actions').replaceChildren(); feedback('');}
  function navigate(view) {
    if (dirty()) return feedback('Najpierw wróć do formularza i uzgodnij niezapisane zmiany. Nie zostały odrzucone.');
    $('notification-drawer').close(); changeView(view);
  }
  async function openLogs() {
    if (!data?.jobId) return;
    const mine = generation;
    try {
      const job = await api(`/api/jobs/${data.jobId}`);
      if (mine !== generation) return;
      if (dirty()) return feedback('Przed przejściem do logów uzgodnij niezapisane zmiany formularza.');
      $('notification-drawer').close(); await openJob(job);
    } catch (_) {if (mine === generation) feedback('Log tego zadania nie jest już dostępny. Zachowaj powiadomienie i sprawdź lokalną historię/kopie.');}
  }
  function render(value) {
    data = value;
    $('problem-title').textContent = value.problem.title;
    $('problem-code').textContent = `${value.problem.code} · ${value.profile ? value.profile.toUpperCase() : 'profil nieustalony'}${value.context.component ? ` · ${value.context.component}` : ''}${value.context.port ? ` · port ${value.context.port}` : ''}`;
    $('problem-impact').textContent = value.problem.impact;
    $('problem-diagnosis').textContent = value.diagnosis;
    $('problem-outcome').textContent = `${outcomes[value.outcome] || outcomes.open}${value.outcomeNote ? ` — ${value.outcomeNote}` : ''}`;
    $('problem-doc-location').textContent = `${value.problem.document} → § ${value.problem.anchor}`;
    $('problem-steps').replaceChildren(...value.problem.steps.map(step => el('li', step)));
    $('problem-actions').replaceChildren(...value.actions.map(action => {
      const button = el('button', action.title, 'button outline'); button.type = 'button';
      button.addEventListener('click', () => {
        if (pending || busy() || dirty() || state.profile !== value.profile) return;
        if (action.kind === 'view' && ['config', 'files'].includes(action.id)) return navigate(action.id);
        const known = state.actions.find(item => item.id === action.id && ['test-env', 'build-bank', 'build-shop'].includes(item.id) && available(item));
        if (known) {$('notification-drawer').close(); requestAction(known);}
      }); return button;
    }));
    $('problem-logs').classList.toggle('hidden', !value.jobId);
    $('problem-check').classList.toggle('hidden', value.readOnly || !value.problem.check || !['dev', 'test'].includes(value.profile));
    $('problem-plan').classList.toggle('hidden', !value.plan);
    $('problem-confirm').checked = false;
    if (value.plan) {
      $('problem-plan-title').textContent = value.plan.title;
      $('problem-plan-source').textContent = `Źródło: ${value.plan.source} · istniejące wartości pozostają ukryte.`;
      $('problem-plan-writes').replaceChildren(...value.plan.writes.map(path => el('li', path)));
      $('problem-plan-effects').textContent = value.plan.effects;
    }
    controls();
  }
  async function load(item) {
    const mine = ++generation; data = null; requestId = null; $('problem-confirm').checked = false;
    $('problem-plan').classList.add('hidden'); $('problem-actions').replaceChildren(); feedback('Odczyt instrukcji i możliwości diagnostyki…');
    try {
      const result = await api(`/api/support/${item.id}`);
      if (mine !== generation || notificationsState.selected?.id !== item.id) return;
      render(result); feedback('');
    } catch (_) {if (mine === generation) feedback('Instrukcja chwilowo niedostępna. Nie uruchamiaj niezweryfikowanych napraw; sprawdź logi.');}
    finally {controls();}
  }
  async function check() {
    if (!data || pending || busy() || state.profile !== data.profile) return;
    const mine = generation, id = data.notificationId; pending = true; controls(); feedback('Sprawdzanie bieżącego stanu — bez zmiany plików i usług…');
    try {const result = await api('/api/support/check', {id}); if (mine === generation) {render(result); feedback('Zapisano wynik diagnostyki. Archiwum i kosz nie zostały zmienione.'); await refreshNotifications();}}
    catch (error) {if (mine === generation) feedback(error.message);}
    finally {pending = false; controls();}
  }
  async function repair() {
    if (!data?.plan || pending || busy() || dirty() || state.profile !== 'dev' || data.profile !== 'dev' || !$('problem-confirm').checked) return;
    pending = true; state.loading = true;
    requestId = attempts.get(attemptKey()) || crypto.randomUUID(); attempts.set(attemptKey(), requestId);
    controls(); renderActions();
    const parameters = {notificationId: data.notificationId, revision: data.plan.revision, planToken: data.plan.planToken, requestId};
    const mine = generation;
    try {
      const job = await api('/api/jobs', {action: 'repair-metrics-dev', profile: 'dev', confirmed: true, parameters});
      const navigate = mine === generation;
      if (navigate) $('notification-drawer').close();
      await openJob(job, {navigate});
    } catch (error) {if (mine === generation) feedback(`${error.message} Sprawdź Testy i logi przed ponowieniem; identyfikator tej próby zachowano.`);}
    finally {pending = false; state.loading = false; controls(); renderActions();}
  }
  function decorateBanner() {
    if (!lastNotice || !$('banner').textContent || $('problem-notice-open')) return;
    const button = el('button', `Otwórz powiadomienie #${lastNotice}`, 'button outline'); button.id = 'problem-notice-open'; button.type = 'button';
    button.addEventListener('click', async () => {
      try {const result = await api(`/api/notifications/${lastNotice}`); await openNotification(result.notification);} catch (_) {banner('Powiadomienie niedostępne. Sprawdź dziennik zadań.');}
    }); $('banner').append(button);
  }
  function notice(id) {if (Number.isSafeInteger(id) && id > 0) {lastNotice = id; banner(`Problem zapisano w Powiadomieniach — #${id}. Otwórz instrukcję rozwiązania.`); refreshNotifications();}}
  $('problem-confirm').addEventListener('change', controls);
  $('problem-check').addEventListener('click', check);
  $('problem-logs').addEventListener('click', openLogs);
  $('problem-run').addEventListener('click', repair);
  $('problem-new-attempt').addEventListener('click', () => {
    if (pending || busy() || !data?.plan || !confirmAction('Poprzednia próba mogła mieć skutki. Czy sprawdzono jej wynik, log i kopie, a nowa próba jest świadomie potrzebna?')) return;
    attempts.delete(attemptKey()); requestId = null; $('problem-confirm').checked = false;
    feedback('Przygotowano nową próbę. Ponownie przeczytaj plan i potwierdź zakres.'); controls();
  });
  $('notification-drawer').addEventListener('close', reset);
  return {load, controls, reset, notice, decorateBanner, clearNotice() {lastNotice = null;}};
})();
