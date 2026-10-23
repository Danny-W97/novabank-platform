'use strict';
// Transient masked inputs. Never persist Vault tokens, shares or TLS passwords.
window.productionUI = (() => {
  const descriptions = {
    vaultToken: 'Token operatora Vault (nie root)', unsealKey: 'Jeden udział unseal Shamir',
    truststorePassword: 'Hasło istniejącego truststore Vault (puste zachowuje wartość)',
    bankRoleId: 'Role ID banku (opcjonalnie)', bankSecretId: 'Secret ID banku (opcjonalnie)',
    shopRoleId: 'Role ID sklepu (opcjonalnie)', shopSecretId: 'Secret ID sklepu (opcjonalnie)',
    bankPassword: 'Nowe hasło keystore banku (minimum 16 znaków)', shopPassword: 'Nowe hasło keystore sklepu (minimum 16 znaków)',
  };
  const fields = action => action.id === 'vault-unseal' ? ['unsealKey'] : action.id === 'vault-setup' || /^vault-(rotate|finalize)-/.test(action.id) ? ['vaultToken'] : action.id === 'prod-bootstrap' ? ['truststorePassword', 'bankRoleId', 'bankSecretId', 'shopRoleId', 'shopSecretId'] : /^prod-tls-/.test(action.id) ? ['bankPassword', 'shopPassword'] : [];
  let current = null;
  function clear() {
    for (const input of $('production-fields').querySelectorAll('input')) input.value = '';
    $('production-fields').replaceChildren(); $('production-word').value = ''; $('production-restarted').checked = false; $('artifact-delete-word').value = ''; current = null;
  }
  function valid() {
    const production = state.profile === 'prod';
    return $('confirm-check').checked && (!production || $('production-word').value === 'PROD') &&
      (!current || !/^vault-finalize-/.test(current.id) || $('production-restarted').checked) &&
      (!current || current.id !== 'artifacts-delete' || $('artifact-delete-word').value === 'USUN') &&
      [...$('production-fields').querySelectorAll('input')].every(input => input.checkValidity());
  }
  function update() {$('confirm-run').disabled = !valid();}
  function configure(action) {
    clear(); current = action;
    const production = state.profile === 'prod';
    $('production-warning').classList.toggle('hidden', !production);
    $('production-confirmation').classList.toggle('hidden', !production);
    $('production-restart-label').classList.toggle('hidden', !/^vault-finalize-/.test(action.id));
    $('production-fields-note').classList.toggle('hidden', !fields(action).length);
    $('artifact-delete-confirmation').classList.toggle('hidden', action.id !== 'artifacts-delete');
    $('artifact-delete-word').value = '';
    for (const key of fields(action)) {
      const label = el('label', descriptions[key], 'production-field'); const input = el('input');
      input.id = `production-${key}`; input.name = key; input.type = 'password'; input.autocomplete = 'new-password'; input.maxLength = 1024;
      input.required = action.id !== 'prod-bootstrap'; input.minLength = key === 'truststorePassword' ? 6 : /Password/.test(key) ? 16 : 1;
      label.htmlFor = input.id; label.append(input); $('production-fields').append(label); input.addEventListener('input', update);
    }
    update();
  }
  function collect(action) {
    if (!valid() || current?.id !== action.id) throw new Error('Potwierdź zakres PROD i uzupełnij wymagane pola.');
    const result = {};
    for (const input of $('production-fields').querySelectorAll('input')) result[input.name] = input.value;
    if (/^vault-finalize-/.test(action.id)) result.restarted = $('production-restarted').checked;
    if (action.id === 'artifacts-delete') result.deleteConfirmation = $('artifact-delete-word').value;
    return {productionConfirmation: state.profile === 'prod' ? $('production-word').value : undefined, parameters: Object.keys(result).length ? result : undefined};
  }
  function landing() {
    const card = el('article', '', 'card operation-card vault-landing');
    card.append(el('span', '◇', 'operation-icon'), el('span', 'VAULT / PROD', 'operation-group'), el('h3', 'HashiCorp Vault'), el('p', 'Status, odpieczętowanie Shamir, polityki/AppRole oraz rotacja i finalizacja Secret ID banku i sklepu. Udziały i token operatora nie trafiają do historii.'));
    const button = el('button', state.profile === 'prod' ? 'Pokaż operacje Vault' : 'Przejdź do PROD → Vault', 'button primary'); button.type = 'button'; button.disabled = busy();
    button.addEventListener('click', () => {if (busy()) return; if (state.profile !== 'prod') {$('profile').value = 'prod'; profileChanged();} if (state.profile === 'prod') {state.category = 'Vault'; changeView('operations');}});
    card.append(button); return card;
  }
  $('production-word').addEventListener('input', update); $('production-restarted').addEventListener('change', update);
  $('artifact-delete-word').addEventListener('input', update);
  document.addEventListener('visibilitychange', () => {if (document.hidden && $('confirm-dialog').open) {for (const input of $('production-fields').querySelectorAll('input')) input.value = ''; update();}});
  window.addEventListener('pagehide', clear);
  return {configure, collect, clear, update, landing};
})();
